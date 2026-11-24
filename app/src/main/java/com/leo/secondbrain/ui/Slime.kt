package com.leo.secondbrain.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leo.secondbrain.ai.VoicePhase
import com.leo.secondbrain.data.SlimeFace
import kotlin.math.sin

@Composable
internal fun WorkspaceHeader(onSearch: (() -> Unit)?, onSettings: (() -> Unit)?, onCalendar: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.header).padding(horizontal = Space.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Second Brain", Modifier.weight(1f).padding(vertical = 4.dp), style = MaterialTheme.typography.titleLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onSearch != null) ActionIcon(Glyph.SEARCH, "Search workspace", onSearch)
        if (onCalendar != null) ActionIcon(Glyph.CALENDAR, "Open calendar", onCalendar)
        if (onSettings != null) ActionIcon(Glyph.SETTINGS, "Settings", onSettings)
    }
}

@Composable
internal fun SlimeFaceDrawing(
    face: SlimeFace, phase: VoicePhase = VoicePhase.IDLE, connecting: Boolean = false,
    deform: Offset = Offset.Zero, edge: Int = 0, asleep: Boolean = false,
    concerned: Boolean = false, modifier: Modifier = Modifier.size(42.dp)
) {
    val palette = MaterialTheme.colorScheme
    val color by androidx.compose.animation.animateColorAsState(when {
        concerned -> palette.errorContainer
        asleep -> palette.surfaceContainerHighest
        connecting || phase == VoicePhase.THINKING -> palette.tertiaryContainer
        else -> palette.primaryContainer
    }, label = "Assistant wake color")
    val feature by androidx.compose.animation.animateColorAsState(when {
        concerned -> palette.onErrorContainer
        asleep -> palette.onSurfaceVariant
        connecting || phase == VoicePhase.THINKING -> palette.onTertiaryContainer
        else -> palette.onPrimaryContainer
    }, label = "Assistant expression color")
    val pulse = remember { mutableFloatStateOf(0f) }
    val blink = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(connecting, phase, asleep, edge) {
        pulse.floatValue = 0f
        blink.floatValue = 1f
        if (edge != 0 && ValueAnimator.areAnimatorsEnabled()) {
            val epoch = withFrameNanos { it }
            while (true) {
                withFrameNanos {
                    val elapsed = (it - epoch) / 1_000_000.0
                    val period = when {
                        phase == VoicePhase.SPEAKING -> 160.0
                        phase == VoicePhase.LISTENING -> 380.0
                        connecting || phase == VoicePhase.THINKING -> 240.0
                        else -> 790.0
                    }
                    pulse.floatValue = sin(elapsed / period).toFloat()
                    val blinkTime = (elapsed + 420) % 6_700
                    blink.floatValue = (.16f + .84f * (kotlin.math.abs(blinkTime - 90) / 90).toFloat()).coerceIn(.16f, 1f)
                }
                kotlinx.coroutines.delay(33)
            }
        }
    }
    val dock by animateFloatAsState(edge.toFloat(), Motion.settle(), label = "Slime bezel contact")
    val body = remember { Path() }
    Canvas(modifier) {
        val wave = pulse.floatValue
        val amount = kotlin.math.abs(dock).coerceIn(0f, 1f)
        scale(size.width / 42, size.height / 42, pivot = Offset.Zero) {
            val pivot = Offset(21f + dock.coerceIn(-1f, 1f) * 21f, 23f)
            val breath = if (phase == VoicePhase.IDLE) .016f else .034f
            scale(1f + deform.x * .43f, 1f + deform.y * .55f + wave * breath, pivot = pivot) {
                drawSlimeOutline(body, amount, dock > 0)
                drawPath(body, color)
                drawPath(body, feature.copy(alpha = .42f), style = Stroke(1.05f))
                if (face == SlimeFace.PLAYFUL) {
                    drawCircle(feature.copy(alpha = .22f), 2.5f, Offset(11f, 27f))
                    drawCircle(feature.copy(alpha = .22f), 2.5f, Offset(31f, 27f))
                }
                val faceShift = -dock * 2f
                for (x in listOf(15f + faceShift, 27f + faceShift)) when {
                    asleep -> drawArc(feature, 20f, 140f, false, Offset(x - 3f, 19f), Size(6f, 4f),
                        style = Stroke(1.4f, cap = StrokeCap.Round))
                    face == SlimeFace.MINIMAL -> {
                        drawLine(feature, Offset(x - 1.5f, 21f), Offset(x, 19.5f), 1.5f, StrokeCap.Round)
                        drawLine(feature, Offset(x, 19.5f), Offset(x + 1.5f, 21f), 1.5f, StrokeCap.Round)
                    }
                    else -> {
                        val eyeHeight = 3.4f * blink.floatValue
                        drawOval(feature, Offset(x + deform.x * 3 - 1.7f, 21f - eyeHeight / 2), Size(3.4f, eyeHeight))
                    }
                }
                if (asleep) {
                    drawCircle(feature.copy(alpha = .7f), 1.15f, Offset(21f + faceShift, 29f), style = Stroke(.75f))
                } else if (phase == VoicePhase.SPEAKING) {
                    val opening = 3f + kotlin.math.abs(wave) * 3f
                    drawOval(feature, Offset(18f, 26f), Size(6f, opening))
                } else if (concerned) {
                    drawArc(feature, 200f, 140f, false, Offset(18f, 28f), Size(6f, 4f),
                        style = Stroke(1.5f, cap = StrokeCap.Round))
                } else if (connecting || phase == VoicePhase.THINKING) {
                    for (index in 0..2) drawCircle(feature.copy(alpha = .65f), .85f,
                        Offset(17f + index * 4, 28f + wave * (index - 1)))
                } else if (face == SlimeFace.MINIMAL) {
                    drawLine(feature, Offset(19f, 28f), Offset(23f, 28f), 1.3f, StrokeCap.Round)
                } else {
                    drawArc(feature, 5f, 170f, false, Offset(17f, 23.5f), Size(8f, 7f),
                        style = Stroke(1.5f, cap = StrokeCap.Round))
                }
                if (phase == VoicePhase.LISTENING) drawCircle(palette.primary, 2f, Offset(35f, 11f))
            }
        }
    }
}

private fun drawSlimeOutline(path: Path, contact: Float, right: Boolean) {
    fun point(index: Int): Float {
        val axis = freeOutline[index] + (bezelOutline[index] - freeOutline[index]) * contact
        return if (right && index % 2 == 0) 42f - axis else axis
    }
    path.reset()
    path.moveTo(point(0), point(1))
    for (index in 2 until freeOutline.size step 6) {
        path.cubicTo(point(index), point(index + 1), point(index + 2),
            point(index + 3), point(index + 4), point(index + 5))
    }
    path.close()
}

private val freeOutline = floatArrayOf(5f, 24f,
    6f, 16f, 11f, 8f, 21f, 7f, 31f, 6f, 37f, 16f, 37f, 27f,
    37f, 35f, 31f, 38f, 21f, 38f, 12f, 38f, 6f, 36f, 5f, 31f, 4f, 29f, 4f, 26f, 5f, 24f)
private val bezelOutline = floatArrayOf(-8f, 8f,
    1f, 8f, 10f, 6f, 19f, 7f, 30f, 7f, 37f, 15f, 37f, 26f,
    37f, 35f, 29f, 38f, 20f, 38f, 10f, 38f, 1f, 37f, -8f, 37f, -8f, 30f, -8f, 16f, -8f, 8f)
