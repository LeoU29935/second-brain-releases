package com.leo.secondbrain.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface BrainDao {
    @Query("SELECT * FROM collections WHERE trashId IS NULL ORDER BY name COLLATE NOCASE, id")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE trashId IS NULL")
    suspend fun collections(): List<CollectionEntity>

    @Transaction
    @Query("SELECT * FROM entries WHERE trashId IS NULL ORDER BY createdAt DESC, id")
    fun observeEntries(): Flow<List<TaggedEntry>>

    @Query("SELECT * FROM entries WHERE id = :id AND trashId IS NULL")
    suspend fun entry(id: String): EntryEntity?

    @Transaction
    @Query("SELECT * FROM entries WHERE id = :id AND trashId IS NULL")
    suspend fun taggedEntry(id: String): TaggedEntry?

    @Transaction
    @Query("SELECT * FROM entries WHERE trashId IS NULL ORDER BY createdAt DESC, id")
    suspend fun snapshotEntries(): List<TaggedEntry>

    @Query("SELECT * FROM entries WHERE trashId IS NULL AND kind = 'TASK' AND completed = 0 AND deadline IS NOT NULL AND reminder IS NOT NULL")
    suspend fun reminderTasks(): List<EntryEntity>

    @Query("""SELECT * FROM entries WHERE trashId IS NULL AND kind = 'TASK' AND completed = 0
        AND flexibleSchedule IS NOT NULL AND deadline IS NOT NULL AND dueMinute IS NOT NULL ORDER BY deadline, dueMinute, id""")
    suspend fun flexibleTasks(): List<EntryEntity>

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE, id")
    fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT id FROM tags")
    suspend fun tagIds(): List<String>

    @Query("SELECT * FROM recurrence_skips")
    fun observeRecurrenceSkips(): Flow<List<RecurrenceSkip>>

    @Query("SELECT * FROM recurrence_skips WHERE seriesId IN (SELECT id FROM entries WHERE trashId IS NULL)")
    suspend fun recurrenceSkips(): List<RecurrenceSkip>

    @Query("SELECT day FROM recurrence_skips WHERE seriesId = :seriesId")
    suspend fun skippedDays(seriesId: String): List<Long>

    @Upsert suspend fun putRecurrenceSkip(skip: RecurrenceSkip)

    @Query("DELETE FROM recurrence_skips WHERE seriesId = :id AND day = :day")
    suspend fun removeRecurrenceSkip(id: String, day: Long)

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE, id")
    suspend fun snapshotTags(): List<TagEntity>

    @Upsert suspend fun upsertCollection(collection: CollectionEntity)
    @Upsert suspend fun upsertEntry(entry: EntryEntity)
    @Upsert suspend fun upsertTag(tag: TagEntity)
    @Query("UPDATE collections SET favorite = NOT favorite WHERE id = :id AND trashId IS NULL")
    suspend fun toggleFolderFavorite(id: String): Int
    @Query("UPDATE entries SET favorite = NOT favorite WHERE id = :id AND trashId IS NULL")
    suspend fun toggleEntryFavorite(id: String): Int
    @Insert suspend fun insertEntryTags(links: List<EntryTag>)

    @Query("DELETE FROM entry_tags WHERE entryId = :id")
    suspend fun clearEntryTags(id: String)

    @Query("UPDATE entries SET completed = :completed, updatedAt = :at WHERE id = :id AND trashId IS NULL AND kind = 'TASK'")
    suspend fun setCompleted(id: String, completed: Boolean, at: Long)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun deleteEntry(id: String)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTag(id: String)
}

@Database(
    entities = [
        CollectionEntity::class, EntryEntity::class, TagEntity::class, EntryTag::class,
        Event::class, EventTag::class, BrainDump::class, Draft::class, TrashRecord::class, RecurrenceSkip::class,
        CaptureRun::class, AssistantMemoryRecord::class
    ],
    version = 9,
    exportSchema = true
)
internal abstract class BrainDatabase : RoomDatabase() {
    abstract fun brainDao(): BrainDao
    abstract fun releaseDao(): ReleaseDao
    abstract fun trashDao(): TrashDao
    abstract fun assistantMemoryDao(): AssistantMemoryDao
}
