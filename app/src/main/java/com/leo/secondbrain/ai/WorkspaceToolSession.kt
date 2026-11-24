package com.leo.secondbrain.ai

import com.leo.secondbrain.data.BrainRepository
import com.leo.secondbrain.data.WorkspaceUndo
import com.leo.secondbrain.data.newId
import com.leo.secondbrain.data.putNullable
import com.leo.secondbrain.data.stringOrNull
import com.leo.secondbrain.data.intOrNull
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.json.JSONArray

internal data class WorkspaceToolOutput(val json: JSONObject, val changed: Boolean = false, val undo: WorkspaceUndo? = null)

/** Owns the workspace state disclosed to one conversation and emits receipts derived from committed data. */
internal class WorkspaceToolSession(private val repository: BrainRepository, private val allowed: Boolean,
    private val sessionId: String = newId()) {
    private val editor = WorkspaceActions(repository)
    private val reader = WorkspaceReader(repository)
    private val conversation = WorkspaceConversation()
    private val items = linkedMapOf<String, com.leo.secondbrain.data.Proposal>()
    private val folders = mutableSetOf<String>()
    private val tags = mutableSetOf<String>()
    private var lastTarget: String? = null
    private var reads = 0
    private var changes = 0
    private val turnUndo = mutableListOf<WorkspaceUndo>()
    private val turnReceipts = mutableMapOf<String, JSONObject>()

    fun beginTurn() {
        turnUndo.clear()
        turnReceipts.clear()
    }

    suspend fun routeSpokenTask(transcript: String): WorkspaceToolOutput? {
        if (!allowed || classifySpokenIntent(transcript) != SpokenWorkspaceIntent.CREATE_TASK) return null
        val timing = ScheduleEvidence(transcript, transcript, LocalDate.now())
        if (timing.localDays().size > 1) return null
        return invoke(CREATE_TASK_TOOL, JSONObject(), transcript, groupWithTurn = true)
    }

    suspend fun initializeMemory(): JSONObject? = if (allowed) memory(null) else null

    private suspend fun memory(query: String?): JSONObject {
        val snapshot = repository.snapshot()
        val context = repository.assistantMemory.context(query, snapshot, sessionId)
        val history = context.optJSONArray("history").objects()
        val ids = history.mapNotNull { it.optJSONObject("currentItem")?.stringOrNull("targetId") }.toSet()
        snapshot.proposals().filter { it.targetId in ids }.forEach { items[requireNotNull(it.targetId)] = it }
        folders.addAll(history.mapNotNull { it.optJSONObject("currentCollection")?.stringOrNull("id") })
        if (lastTarget == null) lastTarget = history.lastOrNull { it.optJSONObject("currentItem") != null }
            ?.optJSONObject("currentItem")?.stringOrNull("targetId")
        return context
    }

    suspend fun invoke(name: String, args: JSONObject, transcript: String, groupWithTurn: Boolean = false): WorkspaceToolOutput = try {
        require(allowed) { "Workspace access is off." }
        when (name) {
            "recall_context" -> {
                limitReads()
                val query = args.getString("query").trim()
                require(query.length in 2..200) { "Use a short memory search query." }
                WorkspaceToolOutput(memory(query))
            }
            "search_workspace" -> {
                limitReads()
                val query = args.getString("query").trim()
                require(query.length in 2..200) { "Use a short workspace search query." }
                val found = relevantContext(query, repository.snapshot())
                remember(found)
                WorkspaceToolOutput(found.json)
            }
            "read_workspace_item" -> {
                limitReads()
                val id = args.getString("itemId")
                val details = reader.read(id, args.optInt("offset"), items.keys)
                lastTarget = id
                WorkspaceToolOutput(details)
            }
            "list_collection_contents" -> {
                limitReads()
                val page = reader.list(args.stringOrNull("collectionId"), args.optInt("offset"), folders)
                remember(page)
                WorkspaceToolOutput(page.json)
            }
            "list_schedule" -> {
                limitReads()
                val from = LocalDate.parse(args.getString("fromDate"))
                val to = LocalDate.parse(args.getString("throughDate"))
                val spokenDay = ScheduleEvidence(transcript, transcript, LocalDate.now()).onlyDate()
                    ?.let(LocalDate::ofEpochDay).takeIf { from == to }
                val page = reader.schedule(spokenDay ?: from, spokenDay ?: to,
                    if (args.isNull("completed")) null else args.getBoolean("completed"),
                    args.intOrNull("fromMinute"), args.intOrNull("untilMinute"), args.optInt("offset"))
                remember(page)
                WorkspaceToolOutput(page.json)
            }
            CREATE_TASK_TOOL, "apply_workspace_change" -> change(name, args, transcript, groupWithTurn)
            else -> throw IllegalArgumentException("The requested workspace tool is unsupported.")
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        WorkspaceToolOutput(JSONObject().put("applied", false).put("message",
            when (error) {
                is IllegalArgumentException -> error.message ?: "Some details are missing."
                is org.json.JSONException -> "The workspace tool received incomplete arguments."
                else -> "The workspace change could not be completed."
            }))
    }

    private suspend fun change(name: String, args: JSONObject, transcript: String, groupWithTurn: Boolean): WorkspaceToolOutput {
        if (!groupWithTurn) beginTurn()
        val operation = args.optString("operation")
        require(changes < 80 || operation == "UNDO") { "Reconnect to continue making changes." }
        val key = name + canonicalArguments(args)
        turnReceipts[key]?.let { return WorkspaceToolOutput(JSONObject(it.toString()).put("changed", false)) }
        val context = relevantContext(transcript, repository.snapshot())
        folders.addAll(context.collections)
        tags.addAll(context.tags)
        val disclosed = RelevantContext(JSONObject(), items, folders, tags)
        val result = if (name == CREATE_TASK_TOOL) editor.createTask(args, transcript, disclosed, conversation)
            else editor.apply(args, disclosed, transcript, lastTarget, conversation)
        val changed = !result.pending && result.changed
        if (changed) {
            changes++
            if (operation == "UNDO") {
                turnUndo.clear()
                val current = repository.snapshot().proposals().associateBy { it.targetId }
                items.keys.toList().forEach { id ->
                    val item = current[id]
                    if (item == null) items.remove(id) else items[id] = item
                }
                if (lastTarget !in items) lastTarget = items.keys.lastOrNull()
            } else if (result.undo != null) {
                turnUndo += result.undo
                conversation.lastUndo = repository.groupAssistantUndo(turnUndo.toList())
            } else {
                turnUndo.clear()
                conversation.lastUndo = null
            }
        }
        if (!result.pending) result.targetId?.let { id ->
            items.remove(id)
            if (result.deleted) {
                folders.remove(id)
                if (lastTarget == id) lastTarget = null
            } else {
                lastTarget = id
                val saved = result.saved ?: repository.readWorkspaceItem(id)
                if (saved != null) items[id] = saved
                else if (repository.hasCollection(id)) folders.add(id)
            }
        }
        val receipt = JSONObject().put("applied", !result.pending).put("pending", result.pending)
            .put("changed", changed)
            .put("message", result.message).putNullable("targetId", result.targetId)
            .putNullable("currentItem", result.targetId?.let { items[it]?.copy(body = "")?.json() })
        if (changed) {
            try {
                repository.assistantMemory.remember(sessionId, "action", result.message, result.targetId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                receipt.put("historySaved", false)
            }
        }
        turnReceipts[key] = receipt
        return WorkspaceToolOutput(receipt, changed, result.undo?.let { conversation.lastUndo })
    }

    private fun remember(context: RelevantContext) {
        items.putAll(context.workspaceItems)
        folders.addAll(context.collections)
        tags.addAll(context.tags)
    }

    private fun limitReads() {
        require(reads++ < 30) { "This session reached its workspace read limit." }
    }
}

private fun canonicalArguments(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") {
        JSONObject.quote(it) + ":" + canonicalArguments(value.get(it))
    }
    is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") {
        canonicalArguments(value.get(it))
    }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}
