package com.example.allinonedailer.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    fun isBlocked(phoneNumber: String): Boolean {
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
    }

    private fun normalize(number: String): String = number.filter { it.isDigit() }.takeLast(10)

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

            if (isBlocked(phoneNumber)) {
                return@withContext withOfflineDetails(
                    CallerInfo(
                        phoneNumber = phoneNumber,
                        name = null,
                        isSpam = true,
                        spamCategory = "Blocked by you",
                        source = "blocklist"
                    )
                )
            }

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
     */
    fun lookupCallerInfo(phoneNumber: String): CallerInfo {
        lookupContact(phoneNumber)?.let { return it }
        if (isBlocked(phoneNumber)) {
            return withOfflineDetails(
                CallerInfo(
                    phoneNumber = phoneNumber,
                    name = null,
                    isSpam = true,
                    spamCategory = "Blocked by you",
                    source = "blocklist"
                )
            )
        }
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
        val details = NumberInfo.describe(info.phoneNumber, NumberInfo.defaultRegion(context)) ?: return info
        val premium = isCallerIdEnabled() && details.type == "Premium-rate"
        return info.copy(
            carrier = info.carrier ?: details.carrier ?: details.type,
            location = info.location ?: details.region,
            isSpam = info.isSpam || premium,
            spamCategory = info.spamCategory ?: if (premium) "Possible premium-rate" else null,
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
            spamCategory = if (possibleSpam) "Possible premium-rate / telemarketing" else null,
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
