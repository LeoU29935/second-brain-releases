package com.leo.secondbrain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leo.secondbrain.ai.RelevantContext
import com.leo.secondbrain.ai.WorkspaceToolSession
import com.leo.secondbrain.ai.readSuggestion
import com.leo.secondbrain.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class V046AssistantTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val empty = RelevantContext(JSONObject(), emptyMap(), emptySet(), emptySet())

    @Test
    fun ordinarySpokenIntentCommitsImmediatelyAndUndoReversesTheGroup() = withDatabase { repo ->
        val session = WorkspaceToolSession(repo, true)
        val source = "I want to work on two internship applications sometime tomorrow morning."
        val expectedDay = LocalDate.now().plusDays(1).toEpochDay()
        val added = session.invoke("apply_workspace_change", action("CREATE", source)
            .put("needsConfirmation", true).put("change", proposal("Work on two internship applications")
                .put("body", "").put("scheduleEvidence", "sometime tomorrow")), source).json
        assertTrue(added.toString(), added.getBoolean("applied"))
        assertFalse(added.getBoolean("pending"))
        val id = added.getString("targetId")
        val stored = repo.snapshot().entries.single().entry
        val message = added.getString("message")
        assertTrue(message, message.startsWith("Added Work on two internship applications · Tomorrow at "))
        assertTrue(message, com.leo.secondbrain.data.minuteLabel(requireNotNull(stored.dueMinute)) in message)
        assertTrue(message, "Reminder ${stored.reminder} min before" in message)
        assertEquals(expectedDay, stored.deadline)
        assertEquals(TimeOfDay.MORNING, stored.timeOfDay)
        assertTrue(requireNotNull(stored.dueMinute) in 480 until 720)
        assertNotNull(stored.reminder)
        assertNull(stored.hardDeadline)

        val change = "Move it to Friday"
        val edited = session.invoke("apply_workspace_change", action("MOVE", change), change).json
        assertTrue(edited.toString(), edited.getBoolean("applied"))
        assertEquals(id, edited.getString("targetId"))
        assertEquals(TimeOfDay.MORNING, repo.snapshot().entries.single().entry.timeOfDay)
        val undo = "Undo that"
        assertTrue(session.invoke("apply_workspace_change", action("UNDO", undo), undo).json.getBoolean("applied"))
        assertEquals(expectedDay, repo.snapshot().entries.single().entry.deadline)

        val both = "Create a note called Practice ideas and an event called Dinner tomorrow evening."
        session.beginTurn()
        for (item in listOf(proposal("Practice ideas", DraftKind.NOTE), proposal("Dinner", DraftKind.EVENT)
            .put("scheduleEvidence", "tomorrow evening"))) {
            val result = session.invoke("apply_workspace_change", action("CREATE", both).put("change", item),
                both, groupWithTurn = true).json
            assertTrue(result.toString(), result.getBoolean("applied"))
        }
        assertEquals(2, repo.snapshot().entries.size)
        assertEquals(TimeOfDay.EVENING, repo.snapshot().events.single().timeOfDay)
        val undone = session.invoke("apply_workspace_change", action("UNDO", undo), undo).json
        assertTrue(undone.toString(), undone.getBoolean("applied"))
        assertEquals(id, repo.snapshot().entries.single().entry.id)
        assertTrue(repo.snapshot().events.isEmpty())

        val appointment = "Schedule a call tomorrow at 10 AM"
        val exact = session.invoke("apply_workspace_change", action("CREATE", appointment)
            .put("change", proposal("Call", DraftKind.EVENT)), appointment).json
        assertTrue(exact.toString(), exact.getBoolean("applied"))
        val event = repo.snapshot().events.single()
        assertEquals(600, event.startMinute)
        assertNull(event.endMinute)
        assertEquals(event.startDay, event.endDay)
    }

    @Test
    fun incompleteModelTimingSurvivesDraftQuickApprovalStandardApprovalAndLiveTools() = withDatabase { repo ->
        val now = LocalDate.now()
        val phrase = "Practice German tomorrow sometime towards the evening."
        val model = proposal(phrase).put("scheduleEvidence", "tomorrow")
            .put("uncertainty", "Exact time is unspecified.")
        for (quick in listOf(true, false)) {
            val capture = BrainDump(original = phrase)
            repo.saveCapture(capture)
            val extracted = readSuggestion(model, empty, phrase, now, suggestReminder = true, singleCaptureItem = true)
            assertEquals("Practice German", extracted.title)
            assertEquals(TimeOfDay.EVENING, extracted.timeOfDay)
            assertTrue(extracted.uncertainty, extracted.uncertainty.isBlank())
            repo.organizeCapture(capture.id, listOf(extracted), draftOnly = true)
            val draft = repo.release.draftsFor(capture.id).single()
            val payload = Proposal.read(JSONObject(draft.proposal))
            assertEquals(now.plusDays(1).toEpochDay(), payload.startDay)
            assertEquals(TimeOfDay.EVENING, payload.timeOfDay)
            val id = repo.approveDrafts(setOf(draft.id), quick = quick).getValue(draft.id)
            val item = repo.snapshot().entries.single { it.entry.id == id }.entry
            assertEquals(now.plusDays(1).toEpochDay(), item.deadline)
            assertEquals(TimeOfDay.EVENING, item.timeOfDay)
            assertTrue(requireNotNull(item.dueMinute) in 1020 until 1260)
            assertNotNull(item.reminder)
            assertNull(item.hardDeadline)
            repo.deleteWithUndo(id)
        }
        val session = WorkspaceToolSession(repo, true)
        val result = session.invoke("apply_workspace_change", action("CREATE", phrase)
            .put("change", proposal("Practice German").put("scheduleEvidence", "towards the evening")), phrase).json
        assertTrue(result.toString(), result.getBoolean("applied"))
        val task = repo.snapshot().entries.single().entry
        assertEquals(now.plusDays(1).toEpochDay(), task.deadline)
        assertEquals(TimeOfDay.EVENING, task.timeOfDay)
        assertTrue(requireNotNull(task.dueMinute) in 1020 until 1260)
        assertNotNull(task.reminder)
        val schedule = session.invoke("list_schedule", JSONObject().put("fromDate", now.plusDays(1).toString())
            .put("throughDate", now.plusDays(1).toString()), phrase).json.getJSONArray("results")
        assertEquals("EVENING", schedule.getJSONObject(0).getString("timeOfDay"))
    }

    @Test
    fun deadlineIsIndependentAndMemoryUsesTheCurrentWorkspaceAfterReopening() = runBlocking {
        val name = "v046-history-${newId()}.db"
        val today = LocalDate.now()
        val phrase = "Create a task to submit the report by Friday."
        var id: String? = null
        try {
            val first = Room.databaseBuilder(context, BrainDatabase::class.java, name).build()
            try {
                val repo = BrainRepository(first)
                repo.assistantMemory.remember("first", "user", "My project is the internship report.")
                val session = WorkspaceToolSession(repo, true, "first")
                val result = session.invoke("apply_workspace_change", action("CREATE", phrase)
                    .put("change", proposal("Submit the report").put("scheduleEvidence", "by Friday")), phrase).json
                assertTrue(result.toString(), result.getBoolean("applied"))
                id = result.getString("targetId")
                val task = repo.snapshot().entries.single().entry
                assertTrue(requireNotNull(task.deadline) <= requireNotNull(task.hardDeadline))
                assertNotNull(task.dueMinute)
                assertNotNull(task.reminder)
                assertEquals(today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.FRIDAY)).toEpochDay(),
                    task.hardDeadline)
            } finally { first.close() }
            val reopened = Room.databaseBuilder(context, BrainDatabase::class.java, name).build()
            try {
                val repo = BrainRepository(reopened)
                val session = WorkspaceToolSession(repo, true, "second")
                val summary = requireNotNull(session.initializeMemory()).toString()
                assertTrue(summary.contains("internship report"))
                assertTrue(summary.contains("Committed action at the time"))
                val task = repo.snapshot().entries.single().entry
                repo.saveEntry(task.copy(title = "Revised report"), emptySet())
                val memory = session.invoke("recall_context", JSONObject().put("query", "report"), "What about the report?")
                assertTrue(memory.json.toString().contains("Revised report"))
                repo.deleteWithUndo(requireNotNull(id))
                val removed = session.invoke("recall_context", JSONObject().put("query", "report"), "What about the report?")
                val history = removed.json.getJSONArray("history")
                assertTrue((0 until history.length()).any { history.getJSONObject(it).optBoolean("targetUnavailable") })
                repo.assistantMemory.clear()
                assertTrue(repo.assistantMemory.history.first().isEmpty())
            } finally { reopened.close() }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    private fun proposal(title: String, kind: DraftKind = DraftKind.TASK) =
        JSONObject().put("kind", kind.name).put("title", title)

    private fun action(operation: String, phrase: String) =
        JSONObject().put("operation", operation).put("instructionEvidence", phrase)

    private fun withDatabase(block: suspend (BrainRepository) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, BrainDatabase::class.java).build()
        try { block(BrainRepository(db)) } finally { db.close() }
    }
}
