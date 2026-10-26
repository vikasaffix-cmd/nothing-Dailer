package com.example.allinonedailer.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.allinonedailer.R

data class CallerInfo(
    val phoneNumber: String,
    val name: String?,
    val carrier: String? = null,
    val location: String? = null,
    val isSpam: Boolean = false,
    val spamCategory: String? = null,
    val photoUri: Uri? = null,
    val inContacts: Boolean = false,
    /** contacts | blocklist | remote | network | heuristic | unknown */
    val source: String = "unknown"
) {
    /** Offline number label, e.g. "Jio · Maharashtra"; null when nothing is known. */
    val numberLabel: String?
        get() = listOfNotNull(carrier, location).distinct().joinToString(" · ").ifBlank { null }
}

/** A user "starts with" rule, e.g. 140 for TRAI promotional telemarketing. */
data class BlockRule(val prefix: String, val enabled: Boolean = true) {
    val label: String get() = "Blocked: starts with $prefix"
}

data class SpamReport(val phoneNumber: String, val reportedAt: Long)

/** Prefix matching, kept free of Android types so it runs in JVM tests. */
object BlockRules {

    fun cleanPrefix(prefix: String): String = prefix.filter { it.isDigit() }.trimStart('0')

    /**
     * The Indian national number: digits without the +91 / 0091 country code
     * or a trunk 0, so "+91 140…", "0140…" and "140…" all become "140…".
     */
    fun nationalNumber(number: String): String {
        val trimmed = number.trim()
        var d = trimmed.filter { it.isDigit() }
        d = when {
            trimmed.startsWith("+") && d.startsWith("91") -> d.drop(2)
            d.startsWith("0091") -> d.drop(4)
            d.length == 12 && d.startsWith("91") -> d.drop(2)
            else -> d
        }
        return d.trimStart('0')
    }

    /** The label of the first enabled rule the number starts with, or null. */
    fun match(number: String, rules: List<BlockRule>): String? = matchingRule(number, rules)?.label

    /** The first enabled rule the number starts with, or null. */
    fun matchingRule(number: String, rules: List<BlockRule>): BlockRule? {
        val national = nationalNumber(number)
        if (national.isEmpty()) return null
        return rules.firstOrNull { it.enabled && it.prefix.isNotEmpty() && national.startsWith(it.prefix) }
    }
}

/**
 * Caller identification and spam handling.
 *
 * Resolution order: device contacts -> user blocklist -> (opt-in) remote
 * lookup -> conservative local heuristic -> honest "unknown". There is no
 * bundled third-party database and no fabricated "verified" result.
 */
class CallerIdRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("caller_id_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SPAM_BLOCK_ENABLED = "spam_block_enabled"
        private const val KEY_CALLER_ID_ENABLED = "caller_id_enabled"
        private const val KEY_ONLINE_LOOKUP_ENABLED = "online_lookup_enabled"
        private const val KEY_LOOKUP_ENDPOINT = "lookup_endpoint"
        private const val KEY_BLOCKED_NUMBERS = "blocked_numbers"
        private const val KEY_PREFIX_RULES = "prefix_rules"
        private const val KEY_PREFIX_RULES_DISABLED = "prefix_rules_disabled"
        private const val KEY_BLOCK_HIDDEN = "block_hidden_numbers"
    }

    // ---- Settings ----
    fun isSpamBlockEnabled(): Boolean = prefs.getBoolean(KEY_SPAM_BLOCK_ENABLED, true)
    fun setSpamBlockEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_SPAM_BLOCK_ENABLED, enabled).apply()

    fun isCallerIdEnabled(): Boolean = prefs.getBoolean(KEY_CALLER_ID_ENABLED, true)
    fun setCallerIdEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_CALLER_ID_ENABLED, enabled).apply()

    fun isTruecallerLookupEnabled(): Boolean = isCallerIdEnabled()
    fun setTruecallerLookupEnabled(enabled: Boolean) = setCallerIdEnabled(enabled)

    fun isOnlineLookupEnabled(): Boolean = prefs.getBoolean(KEY_ONLINE_LOOKUP_ENABLED, false)
    fun setOnlineLookupEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_ONLINE_LOOKUP_ENABLED, enabled).apply()

    fun getLookupEndpoint(): String = prefs.getString(KEY_LOOKUP_ENDPOINT, "").orEmpty()
    fun setLookupEndpoint(url: String) =
        prefs.edit().putString(KEY_LOOKUP_ENDPOINT, url.trim()).apply()

    // ---- Blocklist: real, offline, user-controlled ----
    fun getBlockedNumbers(): Set<String> =
        prefs.getStringSet(KEY_BLOCKED_NUMBERS, emptySet())?.toSet() ?: emptySet()

    /** Blocked by an exact number, a reported-spam entry or an enabled prefix rule. */
    fun isBlocked(phoneNumber: String): Boolean =
        isNumberBlocked(phoneNumber) || isBlockedByRule(phoneNumber) != null

    /** Only the exact-number blocklist (what Unblock can undo). */
    fun isNumberBlocked(phoneNumber: String): Boolean {
        val d = normalize(phoneNumber)
        if (d.isEmpty()) return false
        return getBlockedNumbers().any { normalize(it) == d }
    }

    fun blockNumber(phoneNumber: String) {
        val set = getBlockedNumbers().toMutableSet().apply { add(phoneNumber) }
        prefs.edit().putStringSet(KEY_BLOCKED_NUMBERS, set).apply()
    }

    fun unblockNumber(phoneNumber: String) {
        val d = normalize(phoneNumber)
        val set = getBlockedNumbers().filterNot { normalize(it) == d }.toSet()
        prefs.edit().putStringSet(KEY_BLOCKED_NUMBERS, set).apply()
        removeSpamReport(phoneNumber)
    }

    // ---- Block rules: "starts with" prefixes and hidden numbers ----
    fun getPrefixRules(): List<BlockRule> {
        val disabled = prefs.getStringSet(KEY_PREFIX_RULES_DISABLED, emptySet()) ?: emptySet()
        return (prefs.getStringSet(KEY_PREFIX_RULES, emptySet()) ?: emptySet())
            .sorted()
            .map { BlockRule(it, it !in disabled) }
    }

    /** Adds an enabled rule; returns false when the prefix has no digits. */
    fun addPrefixRule(prefix: String): Boolean {
        val p = BlockRules.cleanPrefix(prefix)
        if (p.isEmpty()) return false
        val all = (prefs.getStringSet(KEY_PREFIX_RULES, emptySet()) ?: emptySet()) + p
        val disabled = (prefs.getStringSet(KEY_PREFIX_RULES_DISABLED, emptySet()) ?: emptySet()) - p
        prefs.edit()
            .putStringSet(KEY_PREFIX_RULES, all)
            .putStringSet(KEY_PREFIX_RULES_DISABLED, disabled)
            .apply()
        return true
    }

    fun removePrefixRule(prefix: String) {
        val all = (prefs.getStringSet(KEY_PREFIX_RULES, emptySet()) ?: emptySet()) - prefix
        val disabled = (prefs.getStringSet(KEY_PREFIX_RULES_DISABLED, emptySet()) ?: emptySet()) - prefix
        prefs.edit()
            .putStringSet(KEY_PREFIX_RULES, all)
            .putStringSet(KEY_PREFIX_RULES_DISABLED, disabled)
            .apply()
    }

    fun setPrefixRuleEnabled(prefix: String, enabled: Boolean) {
        val disabled = (prefs.getStringSet(KEY_PREFIX_RULES_DISABLED, emptySet()) ?: emptySet())
        val updated = if (enabled) disabled - prefix else disabled + prefix
        prefs.edit().putStringSet(KEY_PREFIX_RULES_DISABLED, updated).apply()
    }

    fun isBlockHiddenEnabled(): Boolean = prefs.getBoolean(KEY_BLOCK_HIDDEN, false)
    fun setBlockHiddenEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_BLOCK_HIDDEN, enabled).apply()

    /** The label of the first enabled prefix rule matching the number, e.g. "Blocked: starts with 140". */
    fun isBlockedByRule(phoneNumber: String): String? =
        BlockRules.matchingRule(phoneNumber, getPrefixRules())
            ?.let { context.getString(R.string.caller_blocked_prefix, it.prefix) }

    // ---- Spam reports: local only, never uploaded ----
    private val spamReports: SharedPreferences =
        context.getSharedPreferences("spam_reports", Context.MODE_PRIVATE)

    /** Marks the number as spam ("Reported by you") and adds it to the blocklist. */
    fun reportSpam(phoneNumber: String) {
        val key = normalize(phoneNumber)
        if (key.isEmpty()) return
        spamReports.edit().putString(key, "${System.currentTimeMillis()}|$phoneNumber").apply()
        if (!isNumberBlocked(phoneNumber)) blockNumber(phoneNumber)
    }

    fun isReportedSpam(phoneNumber: String): Boolean =
        normalize(phoneNumber).let { it.isNotEmpty() && spamReports.contains(it) }

    /** Reported numbers, newest first. */
    fun getSpamReports(): List<SpamReport> =
        spamReports.all.mapNotNull { (key, value) ->
            val parts = (value as? String)?.split('|', limit = 2) ?: return@mapNotNull null
            SpamReport(parts.getOrNull(1) ?: key, parts[0].toLongOrNull() ?: 0L)
        }.sortedByDescending { it.reportedAt }

    fun removeSpamReport(phoneNumber: String) {
        val key = normalize(phoneNumber)
        if (key.isNotEmpty()) spamReports.edit().remove(key).apply()
    }

    /** The blocklist result for a number, or null when no list or rule covers it. */
    private fun blocklistInfo(phoneNumber: String): CallerInfo? {
        val category = when {
            isReportedSpam(phoneNumber) -> context.getString(R.string.caller_reported_by_you)
            isNumberBlocked(phoneNumber) -> context.getString(R.string.caller_blocked_by_you)
            else -> isBlockedByRule(phoneNumber) ?: return null
        }
        return withOfflineDetails(
            CallerInfo(
                phoneNumber = phoneNumber,
                name = null,
                isSpam = true,
                spamCategory = category,
                source = "blocklist"
            )
        )
    }

    private fun normalize(number: String): String = PhoneNumbers.key(number)

    // ---- Network caller names (CNAP) ----
    // Names the carrier sent with earlier calls, kept so Recents can show them later.
    private val networkNames: SharedPreferences =
        context.getSharedPreferences("network_caller_names", Context.MODE_PRIVATE)

    fun saveNetworkName(phoneNumber: String, name: String) {
        val key = normalize(phoneNumber)
        if (key.isNotEmpty()) networkNames.edit().putString(key, name.trim()).apply()
    }

    fun networkNameFor(phoneNumber: String): String? =
        normalize(phoneNumber).takeIf { it.isNotEmpty() }?.let { networkNames.getString(it, null) }

    // ---- Lookup ----

    /** Full lookup including the optional network step. Must run off the main thread. */
    suspend fun lookupCallerInfoAsync(phoneNumber: String): CallerInfo =
        withContext(Dispatchers.IO) {
            lookupContact(phoneNumber)?.let { return@withContext it }

            blocklistInfo(phoneNumber)?.let { return@withContext it }

            if (isCallerIdEnabled() && isOnlineLookupEnabled()) {
                val remote = RemoteNumberLookup.lookup(getLookupEndpoint(), phoneNumber)
                if (remote != null) {
                    return@withContext withOfflineDetails(
                        CallerInfo(
                            phoneNumber = phoneNumber,
                            name = remote.name,
                            carrier = remote.carrier,
                            location = remote.location,
                            isSpam = remote.spam,
                            spamCategory = remote.spamCategory,
                            source = "remote"
                        )
                    )
                }
            }

            withOfflineDetails(
                if (isCallerIdEnabled()) localHeuristic(phoneNumber) else unknown(phoneNumber)
            )
        }

    /**
     * Synchronous offline lookup (contacts + blocklist + heuristic + offline
     * carrier/region) for list rendering. Call off the main thread.
     * [contacts] (saved numbers by [PhoneNumbers.key], see
     * [ContactsRepository.cachedByKey]) stands in for the contacts query for
     * full-length numbers, which matters when a list resolves hundreds of them.
     */
    fun lookupCallerInfo(phoneNumber: String, contacts: Map<String, ContactItem>? = null): CallerInfo {
        val key = PhoneNumbers.key(phoneNumber)
        val contact = if (contacts != null && key.length >= 10) {
            contacts[key]?.let {
                CallerInfo(
                    phoneNumber = phoneNumber,
                    name = it.name,
                    photoUri = it.photoUri?.let(Uri::parse),
                    inContacts = true,
                    source = "contacts"
                )
            }
        } else {
            lookupContact(phoneNumber)
        }
        contact?.let { return it }
        blocklistInfo(phoneNumber)?.let { return it }
        return withOfflineDetails(
            if (isCallerIdEnabled()) localHeuristic(phoneNumber) else unknown(phoneNumber)
        )
    }

    /**
     * Adds libphonenumber's offline carrier / region / line type to a
     * non-contact result, and flags premium-rate numbers as possible spam.
     */
    private fun withOfflineDetails(raw: CallerInfo): CallerInfo {
        // A name the network sent on an earlier call (CNAP), when nothing better is known.
        val networkName = if (raw.name == null) networkNameFor(raw.phoneNumber) else null
        val info = when {
            networkName == null -> raw
            // Keep "blocklist" etc.: the screening service keys off the source.
            raw.source == "unknown" -> raw.copy(name = networkName, source = "network")
            else -> raw.copy(name = networkName)
        }
        val details = NumberInfo.describe(info.phoneNumber, NumberInfo.defaultRegion(context), context = context) ?: return info
        val premium = isCallerIdEnabled() && details.premium
        return info.copy(
            carrier = info.carrier ?: details.carrier ?: details.type,
            location = info.location ?: details.region,
            isSpam = info.isSpam || premium,
            spamCategory = info.spamCategory ?: if (premium) context.getString(R.string.caller_possible_premium) else null,
            source = if (premium && info.source == "unknown") "heuristic" else info.source
        )
    }

    private fun unknown(phoneNumber: String) =
        CallerInfo(phoneNumber = phoneNumber, name = null, source = "unknown")

    /**
     * Conservative, honest heuristic. Flags only premium-rate prefixes as a
     * *possible* risk — it never invents a caller name or claims verification.
     */
    private fun localHeuristic(phoneNumber: String): CallerInfo {
        val d = phoneNumber.filter { it.isDigit() }
        val possibleSpam = d.startsWith("1900") || d.startsWith("900")
        return CallerInfo(
            phoneNumber = phoneNumber,
            name = null,
            isSpam = possibleSpam,
            spamCategory = if (possibleSpam) context.getString(R.string.caller_possible_telemarketing) else null,
            source = if (possibleSpam) "heuristic" else "unknown"
        )
    }

    private fun lookupContact(phoneNumber: String): CallerInfo? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        val projection = arrayOf(
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.PHOTO_URI
        )
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    val photoIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.PHOTO_URI)
                    val name = if (nameIdx != -1) cursor.getString(nameIdx) else null
                    val photoStr = if (photoIdx != -1) cursor.getString(photoIdx) else null
                    return CallerInfo(
                        phoneNumber = phoneNumber,
                        name = name,
                        photoUri = photoStr?.let { Uri.parse(it) },
                        inContacts = true,
                        source = "contacts"
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
