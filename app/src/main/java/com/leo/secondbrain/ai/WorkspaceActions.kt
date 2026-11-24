package com.leo.secondbrain.ai

import com.leo.secondbrain.data.BrainRepository
import com.leo.secondbrain.data.CollectionEntity
import com.leo.secondbrain.data.DraftKind
import com.leo.secondbrain.data.Proposal
import com.leo.secondbrain.data.putNullable
import com.leo.secondbrain.data.stringOrNull
import com.leo.secondbrain.data.stringSet
import com.leo.secondbrain.data.savedScheduleSummary
import com.leo.secondbrain.data.RichBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import com.leo.secondbrain.ui.collectionIconIds
import com.leo.secondbrain.ui.theme.accents

internal data class WorkspaceResult(val message: String, val targetId: String? = null, val deleted: Boolean = false,
    val pending: Boolean = false, val undo: com.leo.secondbrain.data.WorkspaceUndo? = null,
    val changed: Boolean = true, val saved: Proposal? = null)

internal class WorkspaceConversation {
    data class Pending(val args: String, val transcript: String, val lastTarget: String?)
    var pending: Pending? = null
        private set
    var lastUndo: com.leo.secondbrain.data.WorkspaceUndo? = null

    fun defer(args: JSONObject, transcript: String, lastTarget: String?) {
        pending = Pending(args.toString(), transcript, lastTarget)
    }

    fun clear() { pending = null }
}

internal class WorkspaceActions(private val repository: BrainRepository) {
    suspend fun apply(
        requested: JSONObject,
        disclosed: RelevantContext,
        latestTranscript: String,
        lastTarget: String?,
        conversation: WorkspaceConversation = WorkspaceConversation()
    ): WorkspaceResult {
        val requestedOperation = requested.getString("operation")
        // A model-selected edit must never overwrite a workspace item for a clear new-task utterance.
        if (requestedOperation == "EDIT") {
            val taskTitle = spokenTaskTitle(latestTranscript)
            if (taskTitle != null) return createSpokenTask(taskTitle, latestTranscript, disclosed, conversation)
        }
        if (requestedOperation == "UNDO") {
            require(!isCancellation(normalize(latestTranscript)) &&
                Regex("\\b(undo|reverse|take that back|rückgängig)\\b").containsMatchIn(normalize(latestTranscript))) {
                "Ask the user if they want to undo the last action."
            }
            val action = requireNotNull(conversation.lastUndo) { "There is no recent action to undo." }
            action.run()
            conversation.lastUndo = null
            return WorkspaceResult("Undid the last reversible action.")
        }
        if (requestedOperation == "CANCEL_PENDING") {
            require(isCancellation(normalize(latestTranscript))) { "Ask whether to cancel the pending action." }
            conversation.clear()
            return WorkspaceResult("Canceled the pending action.", pending = true)
        }
        val pending = if (requestedOperation == "CONFIRM_PENDING") requireNotNull(conversation.pending) {
            "There is no pending action to confirm."
        } else null
        if (pending != null) {
            val reply = normalize(latestTranscript)
            val names = if (requested.has("collectionId")) repository.snapshot().collections else emptyList()
            val proposedParent = requested.stringOrNull("collectionId")?.let { id -> names.firstOrNull { it.id == id } }
            val namedPlace = proposedParent != null && " $reply ".contains(" ${normalize(proposedParent.name)} ")
            require(isConfirmation(reply) || (!isCancellation(reply) && namedPlace)) {
                "Ask the user to confirm the pending action."
            }
        }
        val args = pending?.let { JSONObject(it.args) } ?: requested
        if (pending != null && args.getString("operation").endsWith("_COLLECTION")) {
            val reply = normalize(latestTranscript)
            val named = requested.stringOrNull("collectionName")
            if (args.stringOrNull("collectionName") == null && named != null && normalize(named) in reply) {
                args.put("collectionName", named)
            }
            val folder = requested.stringOrNull("collectionId")
            if (requested.has("collectionId")) {
                if (folder == null && ("top level" in reply || "root" in reply)) args.put("collectionId", JSONObject.NULL)
                if (folder != null && repository.snapshot().collections.any {
                        it.id == folder && (" $reply ".contains(" ${normalize(it.name)} ") || reply == "there")
                    }) args.put("collectionId", folder)
            }
        }
        val sourceText = pending?.transcript ?: latestTranscript
        val source = normalize(sourceText)
        val requestedChange = args.getString("operation")
        val isScheduleMove = requestedChange == "MOVE" && args.isNull("collectionId") &&
            !Regex("\\b(inbox|folder|collection)\\b").containsMatchIn(source) &&
            ScheduleEvidence(sourceText, sourceText, java.time.LocalDate.now()).onlyDate() != null
        val operation = if (isScheduleMove) "EDIT" else requestedChange
        if (operation == "CREATE") {
            require(classifySpokenIntent(sourceText) !in nonCreationIntents) {
                "The current request is for an existing item or a lookup."
            }
        }
        if (isScheduleMove) {
            args.put("changedFields", JSONArray(listOf("start")))
            args.put("change", JSONObject().put("scheduleEvidence", sourceText))
        }
        val targetId = args.stringOrNull("targetId") ?: lastTarget?.takeIf {
            operation in setOf("EDIT", "MOVE", "COMPLETE", "REOPEN") &&
                Regex("\\b(it|that|this|the last (?:one|task|item))\\b").containsMatchIn(source)
        }
        val quote = normalize(args.optString("instructionEvidence").ifBlank {
            sourceText.takeIf { operation in setOf("CREATE", "EDIT", "MOVE", "COMPLETE", "REOPEN") }.orEmpty()
        })
        require(quote.length >= 3 && " $source ".contains(" $quote ")) {
            "Ask what the user wants changed before acting."
        }
        require(hasIntent(operation, quote, targetId != null && targetId == (pending?.lastTarget ?: lastTarget)) ||
            operation == "CREATE" && spokenTaskTitle(sourceText) != null) {
            "This utterance did not authorize a workspace ${operation.substringBefore('_').lowercase(Locale.ROOT)}."
        }
        require(!Regex("\\b(do not|don t|never|nicht|niemals) (?:change|delete|remove|move|edit|skip|erase|create|add|mark)\\b")
            .containsMatchIn(source)) { "The request may be canceling the operation. Ask before making a change." }
        val destination = args.stringOrNull("collectionId")
        require(destination == null || destination in disclosed.collections) { "Search for the destination collection first." }
        if (pending == null && args.optBoolean("needsConfirmation") &&
            operation !in setOf("CREATE", "EDIT", "MOVE", "COMPLETE", "REOPEN")) {
            require(!isCancellation(source)) { "The request canceled the action." }
            conversation.defer(args, latestTranscript, lastTarget)
            return WorkspaceResult("Pending. Confirm the missing detail before applying.", pending = true)
        }
        if (pending != null || conversation.pending != null) conversation.clear()

        if (operation.endsWith("_COLLECTION")) {
            return collectionChange(operation, targetId, destination, args, disclosed)
        }

        val target = targetId?.let { requireNotNull(disclosed.workspaceItems[it]) { "Search for the item first." } }
        if (target != null && targetId != lastTarget) {
            val duplicates = disclosed.workspaceItems.values.filter {
                it.targetId != targetId && it.kind == target.kind && it.title.equals(target.title, ignoreCase = true)
            }
            if (duplicates.isNotEmpty()) {
                val path = target.collectionId?.let { id -> repository.snapshot().collections.firstOrNull { it.id == id }?.name }
                    ?: "Inbox"
                require(" $source ".contains(" ${normalize(path)} ")) { "Several items have that name. Ask which collection." }
            }
        }
        return when (operation) {
            "CREATE", "EDIT" -> {
                require((operation == "EDIT") == (target != null)) { "Identify the item to edit first." }
                val change = args.optJSONObject("change") ?: if (target != null) JSONObject()
                    else throw IllegalArgumentException("The requested item details are missing.")
                if (target != null) {
                    if (change.isNull("kind")) change.put("kind", target.kind.name)
                    if (change.isNull("title")) change.put("title", target.title)
                }
                val today = java.time.LocalDate.now()
                val singleAction = target == null && !Regex("\\b(and|then|und|danach)\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(sourceText)
                val parsed = readSuggestion(change.putNullable("targetId", targetId), disclosed, sourceText, today,
                    singleCaptureItem = singleAction)
                require(parsed.targetId == targetId) { "The requested item type does not match the target." }
                val proposal = if (target == null) {
                    val snapshot = repository.snapshot(includeScheduling = true)
                    TaskPlanner(snapshot, java.time.ZonedDateTime.now()).plan(
                        parsed, scheduleExcerpt(change, sourceText, singleAction), sourceText)
                } else {
                    val merged = merge(target, parsed, args, change, sourceText)
                    if (merged.kind == DraftKind.TASK && merged.inferredSchedule != null &&
                        (merged.startDay == null || merged.startMinute == null || merged.reminder == null)) {
                        val snapshot = repository.snapshot(includeScheduling = true)
                        TaskPlanner(snapshot.copy(entries = snapshot.entries.filterNot { it.entry.id == targetId }),
                            java.time.ZonedDateTime.now()).plan(merged.copy(targetId = null), sourceText, sourceText)
                            .copy(targetId = targetId, targetVersion = merged.targetVersion)
                    } else merged
                }
                require(proposal.uncertainty.isBlank()) { proposal.uncertainty }
                storeProposal(proposal, today)
            }
            "MOVE_UNFINISHED_TASKS" -> {
                require(targetId == null && destination == null &&
                    Regex("^(?:please )?(?:move|reschedule) (?:all )?(?:my |the )?(?:unfinished|incomplete) tasks to .+$")
                        .matches(source)) { "Confirm that you want to move all unfinished tasks, and give the new date." }
                val evidence = ScheduleEvidence(sourceText, sourceText, java.time.LocalDate.now())
                val to = requireNotNull(evidence.onlyDate() ?: evidence.date(args.stringOrNull("occurrenceDate"))) { "Specify the new date." }
                val result = repository.moveUnfinishedTasks(to)
                WorkspaceResult("Moved ${result.count} unfinished task(s). " +
                    "${result.skipped} task(s) stayed in place because of fixed times, recurrence, deadlines, or availability.",
                    undo = result.undo)
            }
            "MOVE" -> {
                requireNotNull(target) { "Search for the item to move first." }
                require(destination != null || quote.contains("inbox")) { "Identify the destination first." }
                val undo = repository.moveWithUndo(requireNotNull(targetId), target.kind, destination, target.targetVersion)
                WorkspaceResult(if (undo == null) "${target.title} is already there." else "Moved ${target.title}.",
                    targetId, undo = undo, changed = undo != null)
            }
            "SKIP_OCCURRENCE" -> {
                require(target?.kind == DraftKind.TASK) { "Identify the recurring task first." }
                val evidence = ScheduleEvidence(sourceText, sourceText, java.time.LocalDate.now())
                val day = requireNotNull(evidence.date(args.stringOrNull("occurrenceDate"))) { "Specify the occurrence date." }
                val undo = repository.skipOccurrence(requireNotNull(targetId), day, target.targetVersion)
                WorkspaceResult("Skipped the ${java.time.LocalDate.ofEpochDay(day)} occurrence of ${target.title}.", targetId, undo = undo)
            }
            "SEPARATE_OCCURRENCE" -> {
                require(target?.kind == DraftKind.TASK) { "Identify the recurring task first." }
                val evidence = ScheduleEvidence(sourceText, sourceText, java.time.LocalDate.now())
                val day = requireNotNull(evidence.date(args.stringOrNull("occurrenceDate"))) { "Specify the occurrence date." }
                val id = com.leo.secondbrain.data.newId()
                repository.separateOccurrence(requireNotNull(targetId), day, id, target.targetVersion)
                WorkspaceResult("Separated the ${java.time.LocalDate.ofEpochDay(day)} occurrence of ${target.title}.", id)
            }
            "COMPLETE", "REOPEN", "DELETE" -> {
                requireNotNull(target) { "Search for the item first." }
                val complete = if (operation == "DELETE") null else operation == "COMPLETE"
                val undo = repository.changeAssistantWithUndo(target, complete)
                checkNotNull(undo) { "The item no longer exists." }
                val verb = when (complete) {
                    true -> "Completed"
                    false -> "Reopened"
                    null -> "Moved to Recently Deleted"
                }
                WorkspaceResult("$verb ${target.title}.", targetId, deleted = complete == null, undo = undo)
            }
            else -> throw IllegalArgumentException("The requested action is unsupported.")
        }
    }

    suspend fun createTask(args: JSONObject, transcript: String, disclosed: RelevantContext,
        conversation: WorkspaceConversation): WorkspaceResult {
        val evidence = args.stringOrNull("instructionEvidence").orEmpty().ifBlank { transcript }
        require(classifySpokenIntent(transcript) !in nonCreationIntents) {
            "The current request is for an existing item or a lookup."
        }
        require(" ${normalize(transcript)} ".contains(" ${normalize(evidence)} ")) {
            "Quote the task using words from the current request."
        }
        val change = args.optJSONObject("change")?.let { JSONObject(it.toString()) } ?: JSONObject()
        val suggestedTitle = change.stringOrNull("title")?.trim()?.takeIf(String::isNotEmpty)
            ?: args.stringOrNull("title")?.trim()?.takeIf(String::isNotEmpty)
            ?: spokenTaskTitle(transcript)
            ?: throw IllegalArgumentException("What task should I add?")
        change.put("kind", "TASK").put("title", suggestedTitle)
        if (!change.has("body")) change.put("body", args.stringOrNull("description").orEmpty())
        if (change.isNull("scheduleEvidence")) change.put("scheduleEvidence", evidence)
        return apply(JSONObject().put("operation", "CREATE").put("instructionEvidence", transcript).put("change", change),
            disclosed, transcript, null, conversation)
    }

    private suspend fun createSpokenTask(title: String, transcript: String, disclosed: RelevantContext,
        conversation: WorkspaceConversation): WorkspaceResult {
        val today = java.time.LocalDate.now()
        val raw = JSONObject().put("kind", "TASK").put("title", title).put("body", "")
            .put("scheduleEvidence", transcript)
        val parsed = readSuggestion(raw, disclosed, transcript, today, singleCaptureItem = true)
        val proposal = TaskPlanner(repository.snapshot(includeScheduling = true), java.time.ZonedDateTime.now())
            .plan(parsed, transcript, transcript)
        require(proposal.uncertainty.isBlank()) { proposal.uncertainty }
        val saved = storeProposal(proposal, today)
        conversation.clear()
        return saved
    }

    private suspend fun storeProposal(proposal: Proposal, today: java.time.LocalDate): WorkspaceResult {
        val (saved, undo) = repository.applyAssistantChange(proposal)
        val action = when {
            undo == null -> "Already saved"
            proposal.targetId == null -> "Added"
            else -> "Updated"
        }
        val details = saved.savedScheduleSummary(today, includeInferred = true)
        return WorkspaceResult("$action ${saved.title}${details.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}.",
            saved.targetId, undo = undo, changed = undo != null, saved = saved)
    }

    private suspend fun collectionChange(operation: String, targetId: String?, destination: String?,
        args: JSONObject, disclosed: RelevantContext): WorkspaceResult {
        if (operation == "CREATE_COLLECTION") {
            val name = args.optString("collectionName").trim().take(180)
            require(name.isNotBlank()) { "Ask for a collection name." }
            val created = CollectionEntity(name = name, description = args.optString("collectionDescription").take(500),
                parentId = destination, icon = collectionIcon(args) ?: "FOLDER", color = collectionColor(args) ?: 0)
            repository.saveCollection(created)
            return WorkspaceResult("Created the ${created.name} collection.", created.id)
        }
        require(targetId != null && targetId in disclosed.collections) { "Search for the collection first." }
        val collection = requireNotNull(repository.snapshot().collections.firstOrNull { it.id == targetId }) {
            "The collection no longer exists."
        }
        if (operation == "DELETE_COLLECTION") {
            val undo = checkNotNull(repository.deleteWithUndo(collection.id, com.leo.secondbrain.data.TrashKind.COLLECTION))
            return WorkspaceResult("Moved ${collection.name} and its contents to Recently Deleted.", collection.id,
                deleted = true, undo = undo)
        }
        val revised = when (operation) {
            "MOVE_COLLECTION" -> collection.copy(parentId = destination)
            "EDIT_COLLECTION" -> collection.copy(
                name = args.stringOrNull("collectionName")?.trim()?.take(180) ?: collection.name,
                description = args.stringOrNull("collectionDescription")?.take(500) ?: collection.description,
                icon = collectionIcon(args) ?: collection.icon,
                color = collectionColor(args) ?: collection.color
            )
            else -> throw IllegalArgumentException("The requested collection action is unsupported.")
        }
        val undo = if (operation == "MOVE_COLLECTION") {
            repository.moveFolderWithUndo(collection.id, destination)
        } else {
            repository.saveCollection(revised)
            null
        }
        return WorkspaceResult(if (operation == "MOVE_COLLECTION") "Moved ${revised.name}." else "Updated ${revised.name}.",
            revised.id, undo = undo)
    }

    private fun collectionIcon(args: JSONObject) = args.stringOrNull("collectionIcon")?.let { icon ->
        val id = normalize(icon).uppercase(Locale.ROOT).replace(' ', '_')
        require(id in collectionIconIds) { "Choose an icon from the app’s available icons." }
        id
    }

    private fun collectionColor(args: JSONObject) = args.stringOrNull("collectionColor")?.let { color ->
        val label = when (color.trim().lowercase(Locale.ROOT)) {
            "blue" -> "Cobalt"
            "green" -> "Pine"
            "red" -> "Coral"
            "pink" -> "Rose"
            "purple" -> "Iris"
            "yellow" -> "Ochre"
            "grey", "gray" -> "Smoke"
            "orange" -> "Copper"
            else -> color.trim()
        }
        val index = accents.indexOfFirst { it.name.equals(label, ignoreCase = true) }
        require(index >= 0) { "Choose an available collection color." }
        index
    }

    private fun merge(old: Proposal, new: Proposal, args: JSONObject, raw: JSONObject, source: String): Proposal {
        val fields = args.optJSONArray("changedFields").stringSet()
        val clear = args.optJSONArray("clearFields").stringSet()
        val accepted = setOf("title", "body", "start", "startTime", "end", "endTime", "allDay", "location",
            "priority", "reminder", "repeat", "tags", "collection", "timeOfDay", "deadline")
        require(fields.isNotEmpty() && accepted.containsAll(fields) && fields.containsAll(clear)) { "Identify the supported fields to change." }
        require(clear.isEmpty() || Regex("\\b(clear|remove|unset|turn off|stop|no|without|entferne)\\b")
            .containsMatchIn(normalize(source))) { "Confirm which fields should be cleared." }
        val editedStart = if ("start" in clear) null else if ("start" in fields) new.startDay else old.startDay
        val today = java.time.LocalDate.now()
        val timing = ScheduleEvidence(scheduleExcerpt(raw, source, true), source, today)
        val requestedTime = timing.time(raw.stringOrNull("startTime")) ?: timing.onlyTime()
        val requestedPeriod = timing.timeOfDay().takeIf { requestedTime == null }
        val periodChanged = "timeOfDay" !in clear &&
            ("timeOfDay" in fields || "start" in fields && requestedPeriod != null)
        val timeChanged = "startTime" in fields || "start" in fields && requestedTime != null
        val startTime = when {
            "start" in clear || "startTime" in clear || periodChanged -> null
            timeChanged -> requireNotNull(requestedTime) { "Specify the new clock time." }
            else -> old.startMinute
        }
        val period = when {
            "start" in clear || "timeOfDay" in clear || timeChanged && startTime != null -> null
            periodChanged -> requireNotNull(requestedPeriod) { "Specify morning, afternoon, or evening." }
            else -> old.timeOfDay
        }
        val inferred = old.inferredSchedule?.takeUnless { clear.any { it in setOf("start", "startTime", "reminder") } }
            ?.split(',')?.filter(String::isNotBlank)?.toMutableSet()
        if ("start" in fields) inferred?.remove("date")
        if (timeChanged) inferred?.remove("time")
        if (periodChanged) inferred?.add("time")
        if ("reminder" in fields) inferred?.remove("reminder")
        val oldStart = old.startDay
        val oldEnd = old.endDay
        val shift = if (old.kind == DraftKind.EVENT && fields.any { it == "start" || it == "startTime" } &&
            fields.none { it == "end" || it == "endTime" } && oldStart != null && oldEnd != null && editedStart != null) {
            val previous = oldStart * 1440L + (old.startMinute ?: 0)
            val updated = editedStart * 1440L + (startTime ?: 0)
            oldEnd * 1440L + (old.endMinute ?: 0) + updated - previous
        } else null
        val body = if ("body" !in fields) old.body else if ("body" in clear) "" else editBody(old.body, raw.getString("body"), args)
        return old.copy(
            title = if ("title" in fields) new.title else old.title,
            body = body,
            startDay = editedStart,
            startMinute = startTime,
            timeOfDay = period,
            hardDeadline = when {
                "deadline" in clear -> null
                "deadline" in fields -> requireNotNull(timing.deadline(today)) { "Specify the deadline." }
                else -> old.hardDeadline
            },
            flexibleSchedule = old.flexibleSchedule.takeIf { fields.none { it in setOf("start", "startTime", "repeat", "timeOfDay") } },
            endDay = if ("end" in fields) new.endDay else shift?.let { Math.floorDiv(it, 1440L) } ?: old.endDay,
            endMinute = if (periodChanged || "startTime" in clear || "endTime" in clear || "end" in clear) null else if ("end" in fields || "endTime" in fields) new.endMinute
                else shift?.let { Math.floorMod(it, 1440L).toInt() }
                    .takeUnless { old.allDay || startTime == null || old.endMinute == null } ?: old.endMinute,
            allDay = if ("allDay" in fields) raw.getBoolean("allDay") else old.allDay && !periodChanged && startTime == null,
            location = if ("location" in fields) raw.optString("location").take(500) else old.location,
            priority = if ("priority" in clear) com.leo.secondbrain.data.Priority.NONE else if ("priority" in fields)
                com.leo.secondbrain.data.Priority.valueOf(raw.getString("priority")) else old.priority,
            reminder = if ("reminder" in clear || "start" in clear) null else if ("reminder" in fields)
                requireNotNull(timing.reminderOffset() ?: new.reminder) { "Specify how long before the task to remind you." }
                else old.reminder,
            repeat = if ("repeat" in clear || "start" in clear) com.leo.secondbrain.data.Repeat.NONE
                else if ("repeat" in fields) requireNotNull(timing.recurrence()?.first
                    ?: new.repeat.takeIf { it != com.leo.secondbrain.data.Repeat.NONE && it != old.repeat }) {
                    "How often should it repeat?"
                } else old.repeat,
            recurrenceRule = if ("repeat" in clear || "start" in clear) null
                else if ("repeat" in fields) timing.recurrence()?.second else old.recurrenceRule,
            inferredSchedule = inferred?.joinToString(","),
            tags = if ("tags" in clear) emptySet() else if ("tags" in fields) {
                if (raw.optJSONArray("tags").stringSet().isEmpty()) emptySet() else new.tags
            } else old.tags,
            collectionId = if ("collection" in fields) args.stringOrNull("collectionId") else old.collectionId
        )
    }

    private fun editBody(old: String, input: String, args: JSONObject): String {
        val document = RichBody.read(old)
        val result = when (args.optString("bodyMode", "replace")) {
            "replace" -> input
            "append" -> document.withText(listOf(document.text, input).filter(String::isNotEmpty).joinToString("\n")).encode()
            "replaceText" -> {
                val original = args.getString("bodyMatch")
                val start = document.text.indexOf(original)
                require(original.isNotEmpty() && start >= 0 && start == document.text.lastIndexOf(original)) {
                    "Read the item again and identify a unique passage to replace."
                }
                document.replace(start, start + original.length, input).encode()
            }
            else -> throw IllegalArgumentException("The requested description edit is unsupported.")
        }
        require(result.length <= 48_000) { "This would exceed the item text limit." }
        return result
    }
}

private fun normalize(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

private fun isCancellation(text: String) =
    Regex("\\b(no|don t|do not|cancel|stop|nein|nicht|abbrechen)\\b").containsMatchIn(text)

private fun isConfirmation(text: String) = !isCancellation(text) &&
    Regex("^(?:yes|okay|ok|sure|go ahead|do it|make it|there|top level|at the top level|ja|genau|mach das)(?:$|\\b.*)")
        .matches(text)

private fun hasIntent(operation: String, quote: String, followUp: Boolean): Boolean {
    val cues = when (operation.removeSuffix("_COLLECTION")) {
        "CREATE" -> "add|create|make|write|jot|schedule|remind|remember|need to|have to|want to|wanna|i should|plan to|going to|i will|new|newly|notiere|erstelle|neu|plane|erinnere"
        "EDIT" -> "update|edit|rename|change|move|reschedule|make|set|actually|instead|push|add|append|replace|clear|remove|turn off|ändere|verschiebe|umbenennen"
        "SKIP_OCCURRENCE" -> "skip|omit|exclude|überspringe"
        "SEPARATE_OCCURRENCE" -> "separate|edit|change|reschedule|move|verschiebe|trenne"
        "MOVE" -> "move|put|file|send|verschiebe|verschieben|lege"
        "MOVE_UNFINISHED_TASKS" -> "move|reschedule"
        "COMPLETE" -> "complete|done|finish|finished|check|completed|erledigt|abschließen"
        "REOPEN" -> "reopen|undo|uncheck|incomplete|wieder|rückgängig"
        "DELETE" -> "delete|remove|trash|discard|lösche|löschen|entferne"
        else -> return false
    }
    val explicit = Regex("\\b($cues)\\b").containsMatchIn(quote)
    val timing = Regex("\\b(today|tomorrow|morning|afternoon|evening|tonight|monday|tuesday|wednesday|thursday|friday|saturday|sunday|heute|morgen|freitag)\\b")
    val scheduledCommand = operation == "CREATE" && timing.containsMatchIn(quote) &&
        Regex("^(?:please )?(?:call|email|text|write|apply|submit|buy|pay|send|read|finish|review|pick up|book|meet|visit|take|bring|practice|study|work on|prepare|find)\\b")
            .containsMatchIn(quote)
    return explicit || scheduledCommand || (operation == "EDIT" && followUp && timing.containsMatchIn(quote))
}

internal val workspaceActionSchema = objectSchema(mapOf(
    "operation" to scalar("string"),
    "instructionEvidence" to scalar("string"),
    "targetId" to scalar("string", true),
    "collectionId" to scalar("string", true),
    "collectionName" to scalar("string", true),
    "collectionDescription" to scalar("string", true),
    "collectionColor" to scalar("string", true),
    "collectionIcon" to scalar("string", true),
    "needsConfirmation" to scalar("boolean"),
    "changedFields" to scalar("array").put("items", scalar("string")),
    "clearFields" to scalar("array").put("items", scalar("string")),
    "bodyMode" to scalar("string"),
    "bodyMatch" to scalar("string"),
    "occurrenceDate" to scalar("string", true),
    "change" to JSONObject(suggestionSchema.toString()).removeRequiredFields()
), strict = false)

internal fun JSONObject.removeRequiredFields(): JSONObject = apply {
    remove("required")
    put("type", JSONArray(listOf("object", "null")))
}
