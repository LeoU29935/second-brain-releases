package com.leo.secondbrain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leo.secondbrain.ai.CaptureProcessor
import com.leo.secondbrain.ai.WorkspaceToolSession
import com.leo.secondbrain.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class V043WorkspaceTest {
    @Test
    fun approvalResolvesCaptureAndDeletionPreservesTheApprovedItem() = withRepository { repository ->
        val capture = BrainDump(original = "Work on the report and keep the access instructions")
        repository.saveCapture(capture)
        val drafts = CaptureProcessor(repository, {
            listOf(Proposal(DraftKind.TASK, "Work on the report"),
                Proposal(DraftKind.NOTE, "Access instructions", uncertainty = "Which instructions?"))
        }, draftOnly = true)
        assertFalse(drafts.drain())
        val proposals = repository.release.draftsFor(capture.id)
        val ready = proposals.single { Proposal.read(JSONObject(it.proposal)).kind == DraftKind.TASK }
        val ambiguous = proposals.single { it.id != ready.id }
        assertTrue(runCatching { repository.approveDrafts(setOf(ambiguous.id), quick = true) }.isFailure)
        val id = repository.approveDrafts(setOf(ready.id), quick = true).getValue(ready.id)
        assertTrue(runCatching { repository.approveDrafts(setOf(ready.id), quick = true) }.isFailure)
        assertEquals(CaptureStatus.ATTENTION, repository.release.captureRun(capture.id)?.status)
        repository.editDraft(ambiguous.id, Proposal.read(JSONObject(ambiguous.proposal)), DraftStatus.REJECTED)
        assertEquals(CaptureStatus.ORGANIZED, repository.release.captureRun(capture.id)?.status)
        repository.release.dumpStatus(capture.id, DumpStatus.ARCHIVED)
        val undo = requireNotNull(repository.deleteWithUndo(capture.id, TrashKind.CAPTURE))
        assertNull(repository.release.dump(capture.id))
        assertEquals(id, repository.snapshot().entries.single().entry.id)
        undo.run()
        assertEquals(DumpStatus.ARCHIVED, repository.release.dump(capture.id)?.status)
        assertEquals(2, repository.release.draftsFor(capture.id).size)
    }

    @Test
    fun reschedulingMaintainsIdentityAndCompletionIsVisibleToLiveTools() = withRepository { repository ->
        val date = LocalDate.now().plusDays(1)
        val day = date.toEpochDay()
        val window = FlexibleSchedule(day, day, 480, 720, 60)
        val id = repository.applyAssistantProposal(Proposal(DraftKind.TASK, "Apply to internships",
            startDay = day, startMinute = 480, flexibleSchedule = window, reminder = 0))
        repository.saveEvent(Event(title = "Morning meeting", startDay = day, endDay = day,
            startMinute = 480, endMinute = 600, allDay = false), emptySet())
        val now = date.atTime(7, 0).atZone(ZoneId.systemDefault())
        repository.reconsiderFlexibleTasks(now)
        var task = repository.snapshot().entries.single().entry
        assertEquals(id, task.id)
        assertEquals(600, task.dueMinute)
        assertNotNull(task.scheduleChange)
        repository.undoScheduleMove(id, requireNotNull(task.scheduleChange))
        repository.reconsiderFlexibleTasks(now)
        task = repository.snapshot().entries.single().entry
        assertEquals(480, task.dueMinute)
        assertNull(task.scheduleChange)
        repository.toggleFavorite(id, TrashKind.TASK)
        val session = WorkspaceToolSession(repository, true)
        val arguments = JSONObject().put("fromDate", date.toString()).put("throughDate", date.toString())
            .put("completed", false)
        val schedule = session.invoke("list_schedule", arguments, "").json.getJSONArray("results")
        assertEquals(id, schedule.getJSONObject(0).getString("targetId"))
        assertFalse(schedule.getJSONObject(0).getBoolean("completed"))
        val spoken = "Mark apply to internships as finished"
        val action = JSONObject().put("operation", "COMPLETE").put("targetId", id).put("instructionEvidence", spoken)
        assertTrue(session.invoke("apply_workspace_change", action, spoken).json.getBoolean("applied"))
        val found = session.invoke("search_workspace", JSONObject().put("query", "internships"), "").json
        assertTrue(found.getJSONArray("items").getJSONObject(0).getBoolean("completed"))
        repository.reconsiderFlexibleTasks(ZonedDateTime.now().plusDays(3))
        assertTrue(repository.snapshot().entries.single().entry.favorite)
        assertEquals(0, session.invoke("list_schedule", arguments, "").json.getJSONArray("results").length())
    }

    private fun withRepository(block: suspend (BrainRepository) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, BrainDatabase::class.java).build()
        try {
            runBlocking { block(BrainRepository(database)) }
        } finally {
            database.close()
        }
    }
}
