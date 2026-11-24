package com.leo.secondbrain.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.leo.secondbrain.data.DraftKind
import com.leo.secondbrain.data.Event
import com.leo.secondbrain.data.Proposal
import com.leo.secondbrain.data.Repeat
import com.leo.secondbrain.data.TimeOfDay
import com.leo.secondbrain.data.asProposal
import com.leo.secondbrain.data.minuteLabel
import com.leo.secondbrain.data.toEvent
import com.leo.secondbrain.ui.theme.accentColor
import java.time.LocalDate

@Composable
internal fun ScheduleFields(proposal: Proposal, change: (Proposal) -> Unit) {
    val event = proposal.kind == DraftKind.EVENT
    var pick by rememberSaveable { mutableStateOf<String?>(null) }
    val window = proposal.flexibleSchedule
    FieldLabel("Schedule")
    if (window != null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Capsule(if (window.automatic) "Flexible" else "Held in place", MaterialTheme.colorScheme.primary, Glyph.CLOCK)
            Capsule("${window.duration} min", MaterialTheme.colorScheme.secondary)
        }
        if (window.afterEventId == null) {
            val from = LocalDate.ofEpochDay(window.firstDay).displayDate()
            val through = LocalDate.ofEpochDay(window.lastDay).displayDate()
            Text("${if (from == through) from else "$from – $through"} · ${minuteLabel(window.fromMinute)}–" +
                if (window.untilMinute == 1440) "24:00" else minuteLabel(window.untilMinute),
                style = MaterialTheme.typography.labelMedium)
        }
        window.deadlineDay?.let { Text("Deadline · ${LocalDate.ofEpochDay(it).displayDate()}",
            style = MaterialTheme.typography.labelMedium) }
    }
    if (event) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("All-day", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(proposal.allDay, {
                change(proposal.copy(allDay = !proposal.allDay, startMinute = null, endMinute = null,
                    timeOfDay = null, reminder = null))
            }, Modifier.semantics { contentDescription = "All-day" })
        }
        EventScheduleLine("Start", proposal.startDay, proposal.startMinute, proposal.allDay,
            { pick = "start" }, { pick = "startTime" })
        EventScheduleLine("End", proposal.endDay, proposal.endMinute, proposal.allDay || proposal.startMinute == null,
            { pick = "end" }, { pick = "endTime" })
        if (!proposal.allDay && (proposal.startMinute != null || proposal.endMinute != null)) {
            QuietButton({
                change(proposal.copy(startMinute = null, endMinute = null, reminder = null))
            }) { Text("Clear times") }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f, fill = false)) {
                ScheduleButton(proposal.startDay, if (window == null) "Set scheduled date" else "Choose a fixed date") { pick = "start" }
            }
            if (proposal.startDay != null) {
                ActionIcon(Glyph.CLOSE, "Clear scheduled date", {
                    change(proposal.copy(startDay = null, startMinute = null, reminder = null, repeat = Repeat.NONE, recurrenceRule = null,
                        flexibleSchedule = null, timeOfDay = null))
                })
            }
        }
        if (proposal.startDay != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f, fill = false)) {
                    ControlButton(proposal.startMinute?.let(::minuteLabel) ?: "Add time", Glyph.CLOCK, { pick = "startTime" })
                }
                if (proposal.startMinute != null) {
                    ActionIcon(Glyph.CLOSE, "Clear time", { change(proposal.copy(startMinute = null, flexibleSchedule = null,
                        reminder = proposal.reminder.takeIf { proposal.timeOfDay == null })) })
                }
            }
        }
    }
    if (!proposal.allDay && proposal.startDay != null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TimeOfDay.entries.forEach { period ->
                val selected = proposal.timeOfDay == period
                BrainChip(selected, {
                    change(if (selected) proposal.copy(timeOfDay = null, flexibleSchedule = null)
                    else proposal.copy(timeOfDay = period, startMinute = null, endMinute = null,
                        flexibleSchedule = null, reminder = null))
                }, { Text(period.label) })
            }
        }
    }
    if (event || proposal.startDay != null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceMenu(proposal.recurrenceRule?.label(proposal.repeat) ?: proposal.repeat.label,
                Repeat.entries, Repeat::label, {
                change(proposal.copy(repeat = it, recurrenceRule = proposal.recurrenceRule.takeIf { _ -> it == proposal.repeat }, flexibleSchedule = proposal.flexibleSchedule.takeIf { _ -> it == Repeat.NONE }))
            }, Glyph.CALENDAR)
            val offsets = listOf(null, 0, 5, 15, 30, 60, 120, 1440, 10080, proposal.reminder).distinct()
            if (proposal.startMinute != null || proposal.allDay || !event && proposal.timeOfDay == null) {
                ChoiceMenu(reminderLabel(proposal.reminder), offsets, ::reminderLabel, {
                    change(proposal.copy(reminder = it))
                }, Glyph.BELL)
            }
        }
    }
    if (!event) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val deadline = proposal.hardDeadline?.let { "Deadline · ${LocalDate.ofEpochDay(it).scheduleDate()}" }
            ControlButton(deadline ?: "Add deadline", Glyph.FLAG, { pick = "deadline" })
            if (proposal.hardDeadline != null) {
                ActionIcon(Glyph.CLOSE, "Clear deadline", { change(proposal.copy(hardDeadline = null)) })
            }
        }
    }
    if (pick == "deadline") {
        DeadlinePicker(proposal.hardDeadline, { pick = null }, label = "Choose deadline") { day ->
            change(proposal.copy(hardDeadline = day))
            pick = null
        }
    }
    if (pick == "start" || pick == "end") {
        val start = pick == "start"
        DeadlinePicker(if (start) proposal.startDay else proposal.endDay, { pick = null }, label = "Choose date") { day ->
            change(if (start) proposal.copy(startDay = day, flexibleSchedule = null) else proposal.copy(endDay = day))
            pick = null
        }
    }
    if (pick == "startTime" || pick == "endTime") {
        val start = pick == "startTime"
        ClockDialog(if (start) proposal.startMinute else proposal.endMinute, { pick = null }) { minute ->
            change(if (!event) proposal.copy(startMinute = minute, flexibleSchedule = null, timeOfDay = null)
            else if (start) {
                val end = minute + 60
                proposal.copy(startMinute = minute, timeOfDay = null,
                    endDay = (proposal.endDay ?: proposal.startDay)?.let { day ->
                        maxOf(day, (proposal.startDay ?: day) + end / 1440)
                    }, endMinute = proposal.endMinute ?: end % 1440)
            } else proposal.copy(endMinute = minute, timeOfDay = null))
            pick = null
        }
    }
}

@Composable
private fun EventScheduleLine(label: String, day: Long?, minute: Int?, allDay: Boolean,
    onDate: () -> Unit, onTime: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            ScheduleButton(day, "Choose date", onDate)
            if (!allDay) ControlButton(minute?.let(::minuteLabel) ?: "Choose time", Glyph.CLOCK, onTime)
        }
    }
}

@Composable
private fun ScheduleButton(day: Long?, label: String, onClick: () -> Unit) {
    ControlButton(day?.let { LocalDate.ofEpochDay(it).displayDate() } ?: label, Glyph.CALENDAR, onClick)
}

internal fun reminderLabel(minutes: Int?): String = when (minutes) {
    null -> "No reminder"
    0 -> "At the scheduled time"
    60 -> "1 hour before"
    120 -> "2 hours before"
    1440 -> "1 day before"
    10080 -> "1 week before"
    else -> "$minutes min before"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockDialog(initial: Int?, onClose: () -> Unit, onSelect: (Int) -> Unit) {
    val default = initial ?: 9 * 60
    val context = LocalContext.current
    val picker = rememberTimePickerState(default / 60, default % 60, android.text.format.DateFormat.is24HourFormat(context))
    BrainDialog(
        title = { Text("Choose time") },
        onDismissRequest = onClose,
        text = { TimeInput(picker) },
        confirmButton = { QuietButton({ onSelect(picker.hour * 60 + picker.minute) }) { Text("Choose time") } },
        dismissButton = { QuietButton(onClose) { Text("Cancel") } }
    )
}

@Composable
internal fun ProposalFields(proposal: Proposal, change: (Proposal) -> Unit, state: LibraryState,
    onNewTag: (() -> Unit)? = null) {
    PageField(proposal.title, { change(proposal.copy(title = it.take(180))) }, "Title", singleLine = true)
    RichBodyEditor(proposal.body, { change(proposal.copy(body = it)) },
        when (proposal.kind) {
            DraftKind.NOTE -> "Note"
            DraftKind.TASK -> "Details"
            DraftKind.EVENT -> "Description"
        }, minLines = if (proposal.kind == DraftKind.NOTE) 5 else 2)
    FieldLabel("Collection")
    CollectionPicker(state.collections, proposal.collectionId, { change(proposal.copy(collectionId = it)) }, "Inbox")
    if (proposal.kind == DraftKind.EVENT) {
        PageField(proposal.location, { change(proposal.copy(location = it.take(500))) }, "Location", singleLine = true)
    }
    if (proposal.kind != DraftKind.NOTE) ScheduleFields(proposal, change)
    proposal.flexibleSchedule?.afterEventId?.let { id ->
        Text(state.events.firstOrNull { it.id == id }?.let { "After ${it.title}" } ?: "Linked event unavailable",
            style = MaterialTheme.typography.labelMedium)
    }
    if (proposal.kind == DraftKind.TASK) {
        FieldLabel("Priority")
        ChoiceMenu(proposal.priority.label, com.leo.secondbrain.data.Priority.entries,
            { it.label }, { change(proposal.copy(priority = it)) }, Glyph.FLAG)
    }
    if (state.tags.isNotEmpty() || onNewTag != null) {
        FieldLabel("Tags")
        val selected = state.tags.filter { it.id in proposal.tags }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            selected.forEach { tag ->
                BrainChip(true, { change(proposal.copy(tags = proposal.tags - tag.id)) }, { Text(tag.name) },
                    leadingIcon = { BrainIcon(Glyph.TAG, tint = com.leo.secondbrain.ui.theme.accentColor(tag.color)) })
            }
        }
        val choices = state.tags.sortedBy { it.name.lowercase() }.map { it.id to it.name }.toMap()
        ChoiceMenu("Choose tag", choices.keys.toList() + listOfNotNull("".takeIf { onNewTag != null }),
            { id -> choices[id]?.let { name -> if (id in proposal.tags) "✓ $name" else name } ?: "New tag" },
            { id ->
                if (id.isEmpty()) onNewTag?.invoke()
                else change(proposal.copy(tags = if (id in proposal.tags) proposal.tags - id else proposal.tags + id))
            }, Glyph.TAG)
    }
}

@Composable
internal fun ReminderSettings(model: BrainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var notifications by rememberSaveable { mutableStateOf(model.app.reminders.notificationsEnabled) }
    var exact by rememberSaveable { mutableStateOf(model.app.reminders.exactAvailable) }
    fun refresh() {
        notifications = model.app.reminders.notificationsEnabled
        exact = model.app.reminders.exactAvailable
        model.refreshReminders()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    LibraryGrid {
        fullWidth("reminder heading") { ScreenHeader("Reminders", onBack) }
        fullWidth("notification access") {
            EmptyCard(Glyph.BELL, if (notifications) "Notifications enabled" else "Allow notifications",
                "Date-only tasks use 9:00 AM. All-day events start at midnight.") {
                QuietButton({
                    if (!notifications && Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                }) { Text(if (notifications) "Notification settings" else "Enable notifications") }
            }
        }
        if (Build.VERSION.SDK_INT >= 31) fullWidth("exact access") {
            EmptyCard(Glyph.CALENDAR, if (exact) "Precise reminders enabled" else "Reminders may arrive late",
                if (exact) "Android can limit closely spaced reminders in battery-saving modes."
                else "Android can delay reminders while the device is idle. Allow precise alarms for time-sensitive reminders.") {
                if (!exact) QuietButton({
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}")))
                }) { Text("Open alarms settings") }
            }
        }
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) fullWidth("battery restrictions") {
            Text("If reminders are late on a Galaxy phone, look for Second Brain under Settings → Battery → Background usage limits. Removing it from Sleeping apps or Deep sleeping apps can help.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ReminderAccessHint(model: BrainViewModel) {
    val context = LocalContext.current
    var allowed by rememberSaveable { mutableStateOf(model.app.reminders.notificationsEnabled) }
    var precise by rememberSaveable { mutableStateOf(model.app.reminders.exactAvailable) }
    var asked by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        allowed = model.app.reminders.notificationsEnabled
        precise = model.app.reminders.exactAvailable
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = model.app.reminders.notificationsEnabled
        model.refreshReminders()
    }
    if (!allowed) {
        Text("Enable Android notifications to receive this reminder.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        QuietButton({
            if (!asked && Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                asked = true
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }) { Text("Allow notifications") }
    } else if (!precise) {
        Text("Android may deliver this reminder late. For precise timing, allow alarms in Reminder settings.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
