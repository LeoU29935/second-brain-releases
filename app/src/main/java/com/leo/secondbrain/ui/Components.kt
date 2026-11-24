package com.leo.secondbrain.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal val cardShape: androidx.compose.ui.graphics.Shape
    @Composable get() = MaterialTheme.shapes.large

@Composable
internal fun cardBorder(): BorderStroke? =
    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

@Composable
internal fun ActionIcon(glyph: Glyph, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick, enabled = enabled) { BrainIcon(glyph, description = label) }
}

@Composable
internal fun IconTile(glyph: Glyph, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(42.dp).background(color.copy(alpha = .13f), RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center
    ) { BrainIcon(glyph, Modifier.size(20.dp), tint = color) }
}

@Composable
internal fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    action: @Composable () -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (onBack != null) ActionIcon(Glyph.BACK, "Back", onBack)
        Text(
            title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        action()
    }
}

@Composable
internal fun SectionHeader(
    title: String,
    detail: String? = null,
    action: @Composable () -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        if (detail != null) Text(detail, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}

@Composable
internal fun EmptyCard(glyph: Glyph, title: String, detail: String? = null, action: @Composable () -> Unit = {}) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            IconTile(glyph, MaterialTheme.colorScheme.primary)
            Text(title, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            if (detail != null) Text(detail, textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action()
        }
    }
}

@Composable
internal fun QuietEmpty(glyph: Glyph, title: String, detail: String? = null, action: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        BrainIcon(glyph, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (detail != null) Text(detail, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}

@Composable
internal fun ConfirmAction(
    title: String,
    detail: String,
    confirm: String,
    busy: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    BrainDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = { Text(detail) },
        dismissButton = { QuietButton(onDismiss, enabled = !busy) { Text("Cancel") } },
        confirmButton = { QuietButton(onConfirm, enabled = !busy) { Text(confirm) } }
    )
}

@Composable
internal fun NavItem(
    label: String,
    glyph: Glyph,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val background by androidx.compose.animation.animateColorAsState(
        if (active) colors.primary else colors.primary.copy(alpha = 0f), Motion.glide(), label = "Navigation color")
    val foreground = if (active) colors.onPrimary else colors.onSurfaceVariant
    val pillWidth by androidx.compose.animation.core.animateDpAsState(
        if (active) 46.dp else 34.dp, Motion.press(), label = "Navigation selection")
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = Space.touch).semantics {
            role = Role.Tab
            selected = active
        },
        shape = RoundedCornerShape(20.dp), color = Color.Transparent, contentColor = foreground
    ) {
        Column(
            Modifier.padding(vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(pillWidth, 27.dp).background(background, CircleShape),
                contentAlignment = Alignment.Center
            ) { BrainIcon(glyph, Modifier.size(18.dp)) }
            Spacer(Modifier.height(2.dp))
            Text(label, color = if (active) colors.primary else colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun DirectionIcon(glyph: Glyph) {
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    BrainIcon(glyph, Modifier.size(18.dp).graphicsLayer { scaleX = if (rtl) -1f else 1f })
}

internal fun LocalDate.scheduleDate(today: LocalDate = LocalDate.now()): String = when (this) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    else -> displayDate()
}

internal fun LocalDate.displayDate(): String = format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
