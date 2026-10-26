package com.example.allinonedailer

import com.example.allinonedailer.data.ReminderPreset
import com.example.allinonedailer.data.ReminderTimes
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderTimesTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun t(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun minutesAndHour() {
        val now = t(26, 14, 10)
        assertEquals(t(26, 14, 25), ReminderTimes.at(ReminderPreset.MINUTES_15, now, zone))
        assertEquals(t(26, 15, 10), ReminderTimes.at(ReminderPreset.HOUR_1, now, zone))
    }

    @Test
    fun hourCrossesMidnight() {
        assertEquals(t(27, 0, 20), ReminderTimes.at(ReminderPreset.HOUR_1, t(26, 23, 20), zone))
    }

    @Test
    fun tonightIsEightPmToday() {
        assertEquals(t(26, 20), ReminderTimes.at(ReminderPreset.TONIGHT, t(26, 14), zone))
    }

    @Test
    fun morningIsNextNineAm() {
        assertEquals(t(27, 9), ReminderTimes.at(ReminderPreset.MORNING, t(26, 22), zone))
        // After midnight, "morning" is the same day.
        assertEquals(t(27, 9), ReminderTimes.at(ReminderPreset.MORNING, t(27, 1), zone))
        // At exactly 9:00 it's the next day.
        assertEquals(t(28, 9), ReminderTimes.at(ReminderPreset.MORNING, t(27, 9), zone))
    }

    @Test
    fun presetsSwitchFromTonightToMorning() {
        val day = listOf(ReminderPreset.MINUTES_15, ReminderPreset.HOUR_1, ReminderPreset.TONIGHT)
        val night = listOf(ReminderPreset.MINUTES_15, ReminderPreset.HOUR_1, ReminderPreset.MORNING)
        assertEquals(day, ReminderTimes.presets(t(26, 10), zone))
        assertEquals(day, ReminderTimes.presets(t(26, 19, 44), zone))
        assertEquals(night, ReminderTimes.presets(t(26, 19, 45), zone))
        assertEquals(night, ReminderTimes.presets(t(26, 23), zone))
        assertEquals(night, ReminderTimes.presets(t(27, 3), zone))
        assertEquals(day, ReminderTimes.presets(t(27, 5), zone))
    }

    @Test
    fun snoozeIsFifteenMinutes() {
        assertEquals(t(26, 20, 15), ReminderTimes.snooze(t(26, 20)))
    }

    @Test
    fun overdueAfterRebootFiresSoon() {
        val now = t(26, 12)
        assertEquals(t(26, 13), ReminderTimes.alarmTime(t(26, 13), now))
        assertEquals(now + 5_000L, ReminderTimes.alarmTime(t(26, 11), now))
    }
}
