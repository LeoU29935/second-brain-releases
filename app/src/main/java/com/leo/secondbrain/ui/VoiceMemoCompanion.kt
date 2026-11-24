package com.leo.secondbrain.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leo.secondbrain.BrainApplication
import com.leo.secondbrain.ai.VoicePhase
import com.leo.secondbrain.ai.objects
import com.leo.secondbrain.capture.RecordingResult
import com.leo.secondbrain.capture.RecordingState
import com.leo.secondbrain.data.CaptureRun
import com.leo.secondbrain.data.CaptureStatus
import com.leo.secondbrain.data.Proposal
import com.leo.secondbrain.data.SlimeFace
import com.leo.secondbrain.data.savedScheduleSummary
import org.json.JSONArray
import java.time.LocalDate
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
internal fun voiceMemoRun(app: BrainApplication, captureId: String?): CaptureRun? {
    if (captureId == null) return null
    val runs by app.repository.captureRuns.collectAsStateWithLifecycle(emptyList())
    return runs.find { it.dumpId == captureId }
}

@Composable
internal fun VoiceMemoSlime(face: SlimeFace, state: RecordingState, run: CaptureRun?) {
    val status = run?.status
    val failed = status in setOf(CaptureStatus.FAILED, CaptureStatus.ATTENTION) || state.result == RecordingResult.ERROR
    val phase = when {
        state.started != null -> VoicePhase.LISTENING
        failed || state.saving || status in setOf(CaptureStatus.QUEUED, CaptureStatus.VERIFYING) -> VoicePhase.THINKING
        else -> VoicePhase.IDLE
    }
    val motion = remember { Animatable(0f) }
    LaunchedEffect(state.captureId, phase, status) {
        motion.snapTo(0f)
        if (!ValueAnimator.areAnimatorsEnabled()) return@LaunchedEffect
        if (status == CaptureStatus.ORGANIZED) {
            motion.animateTo(-5f, Motion.press())
            motion.animateTo(0f, Motion.settle())
        } else if (phase == VoicePhase.THINKING && !failed) {
            while (ValueAnimator.areAnimatorsEnabled()) {
                motion.animateTo(-2f, Motion.glide())
                motion.animateTo(0f, Motion.glide())
            }
        }
    }
    val level = state.level.takeIf { phase == VoicePhase.LISTENING && ValueAnimator.areAnimatorsEnabled() } ?: 0f
    SlimeFaceDrawing(face, phase, deform = Offset(0f, level * .16f),
        asleep = state.started == null && !state.saving && run == null && !failed,
        concerned = failed, modifier = Modifier.size(38.dp).testTag("voice-memo-slime")
            .graphicsLayer { translationY = motion.value.dp.toPx() })
}

@Composable
internal fun VoiceMemoReceipt(app: BrainApplication, run: CaptureRun) {
    val scope = rememberCoroutineScope()
    var retryFailed by remember(run.dumpId, run.status) { mutableStateOf(false) }
    val results = remember(run.results) { JSONArray(run.results).objects() }
    val label = when (run.status) {
        CaptureStatus.QUEUED -> if (run.attempts == 0) "Organizing voice memo…" else "Waiting to retry"
        CaptureStatus.VERIFYING -> "Items saved. Checking reminders…"
        CaptureStatus.ORGANIZED -> countLabel(results.size, "item") + " organized"
        CaptureStatus.ATTENTION -> "Recording saved. Review in Brain Dump."
        CaptureStatus.FAILED -> "Recording saved. Organization failed."
        CaptureStatus.UNDONE -> "Organization undone"
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium)
            if (run.status == CaptureStatus.FAILED) QuietButton({
                scope.launch {
                    try {
                        app.subscription.retryAfterUsageLimit()
                        app.repository.retryCapture(run.dumpId)
                        app.automaticOrganization.kick()
                        retryFailed = false
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        retryFailed = true
                    }
                }
            }) { Text("Retry") }
        }
        if (retryFailed) Text("Couldn’t queue the retry. Your recording is saved.", style = MaterialTheme.typography.bodySmall)
        run.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (run.status == CaptureStatus.ORGANIZED && !app.reminders.notificationsEnabled &&
            results.any { item -> item.optJSONObject("details")?.let { Proposal.read(it).reminder != null } == true }) {
            Text("Enable notifications to receive reminders.", style = MaterialTheme.typography.bodySmall)
        }
        results.take(3).forEach { item ->
            val schedule = item.optJSONObject("details")?.let { Proposal.read(it).savedScheduleSummary(LocalDate.now(), includeInferred = true) }
            Text(listOfNotNull(item.optString("title"), schedule?.takeIf(String::isNotBlank)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (results.size > 3) Text("+${results.size - 3} more in Brain Dump", style = MaterialTheme.typography.bodySmall)
    }
}
