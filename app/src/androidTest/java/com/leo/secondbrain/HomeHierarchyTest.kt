package com.leo.secondbrain

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leo.secondbrain.data.CollectionEntity
import com.leo.secondbrain.data.TrashKind
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeHierarchyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun independentlyExpandedBranchesAndPinnedContentSurviveOpeningAWorkspace() = with(compose) {
        val app = activity.application as BrainApplication
        val before = app.preferences.preferences.value
        val space = CollectionEntity(name = "00 Studio")
        val folder = CollectionEntity(name = "Production", parentId = space.id)
        val leaf = CollectionEntity(name = "Mixing", parentId = folder.id)
        val other = CollectionEntity(name = "01 Reference")
        val otherLeaf = CollectionEntity(name = "Synth manuals", parentId = other.id)
        try {
            app.preferences.update(before.copy(folderCarousel = false, simplifyMotion = true,
                folderOrder = listOf(space.id, other.id)))
            runBlocking {
                listOf(space, folder, leaf, other, otherLeaf).forEach { app.repository.saveCollection(it) }
                app.repository.toggleFavorite(leaf.id, TrashKind.COLLECTION)
            }
            waitUntil(12_000) { onAllNodesWithText(space.name).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("folder:${space.id}").performClick()
            onNodeWithTag("folder:${folder.id}").performClick()
            onNodeWithTag("folder:${other.id}").performScrollTo().performClick()
            onNodeWithTag("folder:${otherLeaf.id}").performScrollTo().assertIsDisplayed()
            onNodeWithTag("folder:${leaf.id}").performScrollTo().assertIsDisplayed()
            onNodeWithTag("pin:${leaf.id}").performScrollTo().performClick()
            onNodeWithTag("workspace-navigation").assertIsDisplayed()
            onNodeWithText("Notes").performClick()
            onNodeWithText("Tasks").performClick()
            onNodeWithText("Events").performClick()
            onNodeWithContentDescription("Back").performClick()
            onNodeWithTag("folder:${folder.id}").performScrollTo().assertIsDisplayed()
            onNodeWithTag("folder:${otherLeaf.id}").performScrollTo().assertIsDisplayed()
            onNodeWithContentDescription("Add to ${otherLeaf.name}").performScrollTo().performClick()
            onNodeWithText("Note").performClick()
            val note = "Quick add ${System.nanoTime()}"
            onNodeWithText("Title").performTextInput(note)
            onNodeWithContentDescription("Back").performClick()
            waitUntil(10_000) { onAllNodesWithText(note).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(note).assertIsDisplayed()
        } finally {
            app.preferences.update(before)
            runBlocking {
                app.repository.deleteCollection(space.id)
                app.repository.deleteCollection(other.id)
            }
        }
        Unit
    }
}
