package com.leo.secondbrain

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leo.secondbrain.ui.TaskCompletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskCompletionFeedbackTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pressedFeedbackStaysOnTheCircleWithTheCornerAlignedIndicator() {
        val checked = mutableStateOf(false)
        compose.setContent {
            Surface {
                Box {
                    TaskCompletion(checked.value, { checked.value = it }, Modifier.testTag("completion"),
                        indicatorAlignment = Alignment.TopEnd)
                }
            }
        }
        val toggle = compose.onNodeWithTag("completion")
        val before = toggle.captureToImage().toPixelMap()
        toggle.performTouchInput { down(center); advanceEventTime(350) }
        compose.mainClock.advanceTimeBy(350)
        val pressed = toggle.captureToImage().toPixelMap()
        assertEquals(before[2, before.height - 3], pressed[2, pressed.height - 3])
        assertEquals(before[2, 2], pressed[2, 2])
        assertNotEquals(before[before.width * 4 / 5, before.height / 5],
            pressed[pressed.width * 4 / 5, pressed.height / 5])
        toggle.performTouchInput { up() }
        toggle.assertIsOn()
    }
}
