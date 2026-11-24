package com.leo.secondbrain

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leo.secondbrain.capture.QuickCalendarActivity
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V048QuickCalendarTest {
    @get:Rule val compose = createAndroidComposeRule<QuickCalendarActivity>()

    @Test
    fun standaloneCalendarDefaultsToUpcomingAndRestoresSelectedMode() = with(compose) {
        waitUntil(15_000) { onAllNodesWithText("Upcoming").fetchSemanticsNodes().size == 1 }
        onNodeWithText("Upcoming").assertIsSelected()
        val frame = onNodeWithTag("calendar-frame").getUnclippedBoundsInRoot()
        onNodeWithTag("workspace-navigation").assertDoesNotExist()
        onNodeWithText("Month").performClick().assertIsSelected()
        assertEquals(frame, onNodeWithTag("calendar-frame").getUnclippedBoundsInRoot())
        activityRule.scenario.recreate()
        waitUntil(15_000) { onAllNodesWithText("Month").fetchSemanticsNodes().size == 1 }
        onNodeWithText("Month").assertIsSelected()
        onNodeWithText("Week").performClick().assertIsSelected()
        assertEquals(frame, onNodeWithTag("calendar-frame").getUnclippedBoundsInRoot())
        onNodeWithContentDescription("Close calendar").performClick()
        Unit
    }
}
