package com.leo.secondbrain.data

import androidx.room.Embedded
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import java.util.UUID

internal fun newId(): String = UUID.randomUUID().toString()
internal const val ACCENT_COUNT = 18

internal enum class EntryKind(val label: String) {
    NOTE("Note"), TASK("Task")
}

internal enum class Priority(val label: String) {
    NONE("None"), LOW("Low"), MEDIUM("Medium"), HIGH("High")
}

@Entity(
    tableName = "collections",
    foreignKeys = [ForeignKey(
        entity = CollectionEntity::class,
        parentColumns = ["id"],
        childColumns = ["parentId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("parentId"), Index("trashId")]
)
internal data class CollectionEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val description: String = "",
    val color: Int = 0,
    val parentId: String? = null,
    @ColumnInfo(defaultValue = "'FOLDER'") val icon: String = "FOLDER",
    val trashId: String? = null,
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false
)

@Entity(
    tableName = "entries",
    foreignKeys = [ForeignKey(
        entity = CollectionEntity::class,
        parentColumns = ["id"],
        childColumns = ["collectionId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("collectionId"), Index("deadline"), Index("trashId")]
)
internal data class EntryEntity(
    @PrimaryKey val id: String = newId(),
    val kind: EntryKind,
    val title: String,
    val body: String = "",
    val collectionId: String? = null,
    val deadline: Long? = null,
    val priority: Priority = Priority.NONE,
    val completed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val dueMinute: Int? = null,
    val reminder: Int? = null,
    val recurrenceAnchor: Long? = null,
    @ColumnInfo(defaultValue = "'NONE'") val repeat: Repeat = Repeat.NONE,
    val trashId: String? = null,
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    val flexibleSchedule: String? = null,
    val scheduleChange: String? = null,
    val timeOfDay: TimeOfDay? = null,
    // The legacy deadline column stores the calendar date. Only explicit due dates use hardDeadline.
    val hardDeadline: Long? = null,
    val durationMinutes: Int? = null,
    val recurrenceRule: String? = null,
    val inferredSchedule: String? = null
)

@Entity(tableName = "tags")
internal data class TagEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val color: Int = 0
)

@Entity(
    tableName = "entry_tags",
    primaryKeys = ["entryId", "tagId"],
    foreignKeys = [
        ForeignKey(EntryEntity::class, ["id"], ["entryId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["id"], ["tagId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("tagId")]
)
internal data class EntryTag(val entryId: String, val tagId: String)

internal data class TaggedEntry(
    @Embedded val entry: EntryEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(EntryTag::class, parentColumn = "entryId", entityColumn = "tagId")
    )
    val tags: List<TagEntity>
)

internal fun List<CollectionEntity>.canPlaceFolder(id: String, parentId: String?): Boolean {
    val existing = firstOrNull { it.id == id }
    if (existing != null && parentId == existing.parentId) return true
    if (parentId == null) return true
    val byId = associateBy { it.id }
    val descendants = descendantIds(id)
    val ancestors = generateSequence(byId[parentId]) { byId[it.parentId] }.take(3).toList()
    if (ancestors.isEmpty() || ancestors.size > 2 || ancestors.any { it.id in descendants }) return false
    val children = filter { it.parentId == id }
    return when (ancestors.size) {
        1 -> none { folder -> children.any { child -> folder.parentId == child.id } }
        else -> children.isEmpty()
    }
}

internal fun List<CollectionEntity>.descendantIds(id: String): Set<String> {
    val children = groupBy { it.parentId }
    val visited = mutableSetOf(id)
    val pending = ArrayDeque<String>()
    pending.addLast(id)
    while (pending.isNotEmpty()) {
        children[pending.removeFirst()].orEmpty().forEach { child ->
            if (visited.add(child.id)) pending.addLast(child.id)
        }
    }
    return visited
}

internal fun List<CollectionEntity>.pathName(id: String): String {
    val byId = associateBy { it.id }
    val visited = mutableSetOf<String>()
    val names = mutableListOf<String>()
    var current = byId[id]
    while (current != null && visited.add(current.id)) {
        names.add(current.name)
        current = byId[current.parentId]
    }
    return names.asReversed().joinToString(" / ")
}
