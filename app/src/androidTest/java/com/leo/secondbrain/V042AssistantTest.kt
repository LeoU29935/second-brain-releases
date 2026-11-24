package com.leo.secondbrain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leo.secondbrain.ai.RelevantContext
import com.leo.secondbrain.ai.CaptureProcessor
import com.leo.secondbrain.ai.AiUnavailable
import com.leo.secondbrain.ai.WorkspaceToolSession
import com.leo.secondbrain.ai.objects
import com.leo.secondbrain.ai.readSuggestion
import com.leo.secondbrain.backup.BackupCodec
import com.leo.secondbrain.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class V042AssistantTest {
    @Test
    fun updatesSeveralTaskPropertiesAndReportsTheCommittedRecord() = scenarios(
        "Update the contract review to tomorrow at 3 p.m., make it high priority, and change the description.",
        "Change the contract review. Set it for tomorrow at 3 p.m. and mark it high priority."
    ) { repo, session, spoken ->
        val item = task(repo, "Contract review")
        session.search("contract review")
        val command = change("EDIT", spoken, item.id).fields("start", "priority", "body")
            .put("change", proposal("TASK", item.title, "Bring the signed copy.").put("priority", "HIGH")
                .put("startDate", LocalDate.now().plusDays(1).toString()).put("startTime", "15:00")
                .put("scheduleEvidence", "tomorrow at 3 p.m."))
        val receipt = session.apply(command, spoken)
        assertEquals(item.id, receipt.getString("targetId"))
        val saved = repo.entry(item.id)
        assertEquals("Bring the signed copy.", saved.body)
        assertEquals(Priority.HIGH, saved.priority)
        assertEquals(900, saved.dueMinute)
        assertEquals(LocalDate.now().plusDays(1).toEpochDay(), saved.deadline)
    }

    @Test
    fun confirmsAPendingFolderAndUsesItForTasksAcrossTurns() = scenarios(
        "Make a folder called Workshop" to "Go ahead",
        "Create a collection named Workshop" to "Yes, at the top level"
    ) { repo, session, (request, answer) ->
        val pending = session.applyUnchecked(change("CREATE_COLLECTION", request).put("collectionName", "Workshop")
            .put("needsConfirmation", true), request)
        assertTrue(pending.getBoolean("pending"))
        assertTrue(repo.snapshot().collections.isEmpty())
        val folder = session.apply(change("CONFIRM_PENDING", answer), answer).getString("targetId")
        assertEquals("Workshop", repo.snapshot().collections.single().name)
        val speech = "Create a task to order supplies in Workshop"
        val task = session.apply(change("CREATE", speech).put("change", proposal("TASK", "Order supplies")
            .put("collectionId", folder)), speech).getString("targetId")
        assertEquals(folder, repo.entry(task).collectionId)
    }

    @Test
    fun captureInterpretsExplicitTimesAndAutomaticallyCreatesMultipleItems() = scenarios(
        "Call Sam tomorrow at 10 a.m.; keep the reference code Delta.",
        "Tomorrow at 10 a.m. book a venue; save the booking reference Delta."
    ) { repo, _, source ->
        val capture = BrainDump(original = source)
        repo.saveCapture(capture)
        assertEquals(CaptureStatus.QUEUED, repo.release.captureRun(capture.id)?.status)
        val context = RelevantContext(JSONObject(), emptyMap(), emptySet(), emptySet())
        val tomorrow = LocalDate.now().plusDays(1)
        val task = readSuggestion(proposal("TASK", if ("Sam" in source) "Call Sam" else "Book a venue")
            .put("startDate", tomorrow.toString()).put("startTime", "10:00").put("scheduleEvidence", "tomorrow at 10 a.m.")
            .let { if ("Tomorrow" in source) it.put("scheduleEvidence", "Tomorrow at 10 a.m.") else it },
            context, source, suggestReminder = true)
        val note = readSuggestion(proposal("NOTE", "Reference code", "Delta"), context, source)
        var calls = 0
        val worker = CaptureProcessor(repo, { stored ->
            calls++
            assertEquals(source, stored.original)
            listOf(task, note)
        })
        assertFalse(worker.drain())
        assertEquals(CaptureStatus.ORGANIZED, repo.release.captureRun(capture.id)?.status)
        val result = repo.snapshot(includeDumps = true)
        assertEquals(2, result.entries.size)
        assertEquals(600, result.entries.single { it.entry.kind == EntryKind.TASK }.entry.dueMinute)
        assertEquals(source, result.dumps.single().original)
        assertFalse(worker.drain())
        assertEquals(1, calls)
        assertEquals(2, repo.snapshot().entries.size)
        assertEquals(result.captureRuns, BackupCodec.decode(BackupCodec.encode(result, emptyList())).first.captureRuns)
    }

    @Test
    fun findsDescriptionTextAndPaginatesTheCompleteBody() = scenarios("marine specimen", "quartz sample") { repo, session, query ->
        val id = "specimen"
        val body = "Field report line. ".repeat(550) + query + " 😀"
        repo.saveEntry(EntryEntity(id, EntryKind.NOTE, "Field observations", body), emptySet())
        assertEquals(1, session.search(query).getJSONArray("items").length())
        var offset = 0
        val combined = StringBuilder()
        while (true) {
            val part = session.read(id, offset)
            combined.append(part.getString("body"))
            if (part.isNull("nextBodyOffset")) break
            offset = part.getInt("nextBodyOffset")
        }
        assertEquals(body, combined.toString())
        assertEquals(body, repo.entry(id).body)
    }

    @Test
    fun skipsOneRecurringOccurrenceAndUndoRestoresIt() = scenarios(
        "Skip the stretches occurrence tomorrow", "Exclude tomorrow from stretches"
    ) { repo, session, spoken ->
        val date = LocalDate.now().plusDays(1)
        val entry = task(repo, "Stretches", date.toEpochDay(), Repeat.DAILY)
        session.search("stretches")
        session.apply(change("SKIP_OCCURRENCE", spoken, entry.id).put("occurrenceDate", date.toString()), spoken)
        val after = repo.snapshot(includeScheduling = true)
        assertEquals(listOf(RecurrenceSkip(entry.id, date.toEpochDay())), after.recurrenceSkips)
        assertEquals(date.plusDays(1).toEpochDay(), repo.entry(entry.id).deadline)
        val undo = "Undo that"
        session.apply(change("UNDO", undo), undo)
        assertEquals(date.toEpochDay(), repo.entry(entry.id).deadline)
        assertTrue(repo.snapshot(includeScheduling = true).recurrenceSkips.isEmpty())
    }

    @Test
    fun refusesAmbiguousNamesAndHonorsTheCorrection() = scenarios("Shipping", "Studio") { repo, session, targetName ->
        val left = CollectionEntity(name = targetName)
        val right = CollectionEntity(name = "Shared inbox")
        repo.saveCollection(left)
        assertEquals(1, repo.snapshot().collections.size)
        repo.saveCollection(right)
        assertEquals(2, repo.snapshot().collections.size)
        val preferred = task(repo, "Weekly review", folder = left.id)
        val other = task(repo, "Weekly review", folder = right.id)
        session.search("Weekly review")
        val original = "Change the weekly review title"
        val edit = change("EDIT", original, preferred.id).fields("title").put("change", proposal("TASK", "Studio review"))
        assertFalse(session.applyUnchecked(edit, original).getBoolean("applied"))
        assertEquals("Weekly review", repo.entry(preferred.id).title)
        val corrected = "Actually change the weekly review title in " + targetName
        edit.put("instructionEvidence", corrected)
        assertTrue(session.apply(edit, corrected).getBoolean("applied"))
        assertEquals("Studio review", repo.entry(preferred.id).title)
        assertEquals("Weekly review", repo.entry(other.id).title)
    }

    @Test
    fun restructuresNestedCollectionsAndRejectsCycles() = scenarios("Move Archives into Studio", "Put Archives into Studio") {
            repo, session, spoken ->
        val root = CollectionEntity(name = "Projects")
        val child = CollectionEntity(name = "Archives", parentId = root.id)
        val studio = CollectionEntity(name = "Studio")
        for (folder in listOf(root, child, studio)) {
            repo.saveCollection(folder)
            assertTrue(repo.snapshot().collections.any { it.id == folder.id && it.parentId == folder.parentId })
        }
        session.search("archives studio projects")
        session.apply(change("MOVE_COLLECTION", spoken, child.id).put("collectionId", studio.id), spoken)
        assertEquals(studio.id, repo.snapshot().collections.single { it.id == child.id }.parentId)
        val page = session.invoke("list_collection_contents", JSONObject().put("collectionId", studio.id), "").json
        assertEquals(child.id, page.getJSONArray("results").getJSONObject(0).getString("id"))
        val invalid = "Move Studio into Archives"
        assertFalse(session.applyUnchecked(change("MOVE_COLLECTION", invalid, studio.id).put("collectionId", child.id), invalid)
            .getBoolean("applied"))
        assertNull(repo.snapshot().collections.single { it.id == studio.id }.parentId)
    }

    @Test
    fun schedulesOvernightEventsAndReturnsTheirTimezone() = scenarios(
        "Schedule testing tomorrow at 11:30 p.m. for 90 minutes",
        "Create a testing event tomorrow at 23:30 lasting 90 minutes"
    ) { repo, session, spoken ->
        val day = LocalDate.now().plusDays(1)
        val created = session.apply(change("CREATE", spoken).put("change", proposal("EVENT", "Testing", "Keep the timestamp.")
            .put("scheduleEvidence", spoken).put("startDate", day.toString()).put("startTime", "23:30")), spoken)
        val event = repo.snapshot().events.single()
        assertEquals(created.getString("targetId"), event.id)
        assertEquals(day.toEpochDay(), event.startDay)
        assertEquals(day.plusDays(1).toEpochDay(), event.endDay)
        assertEquals(60, event.endMinute)
        assertEquals("Keep the timestamp.", event.body)
        assertEquals(ZoneId.systemDefault().id, session.read(event.id).getString("zone"))
    }

    @Test
    fun appendsAndEditsUniquePassagesWithoutDiscardingTheRestOfANote() = scenarios(
        "Append the parking instructions to Field notes", "Add the parking instructions to Field notes"
    ) { repo, session, spoken ->
        val original = "Entrance: east\nDo not move the equipment.\n" + "Preserve this line.\n".repeat(380).trimEnd()
        repo.saveEntry(EntryEntity("field", EntryKind.NOTE, "Field notes", original), emptySet())
        session.search("Field notes")
        session.apply(change("EDIT", spoken, "field").fields("body").put("bodyMode", "append")
            .put("change", proposal("NOTE", "Field notes", "Park in Lot 3.")), spoken)
        assertEquals(original + "\nPark in Lot 3.", repo.entry("field").body)
        val revise = "Replace the parking location to Lot 4"
        session.apply(change("EDIT", revise, "field").fields("body").put("bodyMode", "replaceText")
            .put("bodyMatch", "Lot 3").put("change", proposal("NOTE", "Field notes", "Lot 4")), revise)
        assertEquals(original + "\nPark in Lot 4.", repo.entry("field").body)
    }

    @Test
    fun cancelledAndFailedOperationsLeaveWorkspaceIntactAndUndoReversesCompletion() = scenarios(
        "No, cancel it", "Stop, don't make that change"
    ) { repo, session, cancellation ->
        val entry = task(repo, "Telephone library")
        session.search("telephone library")
        val pending = "Delete telephone library"
        assertTrue(session.applyUnchecked(change("DELETE", pending, entry.id).put("needsConfirmation", true), pending).getBoolean("pending"))
        session.applyUnchecked(change("CANCEL_PENDING", cancellation), cancellation)
        assertFalse(session.applyUnchecked(change("CONFIRM_PENDING", "Go ahead"), "Go ahead").getBoolean("applied"))
        assertFalse(repo.entry(entry.id).completed)
        val complete = "Mark telephone library done"
        session.apply(change("COMPLETE", complete, entry.id), complete)
        assertTrue(repo.entry(entry.id).completed)
        session.apply(change("UNDO", "Undo that"), "Undo that")
        assertFalse(repo.entry(entry.id).completed)
    }

    @Test
    fun duplicateOrUnclearCaptureNeedsAttentionAndRecoverableFailureKeepsTheOriginal() = scenarios(
        "Maybe call the repair shop next week", "I might telephone the repair shop someday"
    ) { repo, _, input ->
        val capture = BrainDump(original = input)
        repo.saveCapture(capture)
        val offline = CaptureProcessor(repo, { throw AiUnavailable("Offline", retryLater = true) })
        assertTrue(offline.drain())
        assertEquals(CaptureStatus.QUEUED, repo.release.captureRun(capture.id)?.status)
        assertFalse(repo.captureFailure(capture.id, "Key needed", retry = false))
        assertEquals(CaptureStatus.FAILED, repo.release.captureRun(capture.id)?.status)
        repo.retryCapture(capture.id)
        repo.organizeCapture(capture.id, listOf(Proposal(DraftKind.TASK, "Call the repair shop", uncertainty = "Which day?")))
        assertEquals(CaptureStatus.ATTENTION, repo.release.captureRun(capture.id)?.status)
        assertTrue(repo.snapshot().entries.isEmpty())
        assertEquals(input, repo.release.dump(capture.id)?.original)
        assertEquals(1, repo.release.draftsFor(capture.id).size)

        val clear = BrainDump(original = "Keep a record")
        repo.saveCapture(clear)
        repo.organizeCapture(clear.id, listOf(Proposal(DraftKind.NOTE, "A record")))
        assertEquals("A record", repo.snapshot().entries.single().entry.title)
        repo.undoCapture(clear.id)
        assertTrue(repo.snapshot().entries.isEmpty())
        assertEquals(CaptureStatus.UNDONE, repo.release.captureRun(clear.id)?.status)
        assertEquals("Keep a record", repo.release.dump(clear.id)?.original)
    }

    private fun proposal(kind: String, title: String, body: String = "") = JSONObject()
        .put("kind", kind).put("title", title).put("body", body).put("priority", "NONE")
    private fun change(operation: String, evidence: String, id: String? = null) =
        JSONObject().put("operation", operation).put("instructionEvidence", evidence).putNullable("targetId", id)
    private fun JSONObject.fields(vararg names: String) = put("changedFields", JSONArray(names.toList()))

    private suspend fun BrainRepository.entry(id: String) = snapshot().entries.single { it.entry.id == id }.entry
    private suspend fun task(repo: BrainRepository, title: String, deadline: Long? = null,
        repeat: Repeat = Repeat.NONE, folder: String? = null): EntryEntity {
        val entry = EntryEntity(kind = EntryKind.TASK, title = title, deadline = deadline, repeat = repeat, collectionId = folder)
        repo.saveEntry(entry, emptySet())
        assertEquals(title, repo.entry(entry.id).title)
        return entry
    }

    private suspend fun WorkspaceToolSession.search(query: String) =
        invoke("search_workspace", JSONObject().put("query", query), query).json
    private suspend fun WorkspaceToolSession.read(id: String, offset: Int = 0) =
        invoke("read_workspace_item", JSONObject().put("itemId", id).put("offset", offset), "").json
    private suspend fun WorkspaceToolSession.applyUnchecked(command: JSONObject, spoken: String) =
        invoke("apply_workspace_change", command, spoken).json
    private suspend fun WorkspaceToolSession.apply(command: JSONObject, spoken: String): JSONObject =
        applyUnchecked(command, spoken).also { assertTrue(it.toString(), it.optBoolean("applied")) }

    private fun <T> scenarios(vararg cases: T, test: suspend (BrainRepository, WorkspaceToolSession, T) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (case in cases) {
            val db = Room.inMemoryDatabaseBuilder(context, BrainDatabase::class.java).build()
            try {
                val repository = BrainRepository(db)
                test(repository, WorkspaceToolSession(repository, true), case)
            } finally {
                db.close()
            }
        }
    }
}
