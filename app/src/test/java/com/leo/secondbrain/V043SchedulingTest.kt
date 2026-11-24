package com.leo.secondbrain

import com.leo.secondbrain.ai.flexibleTime
import com.leo.secondbrain.data.CalendarAvailability
import com.leo.secondbrain.data.DraftKind
import com.leo.secondbrain.data.EntryEntity
import com.leo.secondbrain.data.EntryKind
import com.leo.secondbrain.data.Event
import com.leo.secondbrain.data.Proposal
import com.leo.secondbrain.data.TaskSlot
import com.leo.secondbrain.data.WorkspaceSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class V043SchedulingTest {
    private val today = LocalDate.of(2026, 11, 21)
    private val tomorrow = today.plusDays(1).toEpochDay()
    private val task = Proposal(DraftKind.TASK, "Apply to two internships")
    private val meeting = Event(title = "Meeting", startDay = tomorrow, endDay = tomorrow,
        startMinute = 570, endMinute = 608, allDay = false)
    private val workspace = WorkspaceSnapshot(emptyList(), emptyList(), emptyList(), listOf(meeting), emptyList())

    @Test
    fun flexibleMorningFitsAroundCalendarAndSkipsCompletedTasks() {
        assertNull(flexibleTime("Tomorrow morning", "Tomorrow morning, apply", today, task, workspace))
        val request = "Find a free slot tomorrow morning"
        val window = requireNotNull(flexibleTime(request, request, today, task, workspace))
        assertEquals(60, window.duration)
        val existingTask = EntryEntity(kind = EntryKind.TASK, title = "Breakfast", deadline = tomorrow, dueMinute = 480)
        val completed = existingTask.copy(id = "done", title = "Already done", dueMinute = 615, completed = true)
        val available = CalendarAvailability(listOf(existingTask, completed), listOf(meeting), emptyMap())
        assertEquals(TaskSlot(tomorrow, 510), available.find(window, today.atStartOfDay(ZoneOffset.UTC)))
        val later = today.plusDays(1).atTime(9, 4).atZone(ZoneOffset.UTC)
        assertEquals(TaskSlot(tomorrow, 615), available.find(window, later))
        assertNull(available.find(window, later.withHour(11).withMinute(30)))
    }

    @Test
    fun preservesFixedTimesAndInterpretsDeadlineAndMeetingConstraints() {
        assertNull(flexibleTime("Tomorrow morning at 10 a.m.", "Tomorrow morning at 10 a.m.", today, task, workspace))
        val deadline = requireNotNull(flexibleTime("Before Friday", "Before Friday", today, task, workspace))
        assertEquals(today.plusDays(5).toEpochDay(), deadline.deadlineDay)
        val after = requireNotNull(flexibleTime("After my meeting", "After my meeting", today, task, workspace))
        assertEquals(tomorrow, after.firstDay)
        assertEquals(615, after.fromMinute)
        val nextWeek = requireNotNull(flexibleTime("Next week", "Next week", today, task, workspace))
        assertEquals(today.plusDays(2).toEpochDay(), nextWeek.firstDay)
        assertEquals(6L, nextWeek.lastDay - nextWeek.firstDay)
    }
}
