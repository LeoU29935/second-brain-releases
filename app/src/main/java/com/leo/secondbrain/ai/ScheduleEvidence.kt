package com.leo.secondbrain.ai

import com.leo.secondbrain.data.TimeOfDay
import com.leo.secondbrain.data.RecurrenceRule
import com.leo.secondbrain.data.Repeat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Calendar fields absent from literal evidence are left for the reviewer. */
internal class ScheduleEvidence(quote: String, source: String, reference: LocalDate) {
    private val text = quote.trim().takeIf { it.isNotEmpty() && source.contains(it, ignoreCase = true) }
        .orEmpty().lowercase(Locale.ROOT)
    private val dates = buildSet {
        if (Regex("\\b(today|heute|tonight|this (?:morning|afternoon|evening))\\b").containsMatchIn(text)) add(reference)
        if (Regex("\\b(tomorrow|morgen)\\b").containsMatchIn(text.replace("day after tomorrow", ""))) {
            add(reference.plusDays(1))
        }
        if (Regex("\\b(day after tomorrow|übermorgen)\\b").containsMatchIn(text)) add(reference.plusDays(2))
        Regex("\\b(?:in)\\s+(\\d{1,3})\\s+(days?|weeks?|tagen?|wochen?)\\b").findAll(text).forEach {
            val quantity = it.groupValues[1].toLong()
            val factor = if (it.groupValues[2].startsWith("w")) 7 else 1
            add(reference.plusDays(quantity * factor))
        }
        Regex("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b").findAll(text).forEach {
            addDate(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        Regex("\\b(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})\\b").findAll(text).forEach {
            addDate(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt())
        }
        Regex("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b").findAll(text).forEach {
            val first = it.groupValues[1].toInt()
            val second = it.groupValues[2].toInt()
            val year = it.groupValues[3].toInt()
            if (first > 12 || first == second) addDate(year, second, first)
            else if (second > 12) addDate(year, first, second)
        }
        for (language in locales) {
            for (month in Month.entries) {
                val name = Regex.escape(month.getDisplayName(TextStyle.FULL, language).lowercase(Locale.ROOT))
                val monthFirst = Regex("\\b$name\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?\\b")
                val dayFirst = Regex("\\b(\\d{1,2})(?:st|nd|rd|th|\\.)?\\s+$name(?:,?\\s+(\\d{4}))?\\b")
                (monthFirst.findAll(text) + dayFirst.findAll(text)).forEach {
                    val day = it.groupValues[1].toInt()
                    val year = it.groupValues[2].toIntOrNull()
                    if (year != null) addDate(year, month.value, day)
                    else runCatching { LocalDate.of(reference.year, month.value, day) }.getOrNull()?.let { candidate ->
                        add(if (candidate < reference) candidate.plusYears(1) else candidate)
                    }
                }
            }
            for (weekday in DayOfWeek.entries) {
                val name = Regex.escape(weekday.getDisplayName(TextStyle.FULL, language).lowercase(Locale.ROOT))
                if (Regex("\\b(next|kommenden|nächsten)\\s+$name\\b").containsMatchIn(text)) {
                    add(reference.with(TemporalAdjusters.next(weekday)))
                } else if (Regex("\\b$name\\b").containsMatchIn(text)) {
                    add(reference.with(TemporalAdjusters.nextOrSame(weekday)))
                }
            }
        }
    }
    private val times = buildSet {
        Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\s*(a\\.?m\\.?|p\\.?m\\.?)?\\b").findAll(text).forEach {
            val h = it.groupValues[1].toInt()
            val minute = it.groupValues[2].toInt()
            val suffix = it.groupValues[3].firstOrNull()
            if (suffix == null) add(h * 60 + minute)
            else if (h in 1..12) add((h % 12 + if (suffix == 'p') 12 else 0) * 60 + minute)
        }
        Regex("(?<![\\d:])\\b(1[0-2]|[1-9])\\s*(a\\.?m\\.?|p\\.?m\\.?)\\b").findAll(text).forEach {
            val h = it.groupValues[1].toInt()
            add((h % 12 + if (it.groupValues[2].first() == 'p') 12 else 0) * 60)
        }
        Regex("\\b([0-2]?\\d)\\s+uhr\\b").findAll(text).forEach {
            val hour = it.groupValues[1].toInt()
            if (hour in 0..23) add(hour * 60)
        }
        val namedPeriod = Regex("\\b(morning|afternoon|evening|night)\\b").containsMatchIn(text)
        if (!namedPeriod) Regex("\\b(?:at|around|about)\\s+([01]?\\d|2[0-3])(?![\\d:./]|\\s*[ap]\\.?m\\b)\\b")
            .findAll(text).forEach { add(it.groupValues[1].toInt() * 60) }
        if (Regex("\\b(noon|midday|mittags)\\b").containsMatchIn(text)) add(720)
        if (Regex("\\b(midnight|mitternacht)\\b").containsMatchIn(text)) add(0)
        Regex("\\b(morning|afternoon|evening)\\s+(?:at|around|about)\\s+(1[0-2]|[1-9])" +
            "(?![\\d:.]|\\s*(?:[ap]\\.?m\\.?|uhr)\\b)\\b")
            .findAll(text).forEach {
                val hour = it.groupValues[2].toInt() % 12
                add((hour + if (it.groupValues[1] == "morning") 0 else 12) * 60)
            }
    }

    fun date(value: String?): Long? = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?.takeIf { it in dates }?.toEpochDay()

    private val deadlineMarker get() = Regex("\\b(?:due|deadline(?: is| of)?|no later than|by|before|bis)\\b")
        .findAll(text).firstOrNull { marker ->
            marker.value != "before" || !Regex("\\b(?:minutes?|hours?|days?)\\s*$")
                .containsMatchIn(text.substring(0, marker.range.first))
        }

    fun plannedBeforeDeadline(reference: LocalDate): Long? {
        val marker = deadlineMarker ?: return null
        val before = text.substring(0, marker.range.first)
        val after = text.substring(marker.range.last + 1)
        val contrasting = scheduleClause.find(after)?.let { after.substring(it.range.last + 1) }.orEmpty()
        return ScheduleEvidence(before, before, reference).onlyDate()
            ?: ScheduleEvidence(contrasting, contrasting, reference).onlyDate()
    }

    fun deadline(reference: LocalDate): Long? {
        val marker = deadlineMarker ?: return null
        val remainder = text.substring(marker.range.last + 1)
        val after = scheduleClause.find(remainder)?.let { remainder.substring(0, it.range.first) } ?: remainder
        if (after.isBlank()) return null
        return ScheduleEvidence(after, after, reference).onlyDate()?.let { if (marker.value == "before") it - 1 else it }
    }

    private val scheduleClause get() = Regex("\\b(?:but|while|then)\\b")

    fun unscheduled() = Regex("\\b(?:unscheduled|without (?:a )?(?:date|schedule)|no due date|no schedule)\\b")
        .containsMatchIn(text)

    fun weekend() = Regex("\\b(?:this|the) weekend\\b").containsMatchIn(text)

    fun relativeMinutes(): Long? = Regex("\\bin\\s+(\\d{1,3}|one|two|three|four|five|six)\\s+(hours?|minutes?)\\b")
        .find(text)?.let { match ->
            val value = match.groupValues[1]
            val count = value.toLongOrNull() ?: (listOf("one", "two", "three", "four", "five", "six").indexOf(value) + 1L)
            count * if (match.groupValues[2].startsWith("hour")) 60 else 1
        }

    fun recurrence(): Pair<Repeat, RecurrenceRule?>? {
        val step = Regex("\\bevery\\s+(\\d{1,3}|other|two|three|four)\\s+(days?|weeks?)\\b").find(text)
        if (step != null) {
            val count = when (val value = step.groupValues[1]) {
                "other", "two" -> 2
                "three" -> 3
                "four" -> 4
                else -> value.toInt()
            }
            if (count in 1..365) {
                val frequency = if (step.groupValues[2].startsWith("day")) Repeat.DAILY else Repeat.WEEKLY
                return frequency to RecurrenceRule(count)
            }
        }
        val repeatedWeekdays = Regex("\\bevery\\s+((?:(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)(?:\\s*,\\s*|\\s+and\\s+)?)+)\\b")
            .find(text)?.groupValues?.get(1).orEmpty()
        val days = DayOfWeek.entries.filter { it.name.lowercase(Locale.ROOT) in repeatedWeekdays }.toSet()
        if (days.isNotEmpty()) return Repeat.WEEKLY to RecurrenceRule(weekdays = days)
        return Repeat.entries.firstOrNull { it != Repeat.NONE && repeat(it) }?.let { it to null }
    }

    fun reminderOffset(): Int? {
        if (reminder(0)) return 0
        if (Regex("\\bhalf (?:an? )?hour (?:before|ahead)\\b").containsMatchIn(text)) return 30
        return Regex("\\b(\\d{1,4}|one|two|thirty)\\s*(minutes?|hours?|days?)\\s+(?:before|ahead)\\b")
            .find(text)?.let {
                val amount = when (val value = it.groupValues[1]) {
                    "one" -> 1
                    "two" -> 2
                    "thirty" -> 30
                    else -> value.toInt()
                }
                val unit = when (it.groupValues[2].first()) {
                    'h' -> 60
                    'd' -> 1440
                    else -> 1
                }
                (amount * unit).takeIf { minutes -> minutes in 0..10_080 && reminder(minutes) }
            }
    }

    fun localDays(): Set<Long> = dates.map(LocalDate::toEpochDay).toSet()

    fun onlyDate() = dates.singleOrNull()?.toEpochDay()

    fun onlyTime() = times.singleOrNull()

    fun timeOfDay(): TimeOfDay? = TimeOfDay.entries.filter { period ->
        val words = when (period) {
            TimeOfDay.MORNING -> "morning|vormittag|morgens"
            TimeOfDay.AFTERNOON -> "afternoon|nachmittag"
            TimeOfDay.EVENING -> "evening|tonight|abend|abends"
            TimeOfDay.NIGHT -> "night|nachts"
        }
        Regex("\\b($words)\\b").containsMatchIn(text)
    }.singleOrNull()

    fun duration(): Int? = Regex("\\b(?:for|lasting)\\s+(\\d{1,3}|one|two|three|half an?)\\s*(hours?|minutes?)\\b")
        .findAll(text).map {
            val number = when (it.groupValues[1]) {
                "one" -> 1
                "two" -> 2
                "three" -> 3
                "half a", "half an" -> return@map 30
                else -> it.groupValues[1].toInt()
            }
            number * if (it.groupValues[2].startsWith("hour")) 60 else 1
        }.distinct().singleOrNull()?.takeIf { it in 1..10_080 }

    fun time(value: String?): Int? = value?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
        ?.let { it.toSecondOfDay() / 60 }?.takeIf { it in times }

    fun repeat(value: com.leo.secondbrain.data.Repeat): Boolean {
        val wording = when (value) {
            com.leo.secondbrain.data.Repeat.DAILY -> "daily|every day|täglich"
            com.leo.secondbrain.data.Repeat.WEEKDAYS -> "weekdays|every weekday|monday through friday|montags bis freitags"
            com.leo.secondbrain.data.Repeat.WEEKLY -> "weekly|every week|wöchentlich"
            com.leo.secondbrain.data.Repeat.MONTHLY -> "monthly|every month|monatlich"
            com.leo.secondbrain.data.Repeat.YEARLY -> "yearly|annually|every year|jährlich"
            else -> return true
        }
        return Regex("\\b($wording)\\b").containsMatchIn(text)
    }

    fun allDay() = Regex("\\b(all[- ]day|ganztägig|den ganzen tag)\\b").containsMatchIn(text)

    fun reminder(minutes: Int): Boolean {
        if (!Regex("remind|notif|erinner").containsMatchIn(text)) return false
        if (minutes == 0) return Regex("at the (start|time)|zum beginn|pünktlich").containsMatchIn(text)
        val offsets = Regex("(\\d+|one|two|ein|eine|zwei|thirty)\\s*(min(?:ute[ns]?)?|hours?|stunden?|days?|tage?|weeks?|wochen?)")
        return offsets.findAll(text).any {
            val quantity = when (val value = it.groupValues[1]) {
                "one", "ein", "eine" -> 1
                "two", "zwei" -> 2
                "thirty" -> 30
                else -> value.toIntOrNull() ?: return@any false
            }
            val unit = when (it.groupValues[2].first()) {
                'm' -> 1
                'h', 's' -> 60
                'd', 't' -> 1440
                else -> 10080
            }
            quantity.toLong() * unit == minutes.toLong()
        }
    }

    private fun MutableSet<LocalDate>.addDate(year: Int, month: Int, day: Int) {
        runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let(::add)
    }

    private companion object {
        val locales = listOf(Locale.ENGLISH, Locale.GERMAN)
    }
}
