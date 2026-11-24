package com.leo.secondbrain.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leo.secondbrain.data.CollectionEntity
import com.leo.secondbrain.ui.theme.accentColor
import kotlinx.coroutines.launch
import kotlin.math.abs

internal fun orderedFolders(folders: List<CollectionEntity>, storedOrder: List<String>): List<CollectionEntity> {
    val index = storedOrder.withIndex().associate { it.value to it.index }
    return folders.sortedWith(compareBy({ index[it.id] ?: Int.MAX_VALUE }, { it.name.lowercase() }, { it.id }))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderCarousel(
    folders: List<CollectionEntity>, selected: String?, showTitles: Boolean,
    expandable: Set<String>, onEdit: (CollectionEntity) -> Unit,
    onSelect: (String) -> Unit, onEnter: (String) -> Unit, showIndicators: Boolean = false,
    onReorder: () -> Unit = {}
) {
    if (folders.isEmpty()) return
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        android.provider.Settings.Global.getFloat(context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val select by rememberUpdatedState(onSelect)
    val current by rememberUpdatedState(selected)
    val tileHeight = (36 + 18 * androidx.compose.ui.platform.LocalDensity.current.fontScale).dp.coerceAtLeast(60.dp)
    BoxWithConstraints(Modifier.fillMaxWidth().height(tileHeight)) {
        val gap = 4.dp
        val looping = folders.size >= 5
        val size = if (looping) ((maxWidth - gap * 4) / 5).coerceIn(48.dp, 72.dp)
            else ((maxWidth - gap * (folders.size - 1)) / folders.size).coerceIn(1.dp, 86.dp)
        val tile: @Composable (CollectionEntity, Modifier, () -> Unit) -> Unit = { folder, motion, onClick ->
                val selectedFolder = folder.id == selected
                val hasChildren = folder.id in expandable
                val colors = MaterialTheme.colorScheme
                val tint = accentColor(folder.color)
                val item = WorkspaceItem(folder.id, folder.name, com.leo.secondbrain.data.TrashKind.COLLECTION, folder.parentId)
                ContextCard(
                    Modifier.width(size).height(tileHeight).then(motion),
                    interactionModifier = Modifier.fillMaxHeight().semantics {
                        contentDescription = folder.name
                        this.selected = selectedFolder
                    },
                    role = Role.Tab,
                    onOpen = { if (hasChildren) onEnter(folder.id) else onClick() },
                    menu = { close ->
                        WorkspaceMenuOptions(item, close, { onEdit(folder) }, { if (hasChildren) onEnter(folder.id) else onClick() })
                        if (folders.size > 1) MenuOption("Reorder folders", Glyph.LIST, close, onReorder)
                    },
                    shape = MaterialTheme.shapes.medium, color = androidx.compose.ui.graphics.Color.Transparent, border = null
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        val shape = MaterialTheme.shapes.small
                        Box(Modifier.size(width = 46.dp, height = 32.dp)
                            .background(tint.copy(alpha = .12f), shape)
                            .then(if (selectedFolder) Modifier.border(1.dp, colors.primary.copy(alpha = .65f), shape) else Modifier),
                            contentAlignment = Alignment.Center) {
                            CollectionSymbol(folder.icon, tint)
                            if (hasChildren && showIndicators) Box(Modifier.align(Alignment.TopEnd)
                                .padding(4.dp).size(5.dp).background(tint,
                                    androidx.compose.foundation.shape.CircleShape))
                        }
                        if (showTitles) Text(folder.name, Modifier.padding(top = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selectedFolder) colors.onSurface else colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
        }
        if (!looping) {
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(gap)) {
                folders.forEach { folder -> key(folder.id) { tile(folder, Modifier) { select(folder.id) } } }
            }
        } else key(folders.map { it.id }) {
            val count = folders.size
            val middle = (Int.MAX_VALUE / 2 / count) * count
            val index = folders.indexOfFirst { it.id == selected }.takeIf { it >= 0 } ?: count / 2
            val list = rememberLazyListState(middle + index)
            fun centeredIndex(): Int? = with(list.layoutInfo) {
                val center = (viewportStartOffset + viewportEndOffset) / 2f
                visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2f - center) }?.index
            }
            LaunchedEffect(list, selected) {
                val centered = centeredIndex() ?: return@LaunchedEffect
                val difference = Math.floorMod(index - centered % count + count / 2, count) - count / 2
                if (difference != 0 && !list.isScrollInProgress) list.scrollToItem(centered + difference)
            }
            LaunchedEffect(list) {
                var scrolled = false
                snapshotFlow { list.isScrollInProgress to centeredIndex() }.collect { (scrolling, centered) ->
                    if (scrolling) scrolled = true
                    else if (scrolled && centered != null) {
                        scrolled = false
                        val id = folders[centered % count].id
                        if (id != current) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            select(id)
                        }
                        if (centered < middle / 2 || centered > middle + middle / 2) {
                            // Restore an equivalent position with the same pixel offset well before an edge.
                            list.scrollToItem(middle + list.firstVisibleItemIndex % count, list.firstVisibleItemScrollOffset)
                        }
                    }
                }
            }
            val viewport = maxWidth.coerceAtMost(size * 5 + gap * 4)
            LazyRow(Modifier.align(Alignment.Center).width(viewport), state = list,
                flingBehavior = rememberSnapFlingBehavior(list, SnapPosition.Center),
                contentPadding = PaddingValues(horizontal = ((viewport - size) / 2).coerceAtLeast(0.dp)),
                horizontalArrangement = Arrangement.spacedBy(gap)) {
                items(Int.MAX_VALUE) { position ->
                    val folder = folders[position % count]
                    val motion = if (reducedMotion) Modifier else Modifier.graphicsLayer {
                        val layout = list.layoutInfo
                        val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
                        val metrics = layout.visibleItemsInfo.firstOrNull { it.index == position }
                        val offset = metrics?.let { (it.offset + it.size / 2f - center) / (it.size + gap.toPx()) } ?: 0f
                        val depth = abs(offset).coerceAtMost(2f)
                        scaleX = 1f - depth * .035f
                        scaleY = scaleX
                        rotationY = -offset.coerceIn(-2f, 2f) * 6f
                        cameraDistance = 28.dp.toPx()
                    }
                    tile(folder, motion) {
                        scope.launch {
                            if (reducedMotion) list.scrollToItem(position) else list.animateScrollToItem(position)
                            select(folder.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FolderOrderDialog(
    folders: List<CollectionEntity>, onClose: () -> Unit, onSave: (List<String>) -> Unit
) = ItemOrderDialog("Reorder folders", folders.map { it.id to it.name }, onClose, onSave)

@Composable
internal fun ItemOrderDialog(
    title: String, items: List<Pair<String, String>>, onClose: () -> Unit, onSave: (List<String>) -> Unit
) {
    var order by rememberSaveable { mutableStateOf(ArrayList(items.map { it.first })) }
    val byId = items.toMap()
    val haptic = LocalHapticFeedback.current
    BrainDialog(onDismissRequest = onClose, title = { Text(title) }, text = {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(order.size, key = { order[it] }) { index ->
                val id = order[index]
                fun move(offset: Int): Boolean {
                    val from = order.indexOf(id)
                    val to = from + offset
                    if (from < 0 || to !in order.indices) return false
                    order = ArrayList(order).apply { add(to, removeAt(from)) }
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    return true
                }
                val moveItem by rememberUpdatedState(::move)
                Row(Modifier.fillMaxWidth().animateItem().height(52.dp).padding(horizontal = 8.dp)
                    .semantics {
                        customActions = listOfNotNull(
                            CustomAccessibilityAction("Move up") { move(-1) }.takeIf { index > 0 },
                            CustomAccessibilityAction("Move down") { move(1) }.takeIf { index < order.lastIndex })
                    }, verticalAlignment = Alignment.CenterVertically) {
                    Text(byId[id].orEmpty(), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.size(48.dp).semantics { contentDescription = "Reorder ${byId[id].orEmpty()}" }.pointerInput(id) {
                        var distance = 0f
                        detectDragGestures(onDragStart = {
                            distance = 0f
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }) { change, amount ->
                            change.consume()
                            distance += amount.y
                            val step = 40.dp.toPx()
                            if (abs(distance) >= step) {
                                val direction = if (distance < 0) -1 else 1
                                if (moveItem(direction)) distance -= direction * step else distance = 0f
                            }
                        }
                    }, contentAlignment = Alignment.Center) { BrainIcon(Glyph.LIST) }
                }
            }
        }
    }, confirmButton = { QuietButton({ onSave(order) }) { Text("Save") } },
        dismissButton = { QuietButton(onClose) { Text("Cancel") } })
}
