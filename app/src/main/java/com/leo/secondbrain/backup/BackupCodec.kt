package com.leo.secondbrain.backup

import com.leo.secondbrain.ai.objects
import com.leo.secondbrain.data.*
import org.json.JSONArray
import org.json.JSONObject

internal data class BackupMedia(val name: String, val length: Long, val sha256: String)

internal object BackupCodec {
    fun encode(snapshot: WorkspaceSnapshot, media: List<BackupMedia>): JSONObject = JSONObject()
        .put("format", "second-brain").put("version", 1)
        .put("captureRuns", JSONArray(snapshot.captureRuns.map {
            JSONObject().put("dumpId", it.dumpId).put("status", it.status.name).put("attempts", it.attempts)
                .putNullable("message", it.message).putNullable("zone", it.zone).put("results", JSONArray(it.results))
        }))
        .put("recurrenceSkips", JSONArray(snapshot.recurrenceSkips.map {
            JSONObject().put("seriesId", it.seriesId).put("day", it.day)
        }))
        .put("exportedAt", System.currentTimeMillis())
        .put("collections", JSONArray(snapshot.collections.map {
            JSONObject().put("id", it.id).put("name", it.name).put("description", it.description)
                .put("color", it.color).putNullable("parentId", it.parentId).put("icon", it.icon)
                .put("favorite", it.favorite)
        }))
        .put("tags", JSONArray(snapshot.tags.map { JSONObject().put("id", it.id).put("name", it.name).put("color", it.color) }))
        .put("entries", JSONArray(snapshot.entries.map { tagged ->
            val e = tagged.entry
            tagged.asProposal().json().put("id", e.id).put("createdAt", e.createdAt)
                .put("updatedAt", e.updatedAt).put("completed", e.completed).putNullable("anchor", e.recurrenceAnchor)
                .put("favorite", e.favorite)
        }))
        .put("events", JSONArray(snapshot.events.map { event ->
            val tags = snapshot.eventTags.filter { it.eventId == event.id }.map { it.tagId }.toSet()
            event.asProposal(tags).json().put("id", event.id).put("updatedAt", event.updatedAt).put("favorite", event.favorite)
        }))
        .put("captures", JSONArray(snapshot.dumps.map {
            JSONObject().put("id", it.id).put("original", it.original).putNullable("recording", it.recording)
                .putNullable("transcript", it.transcript).put("status", it.status.name).put("createdAt", it.createdAt)
        }))
        .put("drafts", JSONArray(snapshot.drafts.map {
            JSONObject().put("id", it.id).put("dumpId", it.dumpId).put("original", it.original)
                .put("proposal", it.proposal).put("status", it.status.name).put("createdAt", it.createdAt)
        }))
        .put("recordings", JSONArray(media.map {
            JSONObject().put("name", it.name).put("length", it.length).put("sha256", it.sha256)
        }))

    fun decode(json: JSONObject): Pair<WorkspaceSnapshot, List<BackupMedia>> {
        require(json.getString("format") == "second-brain" && json.getInt("version") == 1) {
            "This backup format or version is unsupported."
        }
        val collections = json.rows("collections").map {
            CollectionEntity(it.id(), it.getString("name"), it.getString("description"),
                it.getInt("color"), it.stringOrNull("parentId"), it.optString("icon", "FOLDER"),
                favorite = it.optBoolean("favorite"))
        }
        val collectionIds = unique(collections.map { it.id })
        collections.forEach {
            require(it.name.isNotBlank() && it.color in 0 until ACCENT_COUNT)
            require(it.parentId == null || it.parentId in collectionIds)
        }
        val parents = collections.associate { it.id to it.parentId }
        val checked = mutableSetOf<String>()
        collections.forEach {
            val path = mutableSetOf<String>()
            var id: String? = it.id
            while (id != null && id !in checked) {
                require(path.add(id)) { "A backup folder points to one of its descendants." }
                id = parents[id]
            }
            checked.addAll(path)
        }

        val tags = json.rows("tags").map { TagEntity(it.id(), it.getString("name"), it.getInt("color")) }
        val tagsById = tags.associateBy { it.id }
        unique(tags.map { it.id })
        require(tags.all { it.name.isNotBlank() && it.color in 0 until ACCENT_COUNT })

        fun check(p: Proposal) {
            require(p.title.isNotBlank() && (p.collectionId == null || p.collectionId in collectionIds))
            require(p.tags.all(tagsById::containsKey))
            require(listOfNotNull(p.startDay, p.endDay, p.hardDeadline).all(::validDay))
            require(p.kind == DraftKind.TASK || p.hardDeadline == null)
            require(listOfNotNull(p.startMinute, p.endMinute).all { it in 0..1439 })
            require(p.reminder == null || p.reminder in 0..10_080)
        }

        val entries = json.rows("entries").map { row ->
            val p = Proposal.read(row, validate = false)
            check(p)
            require(p.kind != DraftKind.EVENT)
            val anchor = row.longOrNull("anchor")
            require(anchor == null || validDay(anchor))
            require(p.kind != DraftKind.TASK || p.startDay != null ||
                (p.startMinute == null && p.reminder == null && p.repeat == Repeat.NONE))
            val entry = EntryEntity(
                id = row.id(), kind = EntryKind.valueOf(p.kind.name), title = p.title, body = p.body,
                collectionId = p.collectionId, deadline = p.startDay, priority = p.priority,
                completed = row.getBoolean("completed"), createdAt = row.getLong("createdAt"),
                updatedAt = row.getLong("updatedAt"), dueMinute = p.startMinute, timeOfDay = p.timeOfDay,
                hardDeadline = p.hardDeadline, durationMinutes = p.durationMinutes,
                recurrenceRule = p.recurrenceRule?.encode(), inferredSchedule = p.inferredSchedule,
                reminder = p.reminder, recurrenceAnchor = anchor, repeat = p.repeat,
                favorite = row.optBoolean("favorite"),
                flexibleSchedule = p.flexibleSchedule?.takeIf {
                    p.kind == DraftKind.TASK && p.repeat == Repeat.NONE && p.startDay != null && p.startMinute != null
                }?.json()?.toString()
            )
            TaggedEntry(entry, p.tags.map { tagsById.getValue(it) })
        }
        unique(entries.map { it.entry.id })

        val eventTags = mutableListOf<EventTag>()
        val events = json.rows("events").map { row ->
            val p = Proposal.read(row)
            check(p)
            require(p.kind == DraftKind.EVENT)
            p.validated(requireSchedule = true)
            val event = p.toEvent(row.id()).copy(updatedAt = row.getLong("updatedAt"), favorite = row.optBoolean("favorite"))
            eventTags += p.tags.map { EventTag(event.id, it) }
            event
        }
        unique(events.map { it.id })

        val dumps = json.rows("captures").map {
            val recording = it.stringOrNull("recording")
            require(recording == null || validName(recording))
            BrainDump(it.id(), it.getString("original"), recording, it.stringOrNull("transcript"),
                DumpStatus.valueOf(it.getString("status")), it.getLong("createdAt"))
        }
        val dumpIds = unique(dumps.map { it.id })
        val media = json.rows("recordings").map {
            BackupMedia(it.getString("name"), it.getLong("length"), it.getString("sha256")).also { info ->
                require(validName(info.name) && info.length in 1..20_000_000 && info.sha256.matches(Regex("[a-f0-9]{64}")))
            }
        }
        unique(media.map { it.name })
        val recordingNames = dumps.mapNotNull { it.recording }.toSet()
        require(media.all { it.name in recordingNames })

        val drafts = json.rows("drafts").map {
            Draft(it.id(), it.getString("dumpId"), it.getString("original"), it.getString("proposal"),
                DraftStatus.valueOf(it.getString("status")), it.getLong("createdAt")).also { draft ->
                require(draft.dumpId in dumpIds)
                Proposal.read(JSONObject(draft.original))
                Proposal.read(JSONObject(draft.proposal))
            }
        }
        unique(drafts.map { it.id })
        val series = entries.map { it.entry.id }.toSet()
        val skips = if (json.has("recurrenceSkips")) json.rows("recurrenceSkips").map {
            RecurrenceSkip(it.getString("seriesId"), it.getLong("day")).also { skip ->
                require(skip.seriesId in series && validDay(skip.day))
            }
        }.distinct() else emptyList()
        val captureRuns = if (json.has("captureRuns")) json.rows("captureRuns").map { row ->
            val dumpId = row.getString("dumpId")
            val state = CaptureStatus.valueOf(row.getString("status"))
            val results = row.rows("results")
            require(dumpId in dumpIds && results.size <= 40)
            results.forEach {
                val kind = DraftKind.valueOf(it.getString("kind"))
                require(it.id().isNotBlank() && it.getString("title").length <= 180 && it.getLong("version") >= 0)
                if (kind == DraftKind.EVENT) require(it.getString("id") !in series)
            }
            CaptureRun(dumpId, state, row.stringOrNull("message")?.take(2_000),
                JSONArray(results).toString(), row.getInt("attempts").coerceIn(0, 4),
                row.stringOrNull("zone")?.let { runCatching { java.time.ZoneId.of(it).id }.getOrNull() })
        }.distinctBy { it.dumpId } else emptyList()
        return WorkspaceSnapshot(collections, entries, tags, events, eventTags, dumps, drafts, skips, captureRuns) to media
    }

    private fun JSONObject.rows(key: String): List<JSONObject> {
        val array = getJSONArray(key)
        require(array.length() <= 50_000)
        return array.objects()
    }

    private fun JSONObject.id(): String = getString("id").also { require(it.matches(Regex("[A-Za-z0-9_-]{1,150}"))) }

    private fun unique(ids: List<String>): Set<String> = ids.toSet().also {
        require(ids.size == it.size) { "The backup contains duplicate identifiers." }
    }

    private fun validName(name: String) = name.matches(Regex("[a-f0-9-]{36}\\.m4a"))
}
