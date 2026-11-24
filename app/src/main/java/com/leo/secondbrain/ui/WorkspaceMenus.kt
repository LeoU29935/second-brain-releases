package com.leo.secondbrain.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import com.leo.secondbrain.data.DraftKind
import com.leo.secondbrain.data.TrashKind
import com.leo.secondbrain.data.canPlaceFolder

internal data class WorkspaceItem(val id: String, val title: String, val kind: TrashKind, val collectionId: String?)
internal data class WorkspaceMenuRequest(val item: WorkspaceItem, val move: Boolean)
internal val LocalWorkspaceMenu = staticCompositionLocalOf<(WorkspaceMenuRequest) -> Unit> { {} }
internal class FavoriteActions(val keys: Set<Pair<TrashKind, String>>, val toggle: (WorkspaceItem) -> Unit)
internal val LocalFavorites = androidx.compose.runtime.compositionLocalOf { FavoriteActions(emptySet()) {} }

@Composable
internal fun ContextCard(
    modifier: Modifier = Modifier,
    shape: Shape = cardShape,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    border: BorderStroke? = cardBorder(),
    onOpen: () -> Unit,
    menu: @Composable (close: () -> Unit) -> Unit,
    interactionModifier: Modifier = Modifier,
    role: Role = Role.Button,
    onSelect: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Box(modifier) {
        Surface(Modifier.fillMaxWidth().then(interactionModifier).softPress(interaction, .012f).clip(shape).combinedClickable(
            interactionSource = interaction, indication = LocalIndication.current,
            role = role, onClick = onOpen, onLongClickLabel = if (onSelect == null) "Show actions" else "Select item",
            onLongClick = onSelect ?: { expanded = true }
        ), shape = shape, color = color, border = border, content = content)
        DropdownMenu(expanded, { expanded = false },
            shape = MaterialTheme.shapes.large, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            menu { expanded = false }
        }
    }
}

@Composable
internal fun WorkspaceMenuOptions(item: WorkspaceItem, close: () -> Unit, onEdit: () -> Unit, onOpen: (() -> Unit)? = null) {
    val request = LocalWorkspaceMenu.current
    val favorites = LocalFavorites.current
    if (onOpen != null) MenuOption("Open", Glyph.NEXT, close, onOpen)
    MenuOption("Edit", Glyph.EDIT, close, onEdit)
    MenuOption("Move", Glyph.FOLDER, close) { request(WorkspaceMenuRequest(item, move = true)) }
    MenuOption(if ((item.kind to item.id) in favorites.keys) "Unpin" else "Pin to Home",
        Glyph.STAR, close) { favorites.toggle(item) }
    MenuOption("Delete", Glyph.DELETE, close) { request(WorkspaceMenuRequest(item, move = false)) }
}

@Composable
internal fun MenuOption(label: String, glyph: Glyph, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { BrainIcon(glyph) },
        onClick = { close(); action() })
}

@Composable
internal fun WorkspaceMenuDialog(request: WorkspaceMenuRequest, state: LibraryState, model: BrainViewModel,
    busy: Boolean, onDismiss: () -> Unit) {
    val item = request.item
    val folder = item.kind == TrashKind.COLLECTION
    if (!request.move) {
        BrainDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = { Text("Delete ${item.title}?") },
            text = { Text("This folder and its contents will move to Recently Deleted.") },
            dismissButton = { QuietButton(onDismiss, enabled = !busy) { Text("Cancel") } },
            confirmButton = {
                BrainButton({ model.deleteWithUndo(item.id, item.kind, onDismiss) }, enabled = !busy) { Text("Delete") }
            }
        )
        return
    }
    var selected by rememberSaveable(item.id) { mutableStateOf(item.collectionId) }
    BrainDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Move ${item.title}") },
        text = {
            Column {
                FieldLabel("Destination")
                CollectionPicker(state.collections, selected, { selected = it },
                    if (folder) "Top level" else "Inbox",
                    if (folder) state.collections.filterNot { state.collections.canPlaceFolder(item.id, it.id) }
                        .map { it.id }.toSet() else emptySet())
            }
        },
        dismissButton = { QuietButton(onDismiss, enabled = !busy) { Text("Cancel") } },
        confirmButton = {
            BrainButton({
                model.write(onDismiss) {
                    model.app.edits.flush()
                    model.offerUndo(if (folder) model.repository.moveFolderWithUndo(item.id, selected)
                        else model.repository.moveWithUndo(item.id, DraftKind.valueOf(item.kind.name), selected))
                }
            }, enabled = !busy && selected != item.collectionId) { Text("Move") }
        }
    )
}
