package com.leo.secondbrain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leo.secondbrain.ai.RelevantContext
import com.leo.secondbrain.ai.WorkspaceActions
import com.leo.secondbrain.ai.WorkspaceConversation
import com.leo.secondbrain.ai.readSuggestion
import com.leo.secondbrain.backup.BackupCodec
import com.leo.secondbrain.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class V041WorkspaceTest {
    @Test
    fun verifiesOrganizerInterpretationsAgainstTheOriginalCapture() {
        val reference = LocalDate.of(2027, 2, 11)
        val capture = "I want to apply to two internships tomorrow morning around 10 a.m."
        val context = RelevantContext(JSONObject(), emptyMap(), setOf("career"), emptySet())
        val task = readSuggestion(suggestion("TASK", capture, capture).put("collectionId", "career"),
            context, capture, reference, suggestReminder = true, singleCaptureItem = true)
        assertEquals("Apply to two internships", task.title)
        assertEquals(reference.plusDays(1).toEpochDay(), task.startDay)
        assertEquals(600, task.startMinute)
        assertEquals(10, task.reminder)
        assertEquals("career", task.collectionId)
        assertEquals("", task.body)

        val mixed = "Call the supplier tomorrow morning. Exchange papers Thursday at 16:00."
        val call = readSuggestion(suggestion("TASK", "Call the supplier", "Use the service number")
            .put("scheduleEvidence", "tomorrow morning"), context, mixed, reference, suggestReminder = true)
        assertEquals(reference.plusDays(1).toEpochDay(), call.startDay)
        assertNull(call.startMinute)
        assertEquals("Use the service number", call.body)

        val appointment = "Review on Friday at 23:30 for 90 minutes"
        val event = readSuggestion(suggestion("EVENT", "Review", "").put("scheduleEvidence", appointment),
            context, appointment, reference, suggestReminder = true, singleCaptureItem = true)
        assertEquals(reference.plusDays(1).toEpochDay(), event.startDay)
        assertEquals(reference.plusDays(2).toEpochDay(), event.endDay)
        assertEquals(60, event.endMinute)
        event.validated(requireSchedule = true)
    }

    @Test
    fun createsAConfirmedFolderAndSupportsAppearanceFollowUps() = runBlocking {
        useRepository { repository ->
            val actions = WorkspaceActions(repository)
            val session = WorkspaceConversation()
            fun context(vararg folders: String) = RelevantContext(JSONObject(), emptyMap(), folders.toSet(), emptySet())
            val spoken = "Can you make me a folder called Workout?"
            val create = JSONObject().put("operation", "CREATE_COLLECTION").put("instructionEvidence", spoken)
                .put("collectionName", "Workout").put("needsConfirmation", true)
            assertTrue(actions.apply(create, context(), spoken, null, session).pending)
            assertTrue(repository.snapshot().collections.isEmpty())
            assertTrue(runCatching {
                actions.apply(JSONObject().put("operation", "CONFIRM_PENDING"), context(), "Tell me what folders I have", null, session)
            }.isFailure)
            val next = actions.apply(JSONObject().put("operation", "CONFIRM_PENDING"), context(),
                "Okay, go ahead and make it.", null, session)
            val id = requireNotNull(next.targetId)
            assertEquals("Workout", repository.snapshot().collections.single().name)
            assertNull(repository.snapshot().collections.single().parentId)
            assertNull(session.pending)
            val change = "Make it blue and give it a dumbbell icon"
            actions.apply(JSONObject().put("operation", "EDIT_COLLECTION").put("instructionEvidence", change)
                .put("collectionColor", "blue").put("collectionIcon", "DUMBBELL").put("targetId", id),
                context(id), change, id, session)
            val updated = repository.snapshot().collections.single()
            assertEquals("DUMBBELL", updated.icon)
            assertEquals(6, updated.color)
        }
    }

    @Test
    fun separatesOccurrencesAndPreservesUndoAndBackupSemantics() = runBlocking {
        useRepository { repository ->
            val start = LocalDate.now().plusDays(2)
            val series = EntryEntity(kind = EntryKind.TASK, title = "Stretch", deadline = start.toEpochDay(),
                repeat = Repeat.DAILY, dueMinute = 540, reminder = 15)
            repository.saveEntry(series, emptySet())
            val separate = start.plusDays(1).toEpochDay()
            repository.separateOccurrence(series.id, separate, "single")
            val old = repository.snapshot(includeDumps = true)
            assertEquals(listOf(RecurrenceSkip(series.id, separate)), old.recurrenceSkips)
            assertEquals(Repeat.NONE, old.entries.single { it.entry.id == "single" }.entry.repeat)
            val encoded = BackupCodec.encode(old, emptyList())
            assertEquals(old.recurrenceSkips, BackupCodec.decode(encoded).first.recurrenceSkips)
            assertTrue(BackupCodec.decode(encoded.apply { remove("recurrenceSkips") }).first.recurrenceSkips.isEmpty())

            val undo = requireNotNull(repository.completeWithUndo(series.id, true, start.toEpochDay()))
            assertEquals(start.plusDays(2).toEpochDay(), repository.snapshot().entries.single { it.entry.id == series.id }.entry.deadline)
            assertNull(repository.completeWithUndo(series.id, true, start.toEpochDay()))
            undo.run()
            assertEquals(start.toEpochDay(), repository.snapshot().entries.single { it.entry.id == series.id }.entry.deadline)

            val folder = CollectionEntity(name = "Wellness")
            repository.saveCollection(folder)
            val move = requireNotNull(repository.moveWithUndo("single", DraftKind.TASK, folder.id))
            assertEquals(folder.id, repository.snapshot().entries.single { it.entry.id == "single" }.entry.collectionId)
            move.run()
            val deletion = requireNotNull(repository.deleteWithUndo("single"))
            assertFalse(repository.snapshot().entries.any { it.entry.id == "single" })
            deletion.run()
            assertNull(repository.snapshot().entries.single { it.entry.id == "single" }.entry.collectionId)
        }
    }

    private fun suggestion(kind: String, title: String, body: String) = JSONObject()
        .put("kind", kind).put("title", title).put("body", body).put("priority", "NONE")

    private suspend fun useRepository(action: suspend (BrainRepository) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, BrainDatabase::class.java).build()
        try {
            action(BrainRepository(database))
        } finally {
            database.close()
        }
    }
}
