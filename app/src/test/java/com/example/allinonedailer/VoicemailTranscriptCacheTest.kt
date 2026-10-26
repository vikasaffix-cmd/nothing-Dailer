package com.example.allinonedailer

import com.example.allinonedailer.data.TranscriptCache
import com.example.allinonedailer.data.TranscriptCodec
import com.example.allinonedailer.data.TranscriptStatus
import com.example.allinonedailer.data.TranscriptStore
import com.example.allinonedailer.data.VoicemailSearch
import com.example.allinonedailer.data.VoicemailTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicemailTranscriptCacheTest {

    private class MapStore : TranscriptStore {
        val map = mutableMapOf<String, String>()
        override fun read(key: String) = map[key]
        override fun write(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private var clock = 1_000_000L
    private var changes = 0
    private val store = MapStore()
    private val cache = TranscriptCache(store, now = { clock }, onChange = { changes++ })

    private val uri = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FVoicemail/document/primary%3ADownload%2FVoicemail%2Fvm.wav"

    private fun done(modified: Long, text: String = "hello") = VoicemailTranscript(
        status = TranscriptStatus.DONE,
        sourceModified = modified,
        updatedAt = clock,
        language = "en",
        text = text,
        summary = "Ravi: delivery, 5 pm",
        reminderStart = 1_800_000_000_000L,
        reminderAllDay = false
    )

    @Test
    fun codecRoundTripsEveryField() {
        val t = done(42L, text = "line one\nline two \u001F with a separator").copy(requestedLanguage = "mr", reminderAllDay = true)
        val back = TranscriptCodec.decode(TranscriptCodec.encode(t))!!
        assertEquals(t.copy(text = "line one\nline two   with a separator"), back)
    }

    @Test
    fun codecKeepsNulls() {
        val t = VoicemailTranscript(TranscriptStatus.PENDING, sourceModified = 7L, updatedAt = 8L)
        assertEquals(t, TranscriptCodec.decode(TranscriptCodec.encode(t)))
    }

    @Test
    fun codecRejectsGarbageAndOtherVersions() {
        assertNull(TranscriptCodec.decode("not a transcript"))
        assertNull(TranscriptCodec.decode(TranscriptCodec.encode(done(1L)).replaceFirst("1", "9")))
    }

    @Test
    fun aChangedFileIsNotServedFromCache() {
        cache.put(uri, done(100L))
        assertEquals("hello", cache.get(uri, 100L)?.text)
        assertNull(cache.get(uri, 200L))
        assertTrue(cache.needsWork(uri, 200L))
        assertFalse(cache.needsWork(uri, 100L))
    }

    @Test
    fun missingFilesNeedWorkButFailuresWaitForRetry() {
        assertTrue(cache.needsWork(uri, 1L))
        cache.put(uri, done(1L).copy(status = TranscriptStatus.FAILED))
        assertFalse(cache.needsWork(uri, 1L))
    }

    @Test
    fun pendingWorkIsRetriedOnlyOnceStale() {
        cache.markPending(uri, 5L, requestedLanguage = "hi")
        val pending = cache.get(uri, 5L)!!
        assertEquals(TranscriptStatus.PENDING, pending.status)
        assertEquals("hi", pending.requestedLanguage)
        assertFalse(cache.needsWork(uri, 5L))
        clock += TranscriptCache.STALE_PENDING_MS + 1
        assertTrue(cache.needsWork(uri, 5L))
    }

    @Test
    fun pruneDropsDeletedFilesAndNotifies() {
        cache.put(uri, done(1L))
        cache.put("content://other", done(1L))
        changes = 0
        assertEquals(1, cache.prune(listOf(uri)))
        assertEquals(setOf(uri), store.keys())
        assertEquals(1, changes)
        assertEquals(0, cache.prune(listOf(uri)))
        assertEquals(1, changes)
    }

    @Test
    fun searchMatchesTranscriptWordsCaseAndDigits() {
        val fields = arrayOf("Ravi Kumar", "+91 98765 43210", "vm_20261026.wav", "Ravi: delivery, कल 5 बजे", "Your PARCEL is out for delivery, ₹500")
        assertTrue(VoicemailSearch.matches("parcel", *fields))
        assertTrue(VoicemailSearch.matches("RAVI delivery", *fields))
        assertTrue(VoicemailSearch.matches("५००", *fields))
        assertTrue(VoicemailSearch.matches("98765432", *fields))
        assertTrue(VoicemailSearch.matches("बजे", *fields))
        assertTrue(VoicemailSearch.matches("  ", *fields))
        assertFalse(VoicemailSearch.matches("parcel refund", *fields))
        assertFalse(VoicemailSearch.matches("priya", *fields))
    }

    @Test
    fun searchIgnoresNuktaSpelling() {
        assertTrue(VoicemailSearch.matches("फोन", "कृपया फ़ोन करें"))
        assertTrue(VoicemailSearch.matches("फ़ोन", "कृपया फोन करें"))
    }
}
