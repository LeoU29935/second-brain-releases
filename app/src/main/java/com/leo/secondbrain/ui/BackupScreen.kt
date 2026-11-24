package com.leo.secondbrain.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.leo.secondbrain.backup.BackupPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
internal fun BackupScreen(model: BrainViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var destination by rememberSaveable { mutableStateOf<String?>(null) }
    var summary by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var audio by rememberSaveable { mutableStateOf(true) }
    var busy by androidx.compose.runtime.remember { mutableStateOf(false) }

    fun work(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalArgumentException) {
                message = error.message?.takeUnless { it == "Failed requirement." } ?: "Invalid or damaged backup."
            } catch (_: Exception) {
                message = "Couldn’t complete the backup operation. Check the file and try again."
            } finally {
                busy = false
            }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) work {
            model.app.edits.flush()
            message = model.app.backups.export(uri, audio)
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            val preview = model.app.backups.preview(uri)
            destination = uri.toString()
            summary = backupSummary(preview)
        }
    }
    LibraryGrid {
        fullWidth("backup title") { ScreenHeader("Backup and restore", onBack) }
        fullWidth("backup actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backups contain notes, tasks, events, collections, tags, and Brain Dump history. API keys are excluded.")
                BrainChip(audio, { audio = !audio }, { Text("Include saved recordings") })
                BrainButton({ export.launch("SecondBrain-${LocalDate.now()}.zip") }, enabled = !busy) { Text("Export backup") }
                BrainButton({ import.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !busy) {
                    Text("Choose backup to restore")
                }
                Text("Restore merges by item ID and keeps the version already on this device.")
                if (busy) Text("Working…")
                message?.let { Text(it) }
            }
        }
    }
    val file = destination
    val details = summary
    if (file != null && details != null) {
        val review = listOfNotNull(details, "Items already on this device will be kept.", message).joinToString("\n\n")
        ConfirmAction("Restore this backup?", review,
            "Restore", busy,
            { destination = null; summary = null }) {
            work {
                val added = model.app.backups.restore(Uri.parse(file))
                destination = null
                summary = null
                message = if (added == 0) "No new items to restore." else "Restored $added items."
                model.refreshReminders()
            }
        }
    }
}

private fun backupSummary(preview: BackupPreview) = listOf(
    countLabel(preview.entries, "note or task"), countLabel(preview.events, "event"),
    countLabel(preview.collections, "collection"), countLabel(preview.tags, "tag"),
    countLabel(preview.captures, "capture"), countLabel(preview.recordings, "recording")
).joinToString(" · ")
