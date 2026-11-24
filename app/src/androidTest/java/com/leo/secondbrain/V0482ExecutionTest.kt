package com.leo.secondbrain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leo.secondbrain.ai.CREATE_TASK_TOOL
import com.leo.secondbrain.ai.CaptureProcessor
import com.leo.secondbrain.ai.WorkspaceToolSession
import com.leo.secondbrain.ai.objects
import com.leo.secondbrain.capture.RecordingStore
import com.leo.secondbrain.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class V0482ExecutionTest {
    @Test
    fun structuredLiveCreationVerifiesFieldsAndFollowupsSurviveReconnect() = withRepository { repository ->
        val music = CollectionEntity(name = "Music")
        val practice = CollectionEntity(name = "Practice", parentId = music.id)
        repository.saveCollection(music)
        repository.saveCollection(practice)
        val session = WorkspaceToolSession(repository, allowed = true)
        val today = LocalDate.now()
        val planned = today.plusDays(2)
        val deadline = today.plusDays(9)
        val request = "Create a high priority task to rehearse in Practice on $planned at 18:30 for 45 minutes, " +
            "due $deadline, remind me half an hour before."
        val change = JSONObject().put("title", "Rehearse").put("body", "Bring the lead sheets.")
            .put("collectionId", practice.id).put("priority", "MEDIUM")
            .put("startDate", planned.toString()).put("startTime", "18:30")
        val args = JSONObject().put("instructionEvidence", request).put("change", change)
        val created = session.invoke(CREATE_TASK_TOOL, args, request, groupWithTurn = true).json
        assertTrue(created.toString(), created.getBoolean("applied"))
        val id = created.getString("targetId")
        val task = repository.snapshot().proposals().single()
        assertEquals(id, task.targetId)
        assertEquals("Rehearse", task.title)
        assertEquals("Bring the lead sheets.", task.body)
        assertEquals(practice.id, task.collectionId)
        assertEquals(Priority.HIGH, task.priority)
        assertEquals(planned.toEpochDay(), task.startDay)
        assertEquals(1110, task.startMinute)
        assertEquals(30, task.reminder)
        assertEquals(deadline.toEpochDay(), task.hardDeadline)
        assertEquals(45, task.durationMinutes)
        assertEquals(Repeat.NONE, task.repeat)
        val reminderTask = repository.reminderSnapshot().first.single()
        assertEquals(task.startMinute, reminderTask.dueMinute)
        assertEquals(task.reminder, reminderTask.reminder)
        assertEquals(task.targetVersion, created.getJSONObject("currentItem").getLong("targetVersion"))
        val repeated = session.invoke(CREATE_TASK_TOOL,
            JSONObject().put("change", change).put("instructionEvidence", request), request, groupWithTurn = true).json
        assertFalse(repeated.getBoolean("changed"))
        assertEquals(id, repeated.getString("targetId"))
        assertEquals(1, repository.snapshot().entries.size)

        val resumed = WorkspaceToolSession(repository, allowed = true)
        assertNotNull(resumed.initializeMemory())
        val recurrence = "Make that recurring every two weeks."
        val repeat = resumed.invoke("apply_workspace_change", JSONObject().put("operation", "EDIT")
            .put("instructionEvidence", recurrence).put("changedFields", JSONArray(listOf("repeat"))), recurrence).json
        assertTrue(repeat.toString(), repeat.getBoolean("applied"))
        val move = "Put it in Music instead."
        val moved = resumed.invoke("apply_workspace_change", JSONObject().put("operation", "MOVE")
            .put("instructionEvidence", move).put("collectionId", music.id), move).json
        assertTrue(moved.toString(), moved.getBoolean("applied"))
        val reminder = "Change the reminder to 20 minutes before."
        val changed = resumed.invoke("apply_workspace_change", JSONObject().put("operation", "EDIT")
            .put("instructionEvidence", reminder).put("targetId", id)
            .put("changedFields", JSONArray(listOf("reminder"))), reminder).json
        assertTrue(changed.toString(), changed.getBoolean("applied"))
        val updated = repository.snapshot().proposals().single()
        assertEquals(music.id, updated.collectionId)
        assertEquals(Repeat.WEEKLY, updated.repeat)
        assertEquals(2, updated.recurrenceRule?.interval)
        assertEquals(20, updated.reminder)
        assertEquals(planned.toEpochDay(), updated.startDay)
        assertEquals(deadline.toEpochDay(), updated.hardDeadline)
    }

    @Test
    fun captureCommitsMultipleVerifiedTasksAndKeepsFailedSources() = withRepository { repository ->
        val source = "Tomorrow afternoon practice guitar; tomorrow evening email Alex."
        val capture = BrainDump(original = source, transcript = source, recording = "guitar.m4a")
        repository.saveCapture(capture)
        var attempts = 0
        val processor = CaptureProcessor(repository, {
            attempts++
            listOf(Proposal(DraftKind.TASK, "Practice guitar"), Proposal(DraftKind.TASK, "Email Alex"))
        })
        assertFalse(processor.drain())
        assertFalse(processor.drain())
        assertEquals(1, attempts)
        val run = requireNotNull(repository.release.captureRun(capture.id))
        assertEquals(CaptureStatus.ORGANIZED, run.status)
        val saved = repository.snapshot().proposals().associateBy { it.title }
        assertEquals(TimeOfDay.AFTERNOON, saved.getValue("Practice guitar").timeOfDay)
        assertEquals(TimeOfDay.EVENING, saved.getValue("Email Alex").timeOfDay)
        for (receipt in JSONArray(run.results).objects()) {
            val persisted = saved.getValue(receipt.getString("title"))
            assertEquals(LocalDate.now().plusDays(1).toEpochDay(), persisted.startDay)
            assertEquals(persisted.copy(body = ""), Proposal.read(receipt.getJSONObject("details")))
            assertNotNull(persisted.startMinute)
            assertNotNull(persisted.reminder)
        }

        val failure = BrainDump(original = "Draft the budget.", transcript = "Draft the budget.", recording = "budget.m4a")
        repository.saveCapture(failure)
        val broken = CaptureProcessor(repository, { throw IllegalStateException("Test failure") })
        assertFalse(broken.drain())
        assertEquals(CaptureStatus.FAILED, repository.release.captureRun(failure.id)?.status)
        assertEquals(failure, repository.release.dump(failure.id))
        assertEquals(2, repository.snapshot().entries.size)
    }

    @Test
    fun savedVoiceMemoRetriesReminderRegistrationWithoutCreatingItemsAgain() = withRepository { repository ->
        val capture = BrainDump(recording = "${newId()}.m4a", transcript = "Tomorrow at 7 pm, remind me to read.")
        repository.saveCapture(capture)
        var interpretations = 0
        val worker = CaptureProcessor(repository, {
            interpretations++
            listOf(Proposal(DraftKind.TASK, "Read"))
        }) {
            val saved = repository.reminderSnapshot().first.single()
            assertEquals(19 * 60, saved.dueMinute)
            assertNotNull(saved.reminder)
            error("Simulated alarm registration failure")
        }
        assertTrue(worker.drain())
        assertEquals(CaptureStatus.VERIFYING, repository.release.captureRun(capture.id)?.status)
        assertEquals(capture.transcript, repository.release.dump(capture.id)?.transcript)
        repository.retryCapture(capture.id)
        val recovered = CaptureProcessor(repository, { error("Must reuse the verified workspace items") }) {}
        assertFalse(recovered.drain())
        assertEquals(CaptureStatus.ORGANIZED, repository.release.captureRun(capture.id)?.status)
        assertEquals(1, interpretations)
        assertEquals(1, repository.snapshot().entries.size)
    }

    @Test
    fun recordingMarkerSurvivesReopeningAndKeepsOriginalCaptureTime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RecordingStore(context)
        val recording = store.file("${newId()}.m4a")
        try {
            assertTrue(store.begin(recording))
            val captureTime = store.startedAt(recording)
            recording.writeBytes(ByteArray(512) { 1 })
            check(recording.setLastModified(captureTime + 30_000))
            val restarted = RecordingStore(context)
            assertTrue(recording in restarted.pendingFiles())
            assertEquals(captureTime, restarted.startedAt(recording))
            assertEquals(512L, recording.length())
            restarted.saved(recording)
            assertFalse(recording in RecordingStore(context).pendingFiles())
        } finally {
            store.saved(recording)
            recording.delete()
        }
    }

    @Test
    fun richBodyRoundTripsAndEditPreservesSpans() = withRepository { repository ->
        val plain = "A *literal* paragraph"
        assertEquals(plain, RichBody.read(plain).encode())
        val body = RichBody("First item\n☐ Follow up").format(0, 5, BodyFormat.BOLD, setOf(BodyFormat.BOLD))
        val edited = body.replace(0, 0, "A ")
        val id = repository.applyAssistantProposal(Proposal(DraftKind.NOTE, "Notes", body = edited.encode()))
        val saved = repository.snapshot().proposals().single()
        assertEquals(id, saved.targetId)
        assertEquals(edited, RichBody.read(saved.body))
        assertEquals("A First item\n☐ Follow up", saved.body.plainBody())
    }

    private fun withRepository(block: suspend (BrainRepository) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, BrainDatabase::class.java).build()
        try {
            block(BrainRepository(database))
        } finally {
            database.close()
        }
    }
}
