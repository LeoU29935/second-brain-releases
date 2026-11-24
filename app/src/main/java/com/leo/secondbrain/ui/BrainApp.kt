package com.leo.secondbrain.ui

import android.content.Intent

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.leo.secondbrain.data.TrashKind
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.SnackbarResult
import com.leo.secondbrain.data.newId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import com.leo.secondbrain.data.EntryKind
import com.leo.secondbrain.data.DraftKind
import com.leo.secondbrain.data.Event
import com.leo.secondbrain.data.Preferences
import com.leo.secondbrain.data.TaggedEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.leo.secondbrain.reminders.OPEN_TODAY
import com.leo.secondbrain.reminders.ReminderTarget
import com.leo.secondbrain.capture.OPEN_CAPTURE
import com.leo.secondbrain.capture.QUICK_CALENDAR_ACTION
import com.leo.secondbrain.capture.QUICK_CALENDAR_DAY
import com.leo.secondbrain.capture.QUICK_CALENDAR_EDIT
import com.leo.secondbrain.data.validDay
import java.time.LocalDate

private enum class Tab(val label: String, val glyph: Glyph) {
    DUMP("Brain Dump", Glyph.INBOX), COLLECTIONS("Collections", Glyph.COLLECTIONS)
}

private enum class Panel { SETTINGS, TAGS, ENTRY, COLLECTION, EVENT, REMINDERS, AI, BACKUP, UPDATE, SEARCH, TASKS, TRASH, FAVORITES }

@Composable
internal fun BrainApp(model: BrainViewModel, preferences: Preferences, layout: WindowLayoutInfo?,
    launchRequest: Intent?, consumeLaunch: () -> Unit) {
    val state by model.library.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        model.checkUpdates()
        model.purgeExpiredItems()
    }
    val busy by model.busy.collectAsStateWithLifecycle()
    val update by model.update.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val undo by model.undo.collectAsStateWithLifecycle()
    LaunchedEffect(undo) {
        undo?.let { action ->
            val result = snackbar.showSnackbar(action.message, "Undo", duration = androidx.compose.material3.SnackbarDuration.Short)
            model.finishUndo(action, result == SnackbarResult.ActionPerformed)
        }
    }
    val journalError by model.app.edits.error.collectAsStateWithLifecycle()
    LaunchedEffect(journalError) {
        if (journalError && snackbar.showSnackbar("Couldn’t finish saving an edit.", "Retry") == SnackbarResult.ActionPerformed) {
            model.app.edits.retry()
        }
    }
    val holders = rememberSaveableStateHolder()
    val today by produceState(LocalDate.now()) {
        while (true) {
            value = LocalDate.now()
            delay(15_000)
        }
    }
    LaunchedEffect(error) {
        error?.let { message ->
            snackbar.showSnackbar(message)
            model.dismissError()
        }
    }
    var tab by rememberSaveable { mutableStateOf(Tab.COLLECTIONS) }
    var calendar by rememberSaveable { mutableStateOf(false) }
    var agendaDate by rememberSaveable { mutableStateOf<Long?>(null) }
    val agendaDay = agendaDate?.let(LocalDate::ofEpochDay) ?: today
    var panel by rememberSaveable { mutableStateOf<Panel?>(null) }
    var approvedResult by remember { mutableStateOf<Pair<String, DraftKind>?>(null) }
    var editorReturn by rememberSaveable { mutableStateOf<Panel?>(null) }
    var editorFrames by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var collectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var inbox by rememberSaveable { mutableStateOf(false) }
    var gridFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var gridInbox by rememberSaveable { mutableStateOf(false) }
    var focusFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var entryKind by rememberSaveable { mutableStateOf(EntryKind.NOTE) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingOccurrence by rememberSaveable { mutableStateOf<String?>(null) }
    var deadline by rememberSaveable { mutableStateOf<Long?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchCapture by rememberSaveable { mutableStateOf<String?>(null) }
    val searchableCaptures by model.dumps.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var visibleCollection by rememberSaveable { mutableStateOf<String?>(null) }
    var recording by rememberSaveable { mutableStateOf(false) }
    var workspaceMenu by remember { mutableStateOf<WorkspaceMenuRequest?>(null) }
    var aiReturn by rememberSaveable { mutableStateOf<Panel?>(Panel.SETTINGS) }
    val collection = state.collections.firstOrNull { it.id == collectionId }
    val screenMotion = remember { Animatable(1f) }
    LaunchedEffect(panel, tab, collectionId, inbox, preferences.simplifyMotion) {
        screenMotion.snapTo(if (preferences.simplifyMotion) 1f else 0f)
        if (!preferences.simplifyMotion) screenMotion.animateTo(1f, Motion.glide())
    }
    LaunchedEffect(pendingOccurrence, state.entries) {
        val id = pendingOccurrence
        if (id != null && state.entries.any { it.entry.id == id }) {
            editorReturn = null
            editId = id
            entryKind = EntryKind.TASK
            panel = Panel.ENTRY
            pendingOccurrence = null
        }
    }
    var editFromCalendar by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(launchRequest, state.loading) {
        val request = launchRequest ?: return@LaunchedEffect
        val launchAction = request.action
        val reminder = ReminderTarget.from(request)
        if (reminder != null) {
            if (state.loading) return@LaunchedEffect
            val exists = if (reminder.event) state.events.any { it.id == reminder.id }
                else state.entries.any { it.entry.id == reminder.id && it.entry.kind == EntryKind.TASK }
            if (exists) {
                editorFrames = arrayListOf()
                editorReturn = null
                deadline = null
                creating = false
                calendar = false
                editId = reminder.id
                editFromCalendar = reminder.id.takeIf { request.getBooleanExtra(QUICK_CALENDAR_EDIT, false) }
                entryKind = EntryKind.TASK
                panel = if (reminder.event) Panel.EVENT else Panel.ENTRY
            }
        }
        if (launchAction == OPEN_TODAY) {
            panel = null
            calendar = true
            agendaDate = null
        }
        if (launchAction == QUICK_CALENDAR_ACTION) {
            val day = request.getLongExtra(QUICK_CALENDAR_DAY, Long.MIN_VALUE)
            val operation = request.getStringExtra("operation")
            if (operation == "tasks" || validDay(day) && operation in setOf("task", "event")) {
                editorFrames = arrayListOf()
                editorReturn = null
                calendar = false
                creating = false
                if (operation == "tasks") panel = Panel.TASKS else {
                    editId = newId()
                    deadline = day
                    entryKind = EntryKind.TASK
                    panel = if (operation == "event") Panel.EVENT else Panel.ENTRY
                }
            }
        }
        if (launchAction == OPEN_CAPTURE) {

            creating = false
            calendar = false
            panel = null
            tab = Tab.DUMP
            recording = true
        }
        consumeLaunch()
    }

    fun goBack() {
        editFromCalendar = null
        if (panel in setOf(Panel.ENTRY, Panel.EVENT, Panel.COLLECTION) && editorFrames.isNotEmpty()) {
            val prior = org.json.JSONObject(editorFrames.last())
            editorFrames = ArrayList(editorFrames.dropLast(1))
            editId = prior.optString("id").takeIf { it.isNotBlank() }
            deadline = if (prior.isNull("deadline")) null else prior.getLong("deadline")
            entryKind = EntryKind.valueOf(prior.getString("kind"))
            editorReturn = prior.optString("return").takeIf { it.isNotBlank() }?.let(Panel::valueOf)
            panel = Panel.valueOf(prior.getString("panel"))
            return
        }
        when {
            panel in listOf(Panel.TAGS, Panel.REMINDERS, Panel.BACKUP, Panel.UPDATE, Panel.TRASH) -> panel = Panel.SETTINGS
            panel == Panel.AI -> panel = aiReturn
            panel in setOf(Panel.ENTRY, Panel.EVENT, Panel.COLLECTION) -> panel = editorReturn
            panel != null -> panel = null
            tab == Tab.COLLECTIONS && (inbox || collectionId != null) -> {
                inbox = false
                collectionId = collection?.parentId.takeIf { preferences.folderCarousel }
            }
            tab != Tab.COLLECTIONS -> tab = Tab.COLLECTIONS
        }
    }

    fun rememberEditor() {
        val active = panel ?: return
        if (active !in setOf(Panel.ENTRY, Panel.EVENT, Panel.COLLECTION)) return
        val prior = org.json.JSONObject().put("panel", active.name).put("id", editId.orEmpty())
            .put("kind", entryKind.name).put("deadline", deadline ?: org.json.JSONObject.NULL)
            .put("return", editorReturn?.name.orEmpty())
        editorFrames = ArrayList(editorFrames + prior.toString())
    }

    fun selectTab(destination: Tab) {
        if (destination == Tab.COLLECTIONS && tab == Tab.COLLECTIONS) {
            collectionId = null
            inbox = false
            focusFolder = null
        }
        tab = destination
    }

    fun revealFolder(id: String) {
        val folders = state.collections.associateBy { it.id }
        val path = generateSequence(folders[id]) { folders[it.parentId] }.distinctBy { it.id }.map { it.id }.toList()
        expanded = ArrayList((expanded + path).distinct())
        focusFolder = id
        collectionId = id.takeIf { path.size > 2 }
        inbox = false
    }

    fun changeLayout() {
        if (preferences.grid) {
            gridFolder = collectionId
            gridInbox = inbox
            if (inbox && INLINE_INBOX !in expanded) expanded = ArrayList(expanded + INLINE_INBOX)
            collectionId?.let(::revealFolder)
            collectionId = null
            inbox = false
        } else {
            collectionId = gridFolder?.takeIf { id -> state.collections.any { it.id == id } }
            inbox = gridInbox && collectionId == null
            focusFolder = null
        }
        model.setPreferences(preferences.copy(grid = !preferences.grid))
    }

    fun newEntry(kind: EntryKind, date: LocalDate? = null) {
        rememberEditor()
        editorReturn = panel
        editId = newId()
        entryKind = kind
        deadline = date?.toEpochDay()
        panel = Panel.ENTRY
    }

    fun newCollection() {
        rememberEditor()
        editorReturn = panel
        editId = newId()
        panel = Panel.COLLECTION
    }

    fun newEvent(date: LocalDate = today) {
        rememberEditor()
        editorReturn = panel
        editId = newId()
        deadline = date.toEpochDay()
        panel = Panel.EVENT
    }

    fun capture() {

        recording = true
    }

    fun aiSettings(returnTo: Panel?) {
        aiReturn = returnTo
        panel = Panel.AI
    }

    BackHandler(panel != null || tab != Tab.COLLECTIONS || collectionId != null || inbox) { goBack() }

    val favoriteKeys = remember(state.collections, state.entries, state.events) {
        buildSet {
            state.collections.filter { it.favorite }.forEach { add(TrashKind.COLLECTION to it.id) }
            state.entries.filter { it.entry.favorite }.forEach { add(TrashKind.valueOf(it.entry.kind.name) to it.entry.id) }
            state.events.filter { it.favorite }.forEach { add(TrashKind.EVENT to it.id) }
        }
    }
    CompositionLocalProvider(LocalFavorites provides FavoriteActions(favoriteKeys) { item ->
        model.write { model.repository.toggleFavorite(item.id, item.kind) }
    }, LocalWorkspaceMenu provides { request ->
        if (request.move || request.item.kind == TrashKind.COLLECTION) workspaceMenu = request
        else model.deleteWithUndo(request.item.id, request.item.kind) {
            if (collectionId == request.item.id) collectionId = collection?.parentId
        }
    }) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val feature = layout?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull { it.isSeparating }
            val pane = with(LocalDensity.current) {
                when (feature?.orientation) {
                    FoldingFeature.Orientation.VERTICAL -> {
                        val left = feature.bounds.left.toDp().coerceIn(0.dp, maxWidth)
                        val right = feature.bounds.right.toDp().coerceIn(0.dp, maxWidth)
                        if (left >= maxWidth - right) Modifier.absolutePadding(right = maxWidth - left)
                        else Modifier.absolutePadding(left = right)
                    }
                    FoldingFeature.Orientation.HORIZONTAL -> {
                        val top = feature.bounds.top.toDp().coerceIn(0.dp, maxHeight)
                        val bottom = feature.bounds.bottom.toDp().coerceIn(0.dp, maxHeight)
                        if (top >= maxHeight - bottom) Modifier.padding(bottom = maxHeight - top)
                        else Modifier.padding(top = bottom)
                    }
                    else -> Modifier
                }
            }
            BoxWithConstraints(
                Modifier.fillMaxSize().then(pane)
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)).imePadding(),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(Modifier.widthIn(max = 1040.dp).fillMaxSize()) {
                    val openEntry: (TaggedEntry) -> Unit = {
                        editorReturn = panel
                        editId = it.entry.id
                        entryKind = it.entry.kind
                        panel = Panel.ENTRY
                    }
                    val complete: (TaggedEntry, Boolean) -> Unit = { item, checked ->
                        model.setCompleted(item.entry.id, checked, item.entry.deadline)
                    }
                    val openEvent: (Event) -> Unit = { event ->
                        editorReturn = panel
                        editId = event.id
                        panel = Panel.EVENT
                    }
                    LaunchedEffect(approvedResult, state.entries, state.events, tab, panel) {
                        val (id, kind) = approvedResult ?: return@LaunchedEffect
                        if (panel != null || tab != Tab.DUMP) {
                            approvedResult = null
                            return@LaunchedEffect
                        }
                        if (kind == DraftKind.EVENT) {
                            state.events.firstOrNull { it.id == id }?.let { approvedResult = null; openEvent(it) }
                        } else {
                            state.entries.firstOrNull { it.entry.id == id }?.let { approvedResult = null; openEntry(it) }
                        }
                    }
                    val collectionContext = (if (panel == Panel.COLLECTION) collectionId else visibleCollection)
                        .takeIf { tab == Tab.COLLECTIONS && !inbox }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                      Box(Modifier.widthIn(max = if (panel == null) 1040.dp else 700.dp).fillMaxSize().graphicsLayer {
                        val progress = screenMotion.value
                        alpha = .84f + .16f * progress
                        translationX = (1f - progress) * if (tab == Tab.DUMP && panel == null) -17.dp.toPx() else 17.dp.toPx()
                      }) {
                        if (state.loading) {
                            CircularProgressIndicator(Modifier.align(Alignment.Center))
                        } else if (state.loadFailed) {
                            Box(Modifier.align(Alignment.Center).padding(18.dp)) {
                                EmptyCard(Glyph.INBOX, "Couldn’t open the library", "Close and reopen Second Brain to try again.")
                            }
                        } else {
                            when (panel) {
                                Panel.SETTINGS -> SettingsScreen(preferences, state.tags.size, model::setPreferences,
                                    { panel = Panel.TAGS }, { panel = Panel.REMINDERS },
                                    { aiSettings(Panel.SETTINGS) }, { panel = Panel.BACKUP }, { panel = Panel.UPDATE },
                                    { panel = Panel.TRASH })
                                Panel.TRASH -> RecentlyDeletedScreen(model, busy, ::goBack)
                                Panel.TAGS -> TagsScreen(state, model, busy, ::goBack)
                                Panel.REMINDERS -> ReminderSettings(model, ::goBack)
                                Panel.AI -> AiSettings(model, ::goBack)
                                Panel.BACKUP -> BackupScreen(model, ::goBack)
                                Panel.UPDATE -> UpdateScreen(model, ::goBack)
                                Panel.SEARCH -> SearchScreen(state, query, { query = it }, openEntry, complete, openEvent, {
                                    if (!preferences.folderCarousel || preferences.grid) { collectionId = it; inbox = false }
                                    else revealFolder(it)
                                    tab = Tab.COLLECTIONS
                                    panel = null
                                }, ::goBack, searchableCaptures, { capture ->
                                    searchCapture = capture
                                    tab = Tab.DUMP
                                    panel = null
                                })
                                Panel.TASKS -> TasksScreen(state, { newEntry(EntryKind.TASK) }, openEntry, complete, ::goBack)
                                Panel.FAVORITES -> FavoritesScreen(state, ::goBack, { id ->
                                    if (!preferences.folderCarousel || preferences.grid) { collectionId = id; inbox = false }
                                    else revealFolder(id)
                                    panel = null
                                }, { folder -> editorReturn = panel; editId = folder.id; panel = Panel.COLLECTION },
                                    openEntry, complete, openEvent)
                                Panel.EVENT -> key(editId) {
                                    val event = state.events.firstOrNull { it.id == editId }
                                    editId?.let { id ->
                                        ItemEditor(id, state.entries.firstOrNull { it.entry.id == id }, event,
                                            DraftKind.EVENT, collectionContext, deadline, agendaDay.toEpochDay(),
                                            state, model, busy, ::goBack, editFromCalendar == id)
                                    }
                                }
                                Panel.ENTRY -> key(editId, entryKind) {
                                    val existing = state.entries.firstOrNull { it.entry.id == editId }
                                    editId?.let { id ->
                                        ItemEditor(id, existing, state.events.firstOrNull { it.id == id },
                                            DraftKind.valueOf(entryKind.name), collectionContext,
                                            deadline, agendaDay.toEpochDay(), state, model, busy, ::goBack, editFromCalendar == id)
                                    }
                                }
                                Panel.COLLECTION -> key(editId) {
                                    val existing = state.collections.firstOrNull { it.id == editId }
                                    editId?.let { id ->
                                        CollectionEditor(id, existing, collectionContext, state, model, busy, ::goBack) {
                                            if (collectionId == existing?.id) collectionId = existing?.parentId
                                            expanded = ArrayList(expanded.filterNot { it == existing?.id })
                                            panel = null
                                        }
                                    }
                                }
                                null -> key(tab) {
                                    Box(Modifier.fillMaxSize()) {
                                      holders.SaveableStateProvider(tab.name) {
                                        when (tab) {
                                        Tab.COLLECTIONS -> if (!preferences.folderCarousel && collection == null && !inbox) {
                                            holders.SaveableStateProvider("Home hierarchy") {
                                                LaunchedEffect(Unit) { visibleCollection = null }
                                                HomeHierarchy(state, preferences, expanded,
                                                    onExpand = { id ->
                                                        expanded = ArrayList(if (id in expanded) expanded - id else expanded + id)
                                                    },
                                                    onFolder = { collectionId = it; inbox = false },
                                                    onEditFolder = { editorReturn = panel; editId = it.id; panel = Panel.COLLECTION },
                                                    onInbox = { collectionId = null; inbox = true },
                                                    onSearch = { panel = Panel.SEARCH }, onEntry = openEntry, onEvent = openEvent,
                                                    onPreference = model::setPreferences)
                                            }
                                        } else key(collectionId, inbox) {
                                            CollectionScreen(
                                                state, collection, inbox, expanded,
                                                onExpand = { id ->
                                                    focusFolder = null
                                                    expanded = ArrayList(if (id in expanded) expanded - id else expanded + id)
                                                },
                                                onCollection = { collectionId = it; inbox = false },
                                                onInbox = { collectionId = null; inbox = true },
                                                onBack = ::goBack,
                                                onAddCollection = ::newCollection,
                                                onEditCollection = { editorReturn = panel; editId = it.id; panel = Panel.COLLECTION },
                                                onAddEntry = { creating = true },
                                                onEntry = openEntry,
                                                onComplete = complete,
                                                grid = !preferences.folderCarousel || preferences.grid,
                                                onLayout = ::changeLayout,
                                                onEvent = openEvent,
                                                onSearch = { panel = Panel.SEARCH },
                                                onFavorites = { panel = Panel.FAVORITES },
                                                focusedFolder = focusFolder,
                                                showFolderTitles = preferences.showFolderTitles,
                                                showSubfolderIndicators = preferences.showSubfolderIndicators,
                                                selectedFolders = preferences.selectedFolders,
                                                onFolderSelected = { level, id -> model.rememberSelectedFolder(level, id) },
                                                onCreationDestination = { visibleCollection = it },
                                                model = model,
                                                busy = busy,
                                                hierarchyWorkspace = !preferences.folderCarousel,
                                                folderOrder = preferences.folderOrder,
                                                onToggleFolderTitles = {
                                                    model.setPreferences(preferences.copy(showFolderTitles = !preferences.showFolderTitles))
                                                },
                                                onFolderOrder = { order ->
                                                    val changed = order.toSet()
                                                    model.setPreferences(preferences.copy(folderOrder =
                                                        preferences.folderOrder.filterNot(changed::contains) + order))
                                                }
                                            )
                                        }
                                        Tab.DUMP -> BrainDumpScreen(model, state, busy, { id, kind ->
                                            approvedResult = id to kind
                                        }, searchCapture) { searchCapture = null }
                                        }
                                      }
                                    }
                                }
                            }
                        }
                      }
                        if (!calendar || panel != null) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                    }
                    val keyboardHidden = WindowInsets.ime.getBottom(LocalDensity.current) == 0
                    if (!state.loading && !state.loadFailed) {
                        val appScope = androidx.compose.runtime.rememberCoroutineScope()
                        CaptureDock(model.app, preferences.slimeFace, Modifier.align(Alignment.CenterHorizontally)
                            .padding(start = 13.dp, end = 13.dp, bottom = 10.dp),
                            if (!keyboardHidden) null else when (panel) {
                                Panel.SETTINGS, Panel.AI, Panel.TAGS, Panel.REMINDERS, Panel.BACKUP, Panel.UPDATE, Panel.TRASH -> 2
                                null -> if (tab == Tab.DUMP) 1 else 0
                                else -> null
                            },
                            {
                                calendar = false
                                if (it == 2) panel = Panel.SETTINGS else {
                                    val selectedTab = if (it == 0) Tab.COLLECTIONS else Tab.DUMP
                                    if (panel != null && tab == selectedTab) panel = null else {
                                        panel = null
                                        selectTab(selectedTab)
                                    }
                                }
                            }, { creating = true },
                            { panel = null; calendar = true }, ::capture,
                            { message -> appScope.launch { snackbar.showSnackbar(message) } })
                    }
                }
                if (calendar && panel == null && !state.loading && !state.loadFailed) {
                    holders.SaveableStateProvider("Calendar") {
                    CalendarPanel(state, today, agendaDay, { agendaDate = it.toEpochDay() }, {
                        holders.removeState("Calendar")
                        calendar = false
                    },
                        { newEntry(EntryKind.TASK, it) }, ::newEvent, { item ->
                            editorReturn = null
                            editId = item.entry.id
                            entryKind = item.entry.kind
                            panel = Panel.ENTRY
                        }, { item, checked -> model.setCompleted(item.entry.id, checked, item.entry.deadline) }, {
                            editorReturn = null
                            editId = it.id
                            panel = Panel.EVENT
                        }, { panel = Panel.TASKS }, update.release?.tag, { panel = Panel.UPDATE },
                        { id, day -> model.editOccurrence(id, day) { pendingOccurrence = it } }, snackbar)
                    }
                }

            }
        }
    }
    }
    workspaceMenu?.let { request ->
        WorkspaceMenuDialog(request, state, model, busy) { workspaceMenu = null }
    }
    if (creating) CreateSheet(
        onClose = { creating = false },
        onNote = { creating = false; newEntry(EntryKind.NOTE) },
        onTask = { creating = false; newEntry(EntryKind.TASK) },
        onCollection = { creating = false; newCollection() },
        onEvent = { creating = false; newEvent(today) },
        onCapture = { creating = false; capture() }
    )
    if (recording) RecordingDialog(model.app, preferences.slimeFace) { recording = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateSheet(onClose: () -> Unit, onNote: () -> Unit, onTask: () -> Unit,
    onCollection: () -> Unit, onEvent: () -> Unit, onCapture: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    BrainSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 19.dp, end = 19.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Create", Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.headlineSmall)
            CreateLink("Note", Glyph.NOTE, colors.primary, onNote)
            CreateLink("Task", Glyph.TASK, colors.secondary, onTask)
            CreateLink("Event", Glyph.CALENDAR, colors.tertiary, onEvent)
            CreateLink("Collection", Glyph.FOLDER, colors.tertiary, onCollection)
            CreateLink("Voice capture", Glyph.MIC, colors.primary, onCapture)
        }
    }
}

@Composable
private fun CreateLink(title: String, glyph: Glyph, color: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = cardShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(glyph, color)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            DirectionIcon(Glyph.NEXT)
        }
    }
}
