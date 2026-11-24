package com.leo.secondbrain.ui

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

internal fun relativeCalendarDate(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String {
    val days = ChronoUnit.DAYS.between(today, date)
    val weekStart = WeekFields.of(locale).firstDayOfWeek
    val daysSinceStart = (today.dayOfWeek.value - weekStart.value + 7) % 7
    val nextWeekStart = 7L - daysSinceStart
    return when {
        days == 0L -> "Today"
        days == 1L -> "Tomorrow"
        days in 7L..13L && days in nextWeekStart until nextWeekStart + 7 -> "Next week"
        days > 1 -> "In $days days"
        days == -1L -> "Yesterday"
        else -> "${-days} days ago"
    }
}
