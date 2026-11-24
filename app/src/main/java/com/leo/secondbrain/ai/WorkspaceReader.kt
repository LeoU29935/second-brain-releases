package com.leo.secondbrain.ai

import com.leo.secondbrain.data.BrainRepository
import com.leo.secondbrain.data.DraftKind
import com.leo.secondbrain.data.occursOn
import com.leo.secondbrain.data.validDay
import com.leo.secondbrain.data.pathName
import com.leo.secondbrain.data.plainBody
import com.leo.secondbrain.data.putNullable
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

internal class WorkspaceReader(private val repository: BrainRepository) {
    suspend fun read(id: String, offset: Int, allowedIds: Set<String>): JSONObject {
        require(id in allowedIds) { "Find the item before reading it." }
        val workspace = repository.snapshot(includeScheduling = true)
        val proposal = requireNotNull(workspace.proposals().firstOrNull { it.targetId == id }) {
            "The item is no longer available."
        }
        val body = proposal.body.plainBody()
        require(offset in 0..body.length) { "The requested body offset is outside the item." }
        val endIndex = (offset + 4_000).coerceAtMost(body.length)
        val end = if (endIndex < body.length && endIndex > offset &&
            Character.isHighSurrogate(body[endIndex - 1])) endIndex - 1 else endIndex
        val entry = workspace.entries.firstOrNull { it.entry.id == id }?.entry
        val tags = workspace.tags.filter { it.id in proposal.tags }
        return proposal.copy(body = body.substring(offset, end)).json()
            .put("bodyOffset", offset).put("bodyLength", body.length)
            .putNullable("nextBodyOffset", end.takeIf { it < body.length })
            .putNullable("createdAt", entry?.createdAt)
            .putNullable("completed", entry?.completed?.takeIf { proposal.kind == DraftKind.TASK })
            .putNullable("recurrenceAnchor", entry?.recurrenceAnchor?.let(LocalDate::ofEpochDay)?.toString())
            .put("recurrenceExceptions", JSONArray(workspace.recurrenceSkips.filter { it.seriesId == id }
                .map { LocalDate.ofEpochDay(it.day).toString() }))
            .put("collectionPath", proposal.collectionId?.let(workspace.collections::pathName) ?: "Inbox")
            .put("tagNames", JSONArray(tags.map { it.name }))
            .put("zone", ZoneId.systemDefault().id)
    }

    suspend fun list(id: String?, offset: Int, allowedFolders: Set<String>): RelevantContext {
        require(id == null || id in allowedFolders) { "Find the collection before listing it." }
        val workspace = repository.snapshot()
        val folder = id?.let { requireNotNull(workspace.collections.firstOrNull { it.id == id }) }
        val folders = workspace.collections.filter { it.parentId == id }.map { it.id to JSONObject()
            .put("id", it.id).put("kind", "COLLECTION").put("title", it.name).put("description", it.description)
            .put("icon", it.icon).put("color", it.color).put("path", workspace.collections.pathName(it.id)) }
        val items = workspace.proposals().filter { it.collectionId == id }.associateBy { requireNotNull(it.targetId) }
        val completion = workspace.entries.associate { it.entry.id to it.entry.completed }
        val rows = folders + items.map { (key, proposal) -> key to proposal.copy(body = proposal.body.plainBody().take(220)).json()
            .putNullable("completed", completion[key]?.takeIf { proposal.kind == DraftKind.TASK }) }
        require(offset in 0..rows.size) { "The collection offset is outside the result list." }
        val page = rows.drop(offset).take(15)
        val keys = page.map { it.first }.toSet()
        val shown = items.filterKeys { it in keys }
        val visibleTagIds = shown.values.flatMap { it.tags }.toSet()
        val tags = workspace.tags.filter { it.id in visibleTagIds }
        val payload = JSONObject()
            .put("collection", folder?.let { workspace.collections.pathName(it.id) } ?: "Inbox and top-level folders")
            .put("results", JSONArray(page.map { it.second }))
            .putNullable("nextOffset", (offset + page.size).takeIf { it < rows.size })
        return RelevantContext(payload, shown,
            folders.map { it.first }.filter { it in keys }.toSet(), tags.map { it.id }.toSet())
    }

    suspend fun schedule(from: LocalDate, to: LocalDate, completed: Boolean?, fromMinute: Int?,
        untilMinute: Int?, offset: Int): RelevantContext {
        val first = from.toEpochDay()
        val last = to.toEpochDay()
        require(validDay(first) && validDay(last) && last - first in 0..30) { "Choose a date range of up to 31 days." }
        val start = fromMinute ?: 0
        val end = untilMinute ?: 1440
        require(start in 0..1439 && end in (start + 1)..1440) { "Choose a valid time range." }
        val workspace = repository.snapshot(includeScheduling = true)
        val proposals = workspace.proposals().associateBy { requireNotNull(it.targetId) }
        val skips = workspace.recurrenceSkips.groupBy({ it.seriesId }, { it.day }).mapValues { it.value.toSet() }
        val rows = buildList {
            for (day in first..last) {
                val date = LocalDate.ofEpochDay(day)
                for (tagged in workspace.entries) {
                    val task = tagged.entry
                    if (task.kind.name != "TASK" || completed != null && task.completed != completed ||
                        !task.occursOn(date, skips[task.id].orEmpty())) continue
                    val minute = task.dueMinute
                    val period = task.timeOfDay
                    val matchesTime = when {
                        minute != null -> minute in start until end
                        period != null -> period.fromMinute < end && period.untilMinute > start
                        else -> fromMinute == null && untilMinute == null
                    }
                    if (!matchesTime) continue
                    add(task.id to proposals.getValue(task.id).copy(body = task.body.plainBody().take(220)).json()
                        .put("occurrenceDate", date.toString()).put("completed", task.completed))
                }
                if (completed != null) continue
                for (event in workspace.events) {
                    val span = event.endDay - event.startDay
                    val occurrence = event.repeat.next(LocalDate.ofEpochDay(event.startDay), date.minusDays(span))
                        ?.toEpochDay() ?: continue
                    if (occurrence > day) continue
                    val period = event.timeOfDay
                    if (!event.allDay && event.startMinute == null && period == null &&
                        (fromMinute != null || untilMinute != null)) continue
                    val eventStart = if (!event.allDay && occurrence == day) {
                        event.startMinute ?: period?.fromMinute ?: 0
                    } else 0
                    val eventEnd = if (!event.allDay && occurrence + span == day) {
                        event.endMinute ?: period?.untilMinute
                            ?: event.startMinute?.plus(1)?.takeIf { span == 0L } ?: 1440
                    } else 1440
                    if (eventStart >= end || eventEnd <= start) continue
                    add(event.id to proposals.getValue(event.id).copy(body = event.body.plainBody().take(220)).json()
                        .put("occurrenceDate", date.toString()).put("occurrenceStartDate", LocalDate.ofEpochDay(occurrence).toString()))
                }
            }
        }
        require(offset in 0..rows.size) { "The schedule offset is outside the result list." }
        val page = rows.drop(offset).take(25)
        return RelevantContext(JSONObject().put("results", JSONArray(page.map { it.second }))
            .put("fromDate", from.toString()).put("throughDate", to.toString())
            .putNullable("nextOffset", (offset + page.size).takeIf { it < rows.size }),
            page.associate { it.first to proposals.getValue(it.first) }, emptySet(), emptySet())
    }
}
