package com.leo.secondbrain.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leo.secondbrain.data.CollectionEntity
import com.leo.secondbrain.data.EntryKind
import com.leo.secondbrain.data.Event
import com.leo.secondbrain.data.Preferences
import com.leo.secondbrain.data.TaggedEntry
import com.leo.secondbrain.data.TrashKind
import com.leo.secondbrain.ui.theme.accentColor

@Composable
internal fun HomeHierarchy(
    state: LibraryState, preferences: Preferences, expanded: List<String>,
    onExpand: (String) -> Unit, onFolder: (String) -> Unit, onEditFolder: (CollectionEntity) -> Unit,
    onInbox: () -> Unit, onSearch: () -> Unit, onEntry: (TaggedEntry) -> Unit, onEvent: (Event) -> Unit,
    onPreference: (Preferences) -> Unit
) {
    val children = remember(state.collections, preferences.folderOrder) {
        state.collections.groupBy(CollectionEntity::parentId)
            .mapValues { (_, siblings) -> orderedFolders(siblings, preferences.folderOrder) }
    }
    val pins = remember(state, preferences.pinOrder) {
        val entries = state.entries.filter { it.entry.favorite }.map { item ->
            HomePin(item.entry.id, item.entry.title, if (item.entry.kind == EntryKind.TASK) Glyph.TASK else Glyph.NOTE,
                TrashKind.valueOf(item.entry.kind.name), item.entry.collectionId) { onEntry(item) }
        }
        val events = state.events.filter { it.favorite }.map { event ->
            HomePin(event.id, event.title, Glyph.CALENDAR, TrashKind.EVENT, event.collectionId) { onEvent(event) }
        }
        val folders = state.collections.filter { it.favorite }.map { folder ->
            HomePin(folder.id, folder.name, Glyph.FOLDER, TrashKind.COLLECTION, folder.parentId) { onFolder(folder.id) }
        }
        val ranks = preferences.pinOrder.withIndex().associate { it.value to it.index }
        (folders + entries + events).sortedBy { ranks[it.id] ?: Int.MAX_VALUE }
    }
    val colors = MaterialTheme.colorScheme
    val favorites = LocalFavorites.current
    var showAllPins by rememberSaveable { mutableStateOf(false) }
    var reorderPins by rememberSaveable { mutableStateOf(false) }
    var reorderBranch by rememberSaveable { mutableStateOf<String?>(null) }
    LibraryGrid {
        fullWidth("search and inbox") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(onSearch, Modifier.weight(1f), shape = CircleShape, color = colors.surfaceContainer) {
                    Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 17.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        BrainIcon(Glyph.SEARCH, tint = colors.primary)
                        Text("Search", color = colors.onSurfaceVariant)
                    }
                }
                QuietButton(onInbox) { Text("Inbox") }
            }
        }
        if (pins.isNotEmpty()) fullWidth("pinned shortcuts") {
            Column(Modifier.padding(vertical = 5.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp), maxItemsInEachRow = 2) {
                    (if (showAllPins) pins else pins.take(4)).forEach { pin ->
                        ContextCard(Modifier.weight(1f), border = null,
                            shape = RoundedCornerShape(18.dp), color = colors.surfaceContainerHigh, onOpen = pin.open,
                            interactionModifier = Modifier.testTag("pin:${pin.id}"),
                            menu = { close ->
                                MenuOption("Unpin", Glyph.STAR, close) {
                                    favorites.toggle(WorkspaceItem(pin.id, pin.title, pin.kind, pin.parent))
                                }
                                if (pins.size > 1) MenuOption("Reorder pinned items", Glyph.LIST, close) { reorderPins = true }
                            }) {
                            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                                BrainIcon(pin.glyph, Modifier.size(19.dp), tint = colors.primary)
                                Text(pin.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                if (pins.size > 4) QuietButton({ showAllPins = !showAllPins }) {
                    Text(if (showAllPins) "Show fewer pins" else "Show all pins")
                }
            }
        }
        children[null].orEmpty().forEach { root ->
            fullWidth("branch:${root.id}") {
                FolderBranch(root, children, expanded, preferences.simplifyMotion, 0,
                    onExpand, onFolder, onEditFolder) { reorderBranch = it.orEmpty() }
            }
        }
        if (children[null].isNullOrEmpty()) fullWidth("empty hierarchy") {
            QuietEmpty(Glyph.FOLDER, "No folders yet")
        }
    }
    if (reorderPins) ItemOrderDialog("Reorder pinned items", pins.map { it.id to it.title }, { reorderPins = false }) {
        onPreference(preferences.copy(pinOrder = it))
        reorderPins = false
    }
    reorderBranch?.let { parent ->
        FolderOrderDialog(children[parent.ifEmpty { null }].orEmpty(), { reorderBranch = null }) { order ->
            onPreference(preferences.copy(folderOrder = preferences.folderOrder.filterNot(order.toSet()::contains) + order))
            reorderBranch = null
        }
    }
}

private data class HomePin(val id: String, val title: String, val glyph: Glyph, val kind: TrashKind,
    val parent: String?, val open: () -> Unit)

@Composable
private fun FolderBranch(
    folder: CollectionEntity, children: Map<String?, List<CollectionEntity>>, expanded: List<String>,
    simpleMotion: Boolean, depth: Int, toggle: (String) -> Unit, open: (String) -> Unit,
    edit: (CollectionEntity) -> Unit, reorder: (String?) -> Unit
) {
    val peers = children[folder.parentId].orEmpty()
    val nested = children[folder.id].orEmpty()
    val showing = nested.isNotEmpty() && folder.id in expanded
    val colors = MaterialTheme.colorScheme
    val tint = accentColor(folder.color)
    val roundness by animateDpAsState(if (showing) 12.dp else 29.dp,
        if (simpleMotion) androidx.compose.animation.core.snap() else Motion.press(), label = "Folder shape")
    val arrow by animateFloatAsState(if (showing) 180f else 0f,
        if (simpleMotion) androidx.compose.animation.core.snap() else Motion.press(), label = "Folder expansion")
    val item = WorkspaceItem(folder.id, folder.name, TrashKind.COLLECTION, folder.parentId)
    Column {
        ContextCard(border = null,
            shape = RoundedCornerShape(topStart = 29.dp, topEnd = 29.dp,
                bottomStart = roundness, bottomEnd = roundness),
            color = if (depth == 0) colors.surfaceContainerHigh else colors.surfaceContainer,
            interactionModifier = Modifier.testTag("folder:${folder.id}").semantics {
                if (nested.isNotEmpty()) stateDescription = if (showing) "Expanded" else "Collapsed"
            },
            onOpen = { if (nested.isEmpty()) open(folder.id) else toggle(folder.id) }, menu = { close ->
                WorkspaceMenuOptions(item, close, { edit(folder) }, { open(folder.id) })
                if (peers.size > 1) MenuOption("Reorder folders", Glyph.LIST, close) { reorder(folder.parentId) }
            }) {
            Row(Modifier.heightIn(min = if (depth == 0) 54.dp else 48.dp).padding(horizontal = 15.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CollectionSymbol(folder.icon, tint)
                Text(folder.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = if (depth == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall)
                if (nested.isNotEmpty()) {
                    BrainIcon(Glyph.DOWN, Modifier.size(20.dp).graphicsLayer { rotationZ = arrow }, tint = tint)
                } else BrainIcon(Glyph.NEXT, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            }
        }
        AnimatedVisibility(showing, enter = if (simpleMotion) androidx.compose.animation.EnterTransition.None else Motion.reveal(),
            exit = if (simpleMotion) androidx.compose.animation.ExitTransition.None else Motion.conceal()) {
            val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
            Column(Modifier.fillMaxWidth().drawBehind {
                val x = if (direction == androidx.compose.ui.unit.LayoutDirection.Ltr) 7.dp.toPx() else size.width - 7.dp.toPx()
                drawLine(tint.copy(alpha = .36f), Offset(x, 0f), Offset(x, size.height - 6.dp.toPx()), 1.dp.toPx())
            }.padding(start = if (depth < 3) 15.dp else 10.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                nested.forEach { sub ->
                    androidx.compose.runtime.key(sub.id) {
                        FolderBranch(sub, children, expanded, simpleMotion, depth + 1, toggle, open, edit, reorder)
                    }
                }
            }
        }
    }
}
