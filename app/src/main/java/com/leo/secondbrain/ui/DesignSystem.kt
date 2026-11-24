package com.leo.secondbrain.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

internal object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val touch = 48.dp
    val header = 64.dp
}

internal object Motion {
    const val ENTER = 220
    const val QUICK = 135
    const val THEME = 230
    private val release = CubicBezierEasing(.20f, .75f, .24f, 1f)
    fun <T> settle() = spring<T>(dampingRatio = .83f, stiffness = Spring.StiffnessMediumLow)
    fun <T> press() = spring<T>(dampingRatio = .86f, stiffness = Spring.StiffnessMedium)
    fun <T> bloom() = spring<T>(dampingRatio = .67f, stiffness = Spring.StiffnessMedium)
    fun <T> glide(duration: Int = ENTER) = tween<T>(duration, easing = release)
    fun reveal(): EnterTransition = expandVertically(settle(), expandFrom = androidx.compose.ui.Alignment.Top) +
        fadeIn(glide(QUICK)) + slideInVertically(glide()) { -minOf(it, 24) }
    fun conceal(): ExitTransition = shrinkVertically(glide(QUICK), shrinkTowards = androidx.compose.ui.Alignment.Top) +
        fadeOut(glide(QUICK))
}

internal val LocalSimplifiedMotion = compositionLocalOf { false }

@Composable private fun Modifier.liquidEntrance(anchor: TransformOrigin): Modifier {
    val simplified = LocalSimplifiedMotion.current
    val progress = remember { Animatable(if (simplified) 1f else 0f) }
    LaunchedEffect(simplified) {
        if (simplified) progress.snapTo(1f) else progress.animateTo(1f, Motion.bloom())
    }
    return graphicsLayer {
        transformOrigin = anchor
        scaleX = .91f + .09f * progress.value
        scaleY = .95f + .05f * progress.value
    }
}

@Composable internal fun BrainButton(onClick: () -> Unit, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Button(onClick, Modifier.heightIn(min = Space.touch).softPress(interaction), enabled = enabled,
        shape = MaterialTheme.shapes.medium, interactionSource = interaction, content = content)
}

@Composable
internal fun Modifier.softPress(interaction: MutableInteractionSource, amount: Float = .025f): Modifier {
    if (LocalSimplifiedMotion.current) return this
    val pressed by interaction.collectIsPressedAsState()
    val scale = androidx.compose.animation.core.animateFloatAsState(
        if (pressed) 1f - amount else 1f, Motion.press(), label = "Press feedback")
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

@Composable internal fun QuietButton(onClick: () -> Unit, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    TextButton(onClick, Modifier.heightIn(min = Space.touch), enabled = enabled,
        shape = MaterialTheme.shapes.medium, content = content)
}

@Composable internal fun BrainChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    FilterChip(selected, onClick, label, modifier = modifier.softPress(interaction),
        interactionSource = interaction, shape = MaterialTheme.shapes.medium, leadingIcon = leadingIcon,
        border = null, colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer
        ))
}

@Composable internal fun BrainField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(),
    label: @Composable () -> Unit, singleLine: Boolean = false, minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 12, enabled: Boolean = true,
    placeholder: (@Composable () -> Unit)? = null, leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium
) {
    val palette = MaterialTheme.colorScheme
    TextField(value, onValueChange, modifier, enabled = enabled, label = label,
        singleLine = singleLine, minLines = minLines, maxLines = maxLines, shape = shape,
        placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        visualTransformation = visualTransformation, textStyle = textStyle,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = palette.surfaceContainerHigh,
            unfocusedContainerColor = palette.surfaceContainer,
            disabledContainerColor = palette.surfaceContainer,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent
        ))
}

@Composable internal fun BrainDialog(
    onDismissRequest: () -> Unit, title: @Composable () -> Unit, text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit, dismissButton: (@Composable () -> Unit)? = null
) {
    AlertDialog(onDismissRequest, confirmButton, modifier = Modifier.liquidEntrance(TransformOrigin.Center),
        dismissButton = dismissButton, title = title, text = text,
        shape = MaterialTheme.shapes.extraLarge, containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun BrainSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest, modifier = Modifier.liquidEntrance(TransformOrigin(.5f, 1f)),
        sheetMaxWidth = 520.dp, shape = RoundedCornerShape(topStart = Space.xl, topEnd = Space.xl),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow, content = content)
}

@Composable internal fun ControlButton(label: String, glyph: Glyph, onClick: () -> Unit, expands: Boolean = false) {
    Surface(onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
        androidx.compose.foundation.layout.Row(
            Modifier.heightIn(min = Space.touch), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            ControlLabel(label, glyph, expands)
        }
    }
}

@Composable private fun ControlLabel(label: String, glyph: Glyph, expands: Boolean) {
    androidx.compose.foundation.layout.Row(
        androidx.compose.ui.Modifier.padding(horizontal = Space.md, vertical = Space.sm),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Space.sm),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        BrainIcon(glyph, Modifier)
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (expands) BrainIcon(Glyph.DOWN, Modifier)
    }
}
