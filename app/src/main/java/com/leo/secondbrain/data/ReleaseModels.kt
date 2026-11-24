package com.leo.secondbrain.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "events",
    foreignKeys = [ForeignKey(CollectionEntity::class, ["id"], ["collectionId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("collectionId"), Index("startDay"), Index("trashId")]
)
internal data class Event(
    @PrimaryKey val id: String = newId(),
    val title: String,
    val body: String = "",
    val startDay: Long,
    val startMinute: Int? = null,
    val endDay: Long,
    val endMinute: Int? = null,
    val allDay: Boolean = true,
    val location: String = "",
    val repeat: Repeat = Repeat.NONE,
    val reminder: Int? = 30,
    val collectionId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val trashId: String? = null,
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    val timeOfDay: TimeOfDay? = null
) {
    fun covers(day: Long): Boolean {
        val length = endDay - startDay
        val occurrence = repeat.next(java.time.LocalDate.ofEpochDay(startDay), java.time.LocalDate.ofEpochDay(day - length))
        return occurrence != null && occurrence.toEpochDay() <= day
    }
}

@Entity(
    tableName = "event_tags",
    primaryKeys = ["eventId", "tagId"],
    foreignKeys = [
        ForeignKey(Event::class, ["id"], ["eventId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["id"], ["tagId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("tagId")]
)
internal data class EventTag(val eventId: String, val tagId: String)

internal enum class DumpStatus { UNREVIEWED, REVIEWED, ARCHIVED }

@Entity(tableName = "brain_dumps")
internal data class BrainDump(
    @PrimaryKey val id: String = newId(),
    val original: String = "",
    val recording: String? = null,
    val transcript: String? = null,
    val status: DumpStatus = DumpStatus.UNREVIEWED,
    val createdAt: Long = System.currentTimeMillis(),
    val trashId: String? = null
)

internal enum class DraftKind(val label: String) { NOTE("Note"), TASK("Task"), EVENT("Event") }
internal enum class DraftStatus { PENDING, APPROVED, REJECTED, DEFERRED }

internal enum class CaptureStatus { QUEUED, VERIFYING, ORGANIZED, ATTENTION, FAILED, UNDONE }
internal val DraftStatus.requiresReview: Boolean
    get() = this == DraftStatus.PENDING || this == DraftStatus.DEFERRED

internal fun captureStatus(run: CaptureRun?, drafts: List<Draft>): CaptureStatus? = when {
    drafts.any { it.status.requiresReview } -> CaptureStatus.ATTENTION
    drafts.isNotEmpty() -> CaptureStatus.ORGANIZED
    else -> run?.status
}

@Entity(tableName = "capture_runs",
    foreignKeys = [ForeignKey(BrainDump::class, ["id"], ["dumpId"], onDelete = ForeignKey.CASCADE)])
internal data class CaptureRun(
    @PrimaryKey val dumpId: String,
    val status: CaptureStatus = CaptureStatus.QUEUED,
    val message: String? = null,
    val results: String = "[]",
    val attempts: Int = 0,
    val zone: String? = null
)

@Entity(
    tableName = "drafts",
    foreignKeys = [ForeignKey(BrainDump::class, ["id"], ["dumpId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("dumpId")]
)
internal data class Draft(
    @PrimaryKey val id: String = newId(),
    val dumpId: String,
    val original: String,
    val proposal: String = original,
    val status: DraftStatus = DraftStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis()
)
