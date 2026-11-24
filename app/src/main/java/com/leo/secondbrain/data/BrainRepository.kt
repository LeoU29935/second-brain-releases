package com.leo.secondbrain.data

import androidx.room.withTransaction
import org.json.JSONObject
import org.json.JSONArray
import com.leo.secondbrain.ai.objects
import java.time.LocalDate
import java.time.Instant
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class WorkspaceUndo(val message: String, val run: suspend () -> Unit)
internal data class BulkTaskMove(val count: Int, val skipped: Int, val undo: WorkspaceUndo)

internal class BrainRepository(private val database: BrainDatabase, private val deleteRecording: (String) -> Unit = {}) {
    private val dao = database.brainDao()
    internal val release = database.releaseDao()
    private val trash = database.trashDao()
    val collections = dao.observeCollections()
    val entries = dao.observeEntries()
    val tags = dao.observeTags()
    val events = release.observeEvents()
    val eventTags = release.observeEventTags()
    val dumps = release.observeDumps()
    val drafts = release.observeDrafts()
    val recentlyDeleted = trash.observe()
    val recurrenceSkips = dao.observeRecurrenceSkips()
    val captureRuns = release.captureRuns()
    val assistantMemory = AssistantMemory(database)

    suspend fun toggleFavorite(id: String, kind: TrashKind) {
        val changed = when (kind) {
            TrashKind.COLLECTION -> dao.toggleFolderFavorite(id)
            TrashKind.EVENT -> release.toggleEventFavorite(id)
            TrashKind.TASK, TrashKind.NOTE -> dao.toggleEntryFavorite(id)
            TrashKind.CAPTURE -> 0
        }
        require(changed == 1) { "This item is no longer in your workspace." }
    }

    suspend fun saveCapture(dump: BrainDump) = database.withTransaction {
        if (!release.captureExists(dump.id)) {
            release.insertDump(dump)
            release.saveCaptureRun(CaptureRun(dump.id, zone = java.time.ZoneId.systemDefault().id))
        }
    }

    suspend fun retryCapture(id: String) = database.withTransaction {
        val dump = release.dump(id) ?: return@withTransaction
        if (dump.status == DumpStatus.ARCHIVED) return@withTransaction
        val previous = release.captureRun(id)
        if (previous?.status in setOf(CaptureStatus.QUEUED, CaptureStatus.VERIFYING, CaptureStatus.ORGANIZED, CaptureStatus.UNDONE) ||
            release.draftsFor(id).isNotEmpty()) return@withTransaction
        release.saveCaptureRun(CaptureRun(id, zone = previous?.zone ?: java.time.ZoneId.systemDefault().id))
    }

    suspend fun captureFailure(id: String, message: String, retry: Boolean): Boolean = database.withTransaction {
        val run = release.captureRun(id)?.takeIf { it.status == CaptureStatus.QUEUED } ?: return@withTransaction false
        val again = retry && run.attempts < 3
        release.saveCaptureRun(run.copy(status = if (again) CaptureStatus.QUEUED else CaptureStatus.FAILED,
            attempts = run.attempts + 1, message = message))
        again
    }

    suspend fun organizeCapture(id: String, suggestions: List<Proposal>, draftOnly: Boolean = false,
        verifyReminders: Boolean = false) = database.withTransaction {
        val run = release.captureRun(id)?.takeIf { it.status == CaptureStatus.QUEUED } ?: return@withTransaction
        val dump = release.dump(id)?.takeIf { it.status == DumpStatus.UNREVIEWED } ?: return@withTransaction
        val drafts = release.draftsFor(id)
        val live = snapshot(includeScheduling = true)
        val zone = run.zone?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: java.time.ZoneId.systemDefault()
        val reference = java.time.Instant.ofEpochMilli(dump.createdAt).atZone(zone)
        val planner = com.leo.secondbrain.ai.TaskPlanner(live, reference, ZonedDateTime.now(zone))
        val source = listOfNotNull(dump.original, dump.transcript).map(String::trim).filter(String::isNotEmpty)
            .distinct().joinToString("\n")
        val proposals = suggestions.map { proposal ->
            if (proposal.kind != DraftKind.TASK || proposal.targetId != null || proposal.inferredSchedule != null ||
                proposal.uncertainty == com.leo.secondbrain.ai.UNSCHEDULED_REQUEST) proposal
            else {
                val clause = com.leo.secondbrain.ai.scheduleExcerpt(JSONObject().put("title", proposal.title), source, suggestions.size == 1)
                planner.plan(proposal, clause, source)
            }
        }
        val originals = live.proposals()
        fun duplicate(proposed: Proposal) = originals.any { existing ->
            existing.kind == proposed.kind && existing.title.equals(proposed.title, ignoreCase = true) &&
                existing.startDay == proposed.startDay && sameTaskTime(existing, proposed) &&
                existing.timeOfDay == proposed.timeOfDay && existing.hardDeadline == proposed.hardDeadline &&
                existing.collectionId == proposed.collectionId
        }
        val valid = proposals.isNotEmpty() && proposals.all { proposed ->
            proposed.targetId == null && proposed.uncertainty.isBlank() &&
                (proposed.collectionId == null || live.collections.any { it.id == proposed.collectionId }) &&
                !duplicate(proposed) && runCatching { proposed.validatedForAutomaticCreation() }.isSuccess
        }
        if (draftOnly || !valid || drafts.isNotEmpty()) {
            if (drafts.isEmpty() && proposals.isNotEmpty()) saveSuggestions(id, proposals.map { proposed ->
                if (proposed.targetId == null && proposed.uncertainty.isBlank() && duplicate(proposed))
                    proposed.copy(uncertainty = "A similar item is already in your workspace.") else proposed
            })
            release.saveCaptureRun(run.copy(status = CaptureStatus.ATTENTION,
                message = if (valid) null else "Review unclear details or possible duplicates."))
            return@withTransaction
        }
        val results = JSONArray()
        for (proposal in proposals) {
            val saved = applyProposal(proposal.validatedForAutomaticCreation())
            results.put(saved.captureReceipt())
        }
        release.saveCaptureRun(run.copy(
            status = if (verifyReminders) CaptureStatus.VERIFYING else CaptureStatus.ORGANIZED,
            message = null, results = results.toString()))
        release.dumpStatus(id, DumpStatus.REVIEWED)
    }

    suspend fun finishCaptureVerification(ids: Set<String>, error: String? = null) = database.withTransaction {
        for (run in release.capturesAwaitingReminders()) {
            if (run.dumpId in ids) release.saveCaptureRun(run.copy(
                status = if (error == null) CaptureStatus.ORGANIZED else CaptureStatus.VERIFYING,
                message = error))
        }
    }

    suspend fun undoCapture(id: String) = database.withTransaction {
        val run = requireNotNull(release.captureRun(id))
        require(run.status == CaptureStatus.ORGANIZED)
        val items = JSONArray(run.results).objects()
        require(items.none { it.has("draft") }) { "Changes from approved drafts can be managed in their workspace items." }
        for (item in items) {
            val target = item.getString("id")
            val kind = TrashKind.valueOf(item.getString("kind"))
            val version = if (kind == TrashKind.EVENT) release.event(target)?.updatedAt else dao.entry(target)?.updatedAt
            require(version == item.getLong("version")) { "A result changed. Edit or delete it directly to keep your changes." }
            checkNotNull(deleteWithUndo(target, kind))
        }
        release.saveCaptureRun(run.copy(status = CaptureStatus.UNDONE, message = null))
    }

    suspend fun hasCollection(id: String) = dao.collections().any { it.id == id }

    suspend fun draftOpen(id: String) =
        release.draft(id)?.status in setOf(DraftStatus.PENDING, DraftStatus.DEFERRED)

    suspend fun saveCollection(collection: CollectionEntity) = database.withTransaction {
        val all = dao.collections()
        require(collection.name.isNotBlank())
        require(trash.collectionTrash(collection.id) == null) { "Restore the collection before editing it." }
        require(collection.parentId !in all.descendantIds(collection.id))
        require(collection.parentId == null || all.any { it.id == collection.parentId })
        require(all.canPlaceFolder(collection.id, collection.parentId)) {
            "Choose a destination that leaves room for all folders in this branch."
        }
        dao.upsertCollection(collection.copy(name = collection.name.trim(),
            favorite = all.firstOrNull { it.id == collection.id }?.favorite ?: collection.favorite))
    }

    suspend fun deleteCollection(id: String): Boolean = database.withTransaction {
        val collections = dao.collections()
        val collection = collections.firstOrNull { it.id == id } ?: return@withTransaction false
        val record = TrashRecord(rootId = id, kind = TrashKind.COLLECTION, title = collection.name,
            deletedAt = System.currentTimeMillis())
        trash.insert(record)
        collections.descendantIds(id).toList().chunked(500).forEach { trash.markCollections(it, record.id) }
        trash.markCollectionEntries(record.id)
        trash.markCollectionEvents(record.id)
        true
    }

    suspend fun saveEntry(entry: EntryEntity, tagIds: Set<String>) = database.withTransaction {
        require(entry.title.isNotBlank())
        require(listOfNotNull(entry.deadline, entry.hardDeadline).all(::validDay))
        require(entry.dueMinute == null || (entry.deadline != null && entry.dueMinute in 0..1439))
        require(entry.reminder == null || (entry.deadline != null && entry.reminder in 0..10_080))
        require(entry.durationMinutes == null || entry.durationMinutes in 1..10_080)
        RecurrenceRule.read(entry.recurrenceRule)
        require(trash.entryTrash(entry.id) == null) { "Restore the item before editing it." }
        val existing = dao.entry(entry.id)
        val isTask = entry.kind == EntryKind.TASK
        val collectionId = entry.collectionId?.takeIf { id ->
            dao.collections().any { it.id == id }
        }
        dao.upsertEntry(
            entry.copy(
                title = entry.title.trim(),
                body = entry.body.trimEnd(),
                collectionId = collectionId,
                deadline = entry.deadline.takeIf { isTask },
                hardDeadline = entry.hardDeadline.takeIf { isTask },
                dueMinute = entry.dueMinute.takeIf { isTask },
                reminder = entry.reminder.takeIf { isTask && (entry.timeOfDay == null || entry.dueMinute != null) },
                timeOfDay = entry.timeOfDay.takeIf { isTask && entry.deadline != null },
                repeat = if (isTask && entry.deadline != null) entry.repeat else Repeat.NONE,
                durationMinutes = entry.durationMinutes.takeIf { isTask },
                recurrenceRule = entry.recurrenceRule.takeIf { isTask && entry.repeat != Repeat.NONE && entry.deadline != null },
                inferredSchedule = entry.inferredSchedule.takeIf { isTask },
                recurrenceAnchor = when {
                    !isTask || entry.repeat == Repeat.NONE -> null
                    existing?.deadline == entry.deadline && existing?.repeat == entry.repeat &&
                        existing?.recurrenceRule == entry.recurrenceRule ->
                        existing?.recurrenceAnchor ?: entry.deadline
                    else -> entry.deadline
                },
                priority = if (isTask) entry.priority else Priority.NONE,
                completed = isTask && (existing?.completed ?: false),
                favorite = existing?.favorite ?: entry.favorite,
                flexibleSchedule = entry.flexibleSchedule.takeIf { isTask && entry.repeat == Repeat.NONE &&
                    entry.deadline != null && entry.dueMinute != null },
                scheduleChange = existing?.scheduleChange.takeIf { existing?.deadline == entry.deadline &&
                    existing?.dueMinute == entry.dueMinute && existing?.flexibleSchedule == entry.flexibleSchedule },
                createdAt = existing?.createdAt ?: entry.createdAt,
                updatedAt = maxOf(System.currentTimeMillis(), (existing?.updatedAt ?: 0) + 1)
            )
        )
        dao.clearEntryTags(entry.id)
        val links = dao.tagIds().filter { it in tagIds }.map { EntryTag(entry.id, it) }
        if (links.isNotEmpty()) dao.insertEntryTags(links)
    }

    suspend fun setCompleted(id: String, completed: Boolean): Boolean = database.withTransaction {
        val entry = dao.entry(id)?.takeIf { it.kind == EntryKind.TASK } ?: return@withTransaction false
        val anchor = entry.recurrenceAnchor ?: entry.deadline
        if (completed && entry.kind == EntryKind.TASK && entry.repeat != Repeat.NONE && anchor != null) {
            val current = LocalDate.ofEpochDay(requireNotNull(entry.deadline))
            val next = entry.repeat.nextUnskipped(LocalDate.ofEpochDay(anchor),
                maxOf(current, LocalDate.now()).plusDays(1), dao.skippedDays(id).toSet(),
                RecurrenceRule.read(entry.recurrenceRule))
            dao.upsertEntry(entry.copy(deadline = next?.toEpochDay(), recurrenceAnchor = anchor,
                completed = next == null, repeat = if (next == null) Repeat.NONE else entry.repeat,
                recurrenceRule = entry.recurrenceRule.takeIf { next != null },
                dueMinute = if (next == null) null else entry.dueMinute,
                timeOfDay = entry.timeOfDay.takeIf { next != null },
                reminder = if (next == null) null else entry.reminder, updatedAt = System.currentTimeMillis()))
        } else {
            dao.setCompleted(id, completed, System.currentTimeMillis())
        }
        true
    }

    suspend fun separateOccurrence(seriesId: String, day: Long, id: String, expectedVersion: Long? = null) = database.withTransaction {
        val series = requireNotNull(dao.entry(seriesId)) { "This series is no longer available." }
        require(expectedVersion == null || series.updatedAt == expectedVersion) { "The series changed. Find it again." }
        val tagged = requireNotNull(dao.snapshotEntries().firstOrNull { it.entry.id == seriesId })
        val scheduled = requireNotNull(series.deadline)
        val date = LocalDate.ofEpochDay(day)
        val skipped = dao.skippedDays(seriesId).toSet()
        require(series.kind == EntryKind.TASK && series.repeat != Repeat.NONE && !series.completed &&
            validDay(day) && series.occursOn(date, skipped)) { "That occurrence is no longer part of the series." }
        val anchor = series.recurrenceAnchor ?: scheduled
        if (scheduled == day) {
            val next = requireNotNull(series.repeat.nextUnskipped(LocalDate.ofEpochDay(anchor), date.plusDays(1), skipped, RecurrenceRule.read(series.recurrenceRule))) {
                "There are no more supported dates for this series."
            }
            dao.upsertEntry(series.copy(deadline = next.toEpochDay(), recurrenceAnchor = anchor,
                updatedAt = System.currentTimeMillis()))
        }
        dao.putRecurrenceSkip(RecurrenceSkip(seriesId, day))
        saveEntry(series.copy(id = id, deadline = day, repeat = Repeat.NONE, recurrenceRule = null, recurrenceAnchor = null,
            createdAt = System.currentTimeMillis()), tagged.tags.map { it.id }.toSet())
    }

    suspend fun skipOccurrence(id: String, day: Long, expectedVersion: Long?): WorkspaceUndo = database.withTransaction {
        val series = requireNotNull(dao.entry(id)) { "This series is no longer available." }
        require(series.updatedAt == expectedVersion) { "The series changed. Find it again." }
        val scheduled = requireNotNull(series.deadline)
        val date = LocalDate.ofEpochDay(day)
        val skipped = dao.skippedDays(id).toSet()
        require(series.kind == EntryKind.TASK && series.repeat != Repeat.NONE && !series.completed &&
            validDay(day) && series.occursOn(date, skipped)) { "That date is not an active occurrence." }
        val after = if (scheduled == day) {
            val anchor = series.recurrenceAnchor ?: scheduled
            val next = requireNotNull(series.repeat.nextUnskipped(LocalDate.ofEpochDay(anchor), date.plusDays(1), skipped, RecurrenceRule.read(series.recurrenceRule))) {
                "No later occurrence is available."
            }
            series.copy(deadline = next.toEpochDay(), recurrenceAnchor = anchor, updatedAt = System.currentTimeMillis())
                .also { dao.upsertEntry(it) }
        } else series
        dao.putRecurrenceSkip(RecurrenceSkip(id, day))
        WorkspaceUndo("Occurrence skipped") {
            database.withTransaction {
                require(dao.entry(id) == after && day in dao.skippedDays(id)) { "The series changed after this action." }
                dao.removeRecurrenceSkip(id, day)
                if (after != series) dao.upsertEntry(series)
            }
        }
    }

    suspend fun completeWithUndo(id: String, completed: Boolean, expectedDeadline: Long?): WorkspaceUndo? = database.withTransaction {
        val before = dao.entry(id)?.takeIf { it.kind == EntryKind.TASK } ?: return@withTransaction null
        if (before.deadline != expectedDeadline || before.completed == completed) return@withTransaction null
        if (!setCompleted(id, completed)) return@withTransaction null
        val after = requireNotNull(dao.entry(id))
        if (after == before) return@withTransaction null
        WorkspaceUndo(if (completed) "Task completed" else "Task reopened") {
            database.withTransaction {
                require(dao.entry(id) == after) { "The task changed after this action." }
                dao.upsertEntry(before.copy(updatedAt = System.currentTimeMillis()))
            }
        }
    }

    suspend fun deleteEntry(id: String): Boolean = database.withTransaction {
        val entry = dao.entry(id) ?: return@withTransaction false
        val record = TrashRecord(rootId = id, kind = TrashKind.valueOf(entry.kind.name), title = entry.title,
            deletedAt = System.currentTimeMillis())
        trash.insert(record)
        trash.markEntry(id, record.id)
        true
    }

    suspend fun saveTag(tag: TagEntity) {
        require(tag.name.isNotBlank())
        dao.upsertTag(tag.copy(name = tag.name.trim()))
    }

    suspend fun deleteTag(id: String) = dao.deleteTag(id)

    suspend fun saveEvent(event: Event, tagIds: Set<String>) = database.withTransaction {
        require(trash.eventTrash(event.id) == null) { "Restore the event before editing it." }
        event.asProposal(tagIds).validated(requireSchedule = true)
        require(event.collectionId == null || dao.collections().any { it.id == event.collectionId })
        val existing = release.event(event.id)
        release.saveEvent(event.copy(title = event.title.trim(), body = event.body.trimEnd(),
            favorite = existing?.favorite ?: event.favorite,
            updatedAt = maxOf(System.currentTimeMillis(), (existing?.updatedAt ?: 0) + 1)))
        release.clearEventTags(event.id)
        val links = dao.tagIds().filter { it in tagIds }.map { EventTag(event.id, it) }
        if (links.isNotEmpty()) release.insertEventTags(links)
    }

    suspend fun deleteEvent(id: String): Boolean = database.withTransaction {
        val event = release.event(id) ?: return@withTransaction false
        val record = TrashRecord(rootId = id, kind = TrashKind.EVENT, title = event.title,
            deletedAt = System.currentTimeMillis())
        trash.insert(record)
        trash.markEvent(id, record.id)
        true
    }

    suspend fun saveEditorItem(id: String, proposal: Proposal, requireVersion: Boolean = false) = database.withTransaction {
        if (requireVersion) {
            val version = dao.entry(id)?.updatedAt ?: release.event(id)?.updatedAt
            require(version != null && version == proposal.targetVersion) { "The item changed. Reopen Edit and try again." }
            proposal.validated(requireSchedule = true)
            require(proposal.collectionId == null || dao.collections().any { it.id == proposal.collectionId }) {
                "The chosen collection is no longer available."
            }
        }
        require(trash.entryTrash(id) == null && trash.eventTrash(id) == null) { "Restore the item before editing it." }
        val latest = if (!requireVersion && proposal.flexibleSchedule != null) dao.entry(id) else null
        val current = if (latest?.flexibleSchedule != null) proposal.copy(startDay = latest.deadline,
            startMinute = latest.dueMinute, flexibleSchedule = FlexibleSchedule.read(JSONObject(latest.flexibleSchedule))) else proposal
        val value = current.copy(title = current.title.ifBlank { "Untitled ${current.kind.label.lowercase()}" })
        if (value.kind == DraftKind.EVENT) {
            saveEvent(value.toEvent(id).copy(favorite = dao.entry(id)?.favorite == true), value.tags)
            dao.deleteEntry(id)
        } else {
            val kind = EntryKind.valueOf(value.kind.name)
            saveEntry(EntryEntity(
                id = id, kind = kind, title = value.title, body = value.body, collectionId = value.collectionId,
                deadline = value.startDay, dueMinute = value.startMinute, reminder = value.reminder,
                timeOfDay = value.timeOfDay, hardDeadline = value.hardDeadline,
                repeat = value.repeat, priority = value.priority, favorite = release.event(id)?.favorite == true,
                durationMinutes = value.durationMinutes, recurrenceRule = value.recurrenceRule?.encode(),
                inferredSchedule = value.inferredSchedule,
                flexibleSchedule = value.flexibleSchedule?.json()?.toString()
            ), value.tags)
            release.deleteEvent(id)
        }
    }

    suspend fun deleteEditorItem(id: String) = database.withTransaction {
        if (release.event(id) != null) deleteEvent(id) else deleteEntry(id)
    }

    suspend fun deleteWithUndo(id: String, kind: TrashKind? = null): WorkspaceUndo? = database.withTransaction {
        val actual = kind ?: if (release.event(id) != null) TrashKind.EVENT else
            dao.entry(id)?.let { TrashKind.valueOf(it.kind.name) } ?: return@withTransaction null
        val batch = when (actual) {
            TrashKind.COLLECTION -> if (deleteCollection(id)) trash.collectionTrash(id) else null
            TrashKind.EVENT -> if (deleteEvent(id)) trash.eventTrash(id) else null
            TrashKind.NOTE, TrashKind.TASK -> if (deleteEntry(id)) trash.entryTrash(id) else null
            TrashKind.CAPTURE -> release.dump(id)?.let { capture ->
                val title = capture.original.ifBlank { capture.transcript.orEmpty() }.trim().lineSequence().firstOrNull()
                    .orEmpty().take(80).ifBlank { "Voice capture" }
                val record = TrashRecord(rootId = id, kind = actual, title = title, deletedAt = System.currentTimeMillis())
                trash.insert(record)
                release.markCapture(id, record.id)
                record.id
            }
        } ?: return@withTransaction null
        WorkspaceUndo("${actual.label} moved to Recently Deleted") { restoreDeleted(batch) }
    }

    suspend fun restoreDeleted(id: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val record = requireNotNull(trash.record(id)) { "This item is no longer in Recently Deleted." }
        require(record.deletedAt > now - TRASH_RETENTION_MS) { "The restore period has ended." }
        val parent = when (record.kind) {
            TrashKind.COLLECTION -> trash.collectionParent(record.rootId)
            TrashKind.EVENT -> trash.eventParent(record.rootId)
            TrashKind.NOTE, TrashKind.TASK -> trash.entryParent(record.rootId)
            TrashKind.CAPTURE -> null
        }
        require(parent == null || trash.collectionTrash(parent) == null) { "Restore its parent collection first." }
        trash.restoreCollections(id)
        trash.restoreEntries(id)
        trash.restoreEvents(id)
        release.restoreCaptures(id)
        trash.remove(id)
    }

    suspend fun permanentlyDelete(id: String) {
        val media = database.withTransaction { purgeBatch(id) }
        withContext(Dispatchers.IO) { media.forEach(deleteRecording) }
    }

    suspend fun purgeExpired(now: Long = System.currentTimeMillis()) {
        val media = database.withTransaction { trash.expired(now - TRASH_RETENTION_MS).flatMap { purgeBatch(it) } }
        withContext(Dispatchers.IO) { media.forEach(deleteRecording) }
    }

    private suspend fun purgeBatch(id: String): Set<String> {
        if (trash.record(id) == null) return emptySet()
        val recordings = release.deletedRecordingNames(id).toSet()
        trash.purgeEntries(id)
        trash.purgeEvents(id)
        trash.purgeCollections(id)
        release.purgeCaptures(id)
        trash.remove(id)
        return if (recordings.isEmpty()) emptySet() else recordings - release.recordingNames().toSet()
    }

    suspend fun snapshot(includeDumps: Boolean = false, includeScheduling: Boolean = includeDumps): WorkspaceSnapshot = database.withTransaction {
        WorkspaceSnapshot(
            dao.collections(), dao.snapshotEntries(), dao.snapshotTags(), release.events(), release.eventTags(),
            if (includeDumps) release.dumps() else emptyList(), if (includeDumps) release.drafts() else emptyList(),
            if (includeScheduling) dao.recurrenceSkips() else emptyList(),
            if (includeDumps) release.snapshotCaptureRuns() else emptyList()
        )
    }

    suspend fun reminderSnapshot(): Triple<List<EntryEntity>, List<Event>, List<RecurrenceSkip>> = database.withTransaction {
        Triple(dao.reminderTasks(), release.reminderEvents(), dao.recurrenceSkips())
    }

    suspend fun reconsiderFlexibleTasks(now: ZonedDateTime): Instant? = database.withTransaction {
        val flexible = dao.flexibleTasks()
        if (flexible.isEmpty()) return@withTransaction null
        val entries = dao.snapshotEntries().map { it.entry }.associateBy { it.id }.toMutableMap()
        val events = release.events()
        val skips = dao.recurrenceSkips().groupBy({ it.seriesId }, { it.day }).mapValues { it.value.toSet() }
        var next: Instant? = null
        for (entry in flexible) {
            val window = FlexibleSchedule.read(JSONObject(requireNotNull(entry.flexibleSchedule)))
            if (!window.automatic || window.afterEventId == null && window.lastDay < now.toLocalDate().toEpochDay()) continue
            val old = TaskSlot(requireNotNull(entry.deadline), requireNotNull(entry.dueMinute))
            fun ends(slot: TaskSlot) = occurrenceInstant(LocalDate.ofEpochDay(slot.day), slot.minute, now.zone)
                .plusSeconds(window.duration * 60L)
            val availability = CalendarAvailability(entries.values.toList(), events, skips)
            val expired = ends(old) <= now.toInstant()
            val conflict = !availability.respectsWindow(old, window, entry.timeOfDay) || !availability.isFree(old, window.duration, entry.id)
            val slot = if (expired || conflict) availability.find(window, now, entry.id, entry.timeOfDay) else old
            val wasWaiting = ScheduleMove.read(entry.scheduleChange)?.waiting == true
            if (slot == null && !wasWaiting) {
                val waiting = entry.copy(
                    scheduleChange = ScheduleMove(old, "No available time fits the requested window.", waiting = true)
                        .json().toString(), updatedAt = maxOf(System.currentTimeMillis(), entry.updatedAt + 1))
                dao.upsertEntry(waiting)
                entries[entry.id] = waiting
            } else if (slot == old && wasWaiting) {
                val restored = entry.copy(scheduleChange = null, updatedAt = maxOf(System.currentTimeMillis(), entry.updatedAt + 1))
                dao.upsertEntry(restored)
                entries[entry.id] = restored
            }
            if (slot != null && slot != old) {
                val reason = if (expired) "the previous time passed" else "the previous time became unavailable"
                val whenLabel = if (slot.day == old.day) minuteLabel(slot.minute)
                    else "${LocalDate.ofEpochDay(slot.day)} at ${minuteLabel(slot.minute)}"
                val updated = entry.copy(deadline = slot.day, dueMinute = slot.minute,
                    updatedAt = maxOf(System.currentTimeMillis(), entry.updatedAt + 1),
                    scheduleChange = ScheduleMove(old, "Moved to $whenLabel because $reason.").json().toString())
                dao.upsertEntry(updated)
                entries[entry.id] = updated
            }
            val recheck = slot?.let(::ends)?.takeIf { it > now.toInstant() }
            if (recheck != null) next = next?.let { minOf(it, recheck) } ?: recheck
        }
        next
    }

    suspend fun undoScheduleMove(id: String, expectedChange: String) = database.withTransaction {
        val entry = requireNotNull(dao.entry(id))
        require(entry.scheduleChange == expectedChange && !entry.completed) { "The task schedule has since changed." }
        val move = requireNotNull(ScheduleMove.read(expectedChange))
        require(!move.waiting) { "Choose a scheduled time for this task." }
        val window = FlexibleSchedule.read(JSONObject(requireNotNull(entry.flexibleSchedule)))
        dao.upsertEntry(entry.copy(deadline = move.previous.day, dueMinute = move.previous.minute,
            flexibleSchedule = window.copy(automatic = false).json().toString(), scheduleChange = null,
            updatedAt = maxOf(System.currentTimeMillis(), entry.updatedAt + 1)))
    }

    suspend fun moveUnfinishedTasks(to: Long): BulkTaskMove = database.withTransaction {
        val now = ZonedDateTime.now()
        require(validDay(to) && to >= now.toLocalDate().toEpochDay()) { "Choose today or a later date." }
        val workspace = snapshot(includeScheduling = true)
        val entries = workspace.entries.map { it.entry }.associateBy { it.id }.toMutableMap()
        val skips = workspace.recurrenceSkips.groupBy({ it.seriesId }, { it.day }).mapValues { it.value.toSet() }
        val changes = mutableListOf<Pair<EntryEntity, Long>>()
        var skipped = 0
        for (task in entries.values.toList()) {
            if (task.kind != EntryKind.TASK || task.completed || task.deadline == to) continue
            val window = task.flexibleSchedule?.let { FlexibleSchedule.read(JSONObject(it)) }
            if (task.repeat != Repeat.NONE || window?.afterEventId != null || task.dueMinute != null && window?.automatic != true ||
                listOfNotNull(window?.deadlineDay, task.hardDeadline).any { to > it }) {
                skipped++
                continue
            }
            val movedWindow = window?.copy(firstDay = to, lastDay = to)
            val slot = movedWindow?.let {
                CalendarAvailability(entries.values.toList(), workspace.events, skips).find(it, now, task.id, task.timeOfDay)
            }
            if (movedWindow != null && slot == null) {
                skipped++
                continue
            }
            val version = maxOf(System.currentTimeMillis(), task.updatedAt + 1)
            val updated = task.copy(deadline = to, dueMinute = slot?.minute, updatedAt = version,
                flexibleSchedule = movedWindow?.json()?.toString(), scheduleChange = null)
            dao.upsertEntry(updated)
            entries[task.id] = updated
            changes.add(task to version)
        }
        require(changes.isNotEmpty()) { "No unfinished tasks could be moved to that date." }
        BulkTaskMove(changes.size, skipped, WorkspaceUndo("Moved ${changes.size} unfinished tasks") {
            database.withTransaction {
                for ((before, version) in changes) {
                    val current = dao.entry(before.id)
                    require(current?.updatedAt == version) { "A task has changed since the move." }
                    dao.upsertEntry(before.copy(favorite = current.favorite,
                        updatedAt = maxOf(System.currentTimeMillis(), version + 1)))
                }
            }
        })
    }

    suspend fun saveSuggestions(dumpId: String, proposals: List<Proposal>): Boolean = database.withTransaction {
        val source = release.dump(dumpId) ?: return@withTransaction false
        if (source.status == DumpStatus.ARCHIVED || release.draftsFor(dumpId).isNotEmpty()) {
            return@withTransaction false
        }
        val drafts = proposals.map { Draft(dumpId = dumpId, original = it.validated().json().toString()) }
        if (drafts.isNotEmpty()) release.insertDrafts(drafts)
        drafts.isNotEmpty()
    }

    suspend fun saveClaudeDrafts(dumpId: String, suggestions: List<Proposal>) = database.withTransaction {
        val source = release.dump(dumpId)
        val run = release.captureRun(dumpId)
        require(source?.status == DumpStatus.UNREVIEWED && !source.transcript.isNullOrBlank() &&
            (run == null || run.status == CaptureStatus.FAILED || run.status == CaptureStatus.ATTENTION) &&
            saveSuggestions(dumpId, suggestions)) { "This capture already has results or is still processing." }
        if (run != null) release.saveCaptureRun(run.copy(status = CaptureStatus.ATTENTION, message = null))
    }

    suspend fun deleteCaptures(ids: Set<String>, voiceOnly: Boolean = false): Set<String> = database.withTransaction {
        val captures = release.dumps()
        val removed = captures.filter { it.id in ids && (!voiceOnly || it.recording != null) }
        removed.forEach { release.deleteDump(it.id) }
        removed.mapNotNull { it.recording }.toSet() - release.recordingNames().toSet()
    }

    suspend fun deleteVoiceCaptures(ids: Set<String>) = deleteCaptures(ids, voiceOnly = true)

    suspend fun addDraft(dumpId: String, proposal: Proposal) = database.withTransaction {
        val source = requireNotNull(release.dump(dumpId))
        require(source.status != DumpStatus.ARCHIVED)
        release.insertDrafts(listOf(Draft(dumpId = dumpId, original = proposal.validated().json().toString())))
        release.dumpStatus(dumpId, DumpStatus.UNREVIEWED)
    }

    suspend fun applyAssistantProposal(proposal: Proposal): String = database.withTransaction {
        requireNotNull(applyProposal(proposal.validated(requireSchedule = proposal.kind == DraftKind.EVENT)).targetId)
    }

    suspend fun readWorkspaceItem(id: String): Proposal? = database.withTransaction {
        dao.taggedEntry(id)?.asProposal() ?: release.event(id)?.asProposal(release.eventTagIds(id).toSet())
    }

    private fun sameTaskTime(existing: Proposal, proposed: Proposal): Boolean =
        existing.startMinute == proposed.startMinute || proposed.kind == DraftKind.TASK &&
            "time" in proposed.inferredSchedule.orEmpty().split(',') && "time" in existing.inferredSchedule.orEmpty().split(',')

    suspend fun applyAssistantChange(proposal: Proposal): Pair<Proposal, WorkspaceUndo?> = database.withTransaction {
        val target = proposal.targetId
        val oldEntry = target?.let { dao.entry(it) }
        val oldEvent = target?.let { release.event(it) }
        val oldTags = when {
            oldEntry != null -> requireNotNull(dao.taggedEntry(target)).tags.map { it.id }
            oldEvent != null -> release.eventTagIds(target)
            else -> emptyList()
        }
        val validated = if (target == null) proposal.validatedForAutomaticCreation()
            else proposal.validated(requireSchedule = true)
        if (target == null) {
            val duplicate = snapshot().proposals().firstOrNull {
                it.title.equals(validated.title, ignoreCase = true) &&
                    sameTaskTime(it, validated) &&
                    it.copy(targetId = null, targetVersion = null, title = validated.title,
                        startMinute = validated.startMinute, inferredSchedule = null) ==
                    validated.copy(related = emptySet(), uncertainty = "", inferredSchedule = null)
            }
            if (duplicate != null) return@withTransaction duplicate to null
        }
        val saved = applyProposal(validated)
        val id = requireNotNull(saved.targetId)
        val savedVersion = requireNotNull(saved.targetVersion)
        saved to WorkspaceUndo(if (target == null) "Item added" else "Item updated") {
            database.withTransaction {
                val currentEvent = release.event(id)
                val currentEntry = dao.entry(id)
                require((currentEvent?.updatedAt ?: currentEntry?.updatedAt) == savedVersion) {
                    "This item changed after the assistant action."
                }
                when {
                    oldEntry != null -> {
                        dao.upsertEntry(oldEntry.copy(favorite = requireNotNull(currentEntry).favorite))
                        dao.clearEntryTags(id)
                        dao.insertEntryTags(dao.tagIds().filter { it in oldTags }.map { EntryTag(id, it) })
                    }
                    oldEvent != null -> {
                        release.saveEvent(oldEvent.copy(favorite = requireNotNull(currentEvent).favorite))
                        release.clearEventTags(id)
                        release.insertEventTags(dao.tagIds().filter { it in oldTags }.map { EventTag(id, it) })
                    }
                    else -> checkNotNull(deleteWithUndo(id)) { "The item is no longer available." }
                }
            }
        }
    }

    fun groupAssistantUndo(actions: List<WorkspaceUndo>): WorkspaceUndo = WorkspaceUndo("Assistant actions") {
        database.withTransaction { actions.asReversed().forEach { it.run() } }
    }

    suspend fun organizeItems(items: Map<String, DraftKind>, destination: String?, delete: Boolean): WorkspaceUndo? =
        database.withTransaction {
            require(items.isNotEmpty())
            val actions = items.mapNotNull { (id, kind) ->
                if (delete) requireNotNull(deleteWithUndo(id, TrashKind.valueOf(kind.name))) {
                    "An item is no longer in your workspace."
                } else moveWithUndo(id, kind, destination)
            }
            if (actions.isEmpty()) null else WorkspaceUndo(
                if (delete) "Moved ${actions.size} to Recently Deleted" else "Moved ${actions.size} items"
            ) {
                database.withTransaction { actions.asReversed().forEach { it.run() } }
            }
        }

    suspend fun moveWorkspaceItem(id: String, kind: DraftKind, collectionId: String?, expectedVersion: Long? = null) =
        database.withTransaction {
            require(collectionId == null || hasCollection(collectionId)) { "The destination collection is unavailable." }
            if (kind == DraftKind.EVENT) {
                val event = requireNotNull(release.event(id)) { "The event no longer exists." }
                require(expectedVersion == null || event.updatedAt == expectedVersion) { "The event changed. Search again." }
                release.saveEvent(event.copy(collectionId = collectionId, updatedAt = System.currentTimeMillis()))
                check(release.event(id)?.let { it.collectionId == collectionId } == true)
            } else {
                val entry = requireNotNull(dao.entry(id)) { "The item no longer exists." }
                require(entry.kind.name == kind.name && (expectedVersion == null || entry.updatedAt == expectedVersion)) {
                    "The item changed. Search again."
                }
                dao.upsertEntry(entry.copy(collectionId = collectionId, updatedAt = System.currentTimeMillis()))
                check(dao.entry(id)?.let { it.collectionId == collectionId } == true)
            }
        }

    suspend fun moveWithUndo(id: String, kind: DraftKind, destination: String?, expectedVersion: Long? = null):
        WorkspaceUndo? = database.withTransaction {
        val previous = if (kind == DraftKind.EVENT) {
            requireNotNull(release.event(id)) { "The event no longer exists." }.collectionId
        } else requireNotNull(dao.entry(id)) { "The item no longer exists." }.collectionId
        if (previous == destination) return@withTransaction null
        moveWorkspaceItem(id, kind, destination, expectedVersion)
        WorkspaceUndo("Item moved") {
            database.withTransaction {
                val current = if (kind == DraftKind.EVENT) requireNotNull(release.event(id)).collectionId
                    else requireNotNull(dao.entry(id)).collectionId
                require(current == destination) { "The item was moved again." }
                moveWorkspaceItem(id, kind, previous)
            }
        }
    }

    suspend fun moveFolderWithUndo(id: String, destination: String?): WorkspaceUndo? = database.withTransaction {
        val previous = requireNotNull(dao.collections().firstOrNull { it.id == id })
        if (previous.parentId == destination) return@withTransaction null
        saveCollection(previous.copy(parentId = destination))
        WorkspaceUndo("Collection moved") {
            database.withTransaction {
                val current = requireNotNull(dao.collections().firstOrNull { it.id == id })
                require(current.parentId == destination) { "The collection was moved again." }
                saveCollection(current.copy(parentId = previous.parentId))
            }
        }
    }

    suspend fun changeAssistantWithUndo(target: Proposal, complete: Boolean?): WorkspaceUndo? = database.withTransaction {
        val id = requireNotNull(target.targetId)
        val entry = dao.entry(id)
        val updated = if (target.kind == DraftKind.EVENT) release.event(id)?.updatedAt else entry?.updatedAt
        require(updated != null && updated == target.targetVersion) { "The item changed. Search again." }
        if (complete == null) deleteWithUndo(id, TrashKind.valueOf(target.kind.name))
        else {
            require(target.kind == DraftKind.TASK && entry?.kind == EntryKind.TASK) { "Only tasks can be completed." }
            completeWithUndo(id, complete, entry.deadline)
        }
    }

    suspend fun changeAssistantItem(target: Proposal, complete: Boolean?): Boolean = database.withTransaction {
        val id = requireNotNull(target.targetId)
        val entry = dao.entry(id)
        val updated = if (target.kind == DraftKind.EVENT) release.event(id)?.updatedAt else entry?.updatedAt
        require(updated != null && updated == target.targetVersion) { "The item changed. Search again." }
        if (complete == null) {
            if (target.kind == DraftKind.EVENT) deleteEvent(id) else deleteEntry(id)
        } else {
            require(target.kind == DraftKind.TASK && entry?.kind == EntryKind.TASK) { "Only tasks can be completed." }
            setCompleted(id, complete)
        }
    }

    suspend fun editDraft(id: String, proposal: Proposal, status: DraftStatus? = null) = database.withTransaction {
        val existing = requireNotNull(release.draft(id))
        require(existing.status == DraftStatus.PENDING || existing.status == DraftStatus.DEFERRED)
        require(status != DraftStatus.APPROVED)
        release.saveDraft(existing.copy(proposal = proposal.validated().json().toString(),
            status = status ?: existing.status))
        refreshDumpStatus(existing.dumpId)
    }

    suspend fun approveDrafts(ids: Set<String>, quick: Boolean = false): Map<String, String> = database.withTransaction {
        require(ids.isNotEmpty())
        ids.associateWith { id ->
            val draft = requireNotNull(release.draft(id))
            require(draft.status == DraftStatus.PENDING || draft.status == DraftStatus.DEFERRED)
            require(release.dump(draft.dumpId)?.status != DumpStatus.ARCHIVED)
            val proposal = Proposal.read(JSONObject(draft.proposal)).validated(requireSchedule = true)
            if (quick) require(draft.status == DraftStatus.PENDING && proposal.targetId == null &&
                proposal.uncertainty.isBlank()) { "Review this draft before approving." }
            val saved = applyProposal(proposal)
            val approvedId = requireNotNull(saved.targetId)
            val run = release.captureRun(draft.dumpId) ?: CaptureRun(draft.dumpId, CaptureStatus.ATTENTION)
            val results = JSONArray(run.results).put(saved.captureReceipt().put("draft", draft.id))
            release.saveCaptureRun(run.copy(results = results.toString()))
            release.saveDraft(draft.copy(status = DraftStatus.APPROVED))
            refreshDumpStatus(draft.dumpId)
            approvedId
        }
    }

    suspend fun approveEditedDraft(id: String, proposal: Proposal) = database.withTransaction {
        editDraft(id, proposal)
        approveDrafts(setOf(id)).getValue(id)
    }

    suspend fun reopenDump(id: String) = database.withTransaction {
        release.dumpStatus(id, DumpStatus.UNREVIEWED)
        if (release.draftsFor(id).isNotEmpty()) refreshDumpStatus(id)
    }

    private suspend fun applyProposal(proposal: Proposal): Proposal {
        val p = proposal.flexibleSchedule?.takeIf { it.automatic && proposal.targetId == null }?.let { window ->
            val workspace = snapshot(includeScheduling = true)
            val availability = CalendarAvailability(workspace.entries.map { it.entry }, workspace.events,
                workspace.recurrenceSkips.groupBy({ it.seriesId }, { it.day }).mapValues { it.value.toSet() })
            val slot = requireNotNull(availability.find(window, ZonedDateTime.now(), period = proposal.timeOfDay)) {
                "The requested window has no free time. Review the draft to choose a time."
            }
            proposal.copy(startDay = slot.day, startMinute = slot.minute)
        } ?: proposal
        require(p.collectionId == null || dao.collections().any { it.id == p.collectionId }) {
            "The chosen collection is no longer available."
        }
        require(dao.tagIds().containsAll(p.tags)) { "A chosen tag is no longer available." }
        val id = p.targetId ?: newId()
        val stale = "The source item changed. Review the latest version before approving."
        if (p.kind == DraftKind.EVENT) {
            if (p.targetId != null) require(release.event(id)?.updatedAt == p.targetVersion) { stale }
            saveEvent(p.toEvent(id), p.tags)
        } else {
            val kind = EntryKind.valueOf(p.kind.name)
            val original = dao.entry(id)
            if (p.targetId != null) require(original?.updatedAt == p.targetVersion && original?.kind == kind) { stale }
            saveEntry(EntryEntity(
                id = id, kind = kind, title = p.title, body = p.body, collectionId = p.collectionId,
                deadline = p.startDay.takeIf { kind == EntryKind.TASK }, priority = p.priority,
                completed = original?.completed ?: false,
                flexibleSchedule = p.flexibleSchedule?.json()?.toString(),
                createdAt = original?.createdAt ?: System.currentTimeMillis(),
                dueMinute = p.startMinute.takeIf { kind == EntryKind.TASK },
                timeOfDay = p.timeOfDay, hardDeadline = p.hardDeadline,
                reminder = p.reminder.takeIf { kind == EntryKind.TASK }, repeat = p.repeat,
                durationMinutes = p.durationMinutes, recurrenceRule = p.recurrenceRule?.encode(),
                inferredSchedule = p.inferredSchedule
            ), p.tags)
        }
        val saved = requireNotNull(readWorkspaceItem(id)) { "The saved item could not be read." }
        val expected = when (p.kind) {
            DraftKind.EVENT -> p.toEvent(id).asProposal(p.tags)
            DraftKind.NOTE -> Proposal(p.kind, p.title, p.body, p.collectionId, p.tags)
            DraftKind.TASK -> p.copy(related = emptySet(), uncertainty = "")
        }
        check(saved == expected.copy(targetId = id, targetVersion = saved.targetVersion)) {
            "The saved item did not match the requested details."
        }
        return saved
    }

    private fun Proposal.captureReceipt() = JSONObject().put("id", requireNotNull(targetId))
        .put("kind", kind.name).put("title", title).put("version", requireNotNull(targetVersion))
        .put("details", copy(body = "").json())

    private suspend fun refreshDumpStatus(id: String) {
        if (release.dump(id)?.status == DumpStatus.ARCHIVED) return
        val drafts = release.draftsFor(id)
        val open = drafts.any { it.status.requiresReview }
        release.dumpStatus(id, if (open) DumpStatus.UNREVIEWED else DumpStatus.REVIEWED)
        val run = release.captureRun(id) ?: return
        if (drafts.isNotEmpty()) release.saveCaptureRun(run.copy(
            status = if (open) CaptureStatus.ATTENTION else CaptureStatus.ORGANIZED,
            message = null
        ))
    }

    suspend fun mergeBackup(snapshot: WorkspaceSnapshot): Int = database.withTransaction {
        val existing = snapshot(includeDumps = true)
        val collectionIds = existing.collections.map { it.id }.toMutableSet()
        val reservedCollections = trash.allCollectionIds().toSet()
        val pending = snapshot.collections.filterNot { it.id in reservedCollections }.toMutableList()
        var added = 0
        while (pending.isNotEmpty()) {
            val ready = pending.filter { it.parentId == null || it.parentId in collectionIds }
            require(ready.isNotEmpty()) { "The backup contains an invalid folder hierarchy." }
            ready.forEach { dao.upsertCollection(it); collectionIds.add(it.id) }
            added += ready.size
            pending.removeAll(ready.toSet())
        }
        val existingTags = existing.tags.map { it.id }.toSet()
        snapshot.tags.filterNot { it.id in existingTags }.forEach { dao.upsertTag(it); added++ }
        val currentEntries = trash.allEntryIds().toSet()
        snapshot.entries.filterNot { it.entry.id in currentEntries }.forEach { tagged ->
            dao.upsertEntry(tagged.entry.copy(collectionId = tagged.entry.collectionId?.takeIf { it in collectionIds }))
            if (tagged.tags.isNotEmpty()) dao.insertEntryTags(tagged.tags.map { EntryTag(tagged.entry.id, it.id) })
            added++
        }
        val importedSeries = snapshot.entries.map { it.entry.id }.toSet() - currentEntries
        snapshot.recurrenceSkips.filter { it.seriesId in importedSeries }.forEach { dao.putRecurrenceSkip(it) }
        val existingEvents = trash.allEventIds().toSet()
        snapshot.events.filterNot { it.id in existingEvents }.forEach { event ->
            release.saveEvent(event.copy(collectionId = event.collectionId?.takeIf { it in collectionIds }))
            val links = snapshot.eventTags.filter { it.eventId == event.id }
            if (links.isNotEmpty()) release.insertEventTags(links)
            added++
        }
        val existingDumps = release.allCaptureIds().toSet()
        val newDumps = snapshot.dumps.filterNot { it.id in existingDumps }
        newDumps.forEach { release.insertDump(it); added++ }
        val newDumpIds = newDumps.map { it.id }.toSet()
        snapshot.captureRuns.filter { it.dumpId in newDumpIds }.forEach { release.saveCaptureRun(it) }
        val existingDraftIds = release.allDraftIds().toSet()
        val activeDumps = existing.dumps.map { it.id }.toSet() + newDumpIds
        val freshDrafts = snapshot.drafts.filter { it.id !in existingDraftIds && it.dumpId in activeDumps }
        if (freshDrafts.isNotEmpty()) release.insertDrafts(freshDrafts)
        added + freshDrafts.size
    }
}
