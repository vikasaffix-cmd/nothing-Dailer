package com.example.allinonedailer.data

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Response contract for a number-intelligence endpoint.
 *
 * This app ships with NO bundled caller-ID database. To get real name / spam
 * data for numbers that are not in the device contacts, point the app at a
 * licensed lookup service (e.g. your own backend wrapping Twilio Lookup, Hiya,
 * Numverify, etc.) via Settings. Fields are optional so partial responses are
 * tolerated.
 */
data class RemoteLookupResponse(
    val name: String? = null,
    val spam: Boolean = false,
    val spamCategory: String? = null,
    val carrier: String? = null,
    val location: String? = null
)

interface NumberLookupApi {
    /** GET {endpoint}/lookup?number=+1555... */
    @GET("lookup")
    suspend fun lookup(@Query("number") number: String): RemoteLookupResponse
}
