package com.leo.secondbrain

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leo.secondbrain.capture.MicWidget
import com.leo.secondbrain.capture.widgetPreferences
import com.leo.secondbrain.data.Appearance
import com.leo.secondbrain.data.ColorTheme
import com.leo.secondbrain.data.DynamicIntensity
import com.leo.secondbrain.data.PreferenceStore
import com.leo.secondbrain.ui.theme.curatedColors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V042MotionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun calendarSwipesBeginningOnEveryControlNeverActivateTheControl() = with(compose) {
        val role = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        waitUntil(12_000) { onAllNodesWithTag("workspace-navigation").fetchSemanticsNodes().size == 1 }
        val dump = onNode(hasText("Brain Dump") and role)
        val collection = onNode(hasText("Home") and role)
        val settings = onNode(hasText("Settings") and role)
        val create = onNodeWithContentDescription("Create")
        for ((target, length, drift) in listOf(Triple(dump, 480L, 28f), Triple(create, 110L, 0f),
            Triple(collection, 240L, -35f), Triple(settings, 210L, 0f))) {
            target.performTouchInput { swipe(center, center + Offset(drift, -92f), length) }
            waitUntil(8_000) { onAllNodesWithText("Month").fetchSemanticsNodes().size == 1 }
            onNodeWithText("Upcoming").assertIsSelected()
            onNodeWithText("Note").assertDoesNotExist()
            onNodeWithContentDescription("Close calendar").performClick()
            waitUntil(8_000) { onAllNodesWithText("Month").fetchSemanticsNodes().isEmpty() }
            collection.assertIsSelected()
        }
        create.performTouchInput {
            down(center)
            moveTo(center - Offset(0f, 80f))
            moveTo(center)
            up()
        }
        onNodeWithText("Month").assertDoesNotExist()
        onNodeWithText("Note").assertDoesNotExist()
        create.performTouchInput { click() }
        onNodeWithText("Note").assertIsDisplayed()
        Unit
    }

    @Test
    fun widgetUsesAppPreferencesAndOverridesPersistIndependently() {
        val app = compose.activity.application as BrainApplication
        val old = app.preferences.preferences.value
        try {
            val appLook = old.copy(colorTheme = ColorTheme.OCEAN, appearance = Appearance.DARK, widgetTheme = null,
                widgetAppearance = null, dynamicColor = true, dynamicIntensity = DynamicIntensity.HIGH)
            assertTrue(app.preferences.update(appLook))
            assertEquals(appLook, widgetPreferences(appLook))
            val customized = appLook.copy(widgetTheme = ColorTheme.LAVENDER, widgetAppearance = Appearance.LIGHT)
            assertTrue(app.preferences.update(customized))
            val persisted = PreferenceStore(app).preferences.value
            assertEquals(customized, persisted)
            val widget = widgetPreferences(persisted)
            assertEquals(ColorTheme.OCEAN, persisted.colorTheme)
            assertEquals(Appearance.DARK, persisted.appearance)
            assertEquals(ColorTheme.LAVENDER, widget.colorTheme)
            assertEquals(Appearance.LIGHT, widget.appearance)
            assertFalse(widget.dynamicColor)
            assertNotEquals(curatedColors(ColorTheme.ROSE, false).surfaceContainer,
                curatedColors(ColorTheme.SAGE, false).surfaceContainer)
            assertEquals(ColorTheme.SAGE, ColorTheme.restored("OLIVE"))
            val ordered = persisted.copy(showFolderTitles = false, folderOrder = listOf("projects", "personal"))
            assertTrue(app.preferences.update(ordered))
            val reopened = PreferenceStore(compose.activity).preferences.value
            assertFalse(reopened.showFolderTitles)
            assertEquals(ordered.folderOrder, reopened.folderOrder)
            for (theme in ColorTheme.entries) for (night in listOf(false, true)) {
                val colors = curatedColors(theme, night)
                assertTrue("${theme.label} text", contrast(colors.onSurface, colors.surfaceContainer) >= 4.5)
                assertTrue("${theme.label} controls", contrast(colors.onPrimary, colors.primary) >= 4.5)
            }
            val provider = AppWidgetManager.getInstance(app).installedProviders.single {
                it.provider == ComponentName(app, MicWidget::class.java)
            }
            if (Build.VERSION.SDK_INT >= 31) assertEquals(2, provider.targetCellWidth)
            assertEquals(R.layout.microphone_widget, provider.initialLayout)
        } finally {
            app.preferences.update(old)
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val firstLight = first.luminance().toDouble()
        val secondLight = second.luminance().toDouble()
        return (maxOf(firstLight, secondLight) + .05) / (minOf(firstLight, secondLight) + .05)
    }
}
