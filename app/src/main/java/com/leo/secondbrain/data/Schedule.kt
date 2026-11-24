package com.leo.secondbrain.data

import androidx.room.Entity
import androidx.room.ForeignKey
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal enum class Repeat(val label: String) {
    NONE("Does not repeat"), DAILY("Daily"), WEEKDAYS("Weekdays"), WEEKLY("Weekly"), MONTHLY("Monthly"), YEARLY("Yearly");

    /** Returns the first occurrence on or after the requested local day. */
    fun next(anchor: LocalDate, target: LocalDate): LocalDate? {
        if (this == WEEKDAYS) {
            val first = maxOf(anchor, target)
            return when (first.dayOfWeek) {
                java.time.DayOfWeek.SATURDAY -> first.plusDays(2)
                java.time.DayOfWeek.SUNDAY -> first.plusDays(1)
                else -> first
            }
        }
        if (target <= anchor) return anchor
        return when (this) {
            NONE -> null
            DAILY, WEEKDAYS -> target
            WEEKLY -> anchor.plusWeeks((ChronoUnit.DAYS.between(anchor, target) + 6) / 7)
            MONTHLY -> {
                val months = ChronoUnit.MONTHS.between(anchor.withDayOfMonth(1), target.withDayOfMonth(1))
                anchor.plusMonths(months).takeIf { it >= target } ?: anchor.plusMonths(months + 1)
            }
            YEARLY -> {
                val years = (target.year - anchor.year).toLong()
                anchor.plusYears(years).takeIf { it >= target } ?: anchor.plusYears(years + 1)
            }
        }
    }
}

internal enum class TimeOfDay(val label: String, val fromMinute: Int, val untilMinute: Int) {
    MORNING("Morning", 8 * 60, 12 * 60),
    AFTERNOON("Afternoon", 12 * 60, 17 * 60),
    EVENING("Evening", 17 * 60, 21 * 60),
    NIGHT("Night", 21 * 60, 24 * 60)
}

internal fun scheduleOrder(minute: Int?, period: TimeOfDay?): Int =
    minute ?: period?.fromMinute ?: -1

internal fun scheduleTimeLabel(minute: Int?, period: TimeOfDay?): String? =
    listOfNotNull(period?.label, minute?.let(::minuteLabel)).joinToString(" · ").takeIf(String::isNotEmpty)

internal fun minuteLabel(minute: Int): String =
    LocalTime.ofSecondOfDay(minute * 60L).format(
        java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
    )

internal val scheduleYears = 1900..2200
private val scheduleDays = LocalDate.of(scheduleYears.first, 1, 1).toEpochDay()..
    LocalDate.of(scheduleYears.last, 12, 31).toEpochDay()

internal fun validDay(day: Long): Boolean = day in scheduleDays

@Entity(tableName = "recurrence_skips", primaryKeys = ["seriesId", "day"],
    foreignKeys = [ForeignKey(entity = EntryEntity::class, parentColumns = ["id"],
        childColumns = ["seriesId"], onDelete = ForeignKey.CASCADE)])
internal data class RecurrenceSkip(val seriesId: String, val day: Long)

internal fun EntryEntity.occursOn(day: LocalDate, skipped: Set<Long> = emptySet()): Boolean {
    val scheduled = deadline ?: return false
    val target = day.toEpochDay()
    if (target in skipped) return false
    if (target == scheduled) return true
    if (target < scheduled || completed || repeat == Repeat.NONE) return false
    val anchor = LocalDate.ofEpochDay(recurrenceAnchor ?: scheduled)
    return (RecurrenceRule.read(recurrenceRule)?.next(repeat, anchor, day) ?: repeat.next(anchor, day)) == day
}

internal fun Repeat.nextUnskipped(anchor: LocalDate, target: LocalDate, skipped: Set<Long>, rule: RecurrenceRule? = null): LocalDate? {
    fun find(day: LocalDate) = if (rule == null) next(anchor, day) else rule.next(this, anchor, day)
    var occurrence = find(target)
    while (occurrence != null && occurrence.toEpochDay() in skipped) {
        occurrence = find(occurrence.plusDays(1))
    }
    return occurrence?.takeIf { validDay(it.toEpochDay()) }
}

internal fun occurrenceInstant(day: LocalDate, minute: Int, zone: ZoneId): Instant =
    day.atTime(minute / 60, minute % 60).atZone(zone).toInstant()

internal fun nextReminder(
    day: Long, minute: Int, advance: Int, repeat: Repeat, after: Instant, zone: ZoneId,
    skipped: Set<Long> = emptySet()
): Instant? = nextReminderOccurrence(day, minute, advance, repeat, after, zone, skipped)?.trigger

internal data class ReminderOccurrence(val day: Long, val trigger: Instant)

internal fun nextReminderOccurrence(
    day: Long, minute: Int, advance: Int, repeat: Repeat, after: Instant, zone: ZoneId,
    skipped: Set<Long>, rule: RecurrenceRule? = null
): ReminderOccurrence? {
    val anchor = LocalDate.ofEpochDay(day)
    val shifted = after.plusSeconds(60L * advance).atZone(zone).toLocalDate()
    val first = repeat.nextUnskipped(anchor, shifted, skipped, rule) ?: return null
    val trigger = occurrenceInstant(first, minute, zone).minusSeconds(60L * advance)
    if (trigger > after) return ReminderOccurrence(first.toEpochDay(), trigger)
    val following = repeat.nextUnskipped(anchor, first.plusDays(1), skipped, rule) ?: return null
    return ReminderOccurrence(following.toEpochDay(), occurrenceInstant(following, minute, zone).minusSeconds(60L * advance))
}
