package com.leo.secondbrain.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.leo.secondbrain.data.BrainDump
import com.leo.secondbrain.data.EntryKind
import com.leo.secondbrain.data.Event
import com.leo.secondbrain.data.TagEntity
import com.leo.secondbrain.data.TaggedEntry
import com.leo.secondbrain.data.plainBody

private val taskOrder = compareBy<TaggedEntry>(
    { it.entry.deadline ?: Long.MAX_VALUE }, { com.leo.secondbrain.data.scheduleOrder(it.entry.dueMinute, it.entry.timeOfDay) },
    { -it.entry.priority.ordinal }
).thenByDescending { it.entry.updatedAt }

@Composable
internal fun SearchScreen(
    state: LibraryState,
    query: String,
    onQuery: (String) -> Unit,
    onEntry: (TaggedEntry) -> Unit,
    onComplete: (TaggedEntry, Boolean) -> Unit,
    onEvent: (Event) -> Unit,
    onCollection: (String) -> Unit,
    onBack: () -> Unit,
    captures: List<BrainDump> = emptyList(),
    onCapture: (String) -> Unit = {}
) {
    var kind by rememberSaveable { mutableStateOf("All") }
    var tagId by rememberSaveable { mutableStateOf<String?>(null) }
    val collections = state.collections.associateBy { it.id }
    val names = collections.mapValues { (_, folder) ->
        generateSequence(folder) { current -> collections[current.parentId] }
            .distinctBy { it.id }.toList().asReversed().joinToString(" / ") { it.name }
    } + ("" to "Inbox")
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    val effectiveTag = tagId?.takeIf { id -> state.tags.any { it.id == id } }
    val results = state.entries.filter { tagged ->
        val entry = tagged.entry
        (kind == "All" || entry.kind.name.equals(kind, ignoreCase = true)) &&
            (effectiveTag == null || tagged.tags.any { it.id == effectiveTag }) &&
            words.all { word ->
                entry.title.contains(word, ignoreCase = true) ||
                    entry.body.plainBody().contains(word, ignoreCase = true) ||
                    names[entry.collectionId ?: ""]?.contains(word, ignoreCase = true) == true ||
                    tagged.tags.any { it.name.contains(word, ignoreCase = true) }
            }
    }
    val eventResults = if (kind == "All" || kind == "Event") state.events.filter { event ->
        val tags = state.eventTags.filter { it.eventId == event.id }.map { it.tagId }
        (effectiveTag == null || effectiveTag in tags) && words.all { word ->
            listOf(event.title, event.body.plainBody(), event.location, names[event.collectionId ?: ""].orEmpty())
                .any { it.contains(word, ignoreCase = true) } ||
                state.tags.any { it.id in tags && it.name.contains(word, ignoreCase = true) }
        }
    } else emptyList()
    val folders = if (kind in setOf("All", "Folder") && words.isNotEmpty() && effectiveTag == null) {
        state.collections.filter { folder -> words.all { word ->
            names[folder.id].orEmpty().contains(word, ignoreCase = true) || folder.description.contains(word, ignoreCase = true)
        } }
    } else emptyList()
    val captureResults = if (kind in setOf("All", "Capture") && words.isNotEmpty() && effectiveTag == null) {
        captures.filter { capture -> words.all { word ->
            capture.original.contains(word, ignoreCase = true) || capture.transcript?.contains(word, ignoreCase = true) == true
        } }
    } else emptyList()
    LibraryGrid {
        fullWidth("search title") { ScreenHeader("Search", onBack) }
        fullWidth("query") {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            BrainField(
                query, onQuery, Modifier.fillMaxWidth(), label = { Text("Search your workspace") },
                shape = androidx.compose.foundation.shape.CircleShape,
                singleLine = true,
                leadingIcon = { BrainIcon(Glyph.SEARCH) },
                trailingIcon = if (query.isEmpty()) null else {
                    { ActionIcon(Glyph.CLOSE, "Clear search", { onQuery("") }) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focus.clearFocus()
                    keyboard?.hide()
                })
            )
        }
        fullWidth("filters") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("All", "Note", "Task", "Event", "Folder", "Capture").forEach { filter ->
                    BrainChip(kind == filter, {
                        kind = filter
                        if (filter in setOf("Folder", "Capture")) tagId = null
                    }, { Text(if (filter == "All") filter else "${filter}s") })
                }
                if (state.tags.isNotEmpty() && kind !in setOf("Folder", "Capture")) {
                    val selectedTag = state.tags.firstOrNull { it.id == effectiveTag }
                    ChoiceMenu(
                        selectedTag?.name ?: "Filter by tag",
                        listOf<TagEntity?>(null) + state.tags,
                        { it?.name ?: "All tags" },
                        { tagId = it?.id },
                        Glyph.TAG
                    )
                }
            }
        }
        fullWidth("results") {
            SectionHeader("Results", countLabel(results.size + eventResults.size + folders.size + captureResults.size, "item"))
        }
        if (results.isEmpty() && eventResults.isEmpty() && folders.isEmpty() && captureResults.isEmpty()) fullWidth("no results") {
            QuietEmpty(Glyph.SEARCH, "No matching items")
        }
        folders.forEach { folder -> fullWidth("search folder:${folder.id}") {
            Surface(onClick = { onCollection(folder.id) }, shape = cardShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrainIcon(Glyph.FOLDER)
                    Column(Modifier.weight(1f)) {
                        Text(folder.name, style = MaterialTheme.typography.titleSmall)
                        Text("Folder · ${names[folder.parentId] ?: "Top level"}",
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        }
        entryCards(results, onEntry, onComplete, names)
        eventCards(eventResults, onEvent, names)
        captureResults.forEach { capture -> fullWidth("capture:${capture.id}") {
            Surface(onClick = { onCapture(capture.id) }, shape = cardShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BrainIcon(if (capture.recording == null) Glyph.INBOX else Glyph.MIC)
                    Text(capture.transcript.orEmpty().ifBlank { capture.original }, maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        } }
    }
}

private enum class TaskFilter(val label: String) {
    OPEN("Open"), COMPLETED("Completed"), ALL("All")
}

@Composable
internal fun TasksScreen(
    state: LibraryState,
    onCreate: () -> Unit,
    onEntry: (TaggedEntry) -> Unit,
    onComplete: (TaggedEntry, Boolean) -> Unit,
    onBack: () -> Unit
) {
    var filter by rememberSaveable { mutableStateOf(TaskFilter.OPEN) }
    val tasks = state.entries.filter { it.entry.kind == EntryKind.TASK }
    val visible = tasks.filter {
        when (filter) {
            TaskFilter.OPEN -> !it.entry.completed
            TaskFilter.COMPLETED -> it.entry.completed
            TaskFilter.ALL -> true
        }
    }.sortedWith(taskOrder)
    LibraryGrid {
        fullWidth("tasks header") {
            ScreenHeader("All tasks", onBack) { ActionIcon(Glyph.ADD, "New task", onCreate) }
        }
        fullWidth("task filters") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskFilter.entries.forEach { option ->
                    BrainChip(filter == option, { filter = option }, { Text(option.label) })
                }
            }
        }
        if (visible.isEmpty()) fullWidth("empty tasks") {
            QuietEmpty(Glyph.TASK, if (filter == TaskFilter.COMPLETED) "No completed tasks" else "No tasks here") {
                if (filter != TaskFilter.COMPLETED) QuietButton(onCreate) { Text("Create task") }
            }
        }
        entryCards(visible, onEntry, onComplete, state.collections.associate { it.id to it.name })
    }
}
