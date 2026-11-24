package com.leo.secondbrain.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Checkbox
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leo.secondbrain.data.EntryKind
import com.leo.secondbrain.data.Event
import com.leo.secondbrain.data.Priority
import com.leo.secondbrain.data.TaggedEntry
import com.leo.secondbrain.data.TrashKind
import com.leo.secondbrain.data.minuteLabel
import com.leo.secondbrain.data.plainBody
import java.time.LocalDate

@Composable
internal fun CollectionItemCard(
    tagged: TaggedEntry, onOpen: () -> Unit, onComplete: (Boolean) -> Unit, modifier: Modifier,
    selection: Boolean? = null, onSelect: (() -> Unit)? = null
) {
    val entry = tagged.entry
    val task = entry.kind == EntryKind.TASK
    val date = entry.deadline?.let { day ->
        listOfNotNull(LocalDate.ofEpochDay(day).scheduleDate(),
            entry.dueMinute?.let(::minuteLabel) ?: entry.timeOfDay?.label).joinToString(" · ")
    }
    CollectionItemCard(
        WorkspaceItem(entry.id, entry.title, if (task) TrashKind.TASK else TrashKind.NOTE, entry.collectionId),
        entry.body, onOpen, modifier, date = date,
        due = entry.hardDeadline?.let { "Due ${LocalDate.ofEpochDay(it).scheduleDate()}" },
        priority = entry.priority, secondary = tagged.tags.take(2).joinToString(" · ") { it.name },
        completed = entry.completed, onComplete = onComplete.takeIf { task }, selection = selection, onSelect = onSelect
    )
}

@Composable
internal fun CollectionItemCard(event: Event, onOpen: () -> Unit, modifier: Modifier,
    selection: Boolean? = null, onSelect: (() -> Unit)? = null) {
    val date = listOfNotNull(LocalDate.ofEpochDay(event.startDay).scheduleDate(),
        if (event.allDay) "All day" else event.startMinute?.let(::minuteLabel) ?: event.timeOfDay?.label).joinToString(" · ")
    CollectionItemCard(WorkspaceItem(event.id, event.title, TrashKind.EVENT, event.collectionId),
        event.body, onOpen, modifier, date = date, secondary = event.location, selection = selection, onSelect = onSelect)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollectionItemCard(
    item: WorkspaceItem, body: String, onOpen: () -> Unit, modifier: Modifier, date: String?,
    due: String? = null, priority: Priority = Priority.NONE, secondary: String = "",
    completed: Boolean = false, onComplete: ((Boolean) -> Unit)? = null,
    selection: Boolean? = null, onSelect: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val event = item.kind == TrashKind.EVENT
    val titleStyle = MaterialTheme.typography.titleMedium
    val background by animateColorAsState(when {
        selection == true -> colors.primaryContainer.copy(alpha = .55f)
        event -> colors.secondaryContainer.copy(alpha = .27f)
        else -> colors.surfaceContainerLow
    }, label = "Item selection")
    ContextCard(modifier = modifier, shape = MaterialTheme.shapes.large,
        color = background,
        border = if (selection == true) BorderStroke(1.dp, colors.primary) else cardBorder(),
        interactionModifier = Modifier.semantics { if (selection != null) selected = selection },
        onOpen = if (selection == null) onOpen else requireNotNull(onSelect), onSelect = onSelect,
        menu = { close -> WorkspaceMenuOptions(item, close, onOpen) }) {
        val cornerCompletion = onComplete?.takeIf { selection == null }
        Box(Modifier.fillMaxWidth().heightIn(min = if (cornerCompletion != null) Space.touch + 7.dp else 0.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, Modifier.weight(1f).padding(end = if (cornerCompletion != null) 20.dp else 0.dp),
                        style = titleStyle, maxLines = if (cornerCompletion != null) 3 else 2, overflow = TextOverflow.Ellipsis,
                        color = if (completed) colors.onSurfaceVariant else colors.onSurface,
                        textDecoration = if (completed) TextDecoration.LineThrough else null)
                    if (selection != null) {
                        Checkbox(selection, onCheckedChange = null, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (body.isNotBlank()) {
                    Text(body.plainBody().trim(), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (date != null || due != null || priority != Priority.NONE) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (date != null) CardMetadata(date, if (event) Glyph.CALENDAR else Glyph.CLOCK,
                            if (event) colors.secondary else colors.onSurfaceVariant)
                        if (priority != Priority.NONE) CardMetadata(priority.label, Glyph.FLAG,
                            if (priority == Priority.HIGH) colors.error else colors.onSurfaceVariant)
                        if (due != null) CardMetadata(due, Glyph.CALENDAR, colors.tertiary)
                    }
                }
                if (secondary.isNotBlank()) {
                    Text(secondary, style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (cornerCompletion != null) {
                TaskCompletion(completed, cornerCompletion,
                    Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 8.dp).semantics {
                        contentDescription = if (completed) "Reopen ${item.title}" else "Complete ${item.title}"
                    }, indicatorSize = 19.dp, indicatorAlignment = Alignment.TopEnd)
            }
        }
    }
}

@Composable
private fun CardMetadata(text: String, glyph: Glyph, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        BrainIcon(glyph, Modifier.size(11.dp), tint = color)
        Text(text, style = MaterialTheme.typography.labelSmall, color = color,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
