package com.example.allinonedailer.data.balance

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.example.allinonedailer.R
import com.example.allinonedailer.data.SmartSimLogic

/** An active SIM the balance screen can send codes from. */
data class BalanceSim(
    val subId: Int,
    val slot: Int,
    /** "Jio" / "Airtel" / … or null when not recognised. */
    val network: String?,
    val name: String
) {
    /** Where this SIM's codes are saved: by operator, so swapping slots keeps them. */
    val storeKey: String get() = network ?: "slot${slot + 1}"
}

object BalanceSims {
    /** Active SIMs in slot order; empty without READ_PHONE_STATE. */
    @SuppressLint("MissingPermission")
    fun active(context: Context): List<BalanceSim> = try {
        context.getSystemService(SubscriptionManager::class.java)
            ?.activeSubscriptionInfoList.orEmpty()
            .sortedBy { it.simSlotIndex }
            .map { s ->
                val carrier = s.carrierName?.toString()
                val display = s.displayName?.toString()
                val network = SmartSimLogic.network(carrier) ?: SmartSimLogic.network(display)
                BalanceSim(
                    subId = s.subscriptionId,
                    slot = s.simSlotIndex,
                    network = network,
                    name = network ?: display?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.sim_slot, s.simSlotIndex + 1)
                )
            }
    } catch (e: SecurityException) {
        emptyList()
    }
}

/** Saved codes per SIM in prefs "ussd_codes" (key "codes.<Jio|Airtel|slotN>"); built-ins until edited. */
class UssdStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun codes(sim: BalanceSim): List<UssdCode> =
        UssdCodes.decode(prefs.getString(KEY_PREFIX + sim.storeKey, null)) ?: UssdCodes.defaults(sim.network)

    fun save(sim: BalanceSim, codes: List<UssdCode>) {
        prefs.edit().putString(KEY_PREFIX + sim.storeKey, UssdCodes.encode(codes)).apply()
    }

    /** Back to the built-in list for this SIM. */
    fun reset(sim: BalanceSim) {
        prefs.edit().remove(KEY_PREFIX + sim.storeKey).apply()
    }

    companion object {
        const val PREFS = "ussd_codes"
        const val KEY_PREFIX = "codes."
    }
}

sealed interface UssdResult {
    data class Reply(val text: String) : UssdResult
    /** The network or phone refused; [code] is TelephonyManager's USSD_RETURN_FAILURE etc. */
    data class Failed(val code: Int) : UssdResult
    data object NoPermission : UssdResult
}

object UssdRunner {
    /**
     * Sends [code] on [subId] and calls [onResult] on the main thread. The
     * caller decides whether the code may be sent (see [UssdCode.oneTap]).
     */
    @SuppressLint("MissingPermission") // checked just below
    fun send(context: Context, subId: Int, code: String, onResult: (UssdResult) -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            onResult(UssdResult.NoPermission)
            return
        }
        val handler = Handler(Looper.getMainLooper())
        try {
            val tm = context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(subId)
            tm.sendUssdRequest(UssdCodes.normalize(code), object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(telephonyManager: TelephonyManager, request: String, response: CharSequence) {
                    onResult(UssdResult.Reply(response.toString()))
                }

                override fun onReceiveUssdResponseFailed(telephonyManager: TelephonyManager, request: String, failureCode: Int) {
                    onResult(UssdResult.Failed(failureCode))
                }
            }, handler)
        } catch (e: SecurityException) {
            onResult(UssdResult.NoPermission)
        } catch (e: Exception) {
            e.printStackTrace()
            onResult(UssdResult.Failed(TelephonyManager.USSD_RETURN_FAILURE))
        }
    }
}
