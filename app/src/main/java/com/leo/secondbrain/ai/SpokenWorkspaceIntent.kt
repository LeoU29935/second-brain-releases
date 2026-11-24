package com.leo.secondbrain.ai

import java.util.Locale

internal const val CREATE_TASK_TOOL = "create_task"
internal val createTaskSchema = objectSchema(mapOf(
    "instructionEvidence" to scalar("string", true),
    "title" to scalar("string", true),
    "description" to scalar("string", true),
    "change" to org.json.JSONObject(suggestionSchema.toString()).removeRequiredFields()
), strict = false)

internal enum class SpokenWorkspaceIntent(val requiresTool: Boolean) {
    CREATE_TASK(true), CREATE_OTHER(true), UPDATE(true), DELETE(true), QUERY(false), CONVERSATION(false)
}

internal val nonCreationIntents = setOf(SpokenWorkspaceIntent.QUERY, SpokenWorkspaceIntent.UPDATE, SpokenWorkspaceIntent.DELETE)

private const val period = "(?:morning|afternoon|evening)"
private const val day = "(?:the day after tomorrow|day after tomorrow|tomorrow|today|tonight|(?:next |this |on )?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|this $period)"
private const val clock = "(?:(?:at|around|about)\\s+(?:[01]?\\d|2[0-3])(?::[0-5]\\d)?(?:\\s*[ap]\\.?m\\.?)?)"
private const val timing = "$day(?:\\s+(?:(?:sometime\\s+)?(?:(?:towards?|in)\\s+(?:the\\s+)?)?)?$period)?(?:\\s+$clock)?"
private val leadingTiming = Regex("^$timing(?:\\s*,\\s*|\\s+)", RegexOption.IGNORE_CASE)
private val trailingTiming = Regex("\\s+(?:sometime\\s+)?(?:$timing|$clock|(?:in|towards?)\\s+the\\s+$period)$", RegexOption.IGNORE_CASE)
private val intention = Regex("^i(?:\\s+(?:wanna|want to|need to|should|have to|must|plan to|intend to|would like to|am going to|will)|['’]m going to)\\s+", RegexOption.IGNORE_CASE)
private val explicitTask = Regex("^(?:add|create|make|schedule)\\s+(?:me\\s+)?(?:a\\s+|the\\s+|my\\s+)?task(?:\\s+(?:to|for|called|named))?\\s+|^remind me to\\s+", RegexOption.IGNORE_CASE)
private val actionableTask = Regex("^(?:apply|call|practice|study|work on|prepare|submit|send|email|message|text|write|read|review|buy|pay|pick up|bring|take|book|visit|finish|complete|clean|fix|update|check|organize|pack|print|look up|research|exercise|run|walk|start|plan|water|wash|renew|sign|reply)\\b", RegexOption.IGNORE_CASE)
private val compoundOrSpecial = Regex("[;\\n]|\\b(and|then|every|daily|weekly|monthly|yearly|until|before|after|deadline|priorit[yi]|repeat|folder|collection|tag|remind)\\b|\\bno later than\\b|\\bby\\s+$day\\b", RegexOption.IGNORE_CASE)
private val queryOpening = Regex("^(?:what|when|which|where|who|is|are|was|were|did|do|does|how|why|show|tell|list|search)\\b")
private val existingTask = Regex("\\b(?:this|that|the|my|an existing)\\s+(?:\\w+\\s+){0,3}(?:task|event|note|folder|collection)\\b|\\b(?:it|that|this)\\b")
private val deleteOpening = Regex("^(?:delete|remove|trash|discard|erase)\\b")
private val updateOpening = Regex("^(?:update|edit|rename|change|move|reschedule|mark|set|append|replace|clear|reopen|undo|take that back|skip|separate|complete|finish)\\b")
private val createOtherOpening = Regex("^(?:add|create|make|write|jot|schedule|remind|remember|notiere|erstelle|plane|erinnere)\\b")
private val politeOpening = Regex("^(?:please\\s+|(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?)", RegexOption.IGNORE_CASE)

private fun commandText(text: String): String {
    val clean = text.trim().replace(Regex("(?i)^actually[, ]+"), "").replaceFirst(politeOpening, "")
    return clean.replaceFirst(leadingTiming, "").replaceFirst(politeOpening, "")
}

internal fun spokenTaskTitle(transcript: String): String? {
    val command = commandText(transcript)
    val explicit = explicitTask.find(command)
    val stated = intention.find(command)
    if (explicit == null && stated == null) return null
    if (explicit == null && transcript.trimEnd().endsWith("?")) return null
    val action = command.substring((explicit ?: stated)!!.range.last + 1).trim()
    if (action.isBlank() || compoundOrSpecial.containsMatchIn(action) || '#' in action || '@' in action) return null
    val simple = action.trimEnd('.', '!', '?').replace(trailingTiming, "").trim()
    if (simple.isBlank() || simple.length > 180) return null
    val normalized = simple.lowercase(Locale.ROOT)
    if (explicit == null && (!actionableTask.containsMatchIn(simple) || deleteOpening.containsMatchIn(normalized) ||
            Regex("^(?:check|see|find out)\\s+(?:if|whether)\\b").containsMatchIn(normalized) ||
            updateOpening.containsMatchIn(normalized) && existingTask.containsMatchIn(normalized))) return null
    return simple.replaceFirstChar { it.titlecase() }
}

internal fun classifySpokenIntent(transcript: String): SpokenWorkspaceIntent {
    if (spokenTaskTitle(transcript) != null) return SpokenWorkspaceIntent.CREATE_TASK
    val command = commandText(transcript)
    val action = command.replaceFirst(intention, "").lowercase(Locale.ROOT)
    return when {
        action.isBlank() -> SpokenWorkspaceIntent.CONVERSATION
        queryOpening.containsMatchIn(action) || Regex("^(?:know|see if|check whether|check if)\\b").containsMatchIn(action) ->
            SpokenWorkspaceIntent.QUERY
        deleteOpening.containsMatchIn(action) -> SpokenWorkspaceIntent.DELETE
        Regex("^(?:make|put) (?:it|that|this)\\b").containsMatchIn(action) -> SpokenWorkspaceIntent.UPDATE
        updateOpening.containsMatchIn(action) &&
            (!actionableTask.containsMatchIn(action) || existingTask.containsMatchIn(action)) -> SpokenWorkspaceIntent.UPDATE
        createOtherOpening.containsMatchIn(action) || actionableTask.containsMatchIn(action) && intention.containsMatchIn(command) ->
            SpokenWorkspaceIntent.CREATE_OTHER
        else -> SpokenWorkspaceIntent.CONVERSATION
    }
}
