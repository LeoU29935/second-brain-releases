package com.leo.secondbrain

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V02FlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun calendarEventCanBeCreatedAndDeleted() {
        val title = "Calendar smoke " + System.nanoTime()
        compose.waitUntil(8_000) {
            compose.onAllNodesWithTag("workspace-navigation").fetchSemanticsNodes().isNotEmpty()
        }
        val open = compose.onNodeWithTag("workspace-navigation").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == "Open calendar" }
        compose.runOnIdle { open.action() }
        compose.onNodeWithContentDescription("New event").performClick()
        compose.onNodeWithText("Title").performTextInput(title)
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor(title)
        compose.onNodeWithText(title).assertIsDisplayed()
        click(title)
        click("Edit")
        compose.onNodeWithContentDescription("Delete").performClick()
        compose.waitUntil(8_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun textCaptureSurvivesNoApiKeyAndManualDraftRequiresApproval() {
        val title = "Brain Dump smoke " + System.nanoTime()
        val capture = "$title\nA follow-up thought."
        tab("Brain Dump")
        click("Write a thought")
        compose.onNodeWithText("What’s on your mind?").performTextInput(capture)
        click("Capture")
        waitFor(capture)
        click(capture)
        waitFor("AI organization isn’t configured", substring = true)
        click("Create draft")
        val noteChoice = hasText("Note") and hasAnyAncestor(isPopup())
        compose.waitUntil(8_000) { compose.onAllNodes(noteChoice).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(noteChoice).performClick()
        click(title)
        click("Approve")
        click("Apply change")
        waitFor("Edit")
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("Write a thought")
        tab("Home")
        click("Search")
        compose.onNodeWithText(title).assertIsDisplayed()
    }

    private fun tab(label: String) {
        val matcher = hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.waitUntil(8_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(matcher).performClick()
    }

    private fun waitFor(label: String, substring: Boolean = false) {
        compose.waitUntil(8_000) { compose.onAllNodesWithText(label, substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun click(label: String) {
        waitFor(label)
        compose.onNodeWithText(label).performClick()
    }
}
