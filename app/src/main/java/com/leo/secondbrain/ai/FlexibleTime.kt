package com.leo.secondbrain.ai

import com.leo.secondbrain.data.FlexibleSchedule
import com.leo.secondbrain.data.Proposal
import com.leo.secondbrain.data.WorkspaceSnapshot
import com.leo.secondbrain.data.DraftKind
import com.leo.secondbrain.data.Repeat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale

internal fun flexibleTime(
    quote: String, source: String, reference: LocalDate, proposal: Proposal, workspace: WorkspaceSnapshot
): FlexibleSchedule? {
    if (quote.isBlank() || !source.contains(quote, ignoreCase = true) ||
        proposal.kind != DraftKind.TASK || proposal.targetId != null || proposal.repeat != Repeat.NONE) return null
    val text = quote.lowercase(Locale.ROOT)
    val evidence = ScheduleEvidence(quote, source, reference)
    if (evidence.onlyTime() != null || proposal.startMinute != null) return null
    val period = evidence.timeOfDay()
    // A day period alone is a preference. Clock placement needs an explicit request.
    if ((period != null || proposal.hardDeadline != null) && !Regex("\\b(find|pick|choose|allocate|block|book)\\b[^.!?]{0,60}\\b(time|slot|minutes?|hours?)\\b|\\bfit (?:it|this) in\\b|\\b(?:free|available) (?:time|slot)\\b")
            .containsMatchIn(text)) return null
    val today = reference.toEpochDay()
    val date = evidence.onlyDate() ?: proposal.startDay
    var begin = date ?: today
    var end = begin
    var from = period?.fromMinute ?: 9 * 60
    val until = period?.untilMinute ?: 18 * 60
    var deadline: Long? = null
    var afterEventId: String? = null
    when {
        date != null && Regex("\\b(before|by|bis|vor)\\b").containsMatchIn(text) -> {
            begin = today
            end = if (Regex("\\b(before|vor)\\b").containsMatchIn(text)) date - 1 else date
            deadline = end
        }
        Regex("\\b(next week|nächste woche)\\b").containsMatchIn(text) -> {
            begin = reference.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).toEpochDay()
            end = begin + 6
        }
        Regex("\\bafter (?:my |the )?meeting\\b").containsMatchIn(text) -> {
            val meeting = workspace.events.filter {
                it.title.contains("meeting", ignoreCase = true) && it.startDay >= today &&
                    it.startDay <= today + 30 && !it.allDay && it.endMinute != null
            }.singleOrNull() ?: return null
            begin = meeting.endDay
            end = begin
            from = maxOf(from.takeIf { period != null } ?: 0, (requireNotNull(meeting.endMinute) + 14) / 15 * 15)
            afterEventId = meeting.id
        }
        period == null -> return null
    }
    if (end < begin || until <= from || end - begin > 30) return null
    val estimate = when {
        Regex("\\b(apply|application|write|prepare|study|research)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(proposal.title) -> 60
        Regex("\\b(call|send|email|check|reply)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(proposal.title) -> 15
        else -> 30
    }
    val duration = evidence.duration() ?: estimate.coerceAtMost(until - from)
    if (duration > until - from) return null
    return FlexibleSchedule(begin, end, from, until, duration, deadline, afterEventId = afterEventId)
}
