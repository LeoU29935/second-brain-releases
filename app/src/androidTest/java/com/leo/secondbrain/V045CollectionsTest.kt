package com.leo.secondbrain

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leo.secondbrain.data.*
import com.leo.secondbrain.ui.CollectionScreen
import com.leo.secondbrain.ui.LibraryState
import com.leo.secondbrain.ui.theme.SecondBrainTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V045CollectionsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun carouselSelectsFoldersAndDisplaysCardsBelowSubfolders() = with(compose) {
        val music = CollectionEntity(id = "music", name = "Music")
        val albums = CollectionEntity(id = "albums", name = "Albums", parentId = music.id)
        val archive = CollectionEntity(id = "archive", name = "Archive")
        val note = EntryEntity(kind = EntryKind.NOTE, title = "Mic placement", body = "Move the mic toward the bridge",
            collectionId = albums.id)
        val state = LibraryState(collections = listOf(music, albums, archive), loading = false,
            entries = listOf(TaggedEntry(note, emptyList())))
        val widths = mutableStateOf(308.dp)
        val titles = mutableStateOf(true)
        val order = mutableStateOf(listOf(music.id, archive.id))
        val opened = mutableStateOf<String?>(null)
        setContent {
            SecondBrainTheme(false, Preferences()) {
                Box(Modifier.width(widths.value).fillMaxHeight()) {
                    CollectionScreen(state, null, false, emptyList(), {}, {}, {}, {}, {}, {}, {},
                        { opened.value = it.entry.id }, { _, _ -> }, true, {}, {}, {},
                        showFolderTitles = titles.value, folderOrder = order.value,
                        selectedFolders = mapOf("" to music.id),
                        onToggleFolderTitles = { titles.value = !titles.value },
                        onFolderOrder = { order.value = it })
                }
            }
        }
        onNodeWithText("Search").assertIsDisplayed()
        onNodeWithText("Inbox").assertIsDisplayed()
        onNodeWithText("Favorites").assertIsDisplayed()
        onNodeWithContentDescription("Music").assertIsSelected().performClick()
        onNodeWithTag("folder-rail").assertIsDisplayed()
        val navigation = onNodeWithTag("folder-navigation").getUnclippedBoundsInRoot()
        assertTrue(navigation.bottom - navigation.top <= 110.dp)
        onNodeWithText("Subfolders").assertDoesNotExist()
        onNodeWithContentDescription("Albums").performClick().assertIsSelected()
        onNode(hasText("Music") and hasAnyAncestor(hasTestTag("folder-breadcrumbs"))).assertIsDisplayed()
        onNode(hasText("Albums") and hasAnyAncestor(hasTestTag("folder-breadcrumbs"))).assertIsDisplayed()
        onNodeWithText("Move the mic toward the bridge").assertIsDisplayed()
        onNodeWithText("Mic placement").performClick()
        runOnIdle { assertEquals(note.id, opened.value); widths.value = 680.dp }
        onNodeWithContentDescription("Albums").assertIsSelected()
        onNodeWithContentDescription("Back to parent folders").performClick()
        onNodeWithContentDescription("Music").assertIsSelected()
        onNodeWithContentDescription("Collection settings").performClick()
        onNodeWithText("Hide folder titles").performClick()
        onNodeWithContentDescription("Music").assertIsSelected()
        onNodeWithContentDescription("Music").performTouchInput { longClick() }
        onNodeWithText("Reorder folders").performClick()
        onNodeWithContentDescription("Reorder Music").performTouchInput {
            swipe(center, center.copy(y = center.y + height * 1.5f), durationMillis = 400)
        }
        onNodeWithText("Save").performClick()
        runOnIdle { assertEquals(listOf(archive.id, music.id), order.value) }
    }

    @Test
    fun cardsShowMixedContentAndKeepActionsReachable() = with(compose) {
        val folder = CollectionEntity(id = "workshop", name = "Workshop", color = 5)
        val nested = CollectionEntity(id = "reference", name = "Reference", parentId = folder.id)
        val another = CollectionEntity(id = "archive", name = "Archive", color = 8)
        val ideas = CollectionEntity(id = "ideas", name = "Ideas", color = 3)
        val note = EntryEntity(kind = EntryKind.NOTE, title = "Ideas",
            body = "Try the linen cover and a warm neutral binding.", collectionId = folder.id)
        val longNote = EntryEntity(kind = EntryKind.NOTE,
            title = "Observations from the first prototype and what to change before the next workshop",
            body = "The hinge holds at a shallow angle. Try a wider stop and repeat the test using a thicker sheet. Keep the thinner sample for comparison.",
            collectionId = folder.id)
        val tomorrow = LocalDate.now().plusDays(1).toEpochDay()
        val task = EntryEntity(kind = EntryKind.TASK, title = "Send the revised layout to the print studio for review",
            body = "Attach the front and back exports and ask for a proof.", priority = Priority.HIGH,
            deadline = tomorrow, dueMinute = 570, hardDeadline = tomorrow + 2, collectionId = folder.id)
        val event = Event(title = "Studio walkthrough", body = "Bring the samples and review the lighting.",
            startDay = tomorrow, endDay = tomorrow, startMinute = 870, endMinute = 930, allDay = false,
            location = "Room 3", collectionId = folder.id)
        val state = LibraryState(collections = listOf(folder, nested, another, ideas), loading = false,
            entries = listOf(TaggedEntry(note, emptyList()), TaggedEntry(task, emptyList()), TaggedEntry(longNote, emptyList())),
            events = listOf(event))
        var opened: String? = null
        var completed = false
        setContent {
            SecondBrainTheme(false, Preferences()) {
                Surface(Modifier.fillMaxWidth().fillMaxHeight(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.safeDrawingPadding()) {
                        CollectionScreen(state, null, false, emptyList(), {}, {}, {}, {}, {}, {}, {},
                            { opened = it.entry.id }, { _, checked -> completed = checked }, true, {},
                            { opened = it.id }, {}, folderOrder = listOf(folder.id, ideas.id, another.id),
                            selectedFolders = mapOf("" to folder.id))
                    }
                }
            }
        }
        onNodeWithText(note.body).assertIsDisplayed()
        onNodeWithText(longNote.title).assertExists()
        onNodeWithText("High", substring = false).assertExists()
        onAllNodesWithText("Tomorrow", substring = true)[0].assertExists()
        val nav = onNodeWithTag("folder-rail").getUnclippedBoundsInRoot()
        assertTrue(nav.bottom - nav.top <= 65.dp)
        captureIfRequested("collections-refined.png")
        onNodeWithContentDescription("Complete ${task.title}").performClick()
        runOnIdle { assertTrue(completed) }
        onNodeWithText(event.title).performScrollTo().performClick()
        runOnIdle { assertEquals(event.id, opened) }
        onNodeWithContentDescription("Workshop").performClick()
        onNodeWithContentDescription("Reference").assertIsDisplayed()
        onNodeWithContentDescription("Back to parent folders").assertIsDisplayed()
        captureIfRequested("collections-drilled.png")
        Unit
    }

    @Test
    fun scrollingSelectsTheFolderAtTheCenter() = with(compose) {
        val folders = List(12) { index -> CollectionEntity(id = "folder-$index", name = "Folder $index") }
        val selected = mutableStateOf(folders.first().id)
        setContent {
            SecondBrainTheme(false, Preferences()) {
                Box(Modifier.width(308.dp)) {
                    com.leo.secondbrain.ui.FolderCarousel(
                        folders, selected.value, true, emptySet(), {}, { selected.value = it }, {}
                    )
                }
            }
        }
        onNodeWithContentDescription("Folder 0").assertIsSelected()
        onNodeWithContentDescription("Folder 0").performTouchInput {
            swipe(center, center.copy(x = center.x - width * 1.6f), durationMillis = 240)
        }
        waitUntil(5_000) { selected.value != folders.first().id }
        val name = runOnIdle { folders.single { it.id == selected.value }.name }
        onNodeWithContentDescription(name).assertIsSelected()
        Unit
    }

    private fun captureIfRequested(file: String) {
        if (InstrumentationRegistry.getArguments().getString("collection_screenshot") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(350)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            instrumentation.targetContext.openFileOutput(file, 0).use {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            image.recycle()
        }
    }
}
