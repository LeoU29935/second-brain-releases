package com.leo.secondbrain.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.leo.secondbrain.data.DraftKind
import com.leo.secondbrain.data.EntryEntity
import com.leo.secondbrain.data.Priority
import com.leo.secondbrain.data.Proposal
import com.leo.secondbrain.data.Repeat
import com.leo.secondbrain.data.TimeOfDay
import com.leo.secondbrain.data.pathName
import com.leo.secondbrain.data.scheduleTimeLabel
import com.leo.secondbrain.ui.theme.accentColor
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ItemDetails(
    item: Proposal, task: EntryEntity?, state: LibraryState,
    onBack: () -> Unit, onEdit: () -> Unit, onChecklistChange: ((String) -> Unit)? = null,
    onCompletion: (Boolean) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(Glyph.BACK, "Back", onBack)
            Spacer(Modifier.weight(1f))
            QuietButton(onEdit) { Text("Edit") }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(item.title, style = MaterialTheme.typography.headlineMedium)
            if (task != null && item.kind == DraftKind.TASK) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TaskCompletion(task.completed, onCompletion)
                    Text(if (task.completed) "Completed" else "Open", style = MaterialTheme.typography.labelLarge)
                }
            }
            if (item.kind != DraftKind.NOTE) {
                val start = detailSchedule(item.startDay, item.startMinute, item.timeOfDay)
                val end = detailSchedule(item.endDay, item.endMinute, null)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Capsule(start ?: "Unscheduled", colors.primary, Glyph.CALENDAR)
                    if (item.allDay) Capsule("All day", colors.primary, Glyph.CLOCK)
                    if (item.kind == DraftKind.EVENT && end != null && end != start)
                        Capsule("Ends $end", colors.primary, Glyph.CLOCK)
                    val minutes = item.startMinute?.let { from -> item.endMinute?.let { to ->
                        ((item.endDay ?: 0) - (item.startDay ?: 0)) * 1440 + to - from
                    } }
                    if (minutes != null && minutes > 0) Capsule(durationLabel(minutes), colors.secondary, Glyph.CLOCK)
                    item.reminder?.let { Capsule(reminderLabel(it), colors.secondary) }
                    item.hardDeadline?.let { Capsule("Due ${LocalDate.ofEpochDay(it).scheduleDate()}", colors.tertiary, Glyph.FLAG) }
                    if (item.priority != Priority.NONE) Capsule("${item.priority.label} priority", colors.tertiary, Glyph.FLAG)
                    if (item.repeat != Repeat.NONE) Capsule(item.recurrenceRule?.label(item.repeat) ?: item.repeat.label, colors.secondary)
                    item.durationMinutes?.let { Capsule(durationLabel(it.toLong()), colors.secondary, Glyph.CLOCK) }
                    item.inferredSchedule?.takeIf(String::isNotBlank)?.let {
                        Capsule("Suggested " + it.replace(",", ", "), colors.secondary)
                    }
                    if (item.location.isNotBlank()) Capsule(item.location, colors.secondary)
                }
            }
            if (item.body.isNotBlank()) RichBodyText(item.body, onChecklistChange)
            val tags = state.tags.filter { it.id in item.tags }
            if (item.collectionId != null || tags.isNotEmpty()) FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item.collectionId?.let { Capsule(state.collections.pathName(it), colors.secondary, Glyph.FOLDER) }
                tags.forEach { Capsule(it.name, accentColor(it.color), Glyph.TAG) }
            }
        }
    }
}

private fun detailSchedule(day: Long?, minute: Int?, period: TimeOfDay?): String? =
    day?.let { listOfNotNull(LocalDate.ofEpochDay(it).displayDate(), scheduleTimeLabel(minute, period)).joinToString(" · ") }

private fun durationLabel(minutes: Long): String =
    if (minutes < 60) "$minutes min" else if (minutes % 60 == 0L) "${minutes / 60} hr"
    else "${minutes / 60} hr ${minutes % 60} min"
