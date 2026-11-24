package com.leo.secondbrain.data

import org.json.JSONArray
import org.json.JSONObject

internal data class Proposal(
    val kind: DraftKind,
    val title: String,
    val body: String = "",
    val collectionId: String? = null,
    val tags: Set<String> = emptySet(),
    val priority: Priority = Priority.NONE,
    val startDay: Long? = null,
    val startMinute: Int? = null,
    val endDay: Long? = null,
    val endMinute: Int? = null,
    val allDay: Boolean = false,
    val location: String = "",
    val repeat: Repeat = Repeat.NONE,
    val reminder: Int? = null,
    val targetId: String? = null,
    val targetVersion: Long? = null,
    val related: Set<String> = emptySet(),
    val uncertainty: String = "",
    val flexibleSchedule: FlexibleSchedule? = null,
    val timeOfDay: TimeOfDay? = null,
    val hardDeadline: Long? = null,
    val inferredSchedule: String? = null,
    val durationMinutes: Int? = null,
    val recurrenceRule: RecurrenceRule? = null
) {
    fun json(): JSONObject = JSONObject()
        .put("kind", kind.name).put("title", title).put("body", body)
        .putNullable("collectionId", collectionId).put("tags", JSONArray(tags.toList()))
        .put("priority", priority.name).putNullable("startDay", startDay)
        .putNullable("startMinute", startMinute).putNullable("endDay", endDay)
        .putNullable("endMinute", endMinute).put("allDay", allDay)
        .put("location", location).put("repeat", repeat.name).putNullable("reminder", reminder)
        .putNullable("targetId", targetId).putNullable("targetVersion", targetVersion)
        .put("related", JSONArray(related.toList())).put("uncertainty", uncertainty)
        .putNullable("flexibleSchedule", flexibleSchedule?.json())
        .putNullable("timeOfDay", timeOfDay?.name)
        .putNullable("hardDeadline", hardDeadline).putNullable("inferredSchedule", inferredSchedule)
        .putNullable("durationMinutes", durationMinutes).putNullable("recurrenceRule", recurrenceRule?.encode())

    fun validated(requireSchedule: Boolean = false): Proposal {
        require(title.isNotBlank() && title.length <= 180) { "Enter a title of 1–180 characters." }
        require(body.length <= 48_000 && location.length <= 500 && uncertainty.length <= 2_000)
        require(listOfNotNull(startDay, endDay, hardDeadline).all(::validDay))
        require(hardDeadline == null || kind == DraftKind.TASK)
        require(listOfNotNull(startMinute, endMinute).all { it in 0..1439 })
        require(reminder == null || reminder in 0..10_080)
        require(durationMinutes == null || kind == DraftKind.TASK && durationMinutes in 1..10_080)
        require(recurrenceRule == null || kind == DraftKind.TASK && repeat in setOf(Repeat.DAILY, Repeat.WEEKLY) &&
            (repeat != Repeat.DAILY || recurrenceRule.weekdays.isEmpty()))
        require(tags.size <= 50 && related.size <= 50)
        require(targetId == null || targetVersion != null)
        require(flexibleSchedule == null || kind == DraftKind.TASK && repeat == Repeat.NONE)
        if (requireSchedule && kind == DraftKind.EVENT) {
            require(startDay != null && endDay != null) { "Choose start and end dates for the event." }
            require(allDay || endMinute == null || startMinute != null) { "Choose a start time for this event." }
            require(endDay > startDay || (endDay == startDay && (allDay ||
                startMinute == null || endMinute == null || endMinute > startMinute))) {
                "The event end must follow its start."
            }
        }
        if (kind == DraftKind.TASK && inferredSchedule != null && requireSchedule) {
            require(startDay != null && startMinute != null && reminder != null) {
                "Choose a date, time, and reminder for the planned task."
            }
        }
        if (requireSchedule && kind == DraftKind.TASK) {
            require(startDay != null || (startMinute == null && timeOfDay == null && reminder == null && repeat == Repeat.NONE)) {
                "Choose a due date for the task schedule."
            }
        }
        return copy(title = title.trim(), body = body.trimEnd())
    }

    fun validatedForAutomaticCreation(): Proposal {
        check(targetId == null)
        if (kind == DraftKind.TASK) {
            require(startDay != null && startMinute != null && reminder != null) {
                "A planned task needs a date, time, and reminder. Review the proposed task."
            }
        }
        return validated(requireSchedule = true)
    }

    companion object {
        fun read(objectValue: JSONObject, validate: Boolean = true): Proposal = with(objectValue) {
            val proposal = Proposal(
                kind = DraftKind.valueOf(getString("kind")), title = getString("title"),
                body = optString("body"), collectionId = stringOrNull("collectionId"),
                tags = optJSONArray("tags").stringSet(), priority = Priority.valueOf(optString("priority", "NONE")),
                startDay = longOrNull("startDay"), startMinute = intOrNull("startMinute"),
                endDay = longOrNull("endDay"), endMinute = intOrNull("endMinute"),
                allDay = optBoolean("allDay"), location = optString("location"),
                repeat = Repeat.valueOf(optString("repeat", "NONE")), reminder = intOrNull("reminder"),
                targetId = stringOrNull("targetId"), targetVersion = longOrNull("targetVersion"),
                related = optJSONArray("related").stringSet(), uncertainty = optString("uncertainty"),
                flexibleSchedule = optJSONObject("flexibleSchedule")?.let(FlexibleSchedule::read),
                timeOfDay = stringOrNull("timeOfDay")?.let(TimeOfDay::valueOf),
                hardDeadline = longOrNull("hardDeadline"), inferredSchedule = stringOrNull("inferredSchedule"),
                durationMinutes = intOrNull("durationMinutes"), recurrenceRule = RecurrenceRule.read(stringOrNull("recurrenceRule"))
            )
            if (validate) proposal.validated() else proposal
        }
    }
}

internal fun JSONObject.putNullable(name: String, value: Any?): JSONObject = put(name, value ?: JSONObject.NULL)
internal fun JSONObject.stringOrNull(name: String): String? = if (isNull(name)) null else getString(name)
internal fun JSONObject.longOrNull(name: String): Long? = if (isNull(name)) null else getLong(name)
internal fun JSONObject.intOrNull(name: String): Int? = if (isNull(name)) null else getInt(name)

internal fun JSONArray?.stringSet(): Set<String> =
    if (this == null) emptySet() else (0 until length()).map { getString(it) }.toSet()

internal fun Proposal.savedScheduleSummary(today: java.time.LocalDate, includeInferred: Boolean = false): String {
    val date = startDay?.let { day ->
        when (day) {
            today.toEpochDay() -> "Today"
            today.plusDays(1).toEpochDay() -> "Tomorrow"
            else -> java.time.LocalDate.ofEpochDay(day).toString()
        }
    }
    val time = scheduleTimeLabel(startMinute, null) ?: timeOfDay?.label
    val reminderLabel = when (reminder) {
        null -> null
        0 -> "Reminder at start"
        else -> "Reminder ${reminder} min before"
    }
    val inferred = inferredSchedule.orEmpty().split(',').filter(String::isNotBlank)
    return listOfNotNull(
        listOfNotNull(date, time).joinToString(" at ").takeIf(String::isNotBlank),
        reminderLabel,
        (if (priority == Priority.NONE) "Normal" else priority.label).takeIf { kind == DraftKind.TASK }?.let { "$it priority" },
        inferred.takeIf { includeInferred && it.isNotEmpty() }?.joinToString(prefix = "Estimated ")
    ).joinToString(" · ")
}

internal fun TaggedEntry.asProposal() = Proposal(
    kind = DraftKind.valueOf(entry.kind.name), title = entry.title, body = entry.body,
    collectionId = entry.collectionId, tags = tags.map { it.id }.toSet(), priority = entry.priority,
    startDay = entry.deadline, startMinute = entry.dueMinute, repeat = entry.repeat, reminder = entry.reminder,
    targetId = entry.id, targetVersion = entry.updatedAt,
    flexibleSchedule = entry.flexibleSchedule?.let { FlexibleSchedule.read(JSONObject(it)) },
    timeOfDay = entry.timeOfDay, hardDeadline = entry.hardDeadline,
    durationMinutes = entry.durationMinutes, recurrenceRule = RecurrenceRule.read(entry.recurrenceRule),
    inferredSchedule = entry.inferredSchedule
)

internal fun Event.asProposal(tags: Set<String>) = Proposal(
    DraftKind.EVENT, title, body, collectionId, tags,
    startDay = startDay, startMinute = startMinute, endDay = endDay, endMinute = endMinute,
    allDay = allDay, location = location, repeat = repeat, reminder = reminder,
    targetId = id, targetVersion = updatedAt, timeOfDay = timeOfDay
)

internal fun Proposal.toEvent(id: String = newId()) = Event(
    id = id, title = title, body = body, startDay = requireNotNull(startDay), endDay = requireNotNull(endDay),
    startMinute = startMinute.takeUnless { allDay }, endMinute = endMinute.takeUnless { allDay },
    allDay = allDay, location = location, repeat = repeat,
    reminder = reminder.takeUnless { !allDay && startMinute == null }, collectionId = collectionId,
    timeOfDay = timeOfDay.takeUnless { allDay }
)

internal fun Proposal.convertTo(kind: DraftKind, fallbackDay: Long): Proposal {
    if (this.kind == kind) return this
    val retainedBody = if (kind != DraftKind.EVENT && location.isNotBlank()) {
        listOf(body.trimEnd(), "Location · $location").filter(String::isNotBlank).joinToString("\n").take(48_000)
    } else body
    return when (kind) {
        DraftKind.NOTE -> copy(kind = kind, body = retainedBody, priority = Priority.NONE, startDay = null,
            startMinute = null, endDay = null, endMinute = null, allDay = false, location = "",
            repeat = Repeat.NONE, reminder = null, flexibleSchedule = null, timeOfDay = null, hardDeadline = null,
            durationMinutes = null, recurrenceRule = null, inferredSchedule = null)
        DraftKind.TASK -> copy(kind = kind, body = retainedBody, endDay = null,
            endMinute = null, allDay = false, location = "")
        DraftKind.EVENT -> {
            val start = startDay ?: fallbackDay
            val plusHour = startMinute?.plus(60)
            copy(kind = kind, priority = Priority.NONE, startDay = start, flexibleSchedule = null, hardDeadline = null,
                durationMinutes = null, recurrenceRule = null, inferredSchedule = null,
                endDay = start + if (plusHour != null && plusHour >= 1440) 1 else 0,
                endMinute = plusHour?.rem(1440), allDay = false)
        }
    }
}

internal data class WorkspaceSnapshot(
    val collections: List<CollectionEntity>,
    val entries: List<TaggedEntry>,
    val tags: List<TagEntity>,
    val events: List<Event>,
    val eventTags: List<EventTag>,
    val dumps: List<BrainDump> = emptyList(),
    val drafts: List<Draft> = emptyList(),
    val recurrenceSkips: List<RecurrenceSkip> = emptyList(),
    val captureRuns: List<CaptureRun> = emptyList()
) {
    fun proposals(): List<Proposal> = entries.map { it.asProposal() } +
        events.map { event -> event.asProposal(eventTags.filter { it.eventId == event.id }.map { it.tagId }.toSet()) }
}
