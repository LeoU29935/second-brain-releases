package com.leo.secondbrain.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leo.secondbrain.BrainApplication
import com.leo.secondbrain.data.CaptureStatus
import com.leo.secondbrain.data.SlimeFace
import com.leo.secondbrain.capture.RecordingResult
import com.leo.secondbrain.capture.RecordingWave
import kotlinx.coroutines.delay
import kotlin.math.abs

internal enum class NavigationTouch { TAP, SWIPE_UP, CANCEL }

internal fun navigationTouch(delta: Offset, exceededSlop: Boolean, slop: Float, swipeDistance: Float): NavigationTouch =
    if (delta.y < -swipeDistance && abs(delta.x) < -delta.y * 1.25f) NavigationTouch.SWIPE_UP
    else if (!exceededSlop && delta.getDistance() <= slop) NavigationTouch.TAP else NavigationTouch.CANCEL

@Composable
internal fun CaptureDock(
    app: BrainApplication, face: SlimeFace, modifier: Modifier, selected: Int?, onNavigate: (Int) -> Unit,
    onCreate: () -> Unit, onCalendar: () -> Unit, onAccessibleRecord: () -> Unit,
    onFeedback: (String) -> Unit, widget: Boolean = false
) {
    val controller = app.capture
    val recording by controller.state.collectAsStateWithLifecycle()
    val run = voiceMemoRun(app, recording.captureId)
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val themed = MaterialTheme.colorScheme
    val withTabs = selected != null
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentNavigate by rememberUpdatedState(onNavigate)
    val currentCreate by rememberUpdatedState(onCreate)
    val currentCalendar by rememberUpdatedState(onCalendar)
    val feedback by rememberUpdatedState(onFeedback)
    var ownRecording by remember { mutableStateOf(false) }
    var inCancelZone by remember { mutableStateOf(false) }
    var awaitsResult by remember { mutableStateOf(false) }
    var dockCaptureId by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<RecordingResult?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var taps by remember { mutableIntStateOf(0) }
    val response = remember { Animatable(1f) }
    LaunchedEffect(taps) {
        if (taps > 0) {
            response.snapTo(.93f)
            response.animateTo(1f, Motion.press())
        }
    }
    val doCreate = { taps++; currentCreate() }
    val morph by animateFloatAsState(if (ownRecording && recording.started != null) 1f else 0f,
        Motion.press(), label = "Microphone morph")
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        feedback(if (it) "Hold the microphone to record." else "Microphone access is off.")
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (ownRecording) controller.stop()
    }
    LaunchedEffect(recording.result, awaitsResult) {
        if (awaitsResult && recording.result != null) {
            result = recording.result
            resultMessage = recording.message
            awaitsResult = false
        }
    }
    LaunchedEffect(result) {
        if (result != null) {
            delay(if (result == RecordingResult.SAVED) 820 else 1_600)
            result = null
            resultMessage = null
        }
    }
    LaunchedEffect(run?.status, dockCaptureId) {
        if (run?.dumpId == dockCaptureId && run?.status == CaptureStatus.ORGANIZED) {
            delay(4_000)
            dockCaptureId = null
        }
    }
    val recordingPulse by animateFloatAsState(recording.level * morph, Motion.press(), label = "Recording pulse")

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (run != null && run.dumpId == dockCaptureId) {
            Surface(Modifier.widthIn(max = 400.dp).padding(bottom = 6.dp), shape = cardShape,
                color = themed.surfaceContainerHigh) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    VoiceMemoSlime(face, recording, run)
                    Column(Modifier.weight(1f, fill = false)) { VoiceMemoReceipt(app, run) }
                    ActionIcon(Glyph.CLOSE, "Dismiss voice memo status", { dockCaptureId = null })
                }
            }
        }
        val elapsed by produceState(0L, recording.started) {
            val since = recording.started ?: return@produceState
            while (true) {
                value = (SystemClock.elapsedRealtime() - since) / 1_000
                delay(250)
            }
        }
        androidx.compose.animation.AnimatedVisibility(ownRecording && recording.started != null,
            enter = Motion.reveal(), exit = Motion.conceal()) {
            Surface(shape = CircleShape, color = if (inCancelZone) themed.errorContainer else themed.surfaceContainerHigh,
                modifier = Modifier.padding(bottom = 6.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!inCancelZone) RecordingWave(recording.level)
                    Text(if (inCancelZone) "Release to cancel" else "%d:%02d".format(elapsed / 60, elapsed % 60),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            result in setOf(RecordingResult.ERROR, RecordingResult.EMPTY) && resultMessage != null,
            enter = Motion.reveal(), exit = Motion.conceal()
        ) {
            Surface(shape = CircleShape, color = themed.surfaceContainerHigh, modifier = Modifier.padding(bottom = 5.dp)) {
                Text(resultMessage.orEmpty(), Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall, color = themed.onSurfaceVariant)
            }
        }
        Surface(
            (if (withTabs) Modifier.widthIn(max = 430.dp).fillMaxWidth().testTag("workspace-navigation") else Modifier)
                .semantics {
                    if (withTabs) customActions = listOf(CustomAccessibilityAction("Open calendar") { onCalendar(); true })
                }
                .pointerInput(withTabs, controller, widget, rtl) {
                    val slop = viewConfiguration.touchSlop
                    val swipe = 23.dp.toPx()
                    val cancelDistance = 58.dp.toPx()
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        first.consume()
                        val x = if (rtl) size.width - first.position.x else first.position.x
                        val tabsEnd = size.width - 64.dp.toPx()
                        val onPlus = !withTabs || x >= tabsEnd
                        var position = first.position
                        var released = false
                        var crossed = false
                        var interrupted = false
                        var started = false
                        // This owner resolves the gesture before child buttons receive any press indication.
                        try {
                            if (onPlus) {
                                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    while (!released && !crossed && !interrupted) {
                                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == first.id }
                                        if (change == null || change.isConsumed) interrupted = true else {
                                            position = change.position
                                            released = !change.pressed
                                            crossed = (position - first.position).getDistance() > slop
                                            change.consume()
                                        }
                                    }
                                    true
                                } ?: run {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                                        PackageManager.PERMISSION_GRANTED) {
                                        permission.launch(Manifest.permission.RECORD_AUDIO)
                                        interrupted = true
                                    } else if (controller.state.value.started == null && !controller.state.value.saving) {
                                        controller.start()
                                        started = controller.state.value.started != null
                                        ownRecording = started
                                        dockCaptureId = controller.state.value.captureId.takeIf { started }
                                        awaitsResult = started || controller.state.value.result == RecordingResult.ERROR
                                        result = null
                                        if (started) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        else {
                                            interrupted = true
                                        }
                                    } else interrupted = true
                                }
                            }
                            while (!released && !interrupted) {
                                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == first.id }
                                if (change == null || change.isConsumed) interrupted = true else {
                                    position = change.position
                                    released = !change.pressed
                                    val delta = position - first.position
                                    crossed = crossed || delta.getDistance() > slop
                                    if (started) inCancelZone = delta.getDistance() >= cancelDistance
                                    change.consume()
                                }
                            }
                            if (!started && released && !interrupted) {
                                when (navigationTouch(position - first.position, crossed, slop, swipe)) {
                                    NavigationTouch.SWIPE_UP -> if (withTabs) currentCalendar()
                                    NavigationTouch.TAP -> if (onPlus) doCreate()
                                        else currentNavigate((((x - 7.dp.toPx()) / (tabsEnd - 7.dp.toPx())) * 3f)
                                            .toInt().coerceIn(0, 2))
                                    NavigationTouch.CANCEL -> Unit
                                }
                            }
                        } finally {
                            if (started && controller.state.value.started != null) {
                                if (inCancelZone) controller.cancel() else controller.stop()
                            }
                            ownRecording = false
                            inCancelZone = false
                        }
                    }
                },
            shape = CircleShape, color = if (withTabs) themed.surfaceContainer else themed.surfaceContainerHigh,
            border = if (withTabs) BorderStroke(1.dp, themed.outlineVariant.copy(alpha = .45f)) else null,
            shadowElevation = if (withTabs) 3.dp else 0.dp
        ) {
            Row(Modifier.padding(horizontal = if (withTabs) 7.dp else 0.dp, vertical = if (withTabs) 5.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (withTabs) {
                    NavItem("Home", Glyph.HOME, selected == 0, Modifier.weight(1f)) { onNavigate(0) }
                    NavItem("Brain Dump", Glyph.INBOX, selected == 1, Modifier.weight(1f)) { onNavigate(1) }
                    NavItem("Settings", Glyph.SETTINGS, selected == 2, Modifier.weight(1f)) { onNavigate(2) }
                }
                Surface(onClick = doCreate, modifier = Modifier.size(if (widget) 72.dp else 54.dp).graphicsLayer {
                    scaleX = response.value * (1f - .06f * morph + recordingPulse * .07f)
                    scaleY = response.value * (1f + .07f * morph - recordingPulse * .03f)
                }.semantics {
                    customActions = listOf(CustomAccessibilityAction("Open voice recorder") {
                        onAccessibleRecord(); true
                    })
                }, shape = CircleShape, color = if (inCancelZone) themed.error else themed.primary,
                    contentColor = if (inCancelZone) themed.onError else themed.onPrimary) {
                    Box(contentAlignment = Alignment.Center) {
                        if (recording.saving && awaitsResult) CircularProgressIndicator(Modifier.size(23.dp),
                            strokeWidth = 2.dp, color = themed.onPrimary)
                        else if (ownRecording || (run != null && result == RecordingResult.SAVED)) VoiceMemoSlime(face, recording, run)
                        else {
                            if (!widget) BrainIcon(Glyph.ADD, Modifier.size(26.dp).graphicsLayer {
                                alpha = 1f - morph
                                rotationZ = morph * 38f
                                scaleX = 1f - morph * .37f
                                scaleY = scaleX
                            }, description = "Create")
                            BrainIcon(Glyph.MIC, Modifier.size(26.dp).graphicsLayer {
                                alpha = if (widget) 1f else morph
                                scaleX = .76f + morph * .24f
                                scaleY = scaleX
                            }, description = if (widget) "Voice capture" else null)
                        }
                    }
                }
            }
        }
    }
}
