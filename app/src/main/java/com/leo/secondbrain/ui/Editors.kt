package com.leo.secondbrain.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leo.secondbrain.data.CollectionEntity
import com.leo.secondbrain.data.canPlaceFolder
import com.leo.secondbrain.data.asProposal
import com.leo.secondbrain.data.DraftKind
import com.leo.secondbrain.data.Proposal
import com.leo.secondbrain.data.Repeat
import com.leo.secondbrain.data.scheduleYears
import com.leo.secondbrain.data.EntryEntity
import com.leo.secondbrain.data.EntryKind
import com.leo.secondbrain.data.Event
import com.leo.secondbrain.data.Priority
import com.leo.secondbrain.data.TagEntity
import com.leo.secondbrain.data.TaggedEntry
import com.leo.secondbrain.data.convertTo
import com.leo.secondbrain.data.newId
import com.leo.secondbrain.data.pathName
import com.leo.secondbrain.ui.theme.accentColor
import com.leo.secondbrain.ui.theme.accents
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
internal fun <T> ChoiceMenu(
    label: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onChoice: (T) -> Unit,
    glyph: Glyph,
    modifier: Modifier = Modifier
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        ControlButton(label, glyph, { open = true }, expands = true)
        DropdownMenu(open, { open = false },
            modifier = Modifier.widthIn(min = 172.dp, max = 320.dp),
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = Space.sm) {
            options.forEach { option ->
                val selected = optionLabel(option) == label
                DropdownMenuItem(
                    modifier = Modifier.padding(horizontal = Space.xs).clip(MaterialTheme.shapes.small)
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .semantics { this.selected = selected },
                    trailingIcon = if (selected) ({ BrainIcon(Glyph.CHECK, tint = MaterialTheme.colorScheme.primary) }) else null,
                    text = { Text(optionLabel(option), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        onChoice(option)
                    }
                )
            }
        }
    }
}

@Composable
internal fun ItemEditor(
    id: String, tagged: TaggedEntry?, event: Event?, kind: DraftKind, defaultCollection: String?,
    defaultDeadline: Long?, fallbackDay: Long,
    state: LibraryState, model: BrainViewModel, busy: Boolean, onClose: () -> Unit,
    openForEdit: Boolean = false
) {
    val current = tagged?.asProposal()
        ?: event?.asProposal(state.eventTags.filter { it.eventId == id }.map { it.tagId }.toSet())
    val initial = remember(id) {
        model.app.edits.proposal(id) ?: tagged?.asProposal()
            ?: event?.asProposal(state.eventTags.filter { it.eventId == id }.map { it.tagId }.toSet())
            ?: if (kind == DraftKind.EVENT) {
                val start = defaultDeadline ?: fallbackDay
                Proposal(kind, "", collectionId = defaultCollection, startDay = start, endDay = start)
            } else Proposal(kind, "", collectionId = defaultCollection, startDay = defaultDeadline)
    }
    var encoded by rememberSaveable(id) { mutableStateOf(initial.json().toString()) }
    val item = remember(encoded) { Proposal.read(org.json.JSONObject(encoded), validate = false) }
    val existing = remember(id) { tagged != null || event != null }
    var started by rememberSaveable(id) { mutableStateOf(existing || initial.title.isNotBlank() || initial.body.isNotBlank()) }
    var addingTag by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable(id) { mutableStateOf(!existing || openForEdit) }
    if (!editing && current != null) {
        ItemDetails(current, tagged?.entry, state, onClose, {
            encoded = current.json().toString()
            editing = true
        }, onChecklistChange = { body ->
            model.write { model.repository.saveEditorItem(id, current.copy(body = body), requireVersion = true) }
        }) { checked -> model.setCompleted(id, checked, tagged?.entry?.deadline) }
        return
    }

    fun change(value: Proposal) {
        val updated = if (value.kind == DraftKind.EVENT) {
            val start = value.startDay ?: fallbackDay
            value.copy(startDay = start, endDay = maxOf(start, value.endDay ?: start))
        } else value
        val edited = if (existing && updated.kind == DraftKind.TASK &&
            (updated.startDay == null || updated.startMinute == null || updated.reminder == null)) {
            updated.copy(inferredSchedule = null)
        } else updated
        encoded = edited.json().toString()
        if (started || updated.title.isNotBlank() || updated.body.isNotBlank() || updated.location.isNotBlank()) {
            started = true
            if (!existing) model.app.edits.put(id, edited)
        }
    }

    EditorLayout(
        heading = if (!existing) "New ${item.kind.label.lowercase()}" else "Edit ${item.kind.label.lowercase()}",
        busy = busy, onClose = { if (existing) editing = false else onClose() },
        onDelete = if (started) ({ model.deleteWithUndo(id, onDeleted = onClose) }) else null,
        actions = if (existing) ({
            QuietButton({ editing = false }, enabled = !busy) { Text("Cancel") }
            QuietButton({
                model.write({ editing = false }) { model.repository.saveEditorItem(id, item, requireVersion = true) }
            }, enabled = !busy && item.title.isNotBlank()) { Text("Save") }
        }) else null
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            listOf(DraftKind.TASK, DraftKind.EVENT, DraftKind.NOTE).forEach { choice ->
                val selectedType = item.kind == choice
                Surface(onClick = { change(item.convertTo(choice, fallbackDay)) },
                    modifier = Modifier.weight(1f).heightIn(min = Space.touch).semantics { selected = selectedType },
                    shape = MaterialTheme.shapes.medium,
                    color = if (selectedType) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                    Box(Modifier.padding(horizontal = Space.xs, vertical = Space.sm), contentAlignment = Alignment.Center) {
                        Text(choice.label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        ProposalFields(item, ::change, state, onNewTag = { addingTag = true })
        if (item.kind == DraftKind.TASK && tagged != null) {
            val entry = tagged.entry
            QuietButton({ model.setCompleted(id, !entry.completed, entry.deadline) }) {
                Text(if (entry.completed) "Reopen task" else "Complete task")
            }
            entry.scheduleChange?.takeUnless { entry.completed }?.let { change ->
                com.leo.secondbrain.data.ScheduleMove.read(change)?.let { move ->
                    Text(move.reason, style = MaterialTheme.typography.bodySmall)
                    if (!move.waiting) QuietButton({ model.write(onClose) { model.repository.undoScheduleMove(id, change) } }, enabled = !busy) {
                        Text("Undo move")
                    }
                }
            }
        }
        if (item.reminder != null) ReminderAccessHint(model)
    }
    if (addingTag) TagEditor(null, busy, { addingTag = false }, { tag ->
        model.write({
            val current = Proposal.read(org.json.JSONObject(encoded), validate = false)
            change(current.copy(tags = current.tags + tag.id))
            addingTag = false
        }) { model.repository.saveTag(tag) }
    })
}

@Composable
internal fun CollectionEditor(
    id: String, existing: CollectionEntity?, parent: String?, state: LibraryState,
    model: BrainViewModel, busy: Boolean, onClose: () -> Unit, onDeleted: () -> Unit
) {
    val initial = remember(id) {
        model.app.edits.collection(id) ?: existing
            ?: CollectionEntity(id, "", parentId = parent.takeIf { state.collections.canPlaceFolder(id, it) })
    }
    var name by rememberSaveable(id) { mutableStateOf(initial.name) }
    var color by rememberSaveable(id) { mutableStateOf(initial.color) }
    var icon by rememberSaveable(id) { mutableStateOf(initial.icon) }
    var parentId by rememberSaveable(id) { mutableStateOf(initial.parentId) }
    var started by rememberSaveable(id) { mutableStateOf(existing != null || initial.name.isNotBlank()) }
    var confirmDelete by rememberSaveable(id) { mutableStateOf(false) }
    fun persist() {
        if (started || name.isNotBlank()) {
            started = true
            model.app.edits.put(CollectionEntity(id, name.ifBlank { "Untitled collection" }, initial.description, color, parentId, icon))
        }
    }

    EditorLayout(if (existing == null) "New collection" else "Edit collection",
        busy = busy, onClose = onClose, onDelete = if (started) ({ confirmDelete = true }) else null) {
        PageField(name, { name = it; persist() }, "Collection name", singleLine = true)
        FieldLabel("Parent collection")
        val excluded = state.collections.filterNot { state.collections.canPlaceFolder(id, it.id) }.map { it.id }.toSet()
        CollectionPicker(state.collections, parentId, { parentId = it; persist() }, "Top level", excluded)
        FieldLabel("Icon")
        CollectionIconPicker(icon, { icon = it; persist() })
        FieldLabel("Color")
        ColorPicker(color, { color = it; persist() })
    }
    if (confirmDelete) BrainDialog(onDismissRequest = { if (!busy) confirmDelete = false },
        title = { Text("Delete ${name.ifBlank { "this folder" }}?") },
        text = { Text("This folder and its contents will move to Recently Deleted.") },
        dismissButton = { QuietButton({ confirmDelete = false }, enabled = !busy) { Text("Cancel") } },
        confirmButton = {
            BrainButton({
                model.deleteWithUndo(id, com.leo.secondbrain.data.TrashKind.COLLECTION) {
                    confirmDelete = false
                    onDeleted()
                }
            }, enabled = !busy) { Text("Delete") }
        })
}

@Composable
internal fun TagEditor(
    existing: TagEntity?,
    busy: Boolean,
    onClose: () -> Unit,
    onSave: (TagEntity) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val id = rememberSaveable { existing?.id ?: newId() }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(existing?.color ?: 0) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    if (deleting) {
        ConfirmAction("Delete “${existing?.name}”?", "This removes the tag from all notes and tasks.", "Delete",
            busy, { deleting = false }) { onDelete?.invoke() }
    } else {
        BrainDialog(
            onDismissRequest = { if (!busy) onClose() },
            title = { Text(if (existing == null) "New tag" else "Edit tag") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    BrainField(name, { name = it }, Modifier.fillMaxWidth(),
                        label = { Text("Tag name") }, singleLine = true)
                    FieldLabel("Color")
                    ColorPicker(color, { color = it })
                    if (onDelete != null) QuietButton({ deleting = true }, enabled = !busy) { Text("Delete tag") }
                }
            },
            confirmButton = {
                QuietButton({ onSave(TagEntity(id, name, color)) }, enabled = name.isNotBlank() && !busy) {
                    Text("Save")
                }
            },
            dismissButton = { QuietButton(onClose, enabled = !busy) { Text("Cancel") } }
        )
    }
}

@Composable
internal fun EditorLayout(
    heading: String,
    busy: Boolean,
    onClose: () -> Unit,
    onDelete: (() -> Unit)?,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissKeyboard = {
        focus.clearFocus()
        keyboard?.hide()
    }
    val back = {
        if (!busy) {
            dismissKeyboard()
            onClose()
        }
    }
    BackHandler(onBack = back)
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(135)) }
    Column(Modifier.fillMaxWidth().graphicsLayer {
        compositingStrategy = CompositingStrategy.ModulateAlpha
        alpha = entrance.value
    }) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 7.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            ActionIcon(Glyph.BACK, "Back", back, !busy)
            Text(heading, Modifier.weight(1f), style = if (actions == null) MaterialTheme.typography.headlineSmall
                else MaterialTheme.typography.titleLarge, maxLines = 2)
            actions?.invoke()
            if (onDelete != null) ActionIcon(Glyph.DELETE, "Delete", onDelete, !busy)
        }
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = Space.lg, vertical = Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm)
        ) { content() }
    }
}

@Composable
internal fun FieldLabel(title: String) {
    Text(title, Modifier.padding(top = 5.dp), style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun CollectionPicker(
    collections: List<CollectionEntity>,
    selected: String?,
    onChoice: (String?) -> Unit,
    root: String,
    excluded: Set<String> = emptySet()
) {
    val options = collections.filterNot { it.id in excluded }
        .map { it.id to collections.pathName(it.id) }.sortedBy { it.second.lowercase() }.toMap()
    ChoiceMenu(options[selected] ?: root, listOf<String?>(null) + options.keys,
        { options[it] ?: root }, onChoice, Glyph.FOLDER)
}

@Composable
private fun ColorPicker(selection: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        accents.forEachIndexed { index, accent ->
            val color = accentColor(index)
            Surface(
                onClick = { onSelect(index) },
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = accent.name
                    selected = index == selection
                },
                shape = CircleShape,
                color = Color.Transparent
            ) {
                Box(Modifier.padding(5.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        modifier = Modifier.size(35.dp), shape = CircleShape, color = color.copy(alpha = .18f),
                        border = BorderStroke(if (index == selection) 2.dp else 1.dp, color)
                    ) {
                        if (index == selection) Box(contentAlignment = Alignment.Center) {
                            BrainIcon(Glyph.CHECK, Modifier.size(18.dp), tint = color)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeadlinePicker(initial: Long?, onClose: () -> Unit, label: String = "Set deadline", onSelect: (Long) -> Unit) {
    val selected = initial ?: LocalDate.now().toEpochDay()
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = LocalDate.ofEpochDay(selected)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = scheduleYears
    )
    DatePickerDialog(
        onDismissRequest = onClose,
        confirmButton = {
            val millis = picker.selectedDateMillis
            QuietButton(
                { if (millis != null) onSelect(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()) },
                enabled = millis != null
            ) { Text(label) }
        },
        dismissButton = { QuietButton(onClose) { Text("Cancel") } }
    ) {
        DatePicker(picker, modifier = Modifier.verticalScroll(rememberScrollState()))
    }
}

@Composable
internal fun PageField(value: String, onChange: (String) -> Unit, label: String,
    singleLine: Boolean = false, minLines: Int = 1) {
    BrainField(value, onChange, label = { Text(label) },
        textStyle = if (label == "Title") MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
        singleLine = singleLine, minLines = minLines, maxLines = if (singleLine) 1 else 16)
}
