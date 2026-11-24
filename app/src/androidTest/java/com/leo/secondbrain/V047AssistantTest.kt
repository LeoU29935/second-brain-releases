package com.leo.secondbrain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leo.secondbrain.ai.CREATE_TASK_TOOL
import com.leo.secondbrain.ai.SpokenWorkspaceIntent
import com.leo.secondbrain.ai.WorkspaceToolSession
import com.leo.secondbrain.ai.classifySpokenIntent
import com.leo.secondbrain.data.BrainDatabase
import com.leo.secondbrain.data.BrainRepository
import com.leo.secondbrain.data.DraftKind
import com.leo.secondbrain.data.TimeOfDay
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V047AssistantTest {
    private val request = "Tomorrow morning I wanna apply to two internships."

    @Test
    fun liveSpokenRequestPersistsOneTaskAndConfirmsTheSavedSchedule() = withWorkspace { repo, session ->
        session.beginTurn()
        val receipt = requireNotNull(session.routeSpokenTask(request)).json
        assertTrue(receipt.toString(), receipt.getBoolean("applied"))
        assertFalse(receipt.getBoolean("pending"))
        val stored = repo.snapshot().entries.single().entry
        val message = receipt.getString("message")
        assertTrue(message, message.startsWith("Added Apply to two internships · Tomorrow at "))
        assertTrue(message, com.leo.secondbrain.data.minuteLabel(requireNotNull(stored.dueMinute)) in message)
        assertTrue(message, "Reminder ${stored.reminder} min before" in message)
        assertTrue(message, "Normal priority" in message)
        assertEquals(receipt.getString("targetId"), stored.id)
        assertEquals("Apply to two internships", stored.title)
        assertEquals(DraftKind.TASK, repo.snapshot().proposals().single().kind)
        assertEquals(LocalDate.now().plusDays(1).toEpochDay(), stored.deadline)
        assertEquals(TimeOfDay.MORNING, stored.timeOfDay)
        assertTrue(requireNotNull(stored.dueMinute) in 480 until 720)
        assertNotNull(stored.reminder)
        assertNull(stored.hardDeadline)

        val retry = session.invoke(CREATE_TASK_TOOL, JSONObject(), request).json
        assertTrue(retry.toString(), retry.getBoolean("applied"))
        assertFalse(retry.getBoolean("changed"))
        assertEquals(stored.id, repo.snapshot().entries.single().entry.id)
    }

    @Test
    fun anErroneousEditCallUsesTheSpokenCreationIntent() = withWorkspace { repo, session ->
        val existing = session.invoke(CREATE_TASK_TOOL, JSONObject(), "I need to call Dad tomorrow.").json
        assertTrue(existing.toString(), existing.getBoolean("applied"))
        val edited = session.invoke("apply_workspace_change", JSONObject().put("operation", "EDIT")
            .put("targetId", existing.getString("targetId")), request).json
        assertTrue(edited.toString(), edited.getBoolean("applied"))
        val entries = repo.snapshot().entries.map { it.entry }.associateBy { it.id }
        assertEquals(2, entries.size)
        assertEquals("Call Dad", entries.getValue(existing.getString("targetId")).title)
        assertEquals("Apply to two internships", entries.getValue(edited.getString("targetId")).title)
    }

    @Test
    fun intentsAreSeparatedAndOnlyExplicitTaskIntentCreates() = withWorkspace { repo, session ->
        val otherIntents = mapOf(
            "I wanna know what's on the schedule tomorrow." to SpokenWorkspaceIntent.QUERY,
            "I need to update my internship task." to SpokenWorkspaceIntent.UPDATE,
            "Delete the internship task." to SpokenWorkspaceIntent.DELETE,
            "Good morning, how are you?" to SpokenWorkspaceIntent.CONVERSATION
        )
        for ((utterance, expected) in otherIntents) {
            assertEquals(expected, classifySpokenIntent(utterance))
            assertNull(session.routeSpokenTask(utterance))
            val rejected = session.invoke(CREATE_TASK_TOOL, JSONObject().put("title", "Internship"), utterance).json
            assertFalse(rejected.toString(), rejected.getBoolean("applied"))
        }
        assertTrue(repo.snapshot().entries.isEmpty())

        for (intent in listOf("wanna", "need to", "should", "have to")) {
            assertEquals(SpokenWorkspaceIntent.CREATE_TASK, classifySpokenIntent("Tomorrow morning I $intent apply to two internships."))
        }
    }

    private fun withWorkspace(block: suspend (BrainRepository, WorkspaceToolSession) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, BrainDatabase::class.java).build()
        try {
            val repo = BrainRepository(database)
            block(repo, WorkspaceToolSession(repo, allowed = true))
        } finally {
            database.close()
        }
    }
}
