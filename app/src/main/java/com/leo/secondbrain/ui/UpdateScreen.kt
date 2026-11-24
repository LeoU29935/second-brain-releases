package com.leo.secondbrain.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun UpdateScreen(model: BrainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updates = model.app.updates
    val state by model.update.collectAsStateWithLifecycle()
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun install() {
        val release = state.release ?: return
        if (working) return
        working = true
        message = null
        scope.launch {
            try {
                val verified = updates.download(release)
                context.startActivity(updates.installIntent(verified, release))
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalArgumentException) {
                message = error.message ?: "The update failed verification."
            } catch (_: Exception) {
                message = "Couldn’t install the update. Try again when you’re online."
            } finally {
                working = false
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else message = "Android installation permission is required for updates."
    }
    LaunchedEffect(Unit) { model.checkUpdates() }
    LibraryGrid {
        fullWidth("update title") { ScreenHeader("App updates", onBack) }
        fullWidth("update status") {
            val release = state.release
            EmptyCard(Glyph.DOWN, when {
                !updates.configured -> "Updates unavailable"
                state.checking -> "Checking for updates…"
                release != null -> "${release.tag} is available"
                state.failed -> "Couldn’t check for updates"
                else -> "You’re up to date"
            }, "Installed version ${updates.installed.versionName}") {
                if (release != null) {
                    BrainButton({
                        if (context.packageManager.canRequestPackageInstalls()) install()
                        else permission.launch(updates.allowInstallsIntent())
                    }, enabled = !working) { Text(if (working) "Verifying update…" else "Update Now") }
                } else if (updates.configured && !state.checking) {
                    QuietButton({ model.checkUpdates(force = true) }) { Text("Check again") }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        state.release?.notes?.takeIf(String::isNotBlank)?.let { notes ->
            fullWidth("release notes") {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    SectionHeader("What’s new")
                    Text(notes, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
