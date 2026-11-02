package com.mundymorning.touchmusicid

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtMost
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.mundymorning.touchmusicid.design.AssetSlot
import com.mundymorning.touchmusicid.design.CompactNotice
import com.mundymorning.touchmusicid.design.TmiButton
import com.mundymorning.touchmusicid.design.TouchMusicIdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class CompactNoticeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsTitleBodyAndBothActionsInACompactBlockAndActionsWork() {
        var searched = 0; var retried = 0
        compose.setContent {
            TouchMusicIdTheme {
                Box(Modifier.testTag("notice")) {
                    CompactNotice(AssetSlot.EMPTY_NO_ARTICLE, Icons.Filled.Search, "No verified article for “Morgan Wallen”",
                        "We checked, and no Wikipedia or Wikidata entry passed our name and type checks. Nothing here is guessed.") {
                        TmiButton("Search Wikipedia", { searched++ }, Modifier.weight(1f), primary = false, compact = true)
                        TmiButton("Check again", { retried++ }, Modifier.weight(1f), primary = false, compact = true)
                    }
                }
            }
        }
        compose.onNodeWithText("No verified article for “Morgan Wallen”").assertIsDisplayed()
        compose.onNodeWithText("Search Wikipedia").assertIsDisplayed().performClick()
        compose.onNodeWithText("Check again").assertIsDisplayed().performClick()
        assertEquals(1, searched); assertEquals(1, retried)
        // The old EmptyState placeholder alone was a 160 dp circle plus padding (>300 dp). The whole notice must stay small.
        compose.onNodeWithTag("notice").assertHeightIsAtMost(190.dp)
    }

    @Test fun failedLookupVariantHasASingleRetryAction() {
        var retried = 0
        compose.setContent {
            TouchMusicIdTheme {
                Box(Modifier.testTag("notice")) {
                    CompactNotice(null, Icons.Filled.Search, "Wikipedia isn't responding", "This says nothing about whether “Morgan Wallen” has an article. Try again in a moment.") {
                        TmiButton("Retry", { retried++ }, Modifier.weight(1f), compact = true)
                    }
                }
            }
        }
        compose.onNodeWithText("Wikipedia isn't responding").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retried)
        compose.onNodeWithTag("notice").assertHeightIsAtMost(170.dp)
    }
}
