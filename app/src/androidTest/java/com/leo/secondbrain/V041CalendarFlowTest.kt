package com.leo.secondbrain

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leo.secondbrain.data.CollectionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V041CalendarFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun calendarOpensFromThePillChangesModesAndDismisses() = with(compose) {
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        waitUntil(15_000) { onAllNodesWithTag("workspace-navigation").fetchSemanticsNodes().size == 1 }
        onNode(hasText("Brain Dump") and tab).assertIsDisplayed()
        onNode(hasText("Home") and tab).assertIsDisplayed()
        onNode(hasText("Today") and tab).assertDoesNotExist()
        onNodeWithTag("workspace-navigation").performTouchInput {
            swipe(center, center - Offset(0f, height * 1.5f), 450)
        }
        waitUntil(10_000) { onAllNodesWithText("Month").fetchSemanticsNodes().size == 1 }
        onNodeWithText("Upcoming").assertIsSelected()
        val positions = listOf("Week", "Upcoming", "Month").map { onNodeWithText(it).fetchSemanticsNode().boundsInRoot.left }
        assertEquals(positions.sorted(), positions)
        onNodeWithText("Month").performClick().assertIsSelected()
        onNodeWithContentDescription("Next month").performClick()
        onNodeWithContentDescription("Previous month").performClick()
        onNodeWithText("Week").performClick().assertIsSelected()
        onNodeWithContentDescription("Next week").performClick()
        onNodeWithText("Upcoming").performClick().assertIsSelected()
        onNodeWithText("Next 30 days").assertIsDisplayed()
        onNodeWithTag("calendar-grip").performTouchInput {
            swipe(center, center + Offset(0f, height * 1.4f), 400)
        }
        waitUntil(8_000) { onAllNodesWithText("Upcoming").fetchSemanticsNodes().isEmpty() }
        val open = onNodeWithTag("workspace-navigation").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == "Open calendar" }
        runOnIdle { open.action() }
        onNodeWithText("Upcoming").assertIsSelected()
        onNodeWithContentDescription("Close calendar").performClick()
        onNodeWithTag("workspace-navigation").assertIsDisplayed()
        Unit
    }

    @Test
    fun collectionLayoutPersistsAndSearchOpensNestedFolders() = with(compose) {
        val app = activity.application as BrainApplication
        val suffix = System.nanoTime().toString().takeLast(7)
        val parent = CollectionEntity(name = "00-$suffix A Plan")
        val sibling = CollectionEntity(name = "00-$suffix B Plan")
        val child = CollectionEntity(name = "Nested $suffix", parentId = parent.id)
        val before = app.preferences.preferences.value
        runBlocking {
            app.preferences.update(before.copy(grid = true, folderCarousel = true))
            listOf(parent, sibling, child).forEach { app.repository.saveCollection(it) }
        }
        waitUntil(12_000) {
            onAllNodesWithText(parent.name).fetchSemanticsNodes().isNotEmpty() &&
                onAllNodesWithText(sibling.name).fetchSemanticsNodes().isNotEmpty()
        }
        fun x(name: String) = onNodeWithContentDescription(name).fetchSemanticsNode().boundsInRoot.left
        assertNotEquals(x(parent.name), x(sibling.name))
        onNodeWithContentDescription("Collection settings").performClick()
        onNodeWithText("List layout").performClick()
        onNodeWithContentDescription(parent.name).performClick()
        onNodeWithContentDescription("Back to parent folders").assertIsDisplayed()
        onNodeWithContentDescription(child.name).performClick().assertIsSelected()
        activityRule.scenario.recreate()
        waitUntil(12_000) { onAllNodesWithContentDescription("Collection settings").fetchSemanticsNodes().size == 1 }
        runOnIdle { org.junit.Assert.assertFalse(app.preferences.preferences.value.grid) }
        onNodeWithText("Search").performClick()
        onNode(hasSetTextAction()).performTextInput(child.name)
        onNode(hasText(child.name) and hasClickAction() and !hasSetTextAction()).performClick()
        onNodeWithContentDescription(child.name).assertIsSelected()
        runBlocking {
            app.repository.deleteCollection(parent.id)
            app.repository.deleteCollection(sibling.id)
            app.preferences.update(before)
        }
        Unit
    }
}
