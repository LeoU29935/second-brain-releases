package com.leo.secondbrain.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ReleaseDao {
    @Query("SELECT * FROM events WHERE trashId IS NULL ORDER BY startDay, startMinute, id")
    fun observeEvents(): Flow<List<Event>>

    @Query("SELECT * FROM events WHERE trashId IS NULL ORDER BY startDay, startMinute, id")
    suspend fun events(): List<Event>

    @Query("SELECT * FROM events WHERE trashId IS NULL AND reminder IS NOT NULL")
    suspend fun reminderEvents(): List<Event>

    @Query("SELECT * FROM events WHERE id = :id AND trashId IS NULL")
    suspend fun event(id: String): Event?

    @Upsert suspend fun saveEvent(event: Event)
    @Query("UPDATE events SET favorite = NOT favorite WHERE id = :id AND trashId IS NULL")
    suspend fun toggleEventFavorite(id: String): Int

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun deleteEvent(id: String)

    @Query("SELECT event_tags.* FROM event_tags JOIN events ON events.id = event_tags.eventId WHERE events.trashId IS NULL")
    fun observeEventTags(): Flow<List<EventTag>>

    @Query("SELECT event_tags.* FROM event_tags JOIN events ON events.id = event_tags.eventId WHERE events.trashId IS NULL")
    suspend fun eventTags(): List<EventTag>

    @Query("SELECT tagId FROM event_tags WHERE eventId = :id")
    suspend fun eventTagIds(id: String): List<String>

    @Query("DELETE FROM event_tags WHERE eventId = :id")
    suspend fun clearEventTags(id: String)

    @Insert suspend fun insertEventTags(links: List<EventTag>)

    @Query("SELECT * FROM brain_dumps WHERE trashId IS NULL ORDER BY createdAt DESC, id")
    fun observeDumps(): Flow<List<BrainDump>>

    @Query("SELECT * FROM brain_dumps WHERE trashId IS NULL ORDER BY createdAt DESC, id")
    suspend fun dumps(): List<BrainDump>

    @Query("SELECT * FROM brain_dumps WHERE id = :id AND trashId IS NULL")
    suspend fun dump(id: String): BrainDump?

    @Insert suspend fun insertDump(dump: BrainDump)

    @Query("DELETE FROM brain_dumps WHERE id = :id")
    suspend fun deleteDump(id: String)
    @Query("SELECT id FROM brain_dumps")
    suspend fun allCaptureIds(): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM brain_dumps WHERE id = :id)")
    suspend fun captureExists(id: String): Boolean
    @Query("SELECT recording FROM brain_dumps WHERE recording IS NOT NULL")
    suspend fun recordingNames(): List<String>
    @Query("SELECT recording FROM brain_dumps WHERE trashId = :batch AND recording IS NOT NULL")
    suspend fun deletedRecordingNames(batch: String): List<String>
    @Query("UPDATE brain_dumps SET trashId = :batch WHERE id = :id AND trashId IS NULL")
    suspend fun markCapture(id: String, batch: String)
    @Query("UPDATE brain_dumps SET trashId = NULL WHERE trashId = :batch")
    suspend fun restoreCaptures(batch: String)
    @Query("DELETE FROM brain_dumps WHERE trashId = :batch")
    suspend fun purgeCaptures(batch: String)

    @Query("UPDATE brain_dumps SET transcript = :transcript WHERE id = :id AND trashId IS NULL")
    suspend fun transcript(id: String, transcript: String)

    @Query("UPDATE brain_dumps SET status = :status WHERE id = :id AND trashId IS NULL")
    suspend fun dumpStatus(id: String, status: DumpStatus)

    @Query("SELECT drafts.* FROM drafts JOIN brain_dumps ON drafts.dumpId = brain_dumps.id WHERE brain_dumps.trashId IS NULL ORDER BY drafts.createdAt DESC, drafts.id")
    fun observeDrafts(): Flow<List<Draft>>

    @Query("SELECT drafts.* FROM drafts JOIN brain_dumps ON drafts.dumpId = brain_dumps.id WHERE brain_dumps.trashId IS NULL ORDER BY drafts.createdAt DESC, drafts.id")
    suspend fun drafts(): List<Draft>
    @Query("SELECT id FROM drafts")
    suspend fun allDraftIds(): List<String>

    @Query("SELECT drafts.* FROM drafts JOIN brain_dumps ON drafts.dumpId = brain_dumps.id WHERE drafts.id = :id AND brain_dumps.trashId IS NULL")
    suspend fun draft(id: String): Draft?

    @Query("SELECT * FROM drafts WHERE dumpId = :id")
    suspend fun draftsFor(id: String): List<Draft>

    @Query("SELECT drafts.* FROM drafts JOIN brain_dumps ON drafts.dumpId = brain_dumps.id WHERE brain_dumps.trashId IS NULL AND drafts.status = 'APPROVED' AND drafts.proposal != drafts.original ORDER BY drafts.createdAt DESC LIMIT 30")
    suspend fun approvedCorrections(): List<Draft>

    @Insert suspend fun insertDrafts(drafts: List<Draft>)

    @Upsert suspend fun saveDraft(draft: Draft)

    @Query("SELECT capture_runs.* FROM capture_runs JOIN brain_dumps ON capture_runs.dumpId = brain_dumps.id WHERE brain_dumps.trashId IS NULL")
    fun captureRuns(): Flow<List<CaptureRun>>

    @Query("SELECT capture_runs.* FROM capture_runs JOIN brain_dumps ON capture_runs.dumpId = brain_dumps.id WHERE brain_dumps.trashId IS NULL AND dumpId = :id")
    suspend fun captureRun(id: String): CaptureRun?

    @Query("SELECT capture_runs.* FROM capture_runs JOIN brain_dumps ON capture_runs.dumpId = brain_dumps.id WHERE brain_dumps.trashId IS NULL")
    suspend fun snapshotCaptureRuns(): List<CaptureRun>

    @Query("""SELECT brain_dumps.* FROM brain_dumps JOIN capture_runs ON brain_dumps.id = capture_runs.dumpId
        WHERE capture_runs.status = 'QUEUED' AND brain_dumps.status != 'ARCHIVED' AND brain_dumps.trashId IS NULL ORDER BY brain_dumps.createdAt, brain_dumps.id""")
    suspend fun queuedCaptures(): List<BrainDump>

    @Query("""SELECT capture_runs.* FROM capture_runs JOIN brain_dumps ON brain_dumps.id = capture_runs.dumpId
        WHERE capture_runs.status = 'VERIFYING' AND brain_dumps.trashId IS NULL""")
    suspend fun capturesAwaitingReminders(): List<CaptureRun>

    @Upsert suspend fun saveCaptureRun(run: CaptureRun)
}
