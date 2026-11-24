package com.leo.secondbrain.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class Glyph {
    BRAND, HOME, COLLECTIONS, CALENDAR, TASK, SEARCH, SETTINGS, ADD, FOLDER, INBOX, NOTE,
    BACK, NEXT, DOWN, CLOSE, EDIT, DELETE, TAG, CHECK, MORE, PALETTE, FLAG, BELL, MIC, LIST, CLOCK, SPARKLE, STAR
}

@Composable
internal fun BrainIcon(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    description: String? = null,
    tint: Color = LocalContentColor.current
) {
    val semantics = if (description == null) Modifier else Modifier.semantics {
        contentDescription = description
    }
    val paths = glyphs.getValue(glyph)
    Canvas(modifier.size(22.dp).then(semantics)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            if (!paths.outline.isEmpty) drawPath(paths.outline, tint, style = iconStroke)
            if (!paths.fill.isEmpty) drawPath(paths.fill, tint)
        }
    }
}

private val iconStroke = Stroke(1.65f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private data class GlyphPaths(val outline: Path, val fill: Path)
private val glyphs by lazy { Glyph.entries.associateWith(::makePaths) }

private fun makePaths(glyph: Glyph): GlyphPaths {
    val outline = Path()
    val fill = Path()

    fun line(vararg points: Number) {
        outline.moveTo(points[0].toFloat(), points[1].toFloat())
        for (index in 2 until points.size step 2) {
            outline.lineTo(points[index].toFloat(), points[index + 1].toFloat())
        }
    }

    fun box(x: Int, y: Int, width: Int, height: Int, radius: Float = 2f) {
        outline.addRoundRect(RoundRect(
            Rect(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat()),
            CornerRadius(radius)
        ))
    }

    fun oval(x: Number, y: Number, radius: Float) =
        Rect(x.toFloat() - radius, y.toFloat() - radius, x.toFloat() + radius, y.toFloat() + radius)

    fun circle(x: Number, y: Number, radius: Float) { outline.addOval(oval(x, y, radius)) }
    fun dot(x: Number, y: Number, radius: Float) { fill.addOval(oval(x, y, radius)) }

    when (glyph) {
        Glyph.BRAND -> {
            line(12, 2, 14.4, 9.6, 22, 12, 14.4, 14.4, 12, 22, 9.6, 14.4, 2, 12, 9.6, 9.6, 12, 2)
        }
        Glyph.HOME -> {
            line(3, 11, 12, 3.5, 21, 11)
            line(5.5, 9, 5.5, 20, 10, 20, 10, 14, 14, 14, 14, 20, 18.5, 20, 18.5, 9)
        }
        Glyph.COLLECTIONS -> {
            box(3, 3, 7, 7, 1.5f)
            box(14, 3, 7, 7, 1.5f)
            box(3, 14, 7, 7, 1.5f)
            box(14, 14, 7, 7, 1.5f)
        }
        Glyph.CALENDAR -> {
            box(3, 5, 18, 16)
            line(3, 10, 21, 10)
            line(8, 3, 8, 7)
            line(16, 3, 16, 7)
            dot(12f, 15.5f, 1.5f)
        }
        Glyph.TASK -> {
            box(3, 3, 18, 18, 4f)
            line(7.5, 12, 10.5, 15, 16.5, 9)
        }
        Glyph.SEARCH -> {
            circle(10.5, 10.5, 6f)
            line(15, 15, 21, 21)
        }
        Glyph.SETTINGS -> {
            circle(12, 12, 3f)
            circle(12, 12, 7.5f)
            repeat(8) {
                val angle = it * Math.PI / 4
                val x = kotlin.math.cos(angle)
                val y = kotlin.math.sin(angle)
                line(12 + 7.5 * x, 12 + 7.5 * y, 12 + 10 * x, 12 + 10 * y)
            }
        }
        Glyph.SPARKLE -> {
            line(10, 4, 12, 10, 18, 12, 12, 14, 10, 20, 8, 14, 2, 12, 8, 10, 10, 4)
            line(19, 3, 19, 9)
            line(16, 6, 22, 6)
        }
        Glyph.CLOCK -> {
            circle(12, 12, 8.5f)
            line(12, 6, 12, 12, 16, 14)
        }
        Glyph.ADD -> {
            line(12, 5, 12, 19)
            line(5, 12, 19, 12)
        }
        Glyph.FOLDER -> line(3, 6, 10, 6, 12, 8.5, 21, 8.5, 21, 19, 3, 19, 3, 6)
        Glyph.INBOX -> {
            line(3, 13, 6, 5, 18, 5, 21, 13, 21, 20, 3, 20, 3, 13, 8, 13,
                10, 16, 14, 16, 16, 13, 21, 13)
        }
        Glyph.NOTE -> {
            box(5, 3, 14, 18)
            line(8, 8, 16, 8)
            line(8, 12, 16, 12)
            line(8, 16, 13, 16)
        }
        Glyph.BACK -> {
            line(19, 12, 5, 12)
            line(11, 6, 5, 12, 11, 18)
        }
        Glyph.NEXT -> line(9, 5, 16, 12, 9, 19)
        Glyph.DOWN -> line(5, 9, 12, 16, 19, 9)
        Glyph.CLOSE -> {
            line(6, 6, 18, 18)
            line(6, 18, 18, 6)
        }
        Glyph.EDIT -> {
            line(4, 16, 15.5, 4.5, 19.5, 8.5, 8, 20, 4, 20, 4, 16)
            line(13.5, 6.5, 17.5, 10.5)
        }
        Glyph.DELETE -> {
            line(4, 6, 20, 6)
            line(9, 6, 9, 3, 15, 3, 15, 6)
            line(6, 6, 7, 21, 17, 21, 18, 6)
            line(10, 10, 10.5, 17)
            line(14, 10, 13.5, 17)
        }
        Glyph.TAG -> {
            line(3, 4, 11, 4, 21, 14, 14, 21, 3, 10, 3, 4)
            dot(7.5f, 8f, 1.4f)
        }
        Glyph.CHECK -> line(5, 12, 10, 17, 19, 7)
        Glyph.STAR -> line(12, 2.5, 15, 8.5, 22, 9.5, 17, 14.5, 18.2, 21.5,
            12, 18.2, 5.8, 21.5, 7, 14.5, 2, 9.5, 9, 8.5, 12, 2.5)
        Glyph.MORE -> listOf(5f, 12f, 19f).forEach { dot(it, 12f, 1.4f) }
        Glyph.PALETTE -> {
            circle(12, 12, 8.5f)
            listOf(8f, 12f, 16f).forEach { dot(it, 10f, 1.05f) }
            dot(12f, 15f, 1.05f)
        }
        Glyph.FLAG -> line(5, 21, 5, 4, 16, 4, 14, 8, 17, 12, 5, 12)
        Glyph.BELL -> {
            line(4, 17, 6, 14, 6, 9, 8, 5, 11, 4, 13, 4, 16, 5, 18, 9, 18, 14, 20, 17, 4, 17)
            line(10, 20, 12, 21, 14, 20)
        }
        Glyph.MIC -> {
            box(9, 3, 6, 12, 3f)
            line(5, 11, 5, 14, 8, 18, 16, 18, 19, 14, 19, 11)
            line(12, 18, 12, 22)
        }
        Glyph.LIST -> {
            line(5, 6, 19, 6)
            line(5, 12, 19, 12)
            line(5, 18, 19, 18)
        }
    }
    return GlyphPaths(outline, fill)
}
