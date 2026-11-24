package com.leo.secondbrain.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.format.TextStyle
import java.util.Locale

internal data class RecurrenceRule(val interval: Int = 1, val weekdays: Set<DayOfWeek> = emptySet()) {
    init { require(interval in 1..365) }

    fun encode() = (listOf(interval) + weekdays.map(DayOfWeek::getValue).sorted()).joinToString(",")

    fun label(repeat: Repeat): String {
        val frequency = if (interval == 1) repeat.label
            else "Every $interval ${if (repeat == Repeat.DAILY) "days" else "weeks"}"
        return listOf(frequency, weekdays.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) })
            .filter(String::isNotBlank).joinToString(" · ")
    }

    fun next(repeat: Repeat, anchor: LocalDate, target: LocalDate): LocalDate? {
        val start = maxOf(anchor, target)
        if (repeat == Repeat.DAILY) {
            val elapsed = ChronoUnit.DAYS.between(anchor, start)
            return anchor.plusDays((elapsed + interval - 1) / interval * interval)
        }
        if (repeat != Repeat.WEEKLY) return repeat.next(anchor, target)
        val firstWeek = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
        val days = weekdays.ifEmpty { setOf(anchor.dayOfWeek) }.sorted()
        val elapsedWeeks = ChronoUnit.DAYS.between(firstWeek, start) / 7
        val week = firstWeek.plusWeeks((elapsedWeeks + interval - 1) / interval * interval)
        return days.map { week.plusDays((it.value - 1).toLong()) }.firstOrNull { it >= start }
            ?: week.plusWeeks(interval.toLong()).plusDays((days.first().value - 1).toLong())
    }

    companion object {
        fun read(encoded: String?): RecurrenceRule? {
            if (encoded == null) return null
            val values = encoded.split(',').map(String::toInt)
            return RecurrenceRule(values.first(), values.drop(1).map(DayOfWeek::of).toSet())
        }
    }
}
