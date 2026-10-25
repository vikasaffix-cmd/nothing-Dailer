package com.example.allinonedailer.data

import android.content.Context
import android.telephony.TelephonyManager
import com.google.i18n.phonenumbers.PhoneNumberToCarrierMapper
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import java.util.Locale

/** What libphonenumber can tell about a number without any network access. */
data class NumberDetails(
    val carrier: String?,
    val region: String?,
    val type: String?
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

    /** The country to assume for numbers dialled without a +country code. */
    fun defaultRegion(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val iso = tm?.networkCountryIso?.takeIf { it.isNotBlank() }
            ?: tm?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country
        return iso.uppercase(Locale.ROOT)
    }

    /** Call off the main thread: the first call loads metadata from the jar. */
    fun describe(number: String, defaultRegion: String, locale: Locale = Locale.ENGLISH): NumberDetails? {
        if (number.count(Char::isDigit) < 5) return null
        return try {
            val parsed = util.parse(number, defaultRegion)
            if (!util.isValidNumber(parsed)) return null

            val type = when (util.getNumberType(parsed)) {
                PhoneNumberUtil.PhoneNumberType.MOBILE -> "Mobile"
                PhoneNumberUtil.PhoneNumberType.FIXED_LINE -> "Landline"
                PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE -> "Phone"
                PhoneNumberUtil.PhoneNumberType.TOLL_FREE -> "Toll-free"
                PhoneNumberUtil.PhoneNumberType.PREMIUM_RATE -> "Premium-rate"
                PhoneNumberUtil.PhoneNumberType.SHARED_COST -> "Shared-cost"
                PhoneNumberUtil.PhoneNumberType.VOIP -> "VoIP"
                else -> null
            }
            NumberDetails(
                carrier = carriers.getNameForNumber(parsed, locale).ifBlank { null },
                region = geocoder.getDescriptionForNumber(parsed, locale).ifBlank { null },
                type = type
            )
        } catch (e: Exception) {
            null
        }
    }
}
