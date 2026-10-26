package com.example.allinonedailer.data

import android.content.Context
import android.os.SystemClock
import android.telephony.TelephonyManager
import com.google.i18n.phonenumbers.PhoneNumberToCarrierMapper
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import java.util.Locale
import com.example.allinonedailer.R

/** What libphonenumber can tell about a number without any network access. */
data class NumberDetails(
    val carrier: String?,
    val region: String?,
    val type: String?,
    val premium: Boolean = false
) {
    /** e.g. "Jio · Maharashtra", "Toll-free · India", or null if nothing is known. */
    val label: String?
        get() = listOfNotNull(carrier ?: type, region).distinct().joinToString(" · ").ifBlank { null }
}

/**
 * Offline number lookup using Google's libphonenumber metadata (Apache-2.0).
 *
 * Carrier is the operator the number range was originally assigned to, so it
 * can be out of date for ported numbers. Nothing leaves the device.
 */
object NumberInfo {

    private val util by lazy { PhoneNumberUtil.getInstance() }
    private val geocoder by lazy { PhoneNumberOfflineGeocoder.getInstance() }
    private val carriers by lazy { PhoneNumberToCarrierMapper.getInstance() }

    @Volatile
    private var region: Pair<Long, String>? = null

    /**
     * The country to assume for numbers dialled without a +country code.
     * Remembered for a minute: Recents asks once per unknown number, and each
     * TelephonyManager read is a binder call.
     */
    fun defaultRegion(context: Context): String {
        val now = SystemClock.elapsedRealtime()
        region?.let { (at, iso) -> if (now - at < 60_000) return iso }
        val tm = context.getSystemService(TelephonyManager::class.java)
        val iso = tm?.networkCountryIso?.takeIf { it.isNotBlank() }
            ?: tm?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country
        return iso.uppercase(Locale.ROOT).also { region = now to it }
    }

    /**
     * Call off the main thread: the first call loads metadata from the jar.
     * With a [context], the line type is in the app's language.
     */
    fun describe(
        number: String,
        defaultRegion: String,
        locale: Locale = Locale.ENGLISH,
        context: Context? = null
    ): NumberDetails? {
        if (number.count(Char::isDigit) < 5) return null
        return try {
            val parsed = util.parse(number, defaultRegion)
            if (!util.isValidNumber(parsed)) return null

            val numberType = util.getNumberType(parsed)
            val (english, res) = when (numberType) {
                PhoneNumberUtil.PhoneNumberType.MOBILE -> "Mobile" to R.string.number_type_mobile
                PhoneNumberUtil.PhoneNumberType.FIXED_LINE -> "Landline" to R.string.number_type_landline
                PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE -> "Phone" to R.string.number_type_phone
                PhoneNumberUtil.PhoneNumberType.TOLL_FREE -> "Toll-free" to R.string.number_type_toll_free
                PhoneNumberUtil.PhoneNumberType.PREMIUM_RATE -> "Premium-rate" to R.string.number_type_premium
                PhoneNumberUtil.PhoneNumberType.SHARED_COST -> "Shared-cost" to R.string.number_type_shared_cost
                PhoneNumberUtil.PhoneNumberType.VOIP -> "VoIP" to R.string.number_type_voip
                else -> null to 0
            }
            NumberDetails(
                carrier = carriers.getNameForNumber(parsed, locale).ifBlank { null },
                region = geocoder.getDescriptionForNumber(parsed, locale).ifBlank { null },
                type = if (english != null && context != null) context.getString(res) else english,
                premium = numberType == PhoneNumberUtil.PhoneNumberType.PREMIUM_RATE
            )
        } catch (e: Exception) {
            null
        }
    }
}
