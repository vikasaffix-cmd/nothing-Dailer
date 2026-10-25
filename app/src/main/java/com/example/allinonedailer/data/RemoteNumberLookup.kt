package com.example.allinonedailer.data

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Thin, opt-in network caller-ID lookup.
 *
 * Does nothing unless the user has configured a lookup endpoint in Settings.
 * Any failure (no endpoint, network error, bad response) returns null so the
 * caller falls back to an honest "unknown number" — the app never fabricates a
 * name or a "verified" result.
 */
object RemoteNumberLookup {

    @Volatile private var cachedBaseUrl: String? = null
    @Volatile private var api: NumberLookupApi? = null

    private fun apiFor(baseUrl: String): NumberLookupApi? {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        api?.let { if (cachedBaseUrl == normalized) return it }
        return try {
            Retrofit.Builder()
                .baseUrl(normalized)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(NumberLookupApi::class.java)
                .also {
                    api = it
                    cachedBaseUrl = normalized
                }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Suspend. Call off the main thread. Returns null when unavailable. */
    suspend fun lookup(baseUrl: String, number: String): RemoteLookupResponse? {
        if (baseUrl.isBlank()) return null
        val service = apiFor(baseUrl) ?: return null
        return try {
            service.lookup(number)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
