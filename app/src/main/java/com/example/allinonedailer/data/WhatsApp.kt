package com.example.allinonedailer.data

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

/** What a WhatsApp action does. */
enum class WaKind { CHAT, VOICE, VIDEO }

/** One WhatsApp contact-data row: open it with [intent]. */
data class WaAction(val kind: WaKind, val dataId: Long, val mimeType: String, val packageName: String, val jid: String?)

/**
 * WhatsApp shortcuts from the rows WhatsApp syncs into Contacts.
 *
 * WhatsApp (and WhatsApp Business) add ContactsContract.Data rows for each
 * contact that uses it, with mimetypes like
 * `vnd.android.cursor.item/vnd.com.whatsapp.profile`. Opening such a row with
 * ACTION_VIEW starts a chat, voice or video call in WhatsApp. Numbers without
 * those rows are never assumed to be on WhatsApp; they only get a "Open chat"
 * link (wa.me) the user has to tap.
 */
object WhatsApp {
    private const val TAG = "WhatsApp"
    const val PACKAGE = "com.whatsapp"
    const val PACKAGE_BUSINESS = "com.whatsapp.w4b"

    private fun mime(pkg: String, suffix: String) = "vnd.android.cursor.item/vnd.$pkg.$suffix"

    /** Mimetype -> (kind, package), for both apps. */
    private val MIMES: Map<String, Pair<WaKind, String>> = listOf(PACKAGE, PACKAGE_BUSINESS).flatMap { pkg ->
        listOf(
            mime(pkg, "profile") to (WaKind.CHAT to pkg),
            mime(pkg, "voip.call") to (WaKind.VOICE to pkg),
            mime(pkg, "video.call") to (WaKind.VIDEO to pkg)
        )
    }.toMap()

    /** WhatsApp and/or WhatsApp Business packages installed on the phone. */
    fun installedPackages(context: Context): List<String> = listOf(PACKAGE, PACKAGE_BUSINESS).filter {
        try {
            context.packageManager.getPackageInfo(it, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isInstalled(context: Context): Boolean = installedPackages(context).isNotEmpty()

    /**
     * The number wa.me wants: country code and number, digits only. Numbers
     * without a country code are taken as Indian: "098765 43210" and
     * "9876543210" become "919876543210"; "+44 20…" and "0044 20…" keep 44.
     * Empty when too short to be a phone number.
     */
    fun waNumber(number: String, defaultCountryCode: String = "91"): String {
        val trimmed = number.trim()
        val digits = trimmed.filter(Char::isDigit)
        val full = when {
            trimmed.startsWith("+") -> digits
            digits.startsWith("00") -> digits.drop(2)
            digits.length == 12 && digits.startsWith(defaultCountryCode) -> digits
            else -> defaultCountryCode + digits.trimStart('0')
        }
        return if (full.length < 8) "" else full
    }

    /** Chat link for any number; opens WhatsApp's chat (or its web page) only when the user taps it. */
    fun chatLink(number: String): Uri? = waNumber(number).ifEmpty { null }?.let { Uri.parse("https://wa.me/$it") }

    /** The WhatsApp rows synced for [contactId], limited to [number] when given (a contact can have several). */
    fun actionsForContact(context: Context, contactId: String, number: String? = null): List<WaAction> {
        val id = contactId.toLongOrNull() ?: return emptyList()
        return query(context, "${ContactsContract.Data.CONTACT_ID} = ?", arrayOf(id.toString()), number)
    }

    /** The WhatsApp rows for whichever contact has [number], or none for an unsaved number. */
    fun actionsForNumber(context: Context, number: String): List<WaAction> {
        if (number.isBlank()) return emptyList()
        val contactId = try {
            context.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup._ID), null, null, null
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        } catch (e: Exception) {
            Log.w(TAG, "PhoneLookup failed", e)
            null
        } ?: return emptyList()
        return actionsForContact(context, contactId.toString(), number)
    }

    private fun query(context: Context, where: String, args: Array<String>, number: String?): List<WaAction> {
        val mimes = MIMES.keys.toList()
        val selection = "$where AND ${ContactsContract.Data.MIMETYPE} IN (${mimes.joinToString(",") { "?" }})"
        val rows = try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1),
                selection, args + mimes, null
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val mime = c.getString(1) ?: continue
                        val (kind, pkg) = MIMES[mime] ?: continue
                        add(WaAction(kind, c.getLong(0), mime, pkg, c.getString(2)))
                    }
                }
            }.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Data query failed", e)
            emptyList()
        }
        val installed = installedPackages(context)
        // DATA1 is the WhatsApp id, "919876543210@s.whatsapp.net"; keep rows for this number.
        return rows.filter { it.packageName in installed }
            .filter { row -> number == null || row.jid == null || PhoneNumbers.sameNumber(row.jid.substringBefore('@'), number) }
            .sortedWith(compareBy<WaAction>({ it.packageName != PACKAGE }, { it.kind }))
            .distinctBy { it.kind }
    }

    fun intent(action: WaAction): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, action.dataId), action.mimeType)
            .setPackage(action.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens the row in WhatsApp. False if it couldn't be opened. */
    fun open(context: Context, action: WaAction): Boolean = start(context, intent(action))

    /**
     * Opens a chat with [number]: the synced chat row when there is one,
     * else the wa.me link (in WhatsApp if installed, else the browser).
     */
    fun openChat(context: Context, number: String, actions: List<WaAction> = emptyList()): Boolean {
        actions.firstOrNull { it.kind == WaKind.CHAT }?.let { if (open(context, it)) return true }
        val link = chatLink(number) ?: return false
        val view = Intent(Intent.ACTION_VIEW, link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        installedPackages(context).firstOrNull()?.let { pkg ->
            if (start(context, Intent(view).setPackage(pkg))) return true
        }
        return start(context, view)
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't open ${intent.data}", e)
        false
    }
}
