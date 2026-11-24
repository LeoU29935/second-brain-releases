package com.leo.secondbrain.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import java.util.Locale

private data class CollectionIcon(val label: String, val path: String) {
    val id = label.uppercase(Locale.ROOT).replace(' ', '_')
}

internal val collectionIconIds by lazy { icons.values.flatten().map { it.id }.toSet() }

private val icons = mapOf(
    "Organization" to listOf(
        CollectionIcon("Folder", "M3 6h7l2 3h9v10H3Z"),
        CollectionIcon("Archive", "M3 4h18v5H3ZM5 9v11h14V9M10 13h4"),
        CollectionIcon("Briefcase", "M3 8h18v12H3ZM8 8V4h8v4M3 13h18M10 12v3h4v-3"),
        CollectionIcon("Notebook", "M6 3h14v18H6ZM6 7H3m3 5H3m3 5H3m7-9h6m-6 4h6"),
        CollectionIcon("Calendar", "M4 5h16v15H4ZM8 3v4m8-4v4M4 10h16m-12 4h2m4 0h2m-8 3h2"),
        CollectionIcon("Checklist", "M5 4h14v16H5ZM8 9l1 1 2-2m2 1h3m-8 5 1 1 2-2m2 1h3"),
        CollectionIcon("Bookmark", "M6 3h12v18l-6-4-6 4Z"),
        CollectionIcon("Label", "M3 6h11l7 6-7 6H3Zm5 6h.1"),
        CollectionIcon("Grid", "M4 4h6v6H4ZM14 4h6v6h-6ZM4 14h6v6H4ZM14 14h6v6h-6Z"),
        CollectionIcon("Link", "M9 10l-4 4a4 4 0 0 0 6 6l4-4m0-2 4-4a4 4 0 0 0-6-6L9 8m0 7 6-6")
    ),
    "Home and life" to listOf(
        CollectionIcon("Home", "M3 11l9-7 9 7M6 9v11h12V9m-8 11v-6h4v6"),
        CollectionIcon("Heart", "M12 20 4 12a5 5 0 0 1 8-6 5 5 0 0 1 8 6Z"),
        CollectionIcon("People", "M12 9a3 3 0 1 0 0-6 3 3 0 1 0 0 6M5 20v-2a7 7 0 0 1 14 0v2M3 10a2.5 2.5 0 0 1 0-5m18 0a2.5 2.5 0 0 1 0 5"),
        CollectionIcon("Plant", "M12 20v-8m0 4C5 16 3 12 4 7c4 0 8 4 8 7m0 2c7-1 9-5 8-9-5 0-8 4-8 7M8 21h8"),
        CollectionIcon("Meal", "M4 4v7c0 3 6 3 6 0V4M7 4v16m11-16c-3 4-3 8 0 8h2v8m0-16v8"),
        CollectionIcon("Shopping", "M3 5h2l2 11h12l2-8H6m3 12h.1m8 0h.1"),
        CollectionIcon("Hanger", "M10 7a2 2 0 1 1 3 2l-1 1v2M3 18l9-6 9 6v2H3Z"),
        CollectionIcon("Key", "M10 13a5 5 0 1 1 3-4l8 0v3h-2v3h-3v-3h-4"),
        CollectionIcon("Medical", "M4 4h16v16H4ZM12 8v8m-4-4h8"),
        CollectionIcon("Dumbbell", "M3 8h3v8H3ZM18 8h3v8h-3ZM6 11h12m-12 2h12m-10-7v12m8-12v12")
    ),
    "Work and study" to listOf(
        CollectionIcon("Laptop", "M5 5h14v11H5ZM3 19h18M9 19h6"),
        CollectionIcon("Code", "M8 7l-5 5 5 5m8-10 5 5-5 5m-3-12-2 14"),
        CollectionIcon("Book", "M12 7c-3-3-6-3-9-2v14c3-1 6-1 9 2 3-3 6-3 9-2V5c-3-1-6-1-9 2v14"),
        CollectionIcon("Graduation", "M2 9l10-5 10 5-10 5Zm5 3v5l5 3 5-3v-5m5-3v7"),
        CollectionIcon("Chart", "M4 4v16h16M8 16v-5m4 5V7m4 9V4"),
        CollectionIcon("Light bulb", "M8 15c0-2-3-3-3-7a7 7 0 1 1 14 0c0 4-3 5-3 7m-8 2h8m-7 3h6"),
        CollectionIcon("Speech", "M4 5h16v12H9l-5 4ZM8 10h8m-8 3h5"),
        CollectionIcon("Globe", "M12 3a9 9 0 1 0 0 18 9 9 0 1 0 0-18Zm0 0c5 5 5 13 0 18-5-5-5-13 0-18ZM3 12h18"),
        CollectionIcon("Target", "M12 3a9 9 0 1 0 0 18 9 9 0 1 0 0-18Zm0 4a5 5 0 1 0 0 10 5 5 0 1 0 0-10Zm0 5h.1"),
        CollectionIcon("Puzzle", "M4 4h6v3a2 2 0 0 0 4 0V4h6v6h-3a2 2 0 0 0 0 4h3v6h-6v-3a2 2 0 0 0-4 0v3H4Z")
    ),
    "Creative" to listOf(
        CollectionIcon("Music", "M8 16V5l12-2v11M4 16a2 2 0 1 0 4 0 2 2 0 1 0-4 0m12-2a2 2 0 1 0 4 0 2 2 0 1 0-4 0M8 8l12-2"),
        CollectionIcon("Headphones", "M4 14v-2a8 8 0 0 1 16 0v2M4 13h4v7H4Zm12 0h4v7h-4Z"),
        CollectionIcon("Camera", "M3 7h5l2-3h4l2 3h5v13H3Zm9 3a4 4 0 1 0 0 8 4 4 0 1 0 0-8Z"),
        CollectionIcon("Photo", "M3 4h18v16H3Zm0 13 6-6 4 4 3-3 5 5m-5-10h.1"),
        CollectionIcon("Film", "M4 4h16v16H4Zm4 0v16m8-16v16M4 9h4m8 0h4M4 15h4m8 0h4"),
        CollectionIcon("Brush", "M10 14 18 4c1-1 3 1 2 2l-8 10m-2-2c-5-2-3 4-6 6 4 1 8-1 8-4Z"),
        CollectionIcon("Pen", "M4 16 16 4l4 4L8 20H4Zm10-10 4 4"),
        CollectionIcon("Spark", "M12 3l2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5Z"),
        CollectionIcon("Microphone", "M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1-6 0Zm-3 5a6 6 0 0 0 12 0m-6 6v4m-3 0h6"),
        CollectionIcon("Palette", "M12 3a9 9 0 0 0-9 9c0 6 5 9 9 9 2 0 3-1 2-3-1-2 1-3 3-3h1c2 0 3-1 3-3a9 9 0 0 0-9-9ZM7 10h.1m4-3h.1m5 2h.1m-9 6h.1")
    ),
    "Places and plans" to listOf(
        CollectionIcon("Map pin", "M12 21s-7-7-7-12a7 7 0 0 1 14 0c0 5-7 12-7 12Zm0-15a3 3 0 1 0 0 6 3 3 0 1 0 0-6Z"),
        CollectionIcon("Map", "M3 6l6-2 6 2 6-2v14l-6 2-6-2-6 2Zm6-2v14m6-12v14"),
        CollectionIcon("Mountain", "M3 20 10 8l4 6 3-4 4 10Zm5-9 2 2 2-2"),
        CollectionIcon("Sun", "M12 8a4 4 0 1 0 0 8 4 4 0 1 0 0-8Zm0-5v2m0 14v2M3 12h2m14 0h2M6 6l1 1m10 10 1 1M18 6l-1 1M7 17l-1 1"),
        CollectionIcon("Moon", "M15 3a9 9 0 1 0 6 13A8 8 0 0 1 15 3Z"),
        CollectionIcon("Plane", "M12 3v18m0-11 8 5v2l-8-2-8 2v-2l8-5m0 9 4 2m-4-2-4 2"),
        CollectionIcon("Train", "M6 4h12v13H6Zm0 7h12M9 20l2-3m4 3-2-3m-4-3h.1m6 0h.1"),
        CollectionIcon("Bicycle", "M6 13a4 4 0 1 0 0 8 4 4 0 1 0 0-8Zm12 0a4 4 0 1 0 0 8 4 4 0 1 0 0-8Zm-12 4 4-8m0 0 3 8H6m7 0 4-8m-1-3h3l-1 11"),
        CollectionIcon("Car", "M4 11l2-5h12l2 5m-16 0v7h16v-7Zm0 7v2m16-2v2m-13-6h2m6 0h2"),
        CollectionIcon("Flag", "M5 21V4c5-4 9 4 14 0v11c-5 4-9-4-14 0")
    )
)

private val byId = icons.values.flatten().associateBy { it.id }

@Composable
internal fun CollectionSymbol(id: String, tint: Color = LocalContentColor.current) {
    val path = remember(id) {
        PathParser.createPathFromPathData((byId[id] ?: byId.getValue("FOLDER")).path).asComposePath()
    }
    Canvas(Modifier.size(21.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            drawPath(path, tint, style = Stroke(1.65f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
internal fun CollectionIconPicker(selectedId: String, onSelect: (String) -> Unit) {
    val open = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    ControlButton(byId[selectedId]?.label ?: "Folder", Glyph.FOLDER, { open.value = true }, expands = true)
    if (!open.value) return
    BrainDialog(
        onDismissRequest = { open.value = false },
        title = { Text("Collection icon") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                icons.forEach { (group, options) ->
                    Text(group, style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs),
                        verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        options.forEach { icon ->
                            val selected = icon.id == selectedId
                            Surface(onClick = { onSelect(icon.id); open.value = false },
                                modifier = Modifier.size(Space.touch).semantics {
                                    contentDescription = icon.label
                                    this.selected = selected
                                },
                                shape = MaterialTheme.shapes.medium,
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainer) {
                                Box(Modifier.padding(Space.sm), contentAlignment = Alignment.Center) {
                                    CollectionSymbol(icon.id)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { QuietButton({ open.value = false }) { Text("Close") } }
    )
}
