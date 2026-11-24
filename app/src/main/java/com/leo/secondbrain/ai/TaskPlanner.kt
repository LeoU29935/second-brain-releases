package com.leo.secondbrain.ai

import com.leo.secondbrain.data.CalendarAvailability
import com.leo.secondbrain.data.DraftKind
import com.leo.secondbrain.data.EntryEntity
import com.leo.secondbrain.data.EntryKind
import com.leo.secondbrain.data.FlexibleSchedule
import com.leo.secondbrain.data.Priority
import com.leo.secondbrain.data.Proposal
import com.leo.secondbrain.data.Repeat
import com.leo.secondbrain.data.TaskSlot
import com.leo.secondbrain.data.WorkspaceSnapshot
import com.leo.secondbrain.data.occurrenceInstant
import com.leo.secondbrain.data.pathName
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

internal const val UNSCHEDULED_REQUEST = "You requested an unscheduled task. Review and save it without a schedule."

internal class TaskPlanner(
    private val workspace: WorkspaceSnapshot,
    private val reference: ZonedDateTime,
    private val now: ZonedDateTime = reference
) {
    private val provisional = mutableListOf<EntryEntity>()
    private val today = now.toLocalDate().toEpochDay()
    private val referenceDay = reference.toLocalDate()

    fun plan(proposal: Proposal, quote: String, source: String): Proposal {
        if (proposal.kind != DraftKind.TASK || proposal.targetId != null) return proposal
        val evidence = ScheduleEvidence(quote, source, referenceDay)
        if (evidence.unscheduled()) return proposal.copy(
            startDay = null, startMinute = null, reminder = null, repeat = Repeat.NONE,
            timeOfDay = null, flexibleSchedule = null, recurrenceRule = null, inferredSchedule = null,
            uncertainty = UNSCHEDULED_REQUEST
        ).validated()

        val relative = evidence.relativeMinutes()?.let(reference::plusMinutes)
        val recurrence = evidence.recurrence()
        val deadline = proposal.hardDeadline ?: evidence.deadline(referenceDay)
        val evidenceDay = if (deadline == null) evidence.onlyDate() else evidence.plannedBeforeDeadline(referenceDay)
        val explicitDay = relative?.toLocalDate()?.toEpochDay()
            ?: (proposal.startDay ?: evidenceDay).takeUnless { (recurrence?.second?.weekdays?.size ?: 0) > 1 }
        val explicitMinute = relative?.let { it.hour * 60 + it.minute } ?: proposal.startMinute
            ?: evidence.onlyTime().takeIf { deadline == null || evidenceDay != null }
        val repeat = recurrence?.first ?: proposal.repeat
        val rule = recurrence?.second ?: proposal.recurrenceRule
        val period = proposal.timeOfDay ?: evidence.timeOfDay().takeIf { explicitMinute == null }
        val preferred = period?.fromMinute ?: 9 * 60
        val until = period?.untilMinute ?: 21 * 60
        val duration = evidence.duration() ?: when (proposal.title.substringBefore(' ').lowercase()) {
            "call", "send", "email", "reply" -> 15
            "apply", "prepare", "study", "write", "research" -> 60
            else -> 30
        }
        val requested = flexibleTime(quote, source, referenceDay, proposal.copy(repeat = repeat), workspace)
        val weekend = if (referenceDay.dayOfWeek == DayOfWeek.SUNDAY) referenceDay.minusDays(1)
            else referenceDay.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        val first = explicitDay ?: if (evidence.weekend()) weekend.toEpochDay() else today
        val last = when {
            explicitDay != null -> first
            evidence.weekend() -> weekend.plusDays(1).toEpochDay()
            deadline != null -> maxOf(first, minOf(deadline, first + 30))
            else -> first + 7
        }
        val window = requested ?: FlexibleSchedule(first, last, preferred, until,
            duration.coerceAtMost(until - preferred), deadlineDay = deadline?.takeIf { it >= last }, automatic = false)
        val availability = CalendarAvailability(workspace.entries.map { it.entry } + provisional, workspace.events,
            workspace.recurrenceSkips.groupBy({ it.seriesId }, { it.day }).mapValues { it.value.toSet() })
        val weekdayOptions = rule?.weekdays.orEmpty()
        val allowedDay: (Long) -> Boolean = { day ->
            val weekday = LocalDate.ofEpochDay(day).dayOfWeek
            when {
                weekdayOptions.isNotEmpty() -> weekday in weekdayOptions
                repeat == Repeat.WEEKDAYS -> weekday.value <= DayOfWeek.FRIDAY.value
                else -> true
            }
        }
        val selected = if (explicitMinute == null) availability.find(window, now, period = period, allowedDay = allowedDay) else {
            val day = explicitDay ?: first.takeIf { at(it, explicitMinute) > now.toInstant() } ?: today + 1
            TaskSlot(day, explicitMinute)
        }
        var slot = selected ?: TaskSlot(explicitDay ?: maxOf(today + 1, window.firstDay), preferred)
        if (!allowedDay(slot.day)) {
            val start = LocalDate.ofEpochDay(slot.day)
            val next = rule?.next(repeat, start, start) ?: repeat.next(start, start)
            slot = slot.copy(day = requireNotNull(next).toEpochDay())
        }
        val remaining = Duration.between(now.toInstant(), at(slot.day, slot.minute)).seconds
        val explicitPriority = Regex("(?i)\\b(?:(high|medium|low|no) priority|priority (?:is )?(high|medium|low|none))\\b")
            .find(quote)?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)
        val priority = when (explicitPriority?.lowercase()) {
            "no", "none" -> Priority.NONE
            "low" -> Priority.LOW
            "medium" -> Priority.MEDIUM
            "high" -> Priority.HIGH
            else -> when {
                proposal.priority != Priority.NONE -> proposal.priority
                Regex("(?i)\\b(urgent|asap|immediately|critical|dringend)\\b").containsMatchIn(quote) -> Priority.HIGH
                deadline != null && deadline <= today + 2 -> Priority.MEDIUM
                else -> Priority.NONE
            }
        }
        val explicitReminder = evidence.reminderOffset() ?: proposal.reminder?.takeIf {
            relative == null && evidence.reminder(it)
        }
        val reminder = explicitReminder ?: when {
            Regex("(?i)\\b(interview|flight|travel|appointment)\\b").containsMatchIn(proposal.title) -> 60
            deadline != null && deadline <= slot.day + 1 -> 30
            priority == Priority.HIGH -> 20
            repeat != Repeat.NONE -> 5
            duration <= 15 -> 0
            duration >= 60 -> 15
            else -> 10
        }.coerceAtMost(((remaining - 1) / 60).coerceIn(0, 10_080).toInt())
        val localTime = LocalDate.ofEpochDay(slot.day).atStartOfDay().plusMinutes(slot.minute.toLong())
        val warning = when {
            now.zone.rules.getValidOffsets(localTime).isEmpty() -> "The local time is skipped by a clock change. Review the schedule."
            remaining <= reminder * 60L && repeat == Repeat.NONE -> "The reminder time has passed. Review the schedule."
            deadline != null && slot.day > deadline -> "The proposed session falls after the deadline."
            selected == null -> "Review the proposed time. No free time was found in the requested window."
            else -> ""
        }
        val terms = words(proposal.title)
        val ranked = workspace.collections.map { it.id to terms.intersect(words(it.name)).size }
            .filter { it.second > 0 }.sortedByDescending { it.second }
        val named = workspace.collections.filter { folder ->
            val path = workspace.collections.pathName(folder.id)
            listOf(folder.name, path).any { name ->
                Regex("(?i)\\b(?:in|into|under|collection|folder) +(?:my |the )?${Regex.escape(name)}(?=\\W|$)")
                    .containsMatchIn(quote)
            }
        }.singleOrNull()?.id
        val collection = if (Regex("(?i)\\b(?:in|into|to) +(?:my |the )?inbox\\b").containsMatchIn(quote)) null
            else named ?: proposal.collectionId
                ?: ranked.firstOrNull()?.takeIf { it.second > (ranked.getOrNull(1)?.second ?: 0) }?.first
        val result = proposal.copy(
            startDay = slot.day, startMinute = slot.minute, reminder = reminder, repeat = repeat,
            timeOfDay = period, hardDeadline = deadline,
            durationMinutes = duration, recurrenceRule = rule,
            priority = priority, collectionId = collection,
            flexibleSchedule = requested?.takeIf { warning.isBlank() },
            inferredSchedule = listOfNotNull(
                "date".takeIf { explicitDay == null },
                "time".takeIf { explicitMinute == null },
                "reminder".takeIf { explicitReminder == null }
            ).joinToString(","),
            uncertainty = listOf(proposal.uncertainty, warning).filter(String::isNotBlank).joinToString(" ").take(2_000)
        ).validated(requireSchedule = true)
        provisional += EntryEntity(kind = EntryKind.TASK, title = result.title, deadline = result.startDay,
            dueMinute = result.startMinute, repeat = result.repeat,
            durationMinutes = duration, recurrenceRule = rule?.encode())
        return result
    }

    private fun at(day: Long, minute: Int) = occurrenceInstant(LocalDate.ofEpochDay(day), minute, now.zone)
    private fun words(text: String) =
        Regex("[\\p{L}\\p{N}]{3,}").findAll(text.lowercase()).map { it.value }.toSet()
}
