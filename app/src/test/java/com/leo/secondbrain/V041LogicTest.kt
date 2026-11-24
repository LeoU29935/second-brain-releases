package com.leo.secondbrain

import com.leo.secondbrain.ai.EchoGate
import com.leo.secondbrain.ai.ScheduleEvidence
import com.leo.secondbrain.data.EntryEntity
import com.leo.secondbrain.data.EntryKind
import com.leo.secondbrain.data.Repeat
import com.leo.secondbrain.data.nextReminder
import com.leo.secondbrain.data.occursOn
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class V041LogicTest {
    @Test
    fun calendarUsesTheCaptureLocalDayAndValidatesApproximateTimes() {
        val captured = Instant.parse("2027-02-10T23:30:00Z").atZone(ZoneId.of("Europe/Zurich")).toLocalDate()
        assertEquals(LocalDate.of(2027, 2, 11), captured)
        val internship = "I want to apply to two internships tomorrow morning around 10 a.m."
        val exact = ScheduleEvidence("tomorrow morning around 10 a.m.", internship, captured)
        assertEquals(captured.plusDays(1).toEpochDay(), exact.date("2027-02-12"))
        assertEquals(600, exact.time("10:00"))
        assertNull(exact.time("09:30"))
        val vague = "Maybe phone the clinic next week"
        assertNull(ScheduleEvidence(vague, vague, captured).onlyDate())
        assertNull(ScheduleEvidence("at noon", internship, captured).onlyTime())

        val event = "Review on Friday at 23:30 for 90 minutes"
        val evidence = ScheduleEvidence(event, event, captured)
        assertEquals(captured.plusDays(1).toEpochDay(), evidence.onlyDate())
        assertEquals(1410, evidence.onlyTime())
        assertEquals(90, evidence.duration())
    }

    @Test
    fun playbackGateWaitsForQueuedAndWrittenFramesAndHandlesAnInterruption() {
        val gate = EchoGate()
        assertFalse(gate.blocks(0, 0, 60))
        val old = gate.enqueue()
        gate.finish()
        assertTrue(gate.blocks(0, 1, 60))
        gate.wrote(old, 2400)
        assertTrue(gate.blocks(2399, 500, 60))
        assertTrue(gate.blocks(2400, 600, 60))
        assertFalse(gate.blocks(2400, 660, 60))
        val interrupted = gate.enqueue()
        gate.interrupt(700)
        assertFalse(gate.isCurrent(interrupted))
        gate.wrote(interrupted, 5000)
        assertFalse(gate.blocks(0, 760, 60))
        assertTrue(gate.isCurrent(gate.enqueue()))
    }

    @Test
    fun scheduledOccurrencesAndRemindersRespectExceptionsAcrossDst() {
        val zone = ZoneId.of("Europe/Zurich")
        val start = LocalDate.of(2027, 3, 27)
        val skipped = start.plusDays(1)
        val expected = start.plusDays(2)
        val task = EntryEntity(kind = EntryKind.TASK, title = "A recurring task", deadline = start.toEpochDay(),
            dueMinute = 540, repeat = Repeat.DAILY)
        assertTrue(task.occursOn(start, setOf(skipped.toEpochDay())))
        assertFalse(task.occursOn(skipped, setOf(skipped.toEpochDay())))
        assertTrue(task.occursOn(expected, setOf(skipped.toEpochDay())))
        val next = nextReminder(start.toEpochDay(), 540, 0, Repeat.DAILY,
            start.atTime(12, 0).atZone(zone).toInstant(), zone, setOf(skipped.toEpochDay()))
        assertEquals(Instant.parse("2027-03-29T07:00:00Z"), next)
    }
}
