package com.leo.secondbrain.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

@Entity(tableName = "assistant_history")
internal data class AssistantMemoryRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: String,
    val text: String,
    val targetId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
internal interface AssistantMemoryDao {
    @Insert suspend fun insert(record: AssistantMemoryRecord)
    @Query("SELECT * FROM assistant_history ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AssistantMemoryRecord>
    @Query("SELECT * FROM assistant_history ORDER BY id DESC LIMIT 500")
    fun observe(): Flow<List<AssistantMemoryRecord>>
    @Query("DELETE FROM assistant_history WHERE id < (SELECT id FROM assistant_history ORDER BY id DESC LIMIT 1 OFFSET 499)")
    suspend fun prune()
    @Query("DELETE FROM assistant_history") suspend fun clear()
}

internal class AssistantMemory(private val database: BrainDatabase) {
    private val dao = database.assistantMemoryDao()
    val history = dao.observe()

    suspend fun remember(session: String, role: String, text: String, target: String? = null) {
        if (text.isBlank()) return
        database.withTransaction {
            dao.insert(AssistantMemoryRecord(sessionId = session, role = role, text = text.trim().take(2_000), targetId = target))
            dao.prune()
        }
    }

    suspend fun clear() = dao.clear()

    suspend fun context(query: String?, workspace: WorkspaceSnapshot, currentSession: String): JSONObject {
        val recent = dao.recent(if (query == null) 80 else 500).filter { it.sessionId != currentSession }
        val terms = words(query.orEmpty())
        val selected = if (query == null) {
            val preferences = recent.filter { it.role == "user" && preference.containsMatchIn(it.text) }.take(2)
            val actions = recent.filter { it.role == "action" }.distinctBy { it.targetId ?: it.id }.take(3)
            (preferences + actions + recent.filter { it.role == "user" }.take(3)).distinctBy { it.id }
        } else {
            val matches = recent.map { record -> record to words(record.text).intersect(terms).size }
                .filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
            matches.ifEmpty { recent.take(4) }.take(8)
        }
        val items = workspace.proposals().associateBy { it.targetId }
        val folders = workspace.collections.associateBy { it.id }
        val completed = workspace.entries.associate { it.entry.id to it.entry.completed }
        return JSONObject().put("history", JSONArray(selected.sortedBy { it.id }.map { record ->
            val item = items[record.targetId]
            val folder = folders[record.targetId]
            JSONObject().put("type", when (record.role) {
                "action" -> "Committed action at the time"
                "assistant" -> "Historical assistant reply"
                else -> "User discussion"
            }).put("excerpt", record.text.take(300)).put("recordedAt", Instant.ofEpochMilli(record.createdAt).toString())
                .putNullable("currentItem", item?.copy(body = "")?.json()
                    ?.putNullable("completed", completed[record.targetId]))
                .putNullable("currentCollection", folder?.let { JSONObject().put("id", it.id).put("name", it.name) })
                .put("targetUnavailable", record.targetId != null && item == null && folder == null)
        }))
    }

    private fun words(text: String) = Regex("[\\p{L}\\p{N}]{3,}").findAll(text.lowercase(Locale.ROOT))
        .map { it.value.removeSuffix("s") }.filterNot { it in stopWords }.toSet()

    private companion object {
        val preference = Regex("\\b(prefer|always|usually|my goal|my project|remember that|from now on)\\b", RegexOption.IGNORE_CASE)
        val stopWords = setOf("the", "that", "what", "from", "with", "have", "about", "was", "there", "did", "our", "we")
    }
}
