package com.leo.secondbrain.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leo.secondbrain.data.Appearance
import com.leo.secondbrain.data.ColorTheme
import com.leo.secondbrain.data.Preferences
import com.leo.secondbrain.data.TagEntity
import com.leo.secondbrain.data.DynamicIntensity
import com.leo.secondbrain.ui.theme.curatedColors
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.semantics.Role
import com.leo.secondbrain.data.SlimeFace
import com.leo.secondbrain.data.WidgetAlignment
import com.leo.secondbrain.ui.theme.accentColor

@Composable
internal fun SettingsScreen(
    preferences: Preferences,
    tagCount: Int,
    onPreference: (Preferences) -> Unit,
    onTags: () -> Unit,
    onReminders: () -> Unit,
    onAi: () -> Unit,
    onBackup: () -> Unit,
    onUpdate: () -> Unit,
    onTrash: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    LibraryGrid {
        fullWidth("app controls") {
            SettingsGroup("App", listOf(
                Setting("AI Intelligence", "Claude, ChatGPT and voice memo organization", Glyph.BRAND, colors.primary, onAi),
                Setting("App updates", "Version and updates", Glyph.DOWN, colors.primary, onUpdate)
            ))
        }
        fullWidth("appearance") {
            Surface(
                Modifier.fillMaxWidth(), shape = cardShape, color = colors.surfaceContainerLow,
                border = BorderStroke(1.dp, colors.outlineVariant)
            ) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Glyph.PALETTE, colors.primary)
                        Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("Home layout", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        BrainChip(!preferences.folderCarousel, { onPreference(preferences.copy(folderCarousel = false)) },
                            { Text("Expandable folders") })
                        BrainChip(preferences.folderCarousel, { onPreference(preferences.copy(folderCarousel = true)) },
                            { Text("Folder carousel") })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Simplify motion", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Switch(preferences.simplifyMotion,
                            { onPreference(preferences.copy(simplifyMotion = it)) },
                            Modifier.semantics { contentDescription = "Simplify motion" })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Appearance.entries.forEach { appearance ->
                            BrainChip(preferences.appearance == appearance,
                                { onPreference(preferences.copy(appearance = appearance)) }, { Text(appearance.label) })
                        }
                    }
                    Text("Color theme", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        ColorTheme.entries.forEach { theme ->
                            val selected = !preferences.dynamicColor && preferences.colorTheme == theme
                            val swatch = curatedColors(theme, preferences.appearance == Appearance.DARK ||
                                preferences.appearance == Appearance.SYSTEM && isSystemInDarkTheme())
                            Surface(Modifier.weight(1f).clip(MaterialTheme.shapes.medium)
                                .selectable(selected, role = Role.RadioButton,
                                onClick = { onPreference(preferences.copy(colorTheme = theme, dynamicColor = false)) }),
                                shape = MaterialTheme.shapes.medium,
                                color = if (selected) colors.secondaryContainer else colors.surfaceContainerLow) {
                                Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(24.dp).background(swatch.primary, CircleShape))
                                    Text(theme.label, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                                }
                            }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Dynamic colors", style = MaterialTheme.typography.titleMedium)
                                Text("Use colors from your wallpaper", color = colors.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            Switch(
                                preferences.dynamicColor,
                                { onPreference(preferences.copy(dynamicColor = it)) },
                                Modifier.semantics { contentDescription = "Dynamic colors" }
                            )
                        }
                        if (preferences.dynamicColor) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                DynamicIntensity.entries.forEach { intensity ->
                                    BrainChip(preferences.dynamicIntensity == intensity,
                                        { onPreference(preferences.copy(dynamicIntensity = intensity)) }, { Text(intensity.label) })
                                }
                            }
                        }
                    }
                    Text("Typography", style = MaterialTheme.typography.titleMedium)
                    ChoiceMenu(preferences.typography.label, com.leo.secondbrain.data.TypographyPreset.entries,
                        { it.label }, { onPreference(preferences.copy(typography = it)) }, Glyph.NOTE)
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("A little more room to think", style = MaterialTheme.typography.headlineSmall)
                        Text("Tomorrow at 9:00 · Morning planning", style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Show subfolder indicators", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Switch(preferences.showSubfolderIndicators,
                            { onPreference(preferences.copy(showSubfolderIndicators = it)) },
                            Modifier.semantics { contentDescription = "Show subfolder indicators" })
                    }
                }
            }
        }
        fullWidth("microphone widget") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                SectionHeader("Microphone widget")
                ChoiceMenu(preferences.widgetTheme?.label ?: "Use app colors", listOf<ColorTheme?>(null) + ColorTheme.entries,
                    { it?.label ?: "Use app colors" }, { onPreference(preferences.copy(widgetTheme = it)) }, Glyph.PALETTE)
                ChoiceMenu(preferences.widgetAppearance?.label ?: "Use app display mode", listOf<Appearance?>(null) + Appearance.entries,
                    { it?.label ?: "Use app display mode" }, { onPreference(preferences.copy(widgetAppearance = it)) }, Glyph.SETTINGS)
                ChoiceMenu(preferences.widgetAlignment.label, WidgetAlignment.entries, WidgetAlignment::label,
                    { onPreference(preferences.copy(widgetAlignment = it)) }, Glyph.MIC)
                if (preferences.widgetTheme != null || preferences.widgetAppearance != null) {
                    QuietButton({ onPreference(preferences.copy(widgetTheme = null, widgetAppearance = null)) }) {
                        Text("Reset to app theme")
                    }
                }
            }
        }
        fullWidth("slime appearance") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SectionHeader("Voice capture companion")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    SlimeFace.entries.forEach { face ->
                        val selected = preferences.slimeFace == face
                        Surface(
                            onClick = { onPreference(preferences.copy(slimeFace = face)) },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) colors.secondaryContainer else colors.surfaceContainer
                        ) {
                            Column(Modifier.padding(vertical = Space.sm),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                SlimeFaceDrawing(face)
                                Text(face.label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        fullWidth("workspace options") {
            SettingsGroup("Workspace", listOf(
                Setting("Manage tags", countLabel(tagCount, "tag"), Glyph.TAG, colors.secondary, onTags),
                Setting("Reminders", "Notification and alarm permissions", Glyph.BELL, colors.tertiary, onReminders),
                Setting("Recently Deleted", "Restore deleted items for 14 days", Glyph.DELETE, colors.tertiary, onTrash)
            ))
        }
        fullWidth("system options") {
            SettingsGroup("Data", listOf(
                Setting("Backup and restore", "Export or import your workspace", Glyph.INBOX, colors.secondary, onBackup)
            ))
        }
        fullWidth("version") {
            Text("Second Brain ${com.leo.secondbrain.BuildConfig.VERSION_NAME}", Modifier.padding(top = 10.dp, start = 4.dp),
                color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private data class Setting(val title: String, val detail: String, val glyph: Glyph, val color: Color, val onClick: () -> Unit)

@Composable
private fun SettingsGroup(title: String, settings: List<Setting>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionHeader(title)
        settings.forEachIndexed { index, row ->
            val start = if (index == 0) Space.xl else Space.xs
            val end = if (index == settings.lastIndex) Space.xl else Space.xs
            SettingsLink(row.title, row.detail, row.glyph, row.color, row.onClick,
                RoundedCornerShape(topStart = start, topEnd = start, bottomStart = end, bottomEnd = end), grouped = true)
        }
    }
}

@Composable
internal fun TagsScreen(state: LibraryState, model: BrainViewModel, busy: Boolean, onBack: () -> Unit) {
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    val close = { editor = null }
    LibraryGrid {
        fullWidth("tags title") {
            ScreenHeader("Tags", onBack) { ActionIcon(Glyph.ADD, "New tag", { editor = "" }) }
        }
        if (state.tags.isEmpty()) fullWidth("no tags") {
            QuietEmpty(Glyph.TAG, "No tags yet", "Use colored tags across notes and tasks.") {
                QuietButton({ editor = "" }) { Text("Create tag") }
            }
        }
        items(state.tags, key = TagEntity::id) { tag ->
            val count = state.entries.count { entry -> entry.tags.any { it.id == tag.id } }
            SettingsLink(tag.name, countLabel(count, "item"), Glyph.TAG, accentColor(tag.color), { editor = tag.id })
        }
    }
    if (editor != null) {
        val existing = state.tags.firstOrNull { it.id == editor }
        if (editor == "" || existing != null) {
            TagEditor(existing, busy, close, { tag ->
                model.write(close) { model.repository.saveTag(tag) }
            }, if (existing == null) null else { { model.write(close) { model.repository.deleteTag(existing.id) } } })
        }
    }
}

@Composable
internal fun SettingsLink(title: String, detail: String, glyph: Glyph, color: Color, onClick: () -> Unit,
    shape: Shape = cardShape, grouped: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = shape, color = if (grouped) colors.surfaceContainer else colors.surfaceContainerLow,
        border = if (grouped) null else BorderStroke(1.dp, colors.outlineVariant)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = if (grouped) Space.sm else 13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(glyph, color)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            DirectionIcon(Glyph.NEXT)
        }
    }
}
