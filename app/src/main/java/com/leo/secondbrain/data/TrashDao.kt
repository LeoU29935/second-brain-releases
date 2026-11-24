package com.leo.secondbrain.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

internal enum class TrashKind(val label: String) {
    NOTE("Note"), TASK("Task"), EVENT("Event"), COLLECTION("Collection"), CAPTURE("Capture")
}

@Entity(tableName = "trash", indices = [Index("deletedAt")])
internal data class TrashRecord(
    @PrimaryKey val id: String = newId(),
    val rootId: String,
    val kind: TrashKind,
    val title: String,
    val deletedAt: Long
)

internal const val TRASH_RETENTION_MS = 14L * 24 * 60 * 60 * 1000

@Dao
internal interface TrashDao {
    @Query("SELECT * FROM trash ORDER BY deletedAt DESC, id")
    fun observe(): Flow<List<TrashRecord>>

    @Query("SELECT * FROM trash WHERE id = :id")
    suspend fun record(id: String): TrashRecord?

    @Query("SELECT id FROM trash WHERE deletedAt <= :cutoff")
    suspend fun expired(cutoff: Long): List<String>

    @Insert
    suspend fun insert(record: TrashRecord)

    @Query("SELECT trashId FROM collections WHERE id = :id")
    suspend fun collectionTrash(id: String): String?

    @Query("SELECT trashId FROM entries WHERE id = :id")
    suspend fun entryTrash(id: String): String?

    @Query("SELECT trashId FROM events WHERE id = :id")
    suspend fun eventTrash(id: String): String?

    @Query("SELECT id FROM collections")
    suspend fun allCollectionIds(): List<String>

    @Query("SELECT id FROM entries")
    suspend fun allEntryIds(): List<String>

    @Query("SELECT id FROM events")
    suspend fun allEventIds(): List<String>

    @Query("UPDATE collections SET trashId = :batch WHERE id IN (:ids) AND trashId IS NULL")
    suspend fun markCollections(ids: List<String>, batch: String)

    @Query("UPDATE entries SET trashId = :batch WHERE id = :id AND trashId IS NULL")
    suspend fun markEntry(id: String, batch: String)

    @Query("UPDATE events SET trashId = :batch WHERE id = :id AND trashId IS NULL")
    suspend fun markEvent(id: String, batch: String)

    @Query("UPDATE entries SET trashId = :batch WHERE trashId IS NULL AND collectionId IN (SELECT id FROM collections WHERE trashId = :batch)")
    suspend fun markCollectionEntries(batch: String)

    @Query("UPDATE events SET trashId = :batch WHERE trashId IS NULL AND collectionId IN (SELECT id FROM collections WHERE trashId = :batch)")
    suspend fun markCollectionEvents(batch: String)

    @Query("SELECT parentId FROM collections WHERE id = :id")
    suspend fun collectionParent(id: String): String?

    @Query("SELECT collectionId FROM entries WHERE id = :id")
    suspend fun entryParent(id: String): String?

    @Query("SELECT collectionId FROM events WHERE id = :id")
    suspend fun eventParent(id: String): String?

    @Query("UPDATE collections SET trashId = NULL WHERE trashId = :batch")
    suspend fun restoreCollections(batch: String)

    @Query("UPDATE entries SET trashId = NULL WHERE trashId = :batch")
    suspend fun restoreEntries(batch: String)

    @Query("UPDATE events SET trashId = NULL WHERE trashId = :batch")
    suspend fun restoreEvents(batch: String)

    @Query("DELETE FROM entries WHERE trashId = :batch")
    suspend fun purgeEntries(batch: String)

    @Query("DELETE FROM events WHERE trashId = :batch")
    suspend fun purgeEvents(batch: String)

    @Query("DELETE FROM collections WHERE trashId = :batch")
    suspend fun purgeCollections(batch: String)

    @Query("DELETE FROM trash WHERE id = :id")
    suspend fun remove(id: String)
}
