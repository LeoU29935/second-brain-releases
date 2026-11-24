package com.leo.secondbrain.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Handoff from input callbacks to Room. The local journal survives Activity and process teardown. */
internal class EditorJournal(
    context: Context,
    private val library: BrainRepository,
    private val onScheduleChanged: suspend () -> Unit
) {
    private val storage = context.getSharedPreferences("pending-editors", Context.MODE_PRIVATE)
    private val storageLock = Any()
    private val writer = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutableError = MutableStateFlow(false)
    val error = mutableError.asStateFlow()

    init {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            for (signal in wake) flush()
        }
        retry()
    }

    fun proposal(id: String): Proposal? = read(id)?.optJSONObject("value")?.let {
        if (it.has("kind")) Proposal.read(it, validate = false) else null
    }

    fun collection(id: String): CollectionEntity? = read(id)?.optJSONObject("value")?.let {
        if (it.has("name")) CollectionEntity(id, it.getString("name"), it.getString("description"),
            it.getInt("color"), it.stringOrNull("parent"), it.optString("icon", "FOLDER")) else null
    }

    fun put(id: String, proposal: Proposal) = write(id, proposal.json())

    fun putDraft(id: String, proposal: Proposal) = put("draft:$id", proposal)

    fun put(collection: CollectionEntity) = write(collection.id, JSONObject().put("name", collection.name)
        .put("description", collection.description).put("color", collection.color)
        .putNullable("parent", collection.parentId).put("icon", collection.icon))

    private fun read(id: String): JSONObject? = synchronized(storageLock) {
        storage.getString(id, null)?.let(::JSONObject)
    }

    private fun write(id: String, value: JSONObject): Boolean {
        val envelope = JSONObject().put("revision", newId()).put("value", value).toString()
        val committed = synchronized(storageLock) { storage.edit().putString(id, envelope).commit() }
        if (committed) retry() else mutableError.value = true
        return committed
    }

    fun retry() {
        wake.trySend(Unit)
    }

    suspend fun flush() = writer.withLock {
        var failed = false
        var scheduleChanged = false
        val values = synchronized(storageLock) { storage.all.toMap() }
        for ((id, encoded) in values) {
            if (encoded !is String) continue
            try {
                val item = JSONObject(encoded).getJSONObject("value")
                if (item.has("kind")) {
                    val proposal = Proposal.read(item, validate = false)
                    if (id.startsWith("draft:")) {
                        if (runCatching { proposal.validated() }.isFailure) continue
                        if (library.draftOpen(id.removePrefix("draft:"))) library.editDraft(id.removePrefix("draft:"), proposal)
                    } else {
                        library.saveEditorItem(id, proposal)
                    }
                    if (!id.startsWith("draft:")) scheduleChanged = true
                } else {
                    val name = item.getString("name")
                    if (name.isBlank()) continue
                    library.saveCollection(CollectionEntity(id, name, item.getString("description"),
                        item.getInt("color"), item.stringOrNull("parent")?.takeIf { parent ->
                            library.hasCollection(parent)
                        }, item.optString("icon", "FOLDER")))
                }
                synchronized(storageLock) {
                    if (storage.getString(id, null) == encoded && !storage.edit().remove(id).commit()) failed = true
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                failed = true
            }
        }
        mutableError.value = failed
        if (scheduleChanged) runCatching { onScheduleChanged() }
    }

    suspend fun delete(id: String, operation: suspend () -> Unit) = writer.withLock {
        val previous = synchronized(storageLock) {
            val value = storage.getString(id, null)
            check(storage.edit().remove(id).commit())
            value
        }
        try {
            operation()
        } catch (error: Exception) {
            if (previous != null) synchronized(storageLock) { storage.edit().putString(id, previous).commit() }
            retry()
            throw error
        }
    }
}
