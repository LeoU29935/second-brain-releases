package com.leo.secondbrain.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leo.secondbrain.data.BodyFormat
import com.leo.secondbrain.data.RichBody

private enum class BodyMenu(val label: String, val icon: Glyph) {
    FORMAT("Formatting", Glyph.EDIT), COLOR("Text color", Glyph.PALETTE), LIST("Lists", Glyph.LIST)
}

private val textSizeFormats = setOf(BodyFormat.HEADING, BodyFormat.SMALL, BodyFormat.LARGE)
private val textColorFormats = setOf(BodyFormat.ACCENT, BodyFormat.WARM, BodyFormat.COOL)
private val listPrefix = Regex("^(?:• |[0-9]+\\. |[☐☑] )")

private fun RichBody.annotated(colors: ColorScheme, onChecklistChange: ((String) -> Unit)? = null): AnnotatedString = buildAnnotatedString {
    append(text)
    for (span in spans) {
        val style = when (span.format) {
            BodyFormat.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
            BodyFormat.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            BodyFormat.HEADING -> SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
            BodyFormat.SMALL -> SpanStyle(fontSize = 12.sp)
            BodyFormat.LARGE -> SpanStyle(fontSize = 18.sp)
            BodyFormat.ACCENT -> SpanStyle(color = colors.primary)
            BodyFormat.WARM -> SpanStyle(color = colors.tertiary)
            BodyFormat.COOL -> SpanStyle(color = colors.secondary)
        }
        addStyle(style, span.start, span.end)
    }
    if (onChecklistChange != null) {
        Regex("(?m)^[☐☑](?= )").findAll(text).forEach { marker ->
            val index = marker.range.first
            addLink(LinkAnnotation.Clickable("checklist-$index", linkInteractionListener = {
                val replacement = if (marker.value == "☐") "☑" else "☐"
                onChecklistChange(replace(index, index + 1, replacement).encode())
            }), index, index + 1)
        }
    }
}

@Composable
internal fun RichBodyText(encoded: String, onChecklistChange: ((String) -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val doc = remember(encoded) { RichBody.read(encoded) }
    Text(doc.annotated(colors, onChecklistChange), style = MaterialTheme.typography.bodyLarge)
}

@Composable
internal fun RichBodyEditor(encoded: String, onChange: (String) -> Unit, label: String, minLines: Int) {
    val colors = MaterialTheme.colorScheme
    val doc = remember(encoded) { RichBody.read(encoded) }
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(doc.text)) }
    var menu by remember { mutableStateOf<BodyMenu?>(null) }
    var tooLong by remember { mutableStateOf(false) }
    val undo = remember { mutableStateListOf<Pair<String, TextFieldValue>>() }
    val redo = remember { mutableStateListOf<Pair<String, TextFieldValue>>() }
    val current = if (value.text == doc.text) value else TextFieldValue(doc.text,
        TextRange(value.selection.start.coerceAtMost(doc.text.length), value.selection.end.coerceAtMost(doc.text.length)))
    fun apply(next: RichBody, edit: TextFieldValue) {
        val storage = next.encode()
        tooLong = storage.length > 48_000
        if (tooLong) return
        value = edit
        if (storage != encoded) {
            undo.add(encoded to current)
            if (undo.size > 40) undo.removeAt(0)
            redo.clear()
            onChange(storage)
        }
    }
    fun undoOrRedo(recovering: Boolean): Boolean {
        val from = if (recovering) redo else undo
        val to = if (recovering) undo else redo
        if (from.isEmpty()) return false
        val (storage, edit) = from.removeAt(from.lastIndex)
        to.add(encoded to current)
        value = edit
        tooLong = false
        onChange(storage)
        return true
    }
    fun lines(): IntRange {
        val start = current.text.lastIndexOf('\n', current.selection.min - 1) + 1
        val last = current.selection.max.let { if (it > current.selection.min) it - 1 else it }
        val end = current.text.indexOf('\n', last).takeIf { it >= 0 } ?: current.text.length
        return start until end
    }
    fun format(style: BodyFormat?, family: Set<BodyFormat>) {
        val selection = current.selection.takeUnless { it.collapsed } ?: lines().let { TextRange(it.first, it.last + 1) }
        apply(doc.format(selection.min, selection.max, style, family), current)
    }
    fun list(prefix: (Int) -> String, toggleChecklist: Boolean = false) {
        val range = lines()
        val lineText = doc.text.substring(range.first, range.last + 1).split('\n')
        val starts = lineText.runningFold(range.first) { offset, line -> offset + line.length + 1 }
        var result = doc
        var shift = 0
        for (index in lineText.indices.reversed()) {
            val marker = listPrefix.find(lineText[index])?.value.orEmpty()
            val replacement = if (toggleChecklist) when (marker) {
                "☐ " -> "☑ "
                "☑ " -> "☐ "
                else -> "☑ "
            } else prefix(index)
            result = result.replace(starts[index], starts[index] + marker.length, replacement)
            shift += replacement.length - marker.length
        }
        val position = (current.selection.max + shift).coerceIn(range.first, result.text.length)
        apply(result, TextFieldValue(result.text, TextRange(position)))
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerLow, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            BasicTextField(current, { proposed -> apply(doc.withText(proposed.text), proposed) },
                Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics { contentDescription = label }
                    .onPreviewKeyEvent { key ->
                        key.type == KeyEventType.KeyDown && key.isCtrlPressed && when (key.key) {
                            Key.Z -> undoOrRedo(key.isShiftPressed)
                            Key.Y -> undoOrRedo(true)
                            else -> false
                        }
                    },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                minLines = minLines, maxLines = 12, cursorBrush = SolidColor(colors.primary),
                visualTransformation = VisualTransformation {
                    TransformedText(if (it.text == doc.text) doc.annotated(colors) else it, OffsetMapping.Identity)
                })
            if (tooLong) Text("The description is too long.", color = colors.error, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BodyMenu.entries.forEach { option ->
                    Box {
                        Surface(onClick = { menu = option }, modifier = Modifier.size(44.dp).semantics {
                            contentDescription = option.label
                        }, shape = CircleShape, color = colors.surfaceContainerHigh) {
                            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                                if (option == BodyMenu.FORMAT) Text("Aa", style = MaterialTheme.typography.labelLarge)
                                else BrainIcon(option.icon, Modifier.size(18.dp))
                            }
                        }
                        DropdownMenu(menu == option, { menu = null }) {
                            when (option) {
                                BodyMenu.FORMAT -> {
                                    BodyMenuItem("Heading", { menu = null }) { format(BodyFormat.HEADING, textSizeFormats) }
                                    BodyMenuItem("Normal text", { menu = null }) { format(null, BodyFormat.entries.toSet()) }
                                    BodyMenuItem("Bold", { menu = null }) { format(BodyFormat.BOLD, setOf(BodyFormat.BOLD)) }
                                    BodyMenuItem("Italic", { menu = null }) { format(BodyFormat.ITALIC, setOf(BodyFormat.ITALIC)) }
                                    BodyMenuItem("Smaller", { menu = null }) { format(BodyFormat.SMALL, textSizeFormats) }
                                    BodyMenuItem("Larger", { menu = null }) { format(BodyFormat.LARGE, textSizeFormats) }
                                    DropdownMenuItem(text = { Text("Undo") }, enabled = undo.isNotEmpty(), onClick = {
                                        menu = null
                                        undoOrRedo(false)
                                    })
                                    DropdownMenuItem(text = { Text("Redo") }, enabled = redo.isNotEmpty(), onClick = {
                                        menu = null
                                        undoOrRedo(true)
                                    })
                                }
                                BodyMenu.COLOR -> {
                                    BodyMenuItem("Default", { menu = null }) { format(null, textColorFormats) }
                                    BodyMenuItem("Accent", { menu = null }) { format(BodyFormat.ACCENT, textColorFormats) }
                                    BodyMenuItem("Warm", { menu = null }) { format(BodyFormat.WARM, textColorFormats) }
                                    BodyMenuItem("Cool", { menu = null }) { format(BodyFormat.COOL, textColorFormats) }
                                }
                                BodyMenu.LIST -> {
                                    BodyMenuItem("Bulleted list", { menu = null }) { list({ "• " }) }
                                    BodyMenuItem("Numbered list", { menu = null }) { list({ "${it + 1}. " }) }
                                    BodyMenuItem("Checklist", { menu = null }) { list({ "☐ " }) }
                                    BodyMenuItem("Toggle checklist items", { menu = null }) { list({ "" }, toggleChecklist = true) }
                                    BodyMenuItem("Remove list markers", { menu = null }) { list({ "" }) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BodyMenuItem(title: String, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(text = { Text(title) }, onClick = { close(); action() })
}
