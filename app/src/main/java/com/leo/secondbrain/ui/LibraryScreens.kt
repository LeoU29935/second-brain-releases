package com.leo.secondbrain.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.height
import com.leo.secondbrain.data.TrashKind

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leo.secondbrain.data.CollectionEntity
import com.leo.secondbrain.data.EntryKind
import com.leo.secondbrain.data.Priority
import com.leo.secondbrain.data.TagEntity
import com.leo.secondbrain.data.TaggedEntry
import com.leo.secondbrain.data.Event
import com.leo.secondbrain.data.scheduleTimeLabel
import com.leo.secondbrain.data.plainBody
import com.leo.secondbrain.ui.theme.accentColor
import java.time.LocalDate

@Composable
internal fun LibraryGrid(masonry: Boolean? = null, compactRows: Boolean = false,
    cardWidth: androidx.compose.ui.unit.Dp = 158.dp,
    content: LazyStaggeredGridScope.() -> Unit) {
    val gutter = 15.dp
    val gap = 8.dp
    val scrolling = rememberLazyStaggeredGridState()
    LazyVerticalStaggeredGrid(
        columns = when (masonry) {
            true -> StaggeredGridCells.Adaptive(cardWidth)
            false -> StaggeredGridCells.Fixed(1)
            null -> StaggeredGridCells.Adaptive(320.dp)
        },
        state = scrolling,
        modifier = if (scrolling.isScrollInProgress) Modifier.preferredFrameRate(FrameRateCategory.High) else Modifier,
        contentPadding = PaddingValues(start = gutter, end = gutter, top = 5.dp, bottom = 16.dp),
        verticalItemSpacing = if (compactRows) 0.dp else gap,
        horizontalArrangement = Arrangement.spacedBy(gap),
        content = content
    )
}

internal fun LazyStaggeredGridScope.fullWidth(key: String, content: @Composable () -> Unit) {
    item(key = key, span = StaggeredGridItemSpan.FullLine) { content() }
}

internal fun LazyStaggeredGridScope.entryCards(
    entries: List<TaggedEntry>,
    onOpen: (TaggedEntry) -> Unit,
    onComplete: (TaggedEntry, Boolean) -> Unit,
    collectionNames: Map<String, String> = emptyMap()
) {
    items(entries, key = { "entry:" + it.entry.id }) { tagged ->
        EntryCard(tagged, collectionNames[tagged.entry.collectionId ?: ""], { onOpen(tagged) },
            { onComplete(tagged, it) }, Modifier.animateItem())
    }
}

@Composable
internal fun EntryCard(
    tagged: TaggedEntry,
    collectionName: String?,
    onOpen: () -> Unit,
    onComplete: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val entry = tagged.entry
    val colors = MaterialTheme.colorScheme
    val titleColor by animateColorAsState(if (entry.completed) colors.onSurfaceVariant else colors.onSurface,
        tween(Motion.ENTER), label = "Task completion")
    ContextCard(
        modifier = modifier, onOpen = onOpen,
        menu = { close ->
            WorkspaceMenuOptions(WorkspaceItem(entry.id, entry.title, TrashKind.valueOf(entry.kind.name),
                entry.collectionId), close, onOpen)
        }
    ) {
        Row(
            Modifier.padding(start = if (entry.kind == EntryKind.TASK) 4.dp else 13.dp, end = 13.dp,
                top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (entry.kind == EntryKind.TASK) {
                TaskCompletion(entry.completed, onComplete, Modifier.semantics {
                    contentDescription = if (entry.completed) "Reopen ${entry.title}" else "Complete ${entry.title}"
                })
            } else {
                IconTile(Glyph.NOTE, colors.primary)
            }
            Column(
                Modifier.weight(1f).padding(vertical = 3.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    entry.title, style = MaterialTheme.typography.titleMedium, color = titleColor, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (entry.completed) TextDecoration.LineThrough else null
                )
                if (entry.body.isNotBlank()) {
                    Text(entry.body.plainBody(), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (collectionName != null) Capsule(entry.kind.label, colors.secondary)
                    entry.deadline?.let {
                        val date = LocalDate.ofEpochDay(it).scheduleDate()
                        val time = scheduleTimeLabel(entry.dueMinute, entry.timeOfDay)
                        Capsule(listOfNotNull(date, time).joinToString(" · "), colors.primary, Glyph.CALENDAR)
                    }
                    entry.hardDeadline?.let { day ->
                        Capsule("Due ${LocalDate.ofEpochDay(day).scheduleDate()}", colors.tertiary, Glyph.FLAG)
                    }
                    if (entry.priority != Priority.NONE) {
                        val priorityColor = when (entry.priority) {
                            Priority.HIGH -> colors.error
                            Priority.MEDIUM -> colors.tertiary
                            else -> colors.secondary
                        }
                        Capsule(entry.priority.label, priorityColor, Glyph.FLAG)
                    }
                    if (collectionName != null) Capsule(collectionName, colors.onSurfaceVariant, Glyph.FOLDER)
                    tagged.tags.sortedBy { it.name.lowercase() }.take(4).forEach { tag ->
                        Capsule(tag.name, accentColor(tag.color), Glyph.TAG)
                    }
                    if (tagged.tags.size > 4) Capsule("+${tagged.tags.size - 4}", colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun Capsule(label: String, color: Color, glyph: Glyph? = null) {
    Row(
        Modifier.background(color.copy(alpha = .10f), CircleShape).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (glyph != null) BrainIcon(glyph, Modifier.size(12.dp), tint = color)
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, color = color,
            style = MaterialTheme.typography.labelSmall)
    }
}

internal const val INLINE_INBOX = "@inbox"

@Composable
internal fun CollectionScreen(
    state: LibraryState, collection: CollectionEntity?, inbox: Boolean, expanded: List<String>,
    onExpand: (String) -> Unit, onCollection: (String) -> Unit, onInbox: () -> Unit, onBack: () -> Unit,
    onAddCollection: () -> Unit, onEditCollection: (CollectionEntity) -> Unit, onAddEntry: () -> Unit,
    onEntry: (TaggedEntry) -> Unit, onComplete: (TaggedEntry, Boolean) -> Unit, grid: Boolean,
    onLayout: () -> Unit, onEvent: (Event) -> Unit, onSearch: () -> Unit, focusedFolder: String? = null,
    onFavorites: () -> Unit = {}, showFolderTitles: Boolean = true, folderOrder: List<String> = emptyList(),
    onToggleFolderTitles: () -> Unit = {}, onFolderOrder: (List<String>) -> Unit = {},
    showSubfolderIndicators: Boolean = false, selectedFolders: Map<String, String> = emptyMap(),
    onFolderSelected: (String, String) -> Unit = { _, _ -> },
    onCreationDestination: (String?) -> Unit = {}, model: BrainViewModel? = null, busy: Boolean = false,
    hierarchyWorkspace: Boolean = false
) {
    val root = collection == null && !inbox
    val children = remember(state.collections, folderOrder) {
        state.collections.groupBy { it.parentId }.mapValues { (_, siblings) -> orderedFolders(siblings, folderOrder) }
    }
    var browseId by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var selectedId by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var showSelectedBreadcrumb by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var reordering by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val branch = state.collections.firstOrNull { it.id == browseId }
    val folderScope = branch ?: collection
    val folders = if (inbox || hierarchyWorkspace) emptyList() else children[folderScope?.id].orEmpty()
    val expandable = remember(children) { children.keys.filterNotNull().toSet() }
    val initialSelections = remember { mutableMapOf<String, String>() }

    LaunchedEffect(focusedFolder) {
        val target = state.collections.firstOrNull { it.id == focusedFolder } ?: return@LaunchedEffect
        browseId = target.parentId.takeUnless { it == collection?.id }
        selectedId = target.id
        showSelectedBreadcrumb = true
    }
    fun backToFolders() {
        selectedId = branch?.id
        browseId = branch?.parentId.takeUnless { it == collection?.id }
        showSelectedBreadcrumb = false
    }
    androidx.activity.compose.BackHandler(branch != null && !inbox, ::backToFolders)
    fun highlighted(level: String?, siblings: List<CollectionEntity>): CollectionEntity? {
        if (level != null && selectedId == level) return null
        val stored = selectedFolders[level.orEmpty()]
        return siblings.firstOrNull { it.id == selectedId } ?: siblings.firstOrNull { it.id == stored }
            ?: siblings.firstOrNull { it.id == initialSelections[level.orEmpty()] }
            ?: siblings.getOrNull(siblings.size / 2)
    }
    val selected = highlighted(folderScope?.id, folders)
    val active = selected ?: folderScope
    val breadcrumbFolder = selected?.takeIf { showSelectedBreadcrumb && it.id == selectedId } ?: folderScope
    val breadcrumbs = remember(breadcrumbFolder, state.collections) {
        val byId = state.collections.associateBy { it.id }
        val path = mutableListOf<CollectionEntity>()
        val visited = mutableSetOf<String>()
        var current = breadcrumbFolder
        while (current != null && visited.add(current.id)) {
            path.add(current)
            current = byId[current.parentId]
        }
        path.asReversed()
    }
    val backFromFolder = when {
        breadcrumbFolder?.id != folderScope?.id -> ({ showSelectedBreadcrumb = false; selectedId = folderScope?.id })
        branch != null -> ::backToFolders
        else -> onBack
    }
    LaunchedEffect(selected?.id, folderScope?.id) {
        selected?.let { initialSelections[folderScope?.id.orEmpty()] = it.id }
    }
    LaunchedEffect(active?.id, folderScope?.id, inbox) {
        onCreationDestination(active?.id.takeIf { !inbox && folderScope != null })
    }
    val entries = remember(state.entries) { state.entries.groupBy { it.entry.collectionId } }
    val events = remember(state.events) { state.events.groupBy { it.collectionId } }
    val directEntries = entries[active?.id].orEmpty()
    val directEvents = events[active?.id].orEmpty()
    var contentFilter by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf("All")
    }
    val visibleEntries = when (contentFilter) {
        "Notes" -> directEntries.filter { it.entry.kind == EntryKind.NOTE }
        "Tasks" -> directEntries.filter { it.entry.kind == EntryKind.TASK }
        "Events" -> emptyList()
        else -> directEntries
    }
    val visibleEvents = if (contentFilter in setOf("All", "Events")) directEvents else emptyList()
    val inlineInbox = root && !grid && INLINE_INBOX in expanded
    var chosen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(arrayListOf<String>()) }
    var batchDelete by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<Boolean?>(null) }
    var destination by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun select(id: String) {
        if (chosen.isEmpty()) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        chosen = ArrayList(if (id in chosen) chosen - id else chosen + id)
    }
    val selectableEntries = if (inlineInbox) directEntries + entries[null].orEmpty() else directEntries
    val selectableEvents = if (inlineInbox) directEvents + events[null].orEmpty() else directEvents
    val selectedItems = (selectableEntries.map { it.entry.id to com.leo.secondbrain.data.DraftKind.valueOf(it.entry.kind.name) } +
        selectableEvents.map { it.id to com.leo.secondbrain.data.DraftKind.EVENT }).filter { it.first in chosen }.toMap()
    LaunchedEffect(active?.id, inbox, inlineInbox) { chosen = arrayListOf() }
    androidx.activity.compose.BackHandler(chosen.isNotEmpty()) { chosen = arrayListOf() }
    LibraryGrid(masonry = grid, compactRows = !grid, cardWidth = if (inbox) 158.dp else 184.dp) {
        fullWidth("header") {
            if (selectedItems.isNotEmpty()) {
                ScreenHeader("${selectedItems.size} selected", { chosen = arrayListOf() }) {
                    ActionIcon(Glyph.FOLDER, "Move selected items", { destination = active?.id; batchDelete = false })
                    ActionIcon(Glyph.DELETE, "Delete selected items", { batchDelete = true })
                    val id = selectedItems.keys.singleOrNull()
                    if (id != null) {
                        val kind = TrashKind.valueOf(selectedItems.getValue(id).name)
                        val favoriteActions = LocalFavorites.current
                        val favorite = (kind to id) in favoriteActions.keys
                        ActionIcon(Glyph.STAR, if (favorite) "Remove from Favorites" else "Add to Favorites", {
                            val entry = selectableEntries.firstOrNull { it.entry.id == id }?.entry
                            val event = selectableEvents.firstOrNull { it.id == id }
                            val title = entry?.title ?: event?.title.orEmpty()
                            favoriteActions.toggle(WorkspaceItem(id, title, kind, entry?.collectionId ?: event?.collectionId))
                        })
                    }
                }
            } else {
                val actions: @Composable () -> Unit = {
                    var menu by remember { androidx.compose.runtime.mutableStateOf(false) }
                    Box {
                        ActionIcon(Glyph.MORE, "Collection settings", { menu = true })
                        androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                            if (!hierarchyWorkspace) {
                                MenuOption(if (grid) "List layout" else "Grid layout", Glyph.LIST, { menu = false }, onLayout)
                                MenuOption(if (showFolderTitles) "Hide folder titles" else "Show folder titles", Glyph.FOLDER,
                                    { menu = false }, onToggleFolderTitles)
                            }
                            breadcrumbFolder?.let { folder ->
                                MenuOption("Edit collection", Glyph.EDIT, { menu = false }) { onEditCollection(folder) }
                            }
                        }
                    }
                }
                if (breadcrumbs.isEmpty()) {
                    ScreenHeader(if (root) "Collections" else "Inbox", onBack.takeUnless { root }, actions)
                } else {
                    FolderBreadcrumbHeader(breadcrumbs, backFromFolder, { folder ->
                        if (hierarchyWorkspace) onCollection(folder.id)
                        else if (collection == null || breadcrumbs.indexOf(folder) >= breadcrumbs.indexOf(collection)) {
                            browseId = folder.id.takeUnless { it == collection?.id }
                            selectedId = folder.id
                            showSelectedBreadcrumb = false
                        } else onCollection(folder.id)
                    }, actions)
                }
            }
        }
        if (root) {
            fullWidth("collection shortcuts") {
                Row(Modifier.fillMaxWidth().padding(vertical = if (grid) 0.dp else 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CollectionShortcut("Search", Glyph.SEARCH, Modifier.weight(1f), onSearch)
                    CollectionShortcut("Inbox", Glyph.INBOX, Modifier.weight(1f), onInbox, menu = { close ->
                        if (!grid) MenuOption(if (inlineInbox) "Collapse Inbox" else "Expand Inbox", Glyph.INBOX, close) {
                            onExpand(INLINE_INBOX)
                        }
                    })
                    CollectionShortcut("Favorites", Glyph.STAR, Modifier.weight(1f), onFavorites)
                }
            }
            if (inlineInbox) {
                items(entries[null].orEmpty(), key = { "inbox-entry:" + it.entry.id }) { item ->
                    CollectionItemCard(item, { onEntry(item) }, { onComplete(item, it) }, Modifier.animateItem(),
                        (item.entry.id in chosen).takeIf { chosen.isNotEmpty() }, { select(item.entry.id) }.takeIf { model != null })
                }
                items(events[null].orEmpty(), key = { "inbox-event:" + it.id }) { event ->
                    CollectionItemCard(event, { onEvent(event) }, Modifier.animateItem(),
                        (event.id in chosen).takeIf { chosen.isNotEmpty() }, { select(event.id) }.takeIf { model != null })
                }
            }
        }
        if (!hierarchyWorkspace && !inbox && (root || collection?.parentId == null || folders.isNotEmpty())) fullWidth("collections") {
            Column(Modifier.fillMaxWidth().testTag("folder-navigation")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (branch != null) ActionIcon(Glyph.BACK, "Back to parent folders", ::backToFolders)
                    Text(folderScope?.name ?: "Folders", Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (folderScope != null && folders.isNotEmpty() && selected != null) {
                        QuietButton({ selectedId = folderScope.id }) { Text("Folder contents") }
                    }
                }
                if (folders.isEmpty() && root && folderScope == null) {
                    QuietEmpty(Glyph.FOLDER, "No collections yet")
                } else if (folders.isNotEmpty()) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = folderScope?.id,
                        modifier = Modifier.testTag("folder-rail"),
                        transitionSpec = { folderRailTransition(initialState, targetState, children) },
                        label = "Folder navigation"
                    ) { level ->
                        val siblings = children[level].orEmpty()
                        FolderCarousel(siblings, highlighted(level, siblings)?.id, showFolderTitles, expandable, onEditCollection,
                            onSelect = { id ->
                                selectedId = id
                                showSelectedBreadcrumb = true
                                onFolderSelected(level.orEmpty(), id)
                            }, onEnter = { id ->
                                onFolderSelected(level.orEmpty(), id)
                                browseId = id
                                selectedId = null
                                showSelectedBreadcrumb = false
                            }, showIndicators = showSubfolderIndicators, onReorder = { reordering = true })
                    }
                }
            }
        }
        if (!root || active != null) {
            fullWidth("entries") {
                if (hierarchyWorkspace) {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("All", "Notes", "Tasks", "Events").forEach { filter ->
                            BrainChip(contentFilter == filter, {
                                chosen = arrayListOf()
                                contentFilter = filter
                            }, { Text(filter) })
                        }
                    }
                } else if (inbox) {
                    SectionHeader("Notes & tasks")
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    val showFolderName = active?.id != folderScope?.id
                    Text(if (showFolderName) active?.name.orEmpty() else "Contents", Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (visibleEntries.isEmpty() && visibleEvents.isEmpty()) fullWidth("empty entries") {
                QuietEmpty(Glyph.NOTE, "Nothing here yet")
            }
                items(visibleEntries, key = { "entry:" + it.entry.id }) { item ->
                    CollectionItemCard(item, { onEntry(item) }, { onComplete(item, it) },
                        Modifier.animateItem().padding(bottom = if (grid) 0.dp else 6.dp),
                        (item.entry.id in chosen).takeIf { chosen.isNotEmpty() }, { select(item.entry.id) }.takeIf { model != null })
                }
                items(visibleEvents, key = { "event:" + it.id }) { event ->
                    CollectionItemCard(event, { onEvent(event) },
                        Modifier.animateItem().padding(bottom = if (grid) 0.dp else 6.dp),
                        (event.id in chosen).takeIf { chosen.isNotEmpty() }, { select(event.id) }.takeIf { model != null })
                }
        }
    }
    if (reordering) FolderOrderDialog(folders, { reordering = false }) { order ->
        onFolderOrder(order)
        reordering = false
    }
    val deleting = batchDelete
    if (model != null && deleting != null && selectedItems.isNotEmpty()) {
        val verb = if (deleting) "Delete" else "Move"
        BrainDialog(onDismissRequest = { if (!busy) batchDelete = null },
            title = { Text("$verb ${selectedItems.size} selected items") },
            text = {
                if (deleting) Text("You can recover these items from Recently Deleted.")
                else CollectionPicker(state.collections, destination, { destination = it }, "Inbox")
            },
            dismissButton = { QuietButton({ batchDelete = null }, enabled = !busy) { Text("Cancel") } },
            confirmButton = {
                BrainButton({
                    model.write({ batchDelete = null; chosen = arrayListOf() }) {
                        model.app.edits.flush()
                        model.offerUndo(model.repository.organizeItems(selectedItems, destination, deleting))
                    }
                }, enabled = !busy) { Text(verb) }
            })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderBreadcrumbHeader(
    folders: List<CollectionEntity>, onBack: () -> Unit, onFolder: (CollectionEntity) -> Unit,
    actions: @Composable () -> Unit
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionIcon(Glyph.BACK, "Back", onBack)
        FlowRow(Modifier.weight(1f).testTag("folder-breadcrumbs"),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            folders.forEachIndexed { index, folder ->
                val tint = accentColor(folder.color)
                val current = index == folders.lastIndex
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (index > 0) BrainIcon(Glyph.NEXT, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.outline)
                    Surface(
                        Modifier.widthIn(max = 158.dp).then(if (current) Modifier else Modifier.clickable(
                            onClickLabel = "Open ${folder.name}", onClick = { onFolder(folder) })),
                        shape = MaterialTheme.shapes.medium, color = tint.copy(alpha = if (current) .19f else .12f)
                    ) {
                        Row(Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(15.dp), contentAlignment = Alignment.Center) {
                                CollectionSymbol(folder.icon, tint)
                            }
                            Text(folder.name, Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        actions()
    }
}

private fun folderRailTransition(from: String?, to: String?, children: Map<String?, List<CollectionEntity>>):
    androidx.compose.animation.ContentTransform {
    val direction = if (children[to].orEmpty().any { it.id == from }) -1 else 1
    return (fadeIn(tween(135)) + slideInHorizontally(tween(170)) { direction * it / 9 }) togetherWith
        (fadeOut(tween(100)) + slideOutHorizontally(tween(170)) { -direction * it / 9 })
}

@Composable
private fun FolderCard(folder: CollectionEntity, count: Int, children: Int, expanded: Boolean?,
    onOpen: () -> Unit, onEdit: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val color = accentColor(folder.color)
    val back by animateColorAsState(if (expanded == true) colors.surfaceContainerHigh else colors.surfaceContainerLow,
        Motion.glide(Motion.QUICK), label = "Collection expansion")
    val detail = listOfNotNull(countLabel(count, "item"), countLabel(children, "folder").takeIf { children > 0 })
        .joinToString(" · ")
    ContextCard(modifier = modifier.semantics {
        if (expanded != null) stateDescription = if (expanded) "Expanded" else "Collapsed"
    }, onOpen = onOpen, color = back, menu = { close ->
        WorkspaceMenuOptions(WorkspaceItem(folder.id, folder.name, TrashKind.COLLECTION, folder.parentId),
            close, onEdit, onOpen)
    }) {
        if (expanded == null) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                FolderBadge(folder, color)
                Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        } else {
            Row(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FolderBadge(folder, color)
                Column(Modifier.weight(1f)) {
                    Text(folder.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun CollectionShortcut(label: String, glyph: Glyph, modifier: Modifier, open: () -> Unit,
    menu: (@Composable (close: () -> Unit) -> Unit)? = null) {
    val tile: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            BrainIcon(glyph, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
    if (menu == null) Surface(onClick = open, modifier = modifier, shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainer, border = cardBorder(), content = tile)
    else ContextCard(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer,
        onOpen = open, menu = menu, content = tile)
}

@Composable
internal fun FavoritesScreen(state: LibraryState, onBack: () -> Unit, onFolder: (String) -> Unit,
    onEditFolder: (CollectionEntity) -> Unit, onEntry: (TaggedEntry) -> Unit,
    onComplete: (TaggedEntry, Boolean) -> Unit, onEvent: (Event) -> Unit) {
    val folders = state.collections.filter { it.favorite }
    val entries = state.entries.filter { it.entry.favorite }
    val events = state.events.filter { it.favorite }
    LibraryGrid {
        fullWidth("favorites header") { ScreenHeader("Favorites", onBack) }
        if (folders.isEmpty() && entries.isEmpty() && events.isEmpty()) fullWidth("no favorites") {
            QuietEmpty(Glyph.STAR, "No favorites yet")
        }
        items(folders, key = { "favorite-folder:${it.id}" }) { folder ->
            FolderCard(folder, 0, 0, false, { onFolder(folder.id) }, { onEditFolder(folder) }, Modifier.animateItem())
        }
        entryCards(entries, onEntry, onComplete)
        eventCards(events, onEvent)
    }
}

@Composable
private fun FolderBadge(folder: CollectionEntity, color: Color) {
    Box(Modifier.size(34.dp).background(color.copy(alpha = .14f), CircleShape),
        contentAlignment = Alignment.Center) { CollectionSymbol(folder.icon, color) }
}

internal fun LazyStaggeredGridScope.eventCards(events: List<Event>, onOpen: (Event) -> Unit,
    collectionNames: Map<String, String> = emptyMap()) {
    items(events, key = { "event:" + it.id }) { event ->
        EventCard(event, collectionNames[event.collectionId ?: ""], { onOpen(event) }, Modifier.animateItem())
    }
}

@Composable
private fun EventCard(event: Event, location: String?, onOpen: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    ContextCard(modifier = modifier, onOpen = onOpen, menu = { close ->
        WorkspaceMenuOptions(WorkspaceItem(event.id, event.title, TrashKind.EVENT, event.collectionId), close, onOpen)
    }) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Capsule(listOfNotNull(LocalDate.ofEpochDay(event.startDay).scheduleDate(),
                if (event.allDay) "All-day" else scheduleTimeLabel(event.startMinute, event.timeOfDay)).joinToString(" · "),
                colors.tertiary, Glyph.CALENDAR)
            Text(event.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            location?.let { Capsule(it, colors.onSurfaceVariant, Glyph.FOLDER) }
            if (event.location.isNotBlank()) Text(event.location, color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal fun countLabel(count: Int, singular: String): String =
    "$count $singular" + if (count == 1) "" else "s"
