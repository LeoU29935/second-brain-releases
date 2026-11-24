package com.leo.secondbrain

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leo.secondbrain.data.EntryEntity
import com.leo.secondbrain.data.EntryKind
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V048DetailAndCalendarTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun noteOpensReadFirstAndEditSupportsCancelAndSave() = with(compose) {
        val app = activity.application as BrainApplication
        val title = "Desk note " + System.nanoTime().toString().takeLast(6)
        val entry = EntryEntity(kind = EntryKind.NOTE, title = title, body = "The original body")
        val tagName = "Tag " + System.nanoTime().toString().takeLast(6)
        runBlocking { app.repository.saveEntry(entry, emptySet()) }
        try {
            waitUntil(10_000) { onAllNodesWithText("Search").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Search").performClick()
            onNode(hasSetTextAction()).performTextInput(title)
            onNode(hasText(title) and hasClickAction() and !hasSetTextAction()).performClick()
            onNodeWithText("The original body").assertIsDisplayed()
            onNodeWithText("Title").assertDoesNotExist()
            onAllNodes(hasSetTextAction()).assertCountEquals(0)
            onNodeWithText("Edit").performClick()
            onNode(hasSetTextAction() and hasText("The original body")).performTextReplacement("Discard this edit")
            onNodeWithText("Cancel").performClick()
            onNodeWithText("The original body").assertIsDisplayed()
            onNodeWithText("Edit").performClick()
            onNode(hasSetTextAction() and hasText("The original body")).performTextReplacement("A saved edit")
            onNodeWithText("Save").performClick()
            waitUntil(10_000) { onAllNodesWithText("A saved edit").fetchSemanticsNodes().isNotEmpty() }
            onAllNodes(hasSetTextAction()).assertCountEquals(0)
            runBlocking { check(app.repository.snapshot().entries.single { it.entry.id == entry.id }.entry.body == "A saved edit") }

            onNodeWithText("Edit").performClick()
            onNodeWithText("Choose tag").performScrollTo().performClick()
            onNodeWithText("New tag").performClick()
            onNodeWithText("Tag name").performTextInput(tagName)
            onNode(hasText("Save") and hasAnyAncestor(isDialog())).performClick()
            waitUntil(10_000) {
                onAllNodes(hasText(tagName) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Save").performClick()
            waitUntil(10_000) {
                runBlocking { app.repository.snapshot().entries.single { it.entry.id == entry.id }.tags.any { it.name == tagName } }
            }
            onNodeWithText(tagName).assertIsDisplayed()
            Unit
        } finally {
            runBlocking {
                app.repository.deleteEntry(entry.id)
                app.repository.snapshot().tags.filter { it.name == tagName }.forEach { app.repository.deleteTag(it.id) }
            }
        }
    }
}
