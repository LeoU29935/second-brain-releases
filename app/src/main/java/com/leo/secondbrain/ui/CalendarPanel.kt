package com.leo.secondbrain.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import com.leo.secondbrain.data.EntryKind
import com.leo.secondbrain.data.Event
import com.leo.secondbrain.data.Repeat
import com.leo.secondbrain.data.TaggedEntry
import com.leo.secondbrain.data.scheduleOrder
import com.leo.secondbrain.data.scheduleTimeLabel
import com.leo.secondbrain.data.occursOn
import com.leo.secondbrain.data.scheduleYears
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

private enum class CalendarMode(val label: String) {
    WEEK("Week"), UPCOMING("Upcoming"), MONTH("Month")
}

private data class AgendaDay(val day: LocalDate, val tasks: List<TaggedEntry>, val events: List<Event>) {
    val ordered = (events.map(AgendaOccurrence::Meeting) + tasks.map(AgendaOccurrence::Task))
        .sortedWith(compareBy({ it.order }, { it.key }))
}

private sealed interface AgendaOccurrence {
    val key: String
    val order: Int

    data class Task(val task: TaggedEntry) : AgendaOccurrence {
        override val key = "task:${task.entry.id}"
        override val order = scheduleOrder(task.entry.dueMinute, task.entry.timeOfDay)
    }

    data class Meeting(val event: Event) : AgendaOccurrence {
        override val key = "event:${event.id}"
        override val order = scheduleOrder(event.startMinute, event.timeOfDay)
    }
}

@Composable
internal fun CalendarPanel(
    state: LibraryState, today: LocalDate, selected: LocalDate, onDay: (LocalDate) -> Unit, onClose: () -> Unit,
    newTask: (LocalDate) -> Unit, newEvent: (LocalDate) -> Unit, onTask: (TaggedEntry) -> Unit,
    onComplete: (TaggedEntry, Boolean) -> Unit, onEvent: (Event) -> Unit, onTasks: () -> Unit,
    updateVersion: String?, onUpdate: () -> Unit, onOccurrence: (String, Long) -> Unit, snackbar: SnackbarHostState
) {
    var mode by rememberSaveable { mutableStateOf(CalendarMode.UPCOMING) }
    var seriesId by rememberSaveable { mutableStateOf<String?>(null) }
    var occurrence by rememberSaveable { mutableStateOf<Long?>(null) }
    val firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    fun LocalDate.weekStart() = minusDays(((dayOfWeek.value - firstDay.value + 7) % 7).toLong())
    val days = remember(mode, selected, firstDay) {
        val start = if (mode == CalendarMode.MONTH) selected.withDayOfMonth(1).weekStart() else selected.minusDays(3)
        List(if (mode == CalendarMode.MONTH) 42 else 7) { start.plusDays(it.toLong()) }
    }
    val tasks = remember(state.entries) { state.entries.filter { it.entry.kind == EntryKind.TASK && it.entry.deadline != null } }
    fun agenda(day: LocalDate): AgendaDay {
        val epoch = day.toEpochDay()
        return AgendaDay(day, tasks.filter { it.entry.occursOn(day, state.recurrenceSkips[it.entry.id].orEmpty()) }
            .sortedWith(compareBy({ scheduleOrder(it.entry.dueMinute, it.entry.timeOfDay) }, { -it.entry.priority.ordinal }, { it.entry.id })),
            state.events.filter { it.covers(epoch) }.sortedWith(compareBy({ scheduleOrder(it.startMinute, it.timeOfDay) }, { it.title })))
    }
    val grid = remember(days, tasks, state.events, state.recurrenceSkips) { days.associateWith(::agenda) }
    val overdue = remember(today, tasks) {
        tasks.filter { !it.entry.completed && (it.entry.deadline ?: Long.MAX_VALUE) < today.toEpochDay() }
    }
    val visible = remember(mode, selected, today, tasks, state.events, state.recurrenceSkips) {
        if (mode == CalendarMode.UPCOMING) {
            val start = maxOf(today, selected)
            List(30) { agenda(start.plusDays(it.toLong())) }.filter { it.tasks.isNotEmpty() || it.events.isNotEmpty() }
        } else listOf(agenda(selected))
    }
    val scroll = rememberLazyListState()
    LaunchedEffect(selected, mode) { scroll.scrollToItem(0) }
    val appear = remember { Animatable(0f) }
    val direction = remember { mutableIntStateOf(1) }
    val gridProgress = remember { Animatable(1f) }
    val monthOrWeek = if (mode == CalendarMode.MONTH) selected.withDayOfMonth(1) else selected
    LaunchedEffect(mode, monthOrWeek) {
        gridProgress.snapTo(0f)
        gridProgress.animateTo(1f, Motion.glide())
    }
    LaunchedEffect(Unit) { appear.animateTo(1f, Motion.settle()) }
    val close by rememberUpdatedState(onClose)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val dismissPanel = {
        if (!closing) {
            closing = true
            scope.launch { appear.animateTo(0f, Motion.glide(Motion.QUICK)); close() }
        }
    }
    val currentDismiss by rememberUpdatedState(dismissPanel)
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val settleDrag by androidx.compose.animation.core.animateFloatAsState(dragDistance,
        Motion.press(), label = "Calendar drag")
    val dismiss = Modifier.pointerInput(Unit) {
        var distance = 0f
        detectVerticalDragGestures(onDragStart = { distance = 0f },
            onVerticalDrag = { _, delta ->
                distance += delta
                dragDistance = distance.coerceIn(0f, 82.dp.toPx())
            },
            onDragEnd = {
                if (distance > 27.dp.toPx()) currentDismiss()
                dragDistance = 0f
            }, onDragCancel = { dragDistance = 0f })
    }

    Dialog(onDismissRequest = dismissPanel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
        BoxWithConstraints(Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 14.dp, end = 14.dp, top = 12.dp),
            contentAlignment = Alignment.BottomCenter) {
            val compact = maxHeight < 620.dp
            val cellSize = if (compact) 34.dp else 41.dp
            val colors = MaterialTheme.colorScheme
            Surface(
                Modifier.widthIn(max = 525.dp).fillMaxWidth().height(maxHeight.coerceAtMost(665.dp))
                    .testTag("calendar-frame").graphicsLayer {
                    alpha = appear.value.coerceIn(0f, 1f)
                    translationY = (1f - appear.value) * 66.dp.toPx() + settleDrag
                }, shape = MaterialTheme.shapes.extraLarge, color = colors.surfaceContainer,
                border = BorderStroke(1.dp, colors.outlineVariant), shadowElevation = 8.dp
            ) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp)) {
                    Column(dismiss.fillMaxWidth().testTag("calendar-grip"), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.padding(vertical = 4.dp).size(31.dp, 4.dp)
                            .background(colors.outlineVariant, CircleShape))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(selected.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault()),
                                    style = MaterialTheme.typography.titleMedium, maxLines = 2)
                                Text(selected.year.toString(), style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant)
                            }
                            fun adjust(amount: Long) {
                                direction.intValue = if (amount > 0) 1 else -1
                                val next = when (mode) {
                                    CalendarMode.MONTH -> selected.plusMonths(amount)
                                    CalendarMode.WEEK -> selected.plusWeeks(amount)
                                    CalendarMode.UPCOMING -> selected.plusDays(30 * amount)
                                }
                                if (next.year in scheduleYears) onDay(next)
                            }
                            val unit = when (mode) {
                                CalendarMode.MONTH -> "month"
                                CalendarMode.WEEK -> "week"
                                CalendarMode.UPCOMING -> "30 days"
                            }
                            CalendarArrow(true, "Previous $unit") { adjust(-1) }
                            CalendarArrow(false, "Next $unit") { adjust(1) }
                            ActionIcon(Glyph.CLOSE, "Close calendar", dismissPanel)
                        }
                    }
                    Row(Modifier.fillMaxWidth().background(colors.surfaceContainerHigh, CircleShape).padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        CalendarMode.entries.forEach { option ->
                            val active = option == mode
                            val fill by animateColorAsState(
                                if (active) colors.primaryContainer else colors.surfaceContainerHigh,
                                tween(Motion.QUICK), label = "Calendar mode"
                            )
                            Box(Modifier.weight(1f).heightIn(min = Space.touch).clip(CircleShape)
                                .background(fill)
                                .selectable(active, role = Role.Tab, onClick = { mode = option }),
                                contentAlignment = Alignment.Center) {
                                Text(option.label, style = MaterialTheme.typography.labelLarge,
                                    maxLines = 2, textAlign = TextAlign.Center,
                                    color = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant)
                            }
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("calendar-agenda"), state = scroll,
                        contentPadding = PaddingValues(bottom = bottomInset + 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (mode != CalendarMode.UPCOMING) item("calendar grid") {
                            Column(Modifier.graphicsLayer {
                                translationX = (1f - gridProgress.value) * direction.intValue * 22.dp.toPx()
                                alpha = .73f + .27f * gridProgress.value
                            }) {
                                Row(Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 2.dp)) {
                                    days.take(7).forEach {
                                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                            Text(it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                                                style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                        }
                                    }
                                }
                                days.chunked(7).forEach { week ->
                                    Row(Modifier.fillMaxWidth()) {
                                        week.forEach { date ->
                                            val marked = grid[date]?.let { it.tasks.isNotEmpty() || it.events.isNotEmpty() } == true
                                            Box(Modifier.weight(1f).height(cellSize), contentAlignment = Alignment.Center) {
                                                CalendarDateCell(date, selected, today, marked, onDay)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item("schedule controls") {
                            Column {
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    val heading = if (mode == CalendarMode.UPCOMING) "Next 30 days"
                                        else if (selected == today) "Today" else selected.displayDate()
                                    Text(heading, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    ActionIcon(Glyph.CLOCK, "Return to today", { onDay(today) }, enabled = selected != today)
                                    ActionIcon(Glyph.ADD, "New scheduled task", { newTask(selected) })
                                    ActionIcon(Glyph.CALENDAR, "New event", { newEvent(selected) })
                                }
                            }
                        }
                        if (visible.isEmpty()) item("empty upcoming") {
                            QuietEmpty(Glyph.CALENDAR, "Nothing scheduled in the next 30 days")
                        }
                        visible.forEach { date ->
                            val epoch = date.day.toEpochDay()
                            if (mode == CalendarMode.UPCOMING) item("day:$epoch") {
                                CalendarSectionHeader(relativeCalendarDate(date.day, today), date.day.displayDate())
                            }
                            if (date.events.isEmpty() && date.tasks.isEmpty()) item("empty:$epoch") {
                                QuietEmpty(Glyph.CALENDAR, "Nothing scheduled")
                            }
                            items(date.ordered, key = { "$epoch:${it.key}" }) { item ->
                                when (item) {
                                    is AgendaOccurrence.Meeting -> CompactEvent(item.event) { onEvent(item.event) }
                                    is AgendaOccurrence.Task -> Box(Modifier.animateItem()) {
                                        val task = item.task
                                        CompactTask(task, epoch, {
                                            if (task.entry.repeat != Repeat.NONE && !task.entry.completed) {
                                                seriesId = task.entry.id
                                                occurrence = epoch
                                            } else onTask(task)
                                        }) { onComplete(task, it) }
                                    }
                                }
                            }
                        }
                        if (overdue.isNotEmpty() && (selected == today || mode == CalendarMode.UPCOMING)) {
                            item("overdue header") { CalendarSectionHeader("Overdue", countLabel(overdue.size, "task")) }
                            items(overdue, key = { "overdue:${it.entry.id}" }) {
                                CompactTask(it, it.entry.deadline, { onTask(it) }, { checked -> onComplete(it, checked) })
                            }
                        }
                        item("calendar footer") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                QuietButton(onTasks) { Text("All tasks") }
                                if (updateVersion != null) QuietButton(onUpdate) { Text("App update available") }
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset))
        }
    }
    val series = tasks.firstOrNull { it.entry.id == seriesId }
    val date = occurrence
    if (series != null && date != null) {
        val reset = { seriesId = null; occurrence = null }
        BrainDialog(onDismissRequest = reset, title = { Text(series.entry.title) },
            text = { Text("Edit the whole series or separate the ${LocalDate.ofEpochDay(date).displayDate()} occurrence.") },
            confirmButton = { QuietButton({ reset(); onOccurrence(series.entry.id, date) }) { Text("Edit this occurrence") } },
            dismissButton = { QuietButton({ reset(); onTask(series) }) { Text("Edit series") } })
    }
}

@Composable
private fun CalendarDateCell(date: LocalDate, selected: LocalDate, today: LocalDate, marked: Boolean,
    onDay: (LocalDate) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val active = date == selected
    val current = date == today
    val label = date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")) + if (marked) ", scheduled items" else ""
    val background by animateColorAsState(if (active) colors.primaryContainer else colors.surfaceContainer,
        Motion.glide(Motion.QUICK), label = "Selected day")
    val selectedScale by androidx.compose.animation.core.animateFloatAsState(if (active) 1f else .94f,
        Motion.press(), label = "Selected day settle")
    Surface(
        modifier = Modifier.fillMaxSize().padding(2.dp).semantics { contentDescription = label }
            .clip(CircleShape).selectable(active, enabled = date.year in scheduleYears,
                role = Role.Button, onClick = { onDay(date) }),
        shape = CircleShape, color = background,
        border = if (current && !active) BorderStroke(1.dp, colors.primary) else null
    ) {
        Box(Modifier.graphicsLayer { scaleX = selectedScale; scaleY = selectedScale }, contentAlignment = Alignment.Center) {
            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium,
                color = when {
                    active -> colors.onPrimaryContainer
                    date.month != selected.month -> colors.onSurfaceVariant.copy(alpha = .65f)
                    else -> colors.onSurface
                })
            if (marked) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp)
                .size(3.dp).background(colors.primary, CircleShape))
        }
    }
}

@Composable
private fun CalendarSectionHeader(title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CalendarArrow(back: Boolean, label: String, onClick: () -> Unit) {
    IconButton(onClick) {
        BrainIcon(Glyph.NEXT, Modifier.size(22.dp).graphicsLayer { scaleX = if (back) -1f else 1f },
            description = label)
    }
}

@Composable
private fun CompactEvent(event: Event, onOpen: () -> Unit) {
    val menu = LocalWorkspaceMenu.current
    val item = WorkspaceItem(event.id, event.title, com.leo.secondbrain.data.TrashKind.EVENT, event.collectionId)
    ContextCard(onOpen = onOpen, menu = { close -> WorkspaceMenuOptions(item, close, onOpen) }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BrainIcon(Glyph.CALENDAR, tint = MaterialTheme.colorScheme.tertiary)
            Column(Modifier.weight(1f)) {
                Text(event.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (event.allDay) "All-day event" else listOfNotNull("Event",
                    scheduleTimeLabel(event.startMinute, event.timeOfDay)).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun CompactTask(tagged: TaggedEntry, day: Long?, onOpen: () -> Unit, onComplete: (Boolean) -> Unit) {
    val task = tagged.entry
    val planned = task.deadline != day
    val item = WorkspaceItem(task.id, task.title, com.leo.secondbrain.data.TrashKind.TASK, task.collectionId)
    val completion by androidx.compose.animation.core.animateFloatAsState(if (task.completed) 1f else 0f,
        Motion.settle(), label = "Task completion")
    ContextCard(modifier = Modifier.graphicsLayer {
        alpha = 1f - .27f * completion.coerceIn(0f, 1f)
        scaleY = 1f - .035f * completion
    }, onOpen = onOpen, menu = { close -> WorkspaceMenuOptions(item, close, onOpen) }) {
        Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (planned) Box(Modifier.size(Space.touch), contentAlignment = Alignment.Center) {
                BrainIcon(Glyph.TASK, tint = MaterialTheme.colorScheme.secondary)
            } else TaskCompletion(task.completed, onComplete, Modifier.semantics {
                contentDescription = if (task.completed) "Reopen ${task.title}" else "Complete ${task.title}"
            })
            Column(Modifier.weight(1f).padding(vertical = if (task.completed) 3.dp else 7.dp)) {
                Text(task.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!task.completed) Text(listOfNotNull(if (planned) "Recurring task" else "Task",
                    scheduleTimeLabel(task.dueMinute, task.timeOfDay)).joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                task.scheduleChange?.takeUnless { task.completed }?.let {
                    com.leo.secondbrain.data.ScheduleMove.read(it)?.let { move ->
                        Text(move.reason, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
