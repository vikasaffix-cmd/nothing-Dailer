package com.example.allinonedailer

import com.example.allinonedailer.data.backup.BackupCodec
import com.example.allinonedailer.data.backup.BackupData
import com.example.allinonedailer.data.backup.BackupException
import com.example.allinonedailer.data.backup.BackupMerge
import com.example.allinonedailer.data.backup.BackupSpec
import com.example.allinonedailer.data.backup.BackupSummary
import com.example.allinonedailer.data.backup.PrefValue
import com.example.allinonedailer.data.backup.RestoreMode
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class BackupFormatTest {

    private val notes = """[{"key":"a","number":"+919876543210","start":1,"end":2,"text":"फ़ोन किया — बात हुई","edited":3}]"""

    private fun sample(language: String? = "mr") = BackupData(
        createdAt = 1_793_000_000_000L,
        sourcePackage = "com.maihuvikas.dialer",
        appVersion = "1.0",
        language = language,
        prefs = mapOf(
            "caller_id_prefs" to mapOf(
                "spam_block_enabled" to PrefValue.Bool(false),
                "blocked_numbers" to PrefValue.StrSet(setOf("+919876543210", "1409999999")),
                "prefix_rules" to PrefValue.StrSet(setOf("140"))
            ),
            "haptics_prefs" to mapOf(
                "strength" to PrefValue.Str("STRONG"),
                "level" to PrefValue.IntValue(-7),
                "since" to PrefValue.LongValue(Long.MAX_VALUE),
                "scale" to PrefValue.FloatValue(0.45f)
            ),
            "call_notes" to mapOf("notes" to PrefValue.Str(notes)),
            "speed_dial" to mapOf("key_2" to PrefValue.Str("""{"name":"Dad","number":"98"}""")),
            "spam_reports" to mapOf("9876543210" to PrefValue.Str("1|+919876543210"))
        ),
        hasFont = true,
        recordings = listOf("rec_1.m4a"),
        secretsIncluded = false
    )

    @Test
    fun roundTripKeepsEveryTypeAndText() {
        val data = sample()
        assertEquals(data, BackupCodec.decode(BackupCodec.encode(data)))
    }

    @Test
    fun roundTripWithoutLanguageAndEmptyPrefs() {
        val data = sample(language = null).copy(prefs = emptyMap(), hasFont = false, recordings = emptyList())
        val back = BackupCodec.decode(BackupCodec.encode(data))
        assertNull(back.language)
        assertEquals(data, back)
    }

    @Test
    fun phoneLanguageIsKeptAsEmptyTag() {
        assertEquals("", BackupCodec.decode(BackupCodec.encode(sample(language = ""))).language)
    }

    @Test
    fun manifestCarriesFormatAndVersion() {
        val obj = JsonParser.parseString(BackupCodec.encode(sample())).asJsonObject
        assertEquals("nodialer-backup", obj.get("format").asString)
        assertEquals(BackupCodec.VERSION, obj.get("version").asInt)
    }

    @Test
    fun newerVersionIsRefused() {
        val text = BackupCodec.encode(sample()).replace("\"version\": 1", "\"version\": 99")
        assertReason(BackupException.Reason.TOO_NEW, text)
        try {
            BackupCodec.decode(text)
        } catch (e: BackupException) {
            assertEquals(99, e.version)
        }
    }

    @Test
    fun notABackupIsRefused() {
        assertReason(BackupException.Reason.NOT_A_BACKUP, "hello")
        assertReason(BackupException.Reason.NOT_A_BACKUP, "[1,2]")
        assertReason(BackupException.Reason.NOT_A_BACKUP, """{"format":"other","version":1}""")
    }

    @Test
    fun missingOrBadVersionIsCorrupt() {
        assertReason(BackupException.Reason.CORRUPT, """{"format":"nodialer-backup"}""")
        assertReason(BackupException.Reason.CORRUPT, """{"format":"nodialer-backup","version":0}""")
        assertReason(BackupException.Reason.CORRUPT, """{"format":"nodialer-backup","version":"x"}""")
    }

    @Test
    fun unknownValueTypeIsCorrupt() {
        val text = """{"format":"nodialer-backup","version":1,"prefs":{"a":{"k":{"type":"blob","value":1}}}}"""
        assertReason(BackupException.Reason.CORRUPT, text)
    }

    @Test
    fun secretsAndDeviceOnlyKeysStayOut() {
        val callerId = BackupSpec.file("caller_id_prefs")!!
        val values = mapOf("lookup_endpoint" to PrefValue.Str("https://x/?key=s3cret"), "caller_id_enabled" to PrefValue.Bool(true))
        assertEquals(setOf("caller_id_enabled"), BackupSpec.exportable(callerId, values, includeSecrets = false).keys)
        assertEquals(values.keys, BackupSpec.exportable(callerId, values, includeSecrets = true).keys)

        val voicemail = BackupSpec.file("voicemail")!!
        val vm = mapOf(
            "folder_uri" to PrefValue.Str("content://tree"),
            "heard" to PrefValue.StrSet(setOf("content://tree/doc")),
            "transcribe_enabled" to PrefValue.Bool(true),
            "service_number" to PrefValue.Str("123")
        )
        assertEquals(setOf("transcribe_enabled", "service_number"), BackupSpec.exportable(voicemail, vm, includeSecrets = true).keys)
    }

    @Test
    fun smartSimLastUsedStaysOnThisPhone() {
        val spec = BackupSpec.file("smart_sim")!!
        val values = mapOf(
            "enabled" to PrefValue.Bool(true),
            "pin.9876543210" to PrefValue.Str("sim1"),
            "last.9876543210" to PrefValue.Str("sim2")
        )
        assertEquals(setOf("enabled", "pin.9876543210"), BackupSpec.exportable(spec, values, includeSecrets = true).keys)
        val replaced = BackupMerge.merge("smart_sim", values, mapOf("enabled" to PrefValue.Bool(false)), RestoreMode.REPLACE)
        assertEquals(setOf("enabled", "last.9876543210"), replaced.keys)
    }

    @Test
    fun widgetCacheIsNotBackedUp() {
        assertNull(BackupSpec.file("dialer_widget"))
        assertNull(BackupSpec.file("voicemail_transcripts"))
    }

    @Test
    fun mergeKeepsLocalAndUnionsSets() {
        val local = mapOf(
            "spam_block_enabled" to PrefValue.Bool(true),
            "blocked_numbers" to PrefValue.StrSet(setOf("111"))
        )
        val incoming = mapOf(
            "spam_block_enabled" to PrefValue.Bool(false),
            "blocked_numbers" to PrefValue.StrSet(setOf("222")),
            "block_hidden_numbers" to PrefValue.Bool(true)
        )
        val merged = BackupMerge.merge("caller_id_prefs", local, incoming, RestoreMode.MERGE)
        assertEquals(PrefValue.Bool(true), merged["spam_block_enabled"])
        assertEquals(PrefValue.StrSet(setOf("111", "222")), merged["blocked_numbers"])
        assertEquals(PrefValue.Bool(true), merged["block_hidden_numbers"])
    }

    @Test
    fun replaceTakesBackupButKeepsLocalSecretsAndFolder() {
        val local = mapOf(
            "lookup_endpoint" to PrefValue.Str("https://mine"),
            "blocked_numbers" to PrefValue.StrSet(setOf("111"))
        )
        val incoming = mapOf("blocked_numbers" to PrefValue.StrSet(setOf("222")))
        val merged = BackupMerge.merge("caller_id_prefs", local, incoming, RestoreMode.REPLACE)
        assertEquals(PrefValue.StrSet(setOf("222")), merged["blocked_numbers"])
        assertEquals(PrefValue.Str("https://mine"), merged["lookup_endpoint"])

        val vm = BackupMerge.merge(
            "voicemail",
            mapOf("folder_uri" to PrefValue.Str("content://tree"), "service_number" to PrefValue.Str("1")),
            emptyMap(),
            RestoreMode.REPLACE
        )
        assertEquals(mapOf("folder_uri" to PrefValue.Str("content://tree")), vm)
    }

    @Test
    fun replaceUsesBackupSecretWhenIncluded() {
        val merged = BackupMerge.merge(
            "caller_id_prefs",
            mapOf("lookup_endpoint" to PrefValue.Str("https://mine")),
            mapOf("lookup_endpoint" to PrefValue.Str("https://theirs")),
            RestoreMode.REPLACE
        )
        assertEquals(PrefValue.Str("https://theirs"), merged["lookup_endpoint"])
    }

    @Test
    fun notesMergeItemByItem() {
        val local = """[{"key":"a","text":"mine"},{"key":"b","text":"b"}]"""
        val incoming = """[{"key":"a","text":"theirs"},{"key":"c","text":"c"}]"""
        val merged = BackupMerge.merge(
            "call_notes",
            mapOf("notes" to PrefValue.Str(local)),
            mapOf("notes" to PrefValue.Str(incoming)),
            RestoreMode.MERGE
        )["notes"] as PrefValue.Str
        val items = JsonParser.parseString(merged.value).asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("a", "b", "c"), items.map { it.get("key").asString })
        assertEquals("mine", items[0].get("text").asString)
    }

    @Test
    fun remindersMergeByNumericIdAndRepeatCallersStayHere() {
        val merged = BackupMerge.merge(
            "call_reminders",
            mapOf("items" to PrefValue.Str("""[{"id":1,"number":"1","at":10}]""")),
            mapOf("items" to PrefValue.Str("""[{"id":1,"number":"1","at":99},{"id":2,"number":"2","at":20}]""")),
            RestoreMode.MERGE
        )["items"] as PrefValue.Str
        val items = JsonParser.parseString(merged.value).asJsonArray.map { it.asJsonObject }
        assertEquals(listOf(1L, 2L), items.map { it.get("id").asLong })
        assertEquals(10L, items[0].get("at").asLong)

        val quiet = BackupSpec.file("quiet_hours")!!
        val values = mapOf("enabled" to PrefValue.Bool(true), "recent_incoming" to PrefValue.Str("[]"))
        assertEquals(setOf("enabled"), BackupSpec.exportable(quiet, values, includeSecrets = false).keys)
    }

    @Test
    fun badJsonListOnOneSideDoesNotLoseTheOther() {
        assertEquals("""[{"key":"c"}]""", BackupMerge.mergeJsonList("not json", """[{"key":"c"}]""", "key"))
        assertEquals("""[{"key":"a"}]""", BackupMerge.mergeJsonList("""[{"key":"a"}]""", "{", "key"))
    }

    @Test
    fun recordingPathsMoveToThisInstall() {
        val json = """[
            {"phoneNumber":"1","filePath":"/storage/emulated/0/Android/data/com.example.allinonedailer/files/recordings/a.m4a","timestamp":1,"durationSeconds":5},
            {"phoneNumber":"2","filePath":"/old/recordings/missing.m4a","timestamp":2,"durationSeconds":5}
        ]"""
        val dir = "/storage/emulated/0/Android/data/com.maihuvikas.dialer/files/recordings"
        val out = BackupMerge.rewriteRecordingPaths(json, dir) { it.endsWith("/a.m4a") }
        val items = JsonParser.parseString(out).asJsonArray.map { it.asJsonObject }
        assertEquals(1, items.size)
        assertEquals("$dir/a.m4a", items[0].get("filePath").asString)
        assertEquals(5, items[0].get("durationSeconds").asInt)
    }

    @Test
    fun summaryCountsWhatIsInside() {
        val s = BackupSummary.of(sample())
        assertEquals(2, s.blockedNumbers)
        assertEquals(1, s.blockRules)
        assertEquals(1, s.spamReports)
        assertEquals(1, s.notes)
        assertEquals(1, s.speedDials)
        assertEquals(0, s.networkNames)
        assertEquals(1, s.recordingFiles)
        assertTrue(s.hasFont)
        assertEquals("mr", s.language)
        assertFalse(s.voicemailSettings)
    }

    @Test
    fun fileNameIsDated() {
        assertEquals("NoDialer-backup-2026-10-26.zip", BackupSpec.fileName(LocalDate.of(2026, 10, 26)))
    }

    @Test
    fun recordingEntriesCannotEscapeTheFolder() {
        assertEquals("a.m4a", BackupSpec.safeRecordingName("recordings/a.m4a"))
        assertNull(BackupSpec.safeRecordingName("recordings/../shared_prefs/x.xml"))
        assertNull(BackupSpec.safeRecordingName("recordings/.."))
        assertNull(BackupSpec.safeRecordingName("recordings/a\\b"))
        assertNull(BackupSpec.safeRecordingName("files/custom_display_font"))
        assertNull(BackupSpec.safeRecordingName("recordings/"))
    }

    private fun assertReason(reason: BackupException.Reason, text: String) {
        try {
            BackupCodec.decode(text)
            fail("Expected $reason")
        } catch (e: BackupException) {
            assertEquals(reason, e.reason)
        }
    }
}
