package com.leo.secondbrain

import com.leo.secondbrain.data.Repeat
import com.leo.secondbrain.ai.ScheduleEvidence
import com.leo.secondbrain.data.nextReminder
import com.leo.secondbrain.updates.isNewRelease
import com.leo.secondbrain.updates.validRepository
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class V02ScheduleTest {
    @Test
    fun monthEndRepeatReturnsToOriginalDay() {
        val anchor = LocalDate.of(2026, 1, 31)
        assertEquals(LocalDate.of(2026, 2, 28), Repeat.MONTHLY.next(anchor, LocalDate.of(2026, 2, 1)))
        assertEquals(LocalDate.of(2026, 3, 31), Repeat.MONTHLY.next(anchor, LocalDate.of(2026, 3, 1)))
    }

    @Test
    fun advanceReminderCanLandOnPreviousDay() {
        val date = LocalDate.of(2026, 11, 20).toEpochDay()
        val zone = ZoneId.of("Europe/Berlin")
        val now = Instant.parse("2026-11-19T18:00:00Z")
        assertEquals(Instant.parse("2026-11-19T22:30:00Z"), nextReminder(date, 0, 30, Repeat.NONE, now, zone))
        assertNull(nextReminder(date, 0, 30, Repeat.NONE, Instant.parse("2026-11-19T22:30:00Z"), zone))
    }

    @Test
    fun morningReminderSubtractsTheFullRequestedLeadTime() {
        val zone = ZoneId.of("Europe/Berlin")
        val day = LocalDate.of(2026, 11, 25)
        val expected = day.atTime(9, 0).atZone(zone).toInstant()
        assertEquals(expected, nextReminder(day.toEpochDay(), 9 * 60 + 30, 30,
            Repeat.NONE, day.atStartOfDay(zone).toInstant(), zone))
    }

    @Test
    fun releaseTagsCompareNumericallyAndRejectInvalidInputs() {
        assertTrue(isNewRelease("v0.10", "0.2"))
        assertFalse(isNewRelease("v0.2.0", "0.2"))
        assertFalse(isNewRelease("v0.3-beta", "0.2"))
        assertFalse(validRepository("org/.."))
        assertTrue(validRepository("org/second-brain"))
    }

    @Test
    fun literalScheduleDoesNotSupportInventedDatesOrEndTimes() {
        val today = LocalDate.of(2026, 11, 20)
        val quote = "Lunch tomorrow at 12:15 PM, remind me 30 minutes before"
        val evidence = ScheduleEvidence(quote, quote, today)
        assertEquals(today.plusDays(1).toEpochDay(), evidence.date("2026-11-21"))
        assertNull(evidence.date("2026-11-22"))
        assertEquals(735, evidence.time("12:15"))
        assertNull(evidence.time("13:15"))
        assertTrue(evidence.reminder(30))
        assertFalse(evidence.reminder(60))
    }

    @Test
    fun followUpWeekdayDoesNotInventATime() {
        val wednesday = LocalDate.of(2026, 11, 18)
        val quote = "Actually, move that to Friday afternoon."
        val evidence = ScheduleEvidence(quote, quote, wednesday)
        assertEquals(LocalDate.of(2026, 11, 20).toEpochDay(), evidence.date("2026-11-20"))
        assertNull(evidence.time("15:00"))
        val nextWeek = "in 2 weeks"
        assertEquals(wednesday.plusWeeks(2).toEpochDay(),
            ScheduleEvidence(nextWeek, nextWeek, wednesday).date("2026-12-02"))
    }
}
