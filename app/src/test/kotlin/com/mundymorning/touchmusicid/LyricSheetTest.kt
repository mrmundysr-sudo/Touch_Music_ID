package com.mundymorning.touchmusicid

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import com.mundymorning.touchmusicid.design.TouchMusicIdTheme
import com.mundymorning.touchmusicid.lyricsproto.LyricSheet
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class LyricSheetTest {
    @get:Rule val compose = createComposeRule()
    private val lines = (0 until 60).map { "line$it" }

    @Test fun synchronizedModeMarksExactlyTheActiveLine() {
        compose.setContent { TouchMusicIdTheme { LyricSheet(lines.take(5), 1, followActive = false, synced = true, onUserTouch = {}, modifier = Modifier.height(400.dp)) } }
        compose.onNodeWithContentDescription("Current line: line1").assertExists()
        compose.onNodeWithContentDescription("Current line: line0").assertDoesNotExist()
    }

    @Test fun plainModeNeverHighlightsAnything() {
        compose.setContent { TouchMusicIdTheme { LyricSheet(lines.take(5), 1, followActive = false, synced = false, onUserTouch = {}, modifier = Modifier.height(400.dp)) } }
        compose.onNodeWithContentDescription("Current line: line1").assertDoesNotExist()
    }

    @Test fun followingScrollsALateLineIntoView() {
        compose.setContent { TouchMusicIdTheme { LyricSheet(lines, 50, followActive = true, synced = true, onUserTouch = {}, modifier = Modifier.height(400.dp)) } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Current line: line50").assertIsDisplayed()
    }

    @Test fun touchingTheListIsReportedSoAutoFollowCanPause() {
        var touched = 0
        compose.setContent { TouchMusicIdTheme { LyricSheet(lines, 2, followActive = false, synced = true, onUserTouch = { touched++ }, modifier = Modifier.testTag("sheet").height(400.dp)) } }
        compose.onNodeWithTag("sheet").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue("touched=$touched", touched > 0)
    }
}
