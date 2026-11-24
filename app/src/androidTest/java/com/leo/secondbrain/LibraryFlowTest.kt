package com.leo.secondbrain

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun createFindAndDeleteANoteThenCompleteAScheduledTask() {
        val suffix = System.nanoTime().toString().takeLast(7)
        val note = "Desk note $suffix"
        val task = "Agenda task $suffix"

        clickDescription("Create")
        click("Note")
        compose.onNodeWithText("Title").performTextInput(note)
        clickDescription("Back")

        click("Search")
        val search = compose.onNode(hasSetTextAction())
        search.performTextInput(suffix)
        search.performImeAction()
        compose.onNodeWithText(note).assertIsDisplayed()
        click(note)
        click("Edit")
        clickDescription("Delete")
        waitForDeletion(note)
        compose.onNodeWithText(note).assertDoesNotExist()

        clickDescription("Back")
        clickDescription("Create")
        click("Task")
        compose.onNodeWithText("Title").performTextInput(task)
        clickDescription("Back")
        click("Inbox")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(task).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(task).assertIsDisplayed()
        clickDescription("Complete $task")
        waitForDescription("Reopen $task")
        compose.onNodeWithContentDescription("Reopen $task").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        waitForDescription("Complete $task")
        compose.onNodeWithText(task).performScrollTo().performClick()
        click("Edit")
        clickDescription("Delete")
        waitForDeletion(task)
        compose.onNodeWithText(task).assertDoesNotExist()
    }

    private fun click(label: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label).performClick()
    }

    private fun clickDescription(label: String) {
        waitForDescription(label)
        compose.onNodeWithContentDescription(label).performClick()
    }

    private fun waitForDescription(label: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForDeletion(title: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty()
        }
    }
}
