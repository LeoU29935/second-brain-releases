package com.leo.secondbrain.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun TaskCompletion(checked: Boolean, onChecked: (Boolean) -> Unit, modifier: Modifier = Modifier,
    indicatorSize: androidx.compose.ui.unit.Dp = 21.dp, indicatorAlignment: Alignment = Alignment.Center) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val active = pressed || focused || hovered
    val fill by animateColorAsState(when {
        checked && pressed -> colors.tertiary
        checked -> colors.primary
        active -> colors.primaryContainer
        else -> Color.Transparent
    }, label = "Completion fill")
    val border by animateColorAsState(when {
        checked && focused -> if (pressed) colors.onTertiary else colors.onPrimary
        checked && pressed -> colors.tertiary
        checked || active -> colors.primary
        else -> colors.outline
    }, label = "Completion border")
    Box(modifier.size(Space.touch).toggleable(checked, interactionSource = interaction,
        indication = null, role = Role.Checkbox, onValueChange = onChecked),
        contentAlignment = indicatorAlignment) {
        Box(Modifier.size(indicatorSize).background(fill, CircleShape)
            .border(if (focused) 2.5.dp else 1.5.dp, border, CircleShape),
            contentAlignment = Alignment.Center) {
            if (checked) BrainIcon(Glyph.CHECK, Modifier.size(indicatorSize * .67f),
                tint = if (pressed) colors.onTertiary else colors.onPrimary)
        }
    }
}
