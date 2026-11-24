package com.leo.secondbrain

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leo.secondbrain.data.*
import com.leo.secondbrain.ui.theme.brainTypography
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class V0482UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun handwrittenPresetCoversContentAndControlsAtDistinctSizes() {
        val handwritten = brainTypography(TypographyPreset.HANDWRITTEN)
        val modern = brainTypography(TypographyPreset.MODERN)
        val family = handwritten.headlineSmall.fontFamily
        assertNotEquals(modern.bodyMedium.fontFamily, family)
        listOf(handwritten.displayLarge, handwritten.titleSmall, handwritten.titleMedium,
            handwritten.bodyLarge, handwritten.bodyMedium, handwritten.bodySmall,
            handwritten.labelLarge, handwritten.labelMedium, handwritten.labelSmall).forEach {
            assertEquals(family, it.fontFamily)
            assertTrue(it.lineHeight.value > it.fontSize.value)
        }
        assertTrue(handwritten.headlineSmall.fontSize.value > handwritten.titleLarge.fontSize.value)
        assertTrue(handwritten.titleLarge.fontSize.value > handwritten.bodyMedium.fontSize.value)
    }

    @Test
    fun longPressSelectionAndRichEditingPersistAcrossScreens() = with(compose) {
        val app = activity.application as BrainApplication
        val preferences = app.preferences.preferences.value
        val folders = listOf("Reading", "Travel", "Music", "Study", "Plans").mapIndexed { index, name ->
            CollectionEntity(name = name, color = index + 2)
        }
        val music = folders[2]
        val subfolder = CollectionEntity(name = "Sheet music", parentId = music.id, color = 8)
        val note = EntryEntity(kind = EntryKind.NOTE, title = "Recording checklist", collectionId = music.id,
            body = "Bring a second microphone and test the cable before the rehearsal.")
        val tomorrow = LocalDate.now().plusDays(1).toEpochDay()
        val task = EntryEntity(kind = EntryKind.TASK, title = "Export the rehearsal tracks",
            collectionId = music.id, body = "Keep a dry copy for the mix engineer.", deadline = tomorrow,
            dueMinute = 960, reminder = 15, priority = Priority.HIGH)
        val event = Event(title = "Studio session", collectionId = music.id, body = "Room B. Bring the lead sheets.",
            startDay = tomorrow, endDay = tomorrow, startMinute = 1080, endMinute = 1140, allDay = false)
        try {
            runBlocking {
                (folders + subfolder).forEach { app.repository.saveCollection(it) }
                app.repository.saveEntry(note, emptySet())
                app.repository.saveEntry(task, emptySet())
                app.repository.saveEvent(event, emptySet())
            }
            runOnIdle {
                app.preferences.update(preferences.copy(grid = true, folderCarousel = true, showFolderTitles = true,
                    folderOrder = folders.map { it.id }, selectedFolders = mapOf("" to music.id)))
            }
            waitUntil(10_000) { onAllNodesWithText(note.title).fetchSemanticsNodes().isNotEmpty() }
            snapshot("collections")
            onNodeWithText(note.title).performTouchInput { longClick() }
            onNodeWithText("1 selected").assertIsDisplayed()
            onNodeWithText(task.title).performClick()
            onNodeWithText("2 selected").assertIsDisplayed()
            snapshot("selection")
            onNodeWithContentDescription("Move selected items").performClick()
            onNode(hasText("Music") and hasAnyAncestor(isDialog())).performClick()
            onNode(hasText("Inbox") and hasAnyAncestor(isPopup())).performClick()
            onNode(hasText("Move") and hasAnyAncestor(isDialog())).performClick()
            waitUntil(10_000) { runBlocking { app.repository.readWorkspaceItem(note.id)?.collectionId == null } }
            runBlocking {
                assertEquals(null, app.repository.readWorkspaceItem(task.id)?.collectionId)
                assertEquals(music.id, app.repository.readWorkspaceItem(event.id)?.collectionId)
            }
            onNodeWithText(event.title).performClick()
            onNodeWithText(event.body).assertIsDisplayed()
            snapshot("event")
            onNodeWithContentDescription("Back").performClick()
            onNodeWithText("Inbox").performClick()
            waitUntil(10_000) { onAllNodesWithText(note.title).fetchSemanticsNodes().isNotEmpty() }
            snapshot("inbox")
            onNodeWithText(task.title).performClick()
            onNodeWithText(task.body).assertIsDisplayed()
            snapshot("task")
            onNodeWithContentDescription("Back").performClick()
            onNodeWithText(note.title).performClick()
            onNodeWithText("Edit").performClick()
            onNodeWithContentDescription("Formatting").performScrollTo().performClick()
            snapshot("formatting-menu")
            onNodeWithText("Bold").performClick()
            onNodeWithContentDescription("Formatting").performClick()
            onNode(hasText("Undo") and hasAnyAncestor(isPopup())).performClick()
            onNodeWithContentDescription("Formatting").performClick()
            onNode(hasText("Redo") and hasAnyAncestor(isPopup())).performClick()
            onNodeWithText("Save").performClick()
            waitUntil(10_000) {
                runBlocking { app.repository.readWorkspaceItem(note.id)?.body?.let { RichBody.read(it).spans.isNotEmpty() } == true }
            }
            assertEquals(listOf(BodySpan(0, note.body.length, BodyFormat.BOLD)),
                runBlocking { RichBody.read(requireNotNull(app.repository.readWorkspaceItem(note.id)).body).spans })
            snapshot("formatted-note")
            Unit
        } finally {
            runBlocking {
                app.repository.deleteEntry(note.id)
                app.repository.deleteEntry(task.id)
                app.repository.deleteEvent(event.id)
                folders.forEach { app.repository.deleteCollection(it.id) }
            }
            runOnIdle { app.preferences.update(preferences) }
        }
    }

    private fun snapshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("release_width") == null) return
        val width = InstrumentationRegistry.getArguments().getString("release_width")
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(300)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            instrumentation.targetContext.openFileOutput("v0482-$width-$name.png", 0).use {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            image.recycle()
        }
    }
}
