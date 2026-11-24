package com.leo.secondbrain.ui

import androidx.compose.animation.togetherWith

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leo.secondbrain.BrainApplication
import com.leo.secondbrain.data.SlimeFace
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.leo.secondbrain.data.BrainDump
import com.leo.secondbrain.data.CaptureStatus
import com.leo.secondbrain.data.CaptureRun
import com.leo.secondbrain.ai.objects
import org.json.JSONArray
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import com.leo.secondbrain.data.Draft
import com.leo.secondbrain.data.DraftKind
import com.leo.secondbrain.data.DraftStatus
import com.leo.secondbrain.data.DumpStatus
import com.leo.secondbrain.data.Proposal
import com.leo.secondbrain.data.asProposal
import com.leo.secondbrain.data.captureStatus
import com.leo.secondbrain.data.requiresReview
import com.leo.secondbrain.data.convertTo
import com.leo.secondbrain.data.TimeOfDay
import com.leo.secondbrain.data.scheduleTimeLabel
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import com.leo.secondbrain.data.savedScheduleSummary

@Composable
internal fun BrainDumpScreen(
    model: BrainViewModel, library: LibraryState, busy: Boolean, openResult: (String, DraftKind) -> Unit,
    requestedSource: String? = null, onSourceOpened: () -> Unit = {}
) {
    val dumps by model.dumps.collectAsStateWithLifecycle()
    val drafts by model.drafts.collectAsStateWithLifecycle()
    val runs by model.captureRuns.collectAsStateWithLifecycle()
    val processing by model.app.automaticOrganization.processing.collectAsStateWithLifecycle()
    var status by rememberSaveable { mutableStateOf<DumpStatus?>(null) }
    var textCapture by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(requestedSource) {
        if (requestedSource != null) {
            sourceId = requestedSource
            onSourceOpened()
        }
    }
    var draftId by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var deletingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    var recovery by rememberSaveable { mutableStateOf(false) }
    var approving by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    val groupedDrafts = remember(drafts) { drafts.groupBy { it.dumpId } }
    val audioOnly = remember(dumps) { dumps.filter { it.recording != null && it.original.isBlank() && it.transcript.isNullOrBlank() } }
    val audioOnlyIds = remember(audioOnly) { audioOnly.map { it.id }.toSet() }
    val shown = if (recovery) audioOnly else dumps.filter {
        (if (status == null) it.status != DumpStatus.ARCHIVED else it.status == status) &&
            (it.status == DumpStatus.ARCHIVED || it.id !in audioOnlyIds)
    }
    val shownIds = shown.map { it.id }.toSet()
    val visibleDrafts = drafts.filter {
        it.status.requiresReview && (it.dumpId in shownIds || !recovery && status == null && it.dumpId in audioOnlyIds)
    }
    val sources = dumps.associateBy { it.id }
    val active = drafts.firstOrNull { it.id == draftId }
    val workspace = remember(library.entries, library.events, library.eventTags) {
        library.entries.map { it.asProposal() } + library.events.map { event ->
            event.asProposal(library.eventTags.filter { it.eventId == event.id }.map { it.tagId }.toSet())
        }
    }

    if (active != null) {
        DraftEditor(active, library, workspace, model, busy, sources[active.dumpId]?.status == DumpStatus.ARCHIVED, openResult) {
            draftId = null
        }
    } else {
        LibraryGrid(masonry = true) {
            fullWidth("capture actions") {
                Surface(onClick = { textCapture = true }, modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        BrainIcon(Glyph.EDIT)
                        Text("Write a thought", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            fullWidth("dump status") {
                val filters = (listOf<DumpStatus?>(null) + DumpStatus.entries).map { option ->
                    Triple(option?.name?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "All",
                        !recovery && status == option, {
                            status = option
                            recovery = false
                            selected = arrayListOf()
                        })
                } + listOfNotNull(Triple("Recordings", recovery, {
                        status = null
                        recovery = !recovery
                        selected = arrayListOf()
                    }).takeIf { audioOnly.isNotEmpty() })
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
                    val capacity = (maxWidth.value / (108 * scale)).toInt().coerceIn(1, filters.size)
                    val rowCount = (filters.size + capacity - 1) / capacity
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(rowCount) { row ->
                            val start = row * filters.size / rowCount
                            val end = (row + 1) * filters.size / rowCount
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                filters.subList(start, end).forEach { (label, active, select) ->
                                    BrainChip(active, select, { Text(label) }, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            if (selected.isNotEmpty()) fullWidth("batch actions") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrainButton({ approving = ArrayList(selected) }, enabled = !busy) { Text("Review ${selected.size} to approve") }
                    QuietButton({ selected = arrayListOf() }) { Text("Clear selection") }
                }
            }
            if (visibleDrafts.isNotEmpty()) fullWidth("draft heading") { SectionHeader("Drafts") }
            visibleDrafts.forEach { draft -> item("draft:${draft.id}", span = StaggeredGridItemSpan.FullLine) {
                val proposal = Proposal.read(JSONObject(draft.proposal))
                val open = draft.status == DraftStatus.PENDING || draft.status == DraftStatus.DEFERRED
                val toggleable = open && sources[draft.dumpId]?.status != DumpStatus.ARCHIVED
                val ready = toggleable && draft.status == DraftStatus.PENDING && proposal.targetId == null &&
                    proposal.uncertainty.isBlank() && runCatching { proposal.validated(true) }.isSuccess &&
                    (proposal.collectionId == null || library.collections.any { it.id == proposal.collectionId }) &&
                    library.tags.map { it.id }.containsAll(proposal.tags)
                Surface({ draftId = draft.id }, modifier = Modifier.fillMaxWidth().animateItem(), shape = cardShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow, border = cardBorder()) {
                    Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Capsule(proposal.kind.label, MaterialTheme.colorScheme.primary)
                        Text(proposal.title, style = MaterialTheme.typography.titleMedium, maxLines = 3,
                            overflow = TextOverflow.Ellipsis)
                        if (proposal.body.isNotBlank()) Text(proposal.body, maxLines = 4, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(if (open) {
                            if (proposal.targetId == null) "Create new" else "Proposed update"
                        } else draft.status.name.lowercase().replaceFirstChar(Char::uppercase),
                            style = MaterialTheme.typography.labelMedium)
                        if (toggleable) Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(draft.id in selected, { checked ->
                                selected = ArrayList(if (checked) selected + draft.id else selected - draft.id)
                            }, Modifier.semantics { contentDescription = "Select ${proposal.title}" })
                            if (ready) BrainButton({
                                model.write({ selected = ArrayList(selected - draft.id) }) {
                                    model.app.edits.flush()
                                    model.repository.approveDrafts(setOf(draft.id), quick = true)
                                }
                            }, enabled = !busy) { Text("Approve") }
                            else QuietButton({ draftId = draft.id }, enabled = !busy) { Text("Review") }
                        }
                    }
                }
            } }
            if (shown.isNotEmpty()) fullWidth("original heading") {
                SectionHeader(if (recovery) "Recording recovery" else "Captures", countLabel(shown.size, "capture"))
            }
            if (shown.isEmpty()) fullWidth("empty dump") {
                QuietEmpty(Glyph.INBOX, if (status == null) "No captures yet" else "No ${status?.name?.lowercase()} captures")
            }
                items(shown, key = { "dump:" + it.id }) { dump ->
                    val audio = dump.recording != null
                    val run = runs[dump.id]
                    val currentStatus = captureStatus(run, groupedDrafts[dump.id].orEmpty())
                    Surface({ sourceId = dump.id }, modifier = Modifier.animateItem(), shape = cardShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLow, border = cardBorder()) {
                        Column(Modifier.fillMaxWidth().height(128.dp).padding(10.dp),
                            verticalArrangement = Arrangement.SpaceBetween) {
                            BrainIcon(if (audio) Glyph.MIC else Glyph.NOTE, Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.tertiary,
                                description = if (audio) "Voice capture" else "Text capture")
                            val preview = dump.original.ifBlank { dump.transcript ?: if (audio)
                                "Recording awaiting transcription" else "Recording unavailable" }
                            Text(preview, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(captureTimestamp(dump.createdAt), maxLines = 1, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            currentStatus?.let { Text(it.label(processing == dump.id), maxLines = 1,
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                }
        }
    }

    if (textCapture) {
        CaptureTextWindow(typed, { typed = it.take(48_000) }, busy, { textCapture = false }) {
            model.write({ textCapture = false; typed = "" }) {
                model.repository.saveCapture(BrainDump(original = typed.trim()))
                model.app.automaticOrganization.kick()
            }
        }
    }
    val source = dumps.firstOrNull { it.id == sourceId }
    if (source != null) {
        SourceDialog(source, model, busy, { sourceId = null }, runs[source.id], groupedDrafts[source.id].orEmpty(),
            processing == source.id, openResult, onDelete = { deletingCapture = source.id; sourceId = null }) { kind ->
            val content = source.transcript.orEmpty().ifBlank { source.original }
            val title = content.lineSequence().firstOrNull()?.trim()?.take(70).orEmpty().ifBlank { "Voice draft" }
            val proposal = Proposal(kind, title, content)
            model.write({ sourceId = null }) { model.repository.addDraft(source.id, proposal) }
        }
    }
    deletingCapture?.let { id ->
        ConfirmAction("Delete capture?", "Approved workspace items stay in your library.",
            "Delete capture", busy, { deletingCapture = null }) {
            model.deleteWithUndo(id, com.leo.secondbrain.data.TrashKind.CAPTURE) { deletingCapture = null }
        }
    }
    approving?.let { ids ->
        BrainDialog(
            onDismissRequest = { if (!busy) approving = null },
            title = { Text("Approve ${countLabel(ids.size, "draft")}?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    drafts.filter { it.id in ids }.forEach { draft ->
                        val proposal = Proposal.read(JSONObject(draft.proposal))
                        FieldLabel(proposal.title)
                        val target = workspace.firstOrNull { it.targetId == proposal.targetId }
                        if (target != null) ChangeSummary(target, proposal, library)
                        else if (proposal.targetId != null) Text("The target is no longer available.")
                        else proposalSummary(proposal, library).forEach { (field, value) -> Text("$field · $value") }
                    }
                }
            },
            confirmButton = { QuietButton({
                model.write({ selected = arrayListOf(); approving = null }) {
                    model.app.edits.flush()
                    model.repository.approveDrafts(ids.toSet())
                }
            }, enabled = !busy) { Text("Apply selected") } },
            dismissButton = { QuietButton({ approving = null }, enabled = !busy) { Text("Cancel") } }
        )
    }
}

@Composable
private fun CaptureReceipt(run: CaptureRun?, status: CaptureStatus?, processing: Boolean, retry: () -> Unit, undo: () -> Unit,
    open: (String, DraftKind) -> Unit) {
    status ?: return
    val results = remember(run?.results) { JSONArray(run?.results ?: "[]").objects() }
    val label = if (status == CaptureStatus.QUEUED && !processing && (run?.attempts ?: 0) > 0)
        "Waiting to retry" else status.label(processing)
    androidx.compose.animation.AnimatedContent(label, transitionSpec = {
        (androidx.compose.animation.fadeIn(Motion.glide()) + androidx.compose.animation.slideInVertically(Motion.glide()) { it / 3 })
            .togetherWith(androidx.compose.animation.fadeOut(Motion.glide(Motion.QUICK)))
    }, label = "Capture status") {
        Text(it, style = MaterialTheme.typography.labelMedium,
            color = if (status == CaptureStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
    run?.message?.takeIf { status != CaptureStatus.ORGANIZED }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    results.distinctBy { it.getString("id") }.forEach { item ->
        QuietButton({ open(item.getString("id"), DraftKind.valueOf(item.getString("kind"))) }) {
            Text(item.getString("title"), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        item.optJSONObject("details")?.let { Proposal.read(it).savedScheduleSummary(LocalDate.now(), includeInferred = true) }
            ?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    when (status) {
        CaptureStatus.FAILED -> QuietButton(retry) { Text("Retry") }
        CaptureStatus.ORGANIZED -> {
            if (results.isNotEmpty() && results.none { it.has("draft") }) QuietButton(undo) { Text("Undo organization") }
        }
        else -> Unit
    }
}

private fun CaptureStatus.label(processing: Boolean) = when (this) {
    CaptureStatus.QUEUED -> if (processing) "Processing" else "Queued"
    CaptureStatus.VERIFYING -> "Checking reminders"
    CaptureStatus.ORGANIZED -> "Organized"
    CaptureStatus.ATTENTION -> "Needs attention"
    CaptureStatus.FAILED -> "Failed"
    CaptureStatus.UNDONE -> "Organization undone"
}

private fun captureTimestamp(time: Long) =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        .format(java.util.Date(time))

@Composable
private fun CaptureTextWindow(value: String, onValue: (String) -> Unit, busy: Boolean, onClose: () -> Unit,
    onCapture: () -> Unit) {
    val focus = remember { FocusRequester() }
    // Keep the input attached to the same layout while keyboard insets animate.
    val compact = LocalConfiguration.current.screenHeightDp < 420
    Dialog(onDismissRequest = { if (!busy) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val keyboard = LocalSoftwareKeyboardController.current
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()
            .padding(horizontal = 14.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            val target = maxHeight.coerceAtMost(330.dp)
            Surface(Modifier.widthIn(max = if (compact) 650.dp else 470.dp).fillMaxWidth().height(target),
                shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
                if (compact) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        BrainField(value, onValue, Modifier.weight(1f).focusRequester(focus),
                            label = { Text("What’s on your mind?") }, maxLines = 3)
                        ActionIcon(Glyph.CLOSE, "Cancel", onClose, enabled = !busy)
                        ActionIcon(Glyph.CHECK, "Capture", onCapture, enabled = !busy && value.isNotBlank())
                    }
                } else {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Capture text", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleMedium)
                        BrainField(value, onValue, Modifier.weight(1f).fillMaxWidth().focusRequester(focus),
                            label = { Text("What’s on your mind?") }, maxLines = 14)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            QuietButton(onClose, enabled = !busy) { Text("Cancel") }
                            BrainButton(onCapture, enabled = !busy && value.isNotBlank()) { Text("Capture") }
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            focus.requestFocus()
            keyboard?.show()
        }
    }
}

@Composable
private fun DraftEditor(
    draft: Draft, library: LibraryState, workspace: List<Proposal>, model: BrainViewModel, busy: Boolean,
    archived: Boolean, openResult: (String, DraftKind) -> Unit, onClose: () -> Unit
) {
    val initial = remember(draft.id) { model.app.edits.proposal("draft:${draft.id}") ?: Proposal.read(JSONObject(draft.proposal)) }
    var value by rememberSaveable { mutableStateOf(initial.json().toString()) }
    val current = Proposal.read(JSONObject(value), validate = false)
    val change: (Proposal) -> Unit = {
        value = it.json().toString()
        model.app.edits.putDraft(draft.id, it)
    }
    var confirming by rememberSaveable { mutableStateOf<DraftStatus?>(null) }
    val pending = !archived && (draft.status == DraftStatus.PENDING || draft.status == DraftStatus.DEFERRED)
    val target = workspace.firstOrNull { it.targetId == current.targetId }
    val targets = workspace.filter { it.kind == current.kind }.associateBy { it.targetId }
    val valid = runCatching { current.validated() }.isSuccess
    val ready = valid && runCatching { current.validated(true) }.isSuccess &&
        (current.targetId == null || current.targetVersion == target?.targetVersion)
    EditorLayout(
        "Review ${current.kind.label.lowercase()}", busy, onClose = onClose, onDelete = null
    ) {
        Capsule(current.kind.label, MaterialTheme.colorScheme.primary, when (current.kind) {
            DraftKind.NOTE -> Glyph.NOTE
            DraftKind.TASK -> Glyph.CHECK
            DraftKind.EVENT -> Glyph.CALENDAR
        })
        Text(current.title, style = MaterialTheme.typography.headlineSmall)
        if (pending) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BrainButton({ confirming = DraftStatus.APPROVED }, enabled = ready && !busy) { Text("Approve") }
                QuietButton({ confirming = DraftStatus.DEFERRED }, enabled = valid && !busy) { Text("Defer") }
                QuietButton({ confirming = DraftStatus.REJECTED }, enabled = !busy) { Text("Reject") }
            }
            if (!ready) Text(
                if (current.targetId != null && current.targetVersion != target?.targetVersion) {
                    "The original changed. Select its latest version or create a new item."
                } else runCatching { current.validated(true) }.exceptionOrNull()?.message ?: "Complete the required fields.",
                color = MaterialTheme.colorScheme.error
            )
            if (current.uncertainty.isNotBlank()) Text(current.uncertainty, color = MaterialTheme.colorScheme.tertiary)
            FieldLabel("Destination")
            ChoiceMenu(current.kind.label, DraftKind.entries, DraftKind::label, { kind ->
                change(current.convertTo(kind, LocalDate.now().toEpochDay()).copy(targetId = null, targetVersion = null))
            }, Glyph.EDIT)
            ChoiceMenu(target?.let { "Update ${it.title}" } ?: "Create new",
                listOf<String?>(null) + targets.keys.filterNotNull(), { targets[it]?.title ?: "Create new" }, {
                    change(current.copy(targetId = it, targetVersion = targets[it]?.targetVersion))
                }, Glyph.COLLECTIONS)
            if (target != null) ChangeSummary(target, current, library)
            ProposalFields(current, change, library)
            if (workspace.isNotEmpty()) {
                FieldLabel("Related items")
                val options = workspace.mapNotNull { it.targetId }.filter { it != current.targetId }
                val names = workspace.associate { it.targetId to it.title }
                ChoiceMenu("Edit relationships", options, {
                    (if (it in current.related) "✓ " else "") + names[it].orEmpty()
                }, {
                    change(current.copy(related = if (it in current.related) current.related - it else current.related + it))
                }, Glyph.SEARCH)
                Text(current.related.mapNotNull { names[it] }.joinToString(", ").ifBlank { "No related items" })
            }
            PageField(current.uncertainty, { change(current.copy(uncertainty = it.take(2_000))) }, "Review notes")
        } else {
            Text(draft.status.name.lowercase().replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.titleMedium)
            proposalSummary(current, library).forEach { (label, description) ->
                Text("$label · $description")
            }
        }
    }
    confirming?.let { action ->
        val detail = when (action) {
            DraftStatus.APPROVED -> if (target == null) "Create “${current.title}” in your workspace."
                else "Apply the displayed changes to “${target.title}”."
            DraftStatus.REJECTED -> "Keep the original capture and reject this proposal."
            else -> "Keep the draft for later review."
        }
        val title = when (action) {
            DraftStatus.APPROVED -> "Approve draft?"
            DraftStatus.REJECTED -> "Reject draft?"
            else -> "Defer draft?"
        }
        ConfirmAction(title, detail, when (action) {
                DraftStatus.APPROVED -> "Apply change"
                DraftStatus.REJECTED -> "Reject draft"
                else -> "Defer draft"
            }, busy, { confirming = null }) {
            var approvedId: String? = null
            model.write({ onClose(); approvedId?.let { openResult(it, current.kind) } }) {
                when (action) {
                    DraftStatus.APPROVED -> {
                        model.app.edits.flush()
                        approvedId = model.repository.approveEditedDraft(draft.id, current)
                    }
                    DraftStatus.REJECTED -> model.app.edits.delete("draft:${draft.id}") {
                        model.repository.editDraft(draft.id, Proposal.read(JSONObject(draft.proposal)), action)
                    }
                    else -> {
                        model.app.edits.flush()
                        model.repository.editDraft(draft.id, current, action)
                    }
                }
            }
        }
    }
}

private fun proposalSummary(p: Proposal, state: LibraryState): Map<String, String> {
    fun whenLabel(day: Long?, minute: Int?, period: TimeOfDay? = null) =
        listOfNotNull(day?.let { LocalDate.ofEpochDay(it).scheduleDate() },
            scheduleTimeLabel(minute, period))
            .joinToString(" · ").ifBlank { "Not set" }
    return buildMap {
        put("Title", p.title)
        put("Description", p.body.ifBlank { "Empty" })
        put("Collection", state.collections.firstOrNull { it.id == p.collectionId }?.name ?: "Inbox")
        put("Tags", state.tags.filter { it.id in p.tags }.joinToString { it.name }.ifBlank { "None" })
        if (p.kind != DraftKind.NOTE) {
            val schedule = whenLabel(p.startDay, p.startMinute, p.timeOfDay)
            val flexible = p.kind == DraftKind.TASK && p.startDay != null &&
                (p.startMinute == null || p.flexibleSchedule?.automatic == true)
            put(if (p.kind == DraftKind.EVENT) "Start" else "Schedule", schedule + if (flexible) " · Flexible" else "")
            if (p.kind == DraftKind.TASK) put("Deadline", whenLabel(p.hardDeadline, null))
            put("Remind", reminderLabel(p.reminder))
            put("Recurrence", p.repeat.label)
        }
        if (p.kind == DraftKind.TASK) put("Priority", p.priority.label)
        if (p.kind == DraftKind.EVENT) {
            put("End", whenLabel(p.endDay, p.endMinute))
            put("All-day", if (p.allDay) "Yes" else "No")
            put("Location", p.location.ifBlank { "Not set" })
        }
    }
}

@Composable
private fun ChangeSummary(before: Proposal, after: Proposal, library: LibraryState) {
    val old = proposalSummary(before, library)
    val changed = proposalSummary(after, library).filter { old[it.key] != it.value }
    FieldLabel("Changes to ${before.title}")
    if (changed.isEmpty()) Text("No workspace fields changed.")
    changed.forEach { (field, value) ->
        Text(field, style = MaterialTheme.typography.labelLarge)
        Text("${old[field]} → $value", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SourceDialog(dump: BrainDump, model: BrainViewModel, busy: Boolean, onClose: () -> Unit, run: CaptureRun?,
    drafts: List<Draft>, processing: Boolean, openResult: (String, DraftKind) -> Unit,
    onDelete: () -> Unit, onDraft: (DraftKind) -> Unit) {
    val archived = dump.status == DumpStatus.ARCHIVED
    BrainDialog(
        onDismissRequest = onClose, title = { Text("Original capture") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(captureTimestamp(dump.createdAt), style = MaterialTheme.typography.labelMedium)
                if (dump.original.isNotBlank()) Text(dump.original)
                if (dump.transcript != null) {
                    FieldLabel("Transcript")
                    Text(dump.transcript.ifBlank { "No transcribed speech." })
                }
                dump.recording?.let {
                    val file = remember(it) { runCatching { model.app.recordings.file(it) }.getOrNull() }
                    if (file?.isFile == true) PlaybackButton(file)
                    else Text("Recording file unavailable", style = MaterialTheme.typography.bodySmall)
                }
                CaptureReceipt(run, captureStatus(run, drafts), processing,
                    { model.retryCapture(dump.id) }, { model.write { model.repository.undoCapture(dump.id) } },
                    { id, kind -> onClose(); openResult(id, kind) })
                QuietButton(onDelete, enabled = !busy) { Text("Delete capture") }
                if (!archived) ChoiceMenu("Create draft", DraftKind.entries, DraftKind::label, onDraft, Glyph.ADD)
                if (!archived && run == null) QuietButton({ model.retryCapture(dump.id); onClose() }, enabled = !busy) {
                    Text("Process this capture")
                }
            }
        },
        confirmButton = { QuietButton(onClose) { Text("Close") } },
        dismissButton = { QuietButton({
            model.write(onClose) {
                if (archived) model.repository.reopenDump(dump.id)
                else model.repository.release.dumpStatus(dump.id, DumpStatus.ARCHIVED)
            }
        }, enabled = !busy) { Text(if (archived) "Unarchive" else "Archive") } }
    )
}

@Composable
private fun PlaybackButton(file: File) {
    var status by remember { mutableStateOf("Play recording") }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    DisposableEffect(file) { onDispose { player[0]?.release(); player[0] = null } }
    QuietButton({
        if (player[0] != null) {
            player[0]?.release()
            player[0] = null
            status = "Play recording"
        } else {
            val media = MediaPlayer()
            try {
                media.setDataSource(file.absolutePath)
                media.setOnPreparedListener { it.start(); status = "Stop playback" }
                media.setOnCompletionListener { it.release(); player[0] = null; status = "Play recording" }
                media.setOnErrorListener { failed, _, _ ->
                    failed.release()
                    player[0] = null
                    status = "Recording unavailable"
                    true
                }
                player[0] = media
                status = "Loading recording"
                media.prepareAsync()
            } catch (_: Exception) {
                media.release()
                player[0] = null
                status = "Recording unavailable"
            }
        }
    }) { Text(status) }
}

@Composable
internal fun RecordingDialog(app: BrainApplication, face: SlimeFace, onClose: () -> Unit) {
    val controller = app.capture
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val state by controller.state.collectAsStateWithLifecycle()
    val run = voiceMemoRun(app, state.captureId)
    val start = {
        controller.start()
        if (controller.state.value.started != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    var permissionError by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) start() else permissionError = true
    }
    val elapsed by produceState(0L, state.started) {
        val started = state.started ?: return@produceState
        while (true) {
            value = (SystemClock.elapsedRealtime() - started) / 1_000
            delay(1_000)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.stop() }
    val close = {
        if (state.started != null) controller.stop()
        onClose()
    }
    BrainSheet(close) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.sm),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
                VoiceMemoSlime(face, state, run)
                if (state.started != null) Text(
                    "Recording · %d:%02d".format(elapsed / 60, elapsed % 60),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium
                ) else if (state.saving) Text("Saving recording…")
                else Text("Voice memo", style = MaterialTheme.typography.titleMedium)
            }
            if (run != null) VoiceMemoReceipt(app, run)
            else if (!state.saving && state.started == null) state.message?.let { Text(it) }
            if (permissionError) Text("Enable microphone access in Android app settings to record a voice memo.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                BrainButton({
                    when {
                        state.started != null -> controller.stop()
                        controller.canRetry -> controller.retrySave()
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                            permissionError = false
                            start()
                        }
                        else -> permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }, enabled = !state.saving) {
                    Text(when {
                        state.started != null -> "Stop and save"
                        controller.canRetry -> "Retry saving"
                        else -> "Start recording"
                    })
                }
                if (state.started != null) QuietButton(controller::cancel) { Text("Cancel recording") }
                else QuietButton(close) { Text("Close") }
            }
        }
    }
}

@Composable
internal fun AiSettings(model: BrainViewModel, onBack: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var planPrompt by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val credential by model.credential.collectAsStateWithLifecycle()
    val chatGpt by model.chatGptSettings.collectAsStateWithLifecycle()
    val action by model.chatGptAction.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appContext = context.applicationContext
    val launch: (Uri) -> Unit = { uri ->
        appContext.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    val manageUsage = { launch(Uri.parse("https://chatgpt.com/settings/usage")) }
    LaunchedEffect(Unit) { model.loadChatGpt() }
    planPrompt?.let { add ->
        AlertDialog(onDismissRequest = { planPrompt = null },
            title = { Text("Use your ChatGPT plan") },
            text = { Text("Check that credit use for other apps is off in ChatGPT Usage. Eligible requests use your included plan allowance. Your recording stays on this device.") },
            confirmButton = {
                QuietButton({
                    planPrompt = null
                    model.signInWithChatGpt(add, launch)
                }) { Text("Continue with ChatGPT") }
            },
            dismissButton = { QuietButton(manageUsage) { Text("Manage usage") } })
    }
    if (chatGpt.notice) {
        AlertDialog(onDismissRequest = model::dismissChatGptNotice,
            title = { Text("You’re using your ChatGPT plan") },
            text = { Text("Eligible organization requests use your plan. You can review and limit usage in ChatGPT settings.") },
            confirmButton = { QuietButton(model::dismissChatGptNotice) { Text("Got it") } },
            dismissButton = { QuietButton(manageUsage) { Text("Manage usage") } })
    }
    LibraryGrid {
        fullWidth("AI title") { ScreenHeader("AI Intelligence", onBack) }
        fullWidth("ChatGPT plan") {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("ChatGPT plan", style = MaterialTheme.typography.titleMedium)
                Text("Second Brain transcribes voice memos on this device and sends text and relevant workspace details for organization.")
                chatGpt.active?.let { account ->
                    Text(account.label)
                    Text(when {
                        !chatGpt.usingSubscription -> "ChatGPT plan is paused"
                        account.planAllowed -> "Using ChatGPT plan"
                        else -> "ChatGPT plan use requires reconnection"
                    })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (chatGpt.active?.planAllowed != true || !chatGpt.usingSubscription) {
                        BrainButton({ planPrompt = false }, enabled = !action.busy) {
                            Text(if (chatGpt.active == null) "Continue with ChatGPT" else "Reconnect ChatGPT")
                        }
                    }
                    QuietButton(manageUsage) { Text("Manage usage") }
                    if (action.busy) QuietButton(model::cancelChatGptAction) { Text("Cancel") }
                    if (chatGpt.active != null) {
                        QuietButton({ planPrompt = true }, enabled = !action.busy) { Text("Add account") }
                        QuietButton(model::signOutOfChatGpt, enabled = !action.busy) { Text("Sign out") }
                    }
                }
                chatGpt.accounts.filterNot { it.id == chatGpt.active?.id }.forEach { account ->
                    QuietButton({ model.selectChatGptAccount(account.id) }, enabled = !action.busy) {
                        Text("Use ${account.label}")
                    }
                }
                if (chatGpt.usingSubscription && chatGpt.active?.planAllowed == true) {
                    if (chatGpt.modelChoices.isEmpty()) {
                        QuietButton(model::showChatGptModels, enabled = !action.busy) { Text("Show available models") }
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            val selected = chatGpt.selectedModel ?: chatGpt.modelChoices.first().slug
                            chatGpt.modelChoices.forEach { choice ->
                                QuietButton({ model.selectChatGptModel(choice.slug) }, enabled = !action.busy && choice.slug != selected) {
                                    Text(choice.label)
                                }
                            }
                        }
                    }
                }
                action.message?.let { Text(it) }
            }
        }
        fullWidth("key") {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (chatGpt.usingSubscription) {
                    QuietButton(model::useOpenAiKey, enabled = !action.busy) { Text("Switch to API key mode") }
                    return@Column
                }
                Text("OpenAI API key", style = MaterialTheme.typography.titleMedium)
                Text(if (credential.configured) "Using your API key for organization." else "AI organization is off. Your recordings and transcripts are still saved.")
                BrainField(key, { key = it.take(500) }, label = { Text("OpenAI API key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrainButton({ model.saveCredential(key); key = "" }, enabled = key.isNotBlank() && !credential.busy) {
                        Text("Save API key")
                    }
                    if (credential.configured) QuietButton(model::removeCredential, enabled = !credential.busy) {
                        Text("Remove key")
                    }
                }
                credential.message?.let { Text(it) }
            }
        }
    }
}
