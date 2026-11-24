package com.leo.secondbrain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leo.secondbrain.ai.RelevantContext
import com.leo.secondbrain.ai.WorkspaceActions
import com.leo.secondbrain.data.BrainDatabase
import com.leo.secondbrain.data.BrainRepository
import com.leo.secondbrain.data.CollectionEntity
import com.leo.secondbrain.data.DraftKind
import com.leo.secondbrain.data.Proposal
import com.leo.secondbrain.data.TagEntity
import com.leo.secondbrain.data.convertTo
import com.leo.secondbrain.data.putNullable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class V04WorkspaceTest {
    @Test
    fun assistantCreatesMovesCompletesAndSoftDeletesOnlySpokenTargets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BrainDatabase::class.java).build()
        try {
            val repository = BrainRepository(db)
            val actions = WorkspaceActions(repository)
            val folder = CollectionEntity(name = "Research")
            repository.saveCollection(folder)
            fun command(operation: String, quote: String, target: String? = null) = JSONObject()
                .put("operation", operation).put("instructionEvidence", quote).putNullable("targetId", target)
            suspend fun disclosed(): RelevantContext {
                val workspace = repository.snapshot()
                return RelevantContext(JSONObject(), workspace.proposals().associateBy { requireNotNull(it.targetId) },
                    workspace.collections.map { it.id }.toSet(), emptySet())
            }

            val create = "Create a task to read the chapter tomorrow"
            val tomorrow = LocalDate.now().plusDays(1)
            val target = requireNotNull(actions.apply(command("CREATE", create).put("change", JSONObject()
                .put("kind", "TASK").put("title", "Read the chapter")
                .put("body", "Read the chapter\nDiscuss the sources").put("priority", "NONE")
                .put("startDate", tomorrow.toString()).put("scheduleEvidence", "tomorrow")),
                disclosed(), create, null).targetId)
            val entry = repository.snapshot().entries.single().entry
            assertEquals("Discuss the sources", entry.body)
            assertEquals(tomorrow.toEpochDay(), entry.deadline)
            assertTrue(entry.dueMinute != null)
            assertTrue(entry.reminder != null)

            val move = "Move that to Research"
            actions.apply(command("MOVE", move, target).put("collectionId", folder.id), disclosed(), move, target)
            assertEquals(folder.id, repository.snapshot().entries.single().entry.collectionId)
            val finish = "Mark the chapter task done"
            actions.apply(command("COMPLETE", finish, target), disclosed(), finish, target)
            assertTrue(repository.snapshot().entries.single().entry.completed)

            val delete = "Delete the chapter task"
            assertTrue(runCatching {
                actions.apply(command("DELETE", delete, target), disclosed(), "Just show me the schedule", target)
            }.isFailure)
            assertTrue(runCatching {
                actions.apply(command("DELETE", delete, "unknown"), disclosed(), delete, target)
            }.isFailure)
            actions.apply(command("DELETE", delete, target), disclosed(), delete, target)
            assertTrue(repository.snapshot().entries.isEmpty())
            assertEquals(target, repository.recentlyDeleted.first().single().rootId)
            assertTrue(repository.snapshot(includeDumps = true).drafts.isEmpty())
        } finally {
            db.close()
        }
        Unit
    }

    @Test
    fun editorConversionKeepsIdentityTagsAndApplicableSchedule() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BrainDatabase::class.java).build()
        try {
            val repository = BrainRepository(db)
            val folder = CollectionEntity(name = "Travel", icon = "TRAIN")
            val tag = TagEntity(name = "Tickets", color = 14)
            repository.saveCollection(folder)
            repository.saveTag(tag)
            val day = LocalDate.now().toEpochDay()
            val original = Proposal(DraftKind.TASK, "Board the train", body = "Bring the printed ticket",
                collectionId = folder.id, tags = setOf(tag.id), startDay = day, startMinute = 1420, reminder = 30)
            repository.saveEditorItem("my-item", original)
            repository.saveEditorItem("my-item", original.convertTo(DraftKind.EVENT, day))
            val workspace = repository.snapshot()
            assertTrue(workspace.entries.isEmpty())
            val event = workspace.events.single()
            assertEquals("my-item", event.id)
            assertEquals(day + 1, event.endDay)
            assertEquals(40, event.endMinute)
            assertEquals(tag.id, workspace.eventTags.single().tagId)

            repository.saveEditorItem(event.id, original.convertTo(DraftKind.NOTE, day))
            val restored = repository.snapshot()
            assertTrue(restored.events.isEmpty())
            assertFalse(restored.entries.single().entry.completed)
            assertEquals("Bring the printed ticket", restored.entries.single().entry.body)
            assertEquals(folder.id, restored.entries.single().entry.collectionId)
            assertEquals(tag.id, restored.entries.single().tags.single().id)
        } finally {
            db.close()
        }
        Unit
    }
}
