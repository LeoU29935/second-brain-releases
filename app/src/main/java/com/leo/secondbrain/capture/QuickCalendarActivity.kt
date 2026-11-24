package com.leo.secondbrain.capture

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.leo.secondbrain.data.asProposal
import com.leo.secondbrain.ui.ItemDetails
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leo.secondbrain.BrainApplication
import com.leo.secondbrain.MainActivity
import com.leo.secondbrain.data.Appearance
import com.leo.secondbrain.reminders.OPEN_REMINDER
import com.leo.secondbrain.reminders.ReminderTarget
import com.leo.secondbrain.ui.BrainViewModel
import com.leo.secondbrain.ui.CalendarPanel
import com.leo.secondbrain.ui.QuietButton
import com.leo.secondbrain.ui.theme.SecondBrainTheme
import java.time.LocalDate

internal const val OPEN_QUICK_CALENDAR = "com.leo.secondbrain.OPEN_QUICK_CALENDAR"
internal const val QUICK_CALENDAR_ACTION = "com.leo.secondbrain.QUICK_CALENDAR_ACTION"
internal const val QUICK_CALENDAR_DAY = "quickCalendarDay"
internal const val QUICK_CALENDAR_EDIT = "quickCalendarEdit"

class QuickCalendarActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as BrainApplication
        setContent {
            val model = viewModel { BrainViewModel(app) }
            val state by model.library.collectAsStateWithLifecycle()
            val preferences by model.preferences.collectAsStateWithLifecycle()
            val undo by model.undo.collectAsStateWithLifecycle()
            val error by model.error.collectAsStateWithLifecycle()
            val snackbar = remember { SnackbarHostState() }
            val today by produceState(LocalDate.now()) {
                while (true) {
                    value = LocalDate.now()
                    kotlinx.coroutines.delay(15_000)
                }
            }
            var selected by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
            var detailId by rememberSaveable { mutableStateOf<String?>(null) }
            val night = when (preferences.appearance) {
                Appearance.SYSTEM -> isSystemInDarkTheme()
                Appearance.DARK -> true
                Appearance.LIGHT -> false
            }
            LaunchedEffect(undo) {
                val action = undo ?: return@LaunchedEffect
                val result = snackbar.showSnackbar(action.message, "Undo")
                model.finishUndo(action, result == SnackbarResult.ActionPerformed)
            }
            LaunchedEffect(error) {
                error?.let {
                    snackbar.showSnackbar(it)
                    model.dismissError()
                }
            }
            SecondBrainTheme(night, preferences) {
                if (state.loading || state.loadFailed) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
                        Surface(shape = androidx.compose.material3.MaterialTheme.shapes.large) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (state.loading) CircularProgressIndicator(Modifier.size(24.dp))
                                else Text("Couldn’t load the calendar.")
                                QuietButton(::finish) { Text("Close") }
                            }
                        }
                    }
                } else {
                    CalendarPanel(state, today, LocalDate.ofEpochDay(selected), { selected = it.toEpochDay() }, ::finish,
                        { openAction("task", it) }, { openAction("event", it) },
                        { detailId = it.entry.id },
                        { item, checked -> model.setCompleted(item.entry.id, checked, item.entry.deadline) },
                        { detailId = it.id }, { openAction("tasks", null) },
                        null, {}, { id, day -> model.editOccurrence(id, day) { detailId = it } }, snackbar)
                    val task = state.entries.firstOrNull { it.entry.id == detailId }
                    val event = state.events.firstOrNull { it.id == detailId }
                    val detail = task?.asProposal() ?: event?.asProposal(
                        state.eventTags.filter { it.eventId == detailId }.map { it.tagId }.toSet())
                    if (detail != null) Dialog(onDismissRequest = { detailId = null }) {
                        Surface(Modifier.fillMaxWidth().heightIn(max = 560.dp), shape = MaterialTheme.shapes.extraLarge) {
                            ItemDetails(detail, task?.entry, state, { detailId = null }, {
                                openItem(requireNotNull(detailId), event != null)
                            }) { checked ->
                                task?.entry?.let { model.setCompleted(it.id, checked, it.deadline) }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as BrainApplication).reminders.requestRefresh()
    }

    private fun openItem(id: String, event: Boolean) {
        open(Intent(this, MainActivity::class.java).setAction(OPEN_REMINDER).setData(ReminderTarget(id, event).uri)
            .putExtra(QUICK_CALENDAR_EDIT, true))
    }

    private fun openAction(action: String, date: LocalDate?) {
        open(Intent(this, MainActivity::class.java).setAction(QUICK_CALENDAR_ACTION)
            .putExtra("operation", action).putExtra(QUICK_CALENDAR_DAY, date?.toEpochDay() ?: Long.MIN_VALUE))
    }

    private fun open(intent: Intent) {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
