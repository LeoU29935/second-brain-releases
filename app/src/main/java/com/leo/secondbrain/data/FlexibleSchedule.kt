package com.leo.secondbrain.data

import org.json.JSONObject
import java.time.LocalDate
import java.time.ZonedDateTime

internal data class FlexibleSchedule(
    val firstDay: Long,
    val lastDay: Long,
    val fromMinute: Int,
    val untilMinute: Int,
    val duration: Int,
    val deadlineDay: Long? = null,
    val automatic: Boolean = true,
    val afterEventId: String? = null
) {
    init {
        require(validDay(firstDay) && validDay(lastDay) && lastDay - firstDay in 0..30)
        require(fromMinute in 0..1439 && untilMinute in (fromMinute + 1)..1440)
        require(duration in 1..(untilMinute - fromMinute))
        require(deadlineDay == null || validDay(deadlineDay) && lastDay <= deadlineDay)
    }

    fun json() = JSONObject().put("firstDay", firstDay).put("lastDay", lastDay)
        .put("fromMinute", fromMinute).put("untilMinute", untilMinute).put("duration", duration)
        .putNullable("deadlineDay", deadlineDay).put("automatic", automatic)
        .putNullable("afterEventId", afterEventId)

    companion object {
        fun read(json: JSONObject) = FlexibleSchedule(json.getLong("firstDay"), json.getLong("lastDay"),
            json.getInt("fromMinute"), json.getInt("untilMinute"), json.getInt("duration"),
            json.longOrNull("deadlineDay"), json.optBoolean("automatic", true), json.stringOrNull("afterEventId"))
    }
}

internal data class TaskSlot(val day: Long, val minute: Int)
internal data class ScheduleMove(val previous: TaskSlot, val reason: String, val waiting: Boolean = false) {
    fun json() = JSONObject().put("day", previous.day).put("minute", previous.minute).put("reason", reason)
        .put("waiting", waiting)
    companion object {
        fun read(value: String?) = value?.let {
            val row = JSONObject(it)
            ScheduleMove(TaskSlot(row.getLong("day"), row.getInt("minute")), row.getString("reason"), row.optBoolean("waiting"))
        }
    }
}

internal class CalendarAvailability(
    private val entries: List<EntryEntity>,
    private val events: List<Event>,
    private val skips: Map<String, Set<Long>>
) {
    private fun busy(day: Long, taskId: String?): List<Pair<Int, Int>> = buildList {
        val date = LocalDate.ofEpochDay(day)
        for (event in events) {
            val span = event.endDay - event.startDay
            val start = event.repeat.next(LocalDate.ofEpochDay(event.startDay), date.minusDays(span))?.toEpochDay()
                ?: continue
            if (start > day) continue
            if (event.allDay) add(0 to 1440)
            else if (event.startMinute != null) {
                val from = if (start == day) event.startMinute else 0
                val until = if (start + span == day) event.endMinute ?: if (span == 0L) from + 1 else 1440 else 1440
                if (until > from) add(from to until)
            }
        }
        for (entry in entries) {
            val start = entry.dueMinute ?: continue
            if (entry.id == taskId || entry.kind != EntryKind.TASK || entry.completed ||
                ScheduleMove.read(entry.scheduleChange)?.waiting == true ||
                !entry.occursOn(date, skips[entry.id].orEmpty())) continue
            val duration = entry.durationMinutes ?: entry.flexibleSchedule?.let { FlexibleSchedule.read(JSONObject(it)).duration } ?: 30
            add(start to (start + duration).coerceAtMost(1440))
        }
    }

    fun isFree(slot: TaskSlot, duration: Int, taskId: String?) =
        busy(slot.day, taskId).none { (start, end) -> slot.minute < end && slot.minute + duration > start }

    fun respectsWindow(slot: TaskSlot, window: FlexibleSchedule, period: TimeOfDay? = null): Boolean {
        val active = resolve(window, period) ?: return false
        return slot.day in active.firstDay..active.lastDay &&
            slot.minute >= active.fromMinute && slot.minute + active.duration <= active.untilMinute
    }

    private fun resolve(window: FlexibleSchedule, period: TimeOfDay?): FlexibleSchedule? {
        val eventId = window.afterEventId
        val resolved = if (eventId == null) window else {
            val event = events.firstOrNull { it.id == eventId && !it.allDay && it.endMinute != null } ?: return null
            val minute = (requireNotNull(event.endMinute) + 14) / 15 * 15
            if (minute + window.duration > window.untilMinute ||
                window.deadlineDay?.let { event.endDay > it } == true) return null
            window.copy(firstDay = event.endDay, lastDay = event.endDay, fromMinute = minute)
        }
        if (period == null) return resolved
        val from = maxOf(resolved.fromMinute, period.fromMinute)
        val until = minOf(resolved.untilMinute, period.untilMinute)
        return if (from + resolved.duration <= until) resolved.copy(fromMinute = from, untilMinute = until) else null
    }

    fun find(window: FlexibleSchedule, now: ZonedDateTime, taskId: String? = null, period: TimeOfDay? = null,
        allowedDay: (Long) -> Boolean = { true }): TaskSlot? {
        val active = resolve(window, period) ?: return null
        val today = now.toLocalDate().toEpochDay()
        val minuteNow = now.hour * 60 + now.minute
        for (day in maxOf(today, active.firstDay)..active.lastDay) {
            if (!allowedDay(day)) continue
            var start = if (day == today) maxOf((minuteNow / 15 + 1) * 15, active.fromMinute) else active.fromMinute
            val commitments = busy(day, taskId).sortedBy { it.first }
            for ((busyStart, busyEnd) in commitments) {
                if (start + window.duration <= busyStart) break
                if (start < busyEnd) start = (busyEnd + 14) / 15 * 15
            }
            while (start + active.duration <= active.untilMinute) {
                val localTime = LocalDate.ofEpochDay(day).atStartOfDay().plusMinutes(start.toLong())
                val slot = TaskSlot(day, start)
                if (now.zone.rules.getValidOffsets(localTime).isNotEmpty() &&
                    commitments.none { (from, to) -> start < to && start + active.duration > from }) {
                    return slot
                }
                start += 15
            }
        }
        return null
    }
}
