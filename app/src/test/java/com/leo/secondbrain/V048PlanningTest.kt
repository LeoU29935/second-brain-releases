package com.leo.secondbrain

import com.leo.secondbrain.ai.TaskPlanner
import com.leo.secondbrain.data.*
import com.leo.secondbrain.ui.relativeCalendarDate
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale

class V048PlanningTest {
    private val now = ZonedDateTime.parse("2026-11-23T09:00:00+01:00[Europe/Berlin]")
    private val tomorrow = now.toLocalDate().plusDays(1)
    private val day = tomorrow.toEpochDay()
    private val empty = WorkspaceSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    @Test
    fun respectsAvailableTimeAndReservesSlotsForMultipleActions() {
        val meeting = Event(title = "Stand-up", startDay = day, endDay = day, startMinute = 480, endMinute = 540, allDay = false)
        val folder = CollectionEntity(name = "German")
        val planner = TaskPlanner(empty.copy(collections = listOf(folder), events = listOf(meeting)), now)
        val capture = "Tomorrow morning practice German for 45 minutes, then call Dad."
        val study = planner.plan(Proposal(DraftKind.TASK, "Practice German", startDay = day, timeOfDay = TimeOfDay.MORNING),
            "Tomorrow morning practice German for 45 minutes", capture)
        val call = planner.plan(Proposal(DraftKind.TASK, "Call Dad", startDay = day, timeOfDay = TimeOfDay.MORNING),
            "call Dad", capture)
        assertEquals(540, study.startMinute)
        assertEquals(45, study.durationMinutes)
        assertEquals(folder.id, study.collectionId)
        assertEquals("time,reminder", study.inferredSchedule)
        assertEquals(585, call.startMinute)
        assertEquals(0, call.reminder)
        assertNotEquals(call.reminder, study.reminder)
        assertNull(study.hardDeadline)
    }

    @Test
    fun relativeTimesUseTheActualZoneAndExplicitPastTimesAreFlagged() {
        val spring = ZonedDateTime.parse("2026-03-29T01:30:00+01:00[Europe/Berlin]")
        val planner = TaskPlanner(empty, spring)
        val quote = "In three hours, remind me to send the email."
        val task = planner.plan(Proposal(DraftKind.TASK, "Send the email"), quote, quote)
        assertEquals(330, task.startMinute)
        val early = planner.plan(Proposal(DraftKind.TASK, "Past task", startDay = spring.toLocalDate().toEpochDay(), startMinute = 30),
            "Past task", "Past task")
        assertEquals(30, early.startMinute)
        assertTrue(early.uncertainty.contains("passed"))
        assertNotNull(early.reminder)
        val missing = planner.plan(Proposal(DraftKind.TASK, "Clock gap", startDay = spring.toLocalDate().toEpochDay(), startMinute = 150),
            "Clock gap", "Clock gap")
        assertTrue(missing.uncertainty.contains("clock change"))
    }

    @Test
    fun recurrenceHandlesSpecificDaysIntervalsAndSkippedNotifications() {
        val planner = TaskPlanner(empty, now)
        val phrase = "Every Monday and Thursday I want to exercise."
        val proposed = planner.plan(Proposal(DraftKind.TASK, "Exercise"), phrase, phrase)
        assertEquals(Repeat.WEEKLY, proposed.repeat)
        val rule = requireNotNull(proposed.recurrenceRule)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), rule.weekdays)
        assertEquals(tomorrow.plusDays(2), rule.next(Repeat.WEEKLY, now.toLocalDate(), tomorrow))
        assertEquals(now.toLocalDate().plusWeeks(1), Repeat.WEEKLY.nextUnskipped(now.toLocalDate(), tomorrow,
            setOf(tomorrow.plusDays(2).toEpochDay()), rule))
        val interval = "Water plants every three days."
        val other = planner.plan(Proposal(DraftKind.TASK, "Water plants"), interval, interval)
        assertEquals(Repeat.DAILY, other.repeat)
        assertEquals(3, other.recurrenceRule?.interval)
    }

    @Test
    fun manualUnscheduledAndRelativeCalendarLabels() {
        val text = "Create an unscheduled task to call Dad"
        val task = TaskPlanner(empty, now).plan(Proposal(DraftKind.TASK, "Call Dad"), text, text)
        assertNull(task.startDay)
        assertNull(task.reminder)
        assertEquals("Today", relativeCalendarDate(tomorrow, tomorrow, Locale.UK))
        assertEquals("Tomorrow", relativeCalendarDate(tomorrow, tomorrow.minusDays(1), Locale.UK))
        assertEquals("In 3 days", relativeCalendarDate(tomorrow, tomorrow.minusDays(3), Locale.UK))
        assertEquals("Next week", relativeCalendarDate(tomorrow, tomorrow.minusDays(7), Locale.UK))
    }
}
