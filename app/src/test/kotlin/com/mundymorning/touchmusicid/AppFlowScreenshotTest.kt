package com.mundymorning.touchmusicid

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.provider.Settings
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.mundymorning.touchmusicid.recognition.DemoEngine
import com.mundymorning.touchmusicid.recognition.EngineProvider
import com.mundymorning.touchmusicid.recognition.TokenResult
import com.mundymorning.touchmusicid.recognition.TokenSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * End-to-end UI flow on the real MainActivity under Robolectric (native graphics), with a fake Wikipedia/Wikidata backend.
 * Doubles as the screenshot generator: PNGs are written to app/build/screenshots/.
 * These are JVM renderings, not a physical device; they prove layout and flow, not performance.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class AppFlowScreenshotTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val outDir = File("build/screenshots").apply { mkdirs() }

    private object NoToken : TokenSource {
        override val isConfigured = false
        override suspend fun token() = TokenResult.NotConfigured
    }

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>() as TouchMusicIdApp
        // Reduce-motion path: no infinite decorative animation, so the test can reach idle.
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val wiki = QueenWorld.build()
        app.container = AppContainer(
            app, http = wiki.http, inMemoryDatabase = true,
            engineProvider = EngineProvider(NoToken, sdkLinked = { false }, demo = DemoEngine(listenMillis = 300, processMillis = 100, pick = { 0 })),
        )
        compose.mainClock.autoAdvance = true
        scenario = ActivityScenario.launch(MainActivity::class.java)
        settle()
    }

    @After fun tearDown() { scenario?.close() }

    /** Advances virtual time while giving Room's background executors real time to finish. */
    private fun settle(ms: Long = 600) {
        val steps = 12
        repeat(steps) {
            ShadowLooper.idleMainLooper(ms / steps, TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(ms / steps)   // effect delays (LaunchedEffect) run on the test clock
            compose.waitForIdle()
            Thread.sleep(25)
        }
    }

    private fun shot(name: String) {
        var bmp: Bitmap? = null
        scenario!!.onActivity { act ->
            val v = act.window.decorView
            val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(act.window, b, { _ -> }, Handler(Looper.getMainLooper()))
            bmp = b
        }
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        File(outDir, "$name.png").outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tapListen() = compose.onNode(hasContentDescription("Listen. Double tap to identify the music playing nearby.")).performClick()

    @Test fun withoutDemoModeListenNeverProducesAMatch() {
        compose.onNodeWithText("Tap to listen").assertIsDisplayed()
        compose.onNodeWithText("Recognition not configured", ignoreCase = true).assertIsDisplayed()
        shot("01_listen_idle")
        tapListen(); settle()
        compose.onNodeWithText("Recognition isn't set up yet").assertIsDisplayed()
        // Still on the Listen screen, no result, no history.
        compose.onNodeWithText("Tap to listen").assertIsDisplayed()
        shot("02_listen_not_configured")
    }

    @Test fun demoFlowReachesLabelledResultWithWikipediaFactsAndPersistsToHistoryAndFavorites() {
        tapListen(); settle()
        compose.onNodeWithText("Try demo mode").performClick()
        settle(100)
        compose.onNodeWithText("Listening…").assertIsDisplayed()
        shot("03_listen_listening_demo")
        settle(3000)

        // Result screen: demo is labelled, facts are sourced.
        compose.onNodeWithText("Bohemian Rhapsody").assertIsDisplayed()
        compose.onNodeWithText("Demo result · not real recognition", ignoreCase = true).assertIsDisplayed()
        shot("04_result_top")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("About the song"))
        settle(200)
        compose.onNodeWithText("About the song").assertIsDisplayed()
        shot("05_result_song_facts")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sources & attribution"))
        settle(200)
        compose.onNodeWithText("Sources & attribution").assertIsDisplayed()
        shot("06_result_sources")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Listen on"))
        settle(200)

        // Favorite it, then see it in History and Favorites.
        compose.onNodeWithContentDescription("Add to favorites").performClick(); settle()
        compose.onNodeWithContentDescription("Remove from favorites").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick(); settle()
        compose.onAllNodesWithText("History")[0].performClick(); settle()
        compose.onNodeWithText("Bohemian Rhapsody").assertIsDisplayed()
        shot("07_history")
        compose.onAllNodesWithText("Favorites")[0].performClick(); settle()
        compose.onNodeWithText("Bohemian Rhapsody").assertIsDisplayed()
        shot("08_favorites")
        compose.onAllNodesWithText("Settings")[0].performClick(); settle()
        compose.onNodeWithText("Apple ShazamKit SDK").assertIsDisplayed()
        shot("09_settings")
    }

    @Test fun setupGuideStatesWhatIsBlocked() {
        tapListen(); settle()
        compose.onNodeWithText("Setup guide").performClick(); settle()
        compose.onNodeWithText("What is blocked, and why").assertIsDisplayed()
        shot("10_setup_guide")
        assertTrue(compose.onAllNodesWithText("Apple Developer account", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
}
