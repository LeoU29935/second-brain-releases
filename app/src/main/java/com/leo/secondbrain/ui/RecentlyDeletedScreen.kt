package com.leo.secondbrain.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leo.secondbrain.data.TRASH_RETENTION_MS
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun RecentlyDeletedScreen(model: BrainViewModel, busy: Boolean, onBack: () -> Unit) {
    val deleted by model.repository.recentlyDeleted.collectAsStateWithLifecycle(initialValue = emptyList())
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { model.purgeExpiredItems() }
    LibraryGrid {
        fullWidth("trash heading") { ScreenHeader("Recently Deleted", onBack) }
        if (deleted.isEmpty()) fullWidth("empty trash") {
            QuietEmpty(Glyph.DELETE, "Nothing deleted", "Deleted items are kept here for 14 days.")
        }
        deleted.forEach { record ->
            fullWidth(record.id) {
                Surface(Modifier.fillMaxWidth(), shape = cardShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow, border = cardBorder()) {
                    Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text(record.title, style = MaterialTheme.typography.titleMedium)
                        val expires = Instant.ofEpochMilli(record.deletedAt + TRASH_RETENTION_MS)
                            .atZone(ZoneId.systemDefault()).toLocalDate()
                        Text("${record.kind.label} · Expires ${expires.displayDate()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                            QuietButton({ model.write { model.repository.restoreDeleted(record.id) } }, enabled = !busy) {
                                Text("Restore")
                            }
                            QuietButton({ pendingDelete = record.id }, enabled = !busy) { Text("Delete forever") }
                        }
                    }
                }
            }
        }
    }
    val record = deleted.firstOrNull { it.id == pendingDelete }
    if (record != null) {
        ConfirmAction("Permanently delete ${record.title}?", "You can’t restore this item after this step.",
            "Delete forever", busy, { pendingDelete = null }) {
            model.write({ pendingDelete = null }) { model.repository.permanentlyDelete(record.id) }
        }
    }
}
