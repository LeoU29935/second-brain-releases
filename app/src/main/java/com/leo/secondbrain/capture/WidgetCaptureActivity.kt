package com.leo.secondbrain.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leo.secondbrain.BrainApplication
import com.leo.secondbrain.data.Appearance
import com.leo.secondbrain.ui.ActionIcon
import com.leo.secondbrain.ui.Glyph
import com.leo.secondbrain.ui.Motion
import com.leo.secondbrain.ui.QuietButton
import com.leo.secondbrain.ui.VoiceMemoReceipt
import com.leo.secondbrain.ui.VoiceMemoSlime
import com.leo.secondbrain.ui.voiceMemoRun
import com.leo.secondbrain.ui.theme.SecondBrainTheme
import kotlinx.coroutines.delay

class WidgetCaptureActivity : ComponentActivity() {
    private val app get() = application as BrainApplication
    private val capture get() = app.capture
    private var ownedRecording: String? = null
    private var startRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ownedRecording = savedInstanceState?.getString("owned")
        startRequest = savedInstanceState?.getInt("startRequest") ?: if (autoStarts(intent)) 1 else 0
        val app = application as BrainApplication
        setContent {
            val actual by app.preferences.preferences.collectAsStateWithLifecycle()
            val preferences = widgetPreferences(actual)
            val night = when (preferences.appearance) {
                Appearance.SYSTEM -> isSystemInDarkTheme()
                Appearance.LIGHT -> false
                Appearance.DARK -> true
            }
            val state by capture.state.collectAsStateWithLifecycle()
            val run = voiceMemoRun(app, state.captureId.takeIf { it == ownedRecording })
            var handledRequest by rememberSaveable { mutableIntStateOf(0) }
            var message by rememberSaveable { mutableStateOf<String?>(null) }
            val begin = {
                if (capture.state.value.started == null && !capture.state.value.saving) {
                    capture.start()
                    ownedRecording = capture.state.value.captureId
                }
            }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) begin() else message = "Microphone access is off"
            }
            val request = {
                message = null
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    begin()
                } else permission.launch(Manifest.permission.RECORD_AUDIO)
            }
            LaunchedEffect(state.saving, startRequest) {
                if (!state.saving && handledRequest != startRequest) {
                    handledRequest = startRequest
                    request()
                }
            }
            val cancel = {
                if (ownedRecording != null && ownedRecording == capture.state.value.captureId) capture.cancel()
                finish()
            }
            BackHandler(onBack = cancel)
            SecondBrainTheme(night, preferences) {
                val elapsed by produceState(0L, state.started) {
                    val start = state.started ?: return@produceState
                    while (true) {
                        value = (SystemClock.elapsedRealtime() - start) / 1_000
                        delay(250)
                    }
                }
                Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 13.dp, vertical = 9.dp),
                    contentAlignment = Alignment.BottomCenter) {
                    Surface(Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = 10.dp) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                            VoiceMemoSlime(preferences.slimeFace, state, run)
                            Column(Modifier.weight(1f)) {
                                if (run != null) VoiceMemoReceipt(app, run)
                                else Text(when {
                                    state.started != null -> "Recording"
                                    state.saving -> "Saving…"
                                    message != null -> message.orEmpty()
                                    state.message != null && (ownedRecording != null || capture.canRetry) -> state.message.orEmpty()
                                    else -> "Voice capture"
                                }, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                if (state.started != null) Text("%d:%02d".format(elapsed / 60, elapsed % 60),
                                    style = MaterialTheme.typography.labelMedium)
                            }
                            if (state.started != null && state.captureId == ownedRecording) {
                                RecordingWave(state.level)
                                ActionIcon(Glyph.CHECK, "Finish recording", capture::stop)
                            } else if (capture.canRetry) {
                                QuietButton(capture::retrySave, enabled = !state.saving) { Text("Retry") }
                            } else if (!state.saving && state.started == null &&
                                !(state.result == RecordingResult.SAVED && ownedRecording != null)) {
                                ActionIcon(Glyph.MIC, "Start recording", request)
                            }
                            ActionIcon(Glyph.CLOSE, if (state.started != null) "Cancel recording" else "Close",
                                cancel, enabled = !state.saving)
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        ownedRecording?.let { outState.putString("owned", it) }
        outState.putInt("startRequest", startRequest)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (autoStarts(intent)) startRequest++
    }

    private fun autoStarts(intent: Intent): Boolean = when (intent.component?.className) {
        WidgetCaptureActivity::class.java.name -> intent.action == OPEN_CAPTURE
        "$packageName.VoiceMemo" -> intent.action == Intent.ACTION_MAIN || intent.action == OPEN_CAPTURE
        else -> false
    }

    override fun onStop() {
        if (!isChangingConfigurations && ownedRecording != null && ownedRecording == capture.state.value.captureId) capture.stop()
        super.onStop()
    }
}

@Composable
internal fun RecordingWave(level: Float, modifier: Modifier = Modifier) {
    val height by animateFloatAsState(level, Motion.press(), label = "Microphone level")
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(24.dp, 21.dp)) {
        val factors = listOf(.53f, .87f, 1f, .72f, .43f)
        for ((index, factor) in factors.withIndex()) {
            val x = size.width * (index + .5f) / factors.size
            val half = size.height * (.14f + height.coerceIn(0f, 1f) * factor * .29f)
            drawLine(color, Offset(x, center.y - half), Offset(x, center.y + half),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}
