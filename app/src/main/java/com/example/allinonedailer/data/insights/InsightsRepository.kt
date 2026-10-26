package com.example.allinonedailer.data.insights

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.provider.CallLog
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.util.Log
import com.example.allinonedailer.R
import com.example.allinonedailer.data.ContactsRepository
import com.example.allinonedailer.data.PhoneNumbers
import com.example.allinonedailer.data.SmartSimLogic

/**
 * Reads the call log for insights: its own light query (no caller-ID or
 * recording work, unlike CallLogRepository) with the SIM column. Call off the main thread.
 */
object InsightsRepository {

    fun loadCalls(context: Context): List<InsightCall> {
        val startedAt = SystemClock.elapsedRealtime()
        val list = ArrayList<InsightCall>(1024)
        val contacts = ContactsRepository.cachedByKey()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.PHONE_ACCOUNT_ID
                ),
                null, null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(0).orEmpty()
                    val name = c.getString(1)?.takeIf { it.isNotBlank() }
                        ?: contacts?.get(PhoneNumbers.key(number))?.name
                    list.add(InsightCall(number, name, c.getInt(2), c.getLong(3), c.getLong(4), c.getString(5)))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        Log.d("InsightsRepository", "Read ${list.size} calls in ${SystemClock.elapsedRealtime() - startedAt} ms")
        return list
    }

    /**
     * PHONE_ACCOUNT_ID → SIM name ("Jio", "Airtel"). Telecom's account ids
     * are usually the subscription id on current Android, older rows may hold
     * the ICCID; both are mapped where the phone lets us read them.
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun simLabels(context: Context): Map<String, String> {
        val labels = HashMap<String, String>()
        try {
            val subs = context.getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList.orEmpty()
            for (s in subs) {
                val name = nameFor(context, s.carrierName?.toString(), s.displayName?.toString(), s.simSlotIndex)
                labels[s.subscriptionId.toString()] = name
                // Android 10+ hides the ICCID from apps without carrier privileges.
                runCatching { s.iccId }.getOrNull()?.takeIf { it.isNotBlank() }?.let { labels[it] = name }
            }
        } catch (e: SecurityException) {
            // No READ_PHONE_STATE: fall through to Telecom's labels.
        }
        try {
            val telecom = context.getSystemService(TelecomManager::class.java)
            telecom.callCapablePhoneAccounts.forEachIndexed { i, handle ->
                if (handle.id !in labels) {
                    val label = telecom.getPhoneAccount(handle)?.label?.toString()
                    labels[handle.id] = nameFor(context, label, label, i)
                }
            }
        } catch (e: SecurityException) {
        }
        return labels
    }

    /** "Jio" / "Airtel" when recognisable, else the SIM's own name, else "SIM n". */
    private fun nameFor(context: Context, carrier: String?, display: String?, slot: Int): String =
        SmartSimLogic.network(carrier) ?: SmartSimLogic.network(display)
            ?: display?.takeIf { it.isNotBlank() } ?: carrier?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.sim_slot, slot + 1)
}
