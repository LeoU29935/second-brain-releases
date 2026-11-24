package com.leo.secondbrain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leo.secondbrain.data.BrainDatabase
import com.leo.secondbrain.data.BrainRepository
import com.leo.secondbrain.data.CollectionEntity
import com.leo.secondbrain.data.EntryEntity
import com.leo.secondbrain.data.EntryKind
import com.leo.secondbrain.data.Priority
import com.leo.secondbrain.data.TagEntity
import com.leo.secondbrain.data.WorkspaceSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomLibraryTest {
    @Test
    fun deletedCollectionCanBeRestoredWithContentsAfterReopeningDatabase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = "library-test-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, BrainDatabase::class.java, file).build()
        val parent = CollectionEntity(name = "Projects")
        val middle = CollectionEntity(name = "Planning", parentId = parent.id)
        val child = CollectionEntity(name = "Ideas", parentId = middle.id)
        val legacy = CollectionEntity(name = "Legacy ideas", parentId = child.id)
        val tag = TagEntity(name = " Green ", color = 4)
        val note = EntryEntity(kind = EntryKind.NOTE, title = " A note ", collectionId = middle.id)
        val task = EntryEntity(
            kind = EntryKind.TASK, title = "Check notes", collectionId = middle.id,
            priority = Priority.HIGH, deadline = LocalDate.of(2026, 11, 20).toEpochDay()
        )
        try {
            val first = open()
            try {
                val repository = BrainRepository(first)
                listOf(parent, middle, child).forEach { repository.saveCollection(it) }
                assertTrue(runCatching { repository.saveCollection(legacy) }.exceptionOrNull() is IllegalArgumentException)
                repository.mergeBackup(WorkspaceSnapshot(listOf(legacy), emptyList(), emptyList(), emptyList(), emptyList()))
                repository.saveCollection(legacy.copy(name = "Older ideas"))
                assertEquals(middle.id, repository.collections.first().single { it.id == child.id }.parentId)
                val cycle = runCatching { repository.saveCollection(parent.copy(parentId = child.id)) }
                assertTrue(cycle.exceptionOrNull() is IllegalArgumentException)
                repository.saveTag(tag)
                repository.saveEntry(note, setOf(tag.id))
                repository.saveEntry(task, setOf(tag.id))
                repository.setCompleted(task.id, true)
                repository.saveEntry(task.copy(body = "Updated details"), setOf(tag.id))
                repository.deleteCollection(middle.id)
            } finally {
                first.close()
            }
            val reopened = open()
            try {
                val repository = BrainRepository(reopened)
                assertEquals(listOf(parent.id), repository.collections.first().map { it.id })
                assertTrue(repository.entries.first().isEmpty())
                repository.restoreDeleted(repository.recentlyDeleted.first().single().id)
                val entries = repository.entries.first().associateBy { it.entry.id }
                assertEquals(middle.id, repository.collections.first().single { it.id == child.id }.parentId)
                assertEquals(middle.id, entries.getValue(note.id).entry.collectionId)
                assertEquals("A note", entries.getValue(note.id).entry.title)
                assertEquals("Green", entries.getValue(note.id).tags.single().name)
                val savedTask = entries.getValue(task.id).entry
                assertEquals(middle.id, savedTask.collectionId)
                assertEquals(task.deadline, savedTask.deadline)
                assertEquals(Priority.HIGH, savedTask.priority)
                assertEquals("Updated details", savedTask.body)
                assertTrue(savedTask.completed)
                repository.deleteTag(tag.id)
                assertTrue(repository.entries.first().all { it.tags.isEmpty() })
                repository.deleteEntry(note.id)
                assertEquals(task.id, repository.entries.first().single().entry.id)
                val record = repository.recentlyDeleted.first().single()
                repository.purgeExpired(record.deletedAt + com.leo.secondbrain.data.TRASH_RETENTION_MS)
                assertTrue(repository.recentlyDeleted.first().isEmpty())
                assertTrue(runCatching { repository.restoreDeleted(record.id) }.isFailure)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(file)
        }
        Unit
    }
}
