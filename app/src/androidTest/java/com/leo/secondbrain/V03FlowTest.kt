package com.leo.secondbrain

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V03FlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun oneCharacterSurvivesLeavingAndRecreatingActivity() {
        val character = "Ω"
        with(compose) {
            waitUntil(8000) { onAllNodesWithContentDescription("Create").fetchSemanticsNodes().size == 1 }
            onNodeWithContentDescription("Create").performClick()
            onNodeWithText("Note").performClick()
            onNodeWithText("Title").performTextInput(character)
            onNodeWithContentDescription("Back").performClick()
            activityRule.scenario.recreate()
            waitUntil(8000) { onAllNodesWithText("Search").fetchSemanticsNodes().size == 1 }
            onNodeWithText("Search").performClick()
            val search = onNode(hasSetTextAction())
            search.performTextInput(character)
            search.performImeAction()
            waitUntil(8000) {
                onAllNodes(hasText(character) and hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
            }
            onNode(hasText(character) and hasClickAction() and !hasSetTextAction())
                .performSemanticsAction(SemanticsActions.OnClick)
            val edit = hasText("Edit") and hasClickAction()
            val delete = hasContentDescription("Delete") and hasClickAction()
            waitUntil(8_000) { onAllNodes(edit or delete).fetchSemanticsNodes().size == 1 }
            onNodeWithText(character).assertIsDisplayed()
            if (onAllNodes(edit).fetchSemanticsNodes().isNotEmpty()) onNode(edit).performClick()
            onNode(delete).performClick()
            waitUntil(8_000) {
                onAllNodes(hasText(character) and hasClickAction() and !hasSetTextAction())
                    .fetchSemanticsNodes().isEmpty()
            }
        }
    }

    @Test
    fun slimeAppearsInVoiceCaptureAndLiveConversationIsRetired() = with(compose) {
        waitUntil(8_000) { onAllNodesWithContentDescription("Create").fetchSemanticsNodes().size == 1 }
        onNodeWithContentDescription("Start live assistant").assertDoesNotExist()
        onNodeWithContentDescription("Create").performClick()
        onNodeWithText("Voice capture").performClick()
        onNodeWithTag("voice-memo-slime").assertIsDisplayed()
        onNodeWithText("Start recording").assertIsDisplayed()
        onNodeWithText("Close").performClick()
        onNodeWithTag("voice-memo-slime").assertDoesNotExist()
    }
}
