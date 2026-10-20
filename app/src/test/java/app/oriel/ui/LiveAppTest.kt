package app.oriel.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.LiveTestApp
import app.oriel.MainActivity
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.semantics.getOrNull
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The real app against the real YouTube, end to end, signed out: launches
 * MainActivity, waits for the composed home feed, searches, opens a video's
 * watch page (page, related, comments), its channel, and Shorts, capturing
 * each. Nothing plays under Robolectric; this checks pages, not playback.
 * Opt-in: `-Plive=true`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = LiveTestApp::class, sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class LiveAppTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun gate() = assumeTrue("live run (-Plive=true)", System.getProperty("oriel.live") == "true")
    }

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun await(what: String, timeoutMs: Long = 45_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            rule.mainClock.advanceTimeBy(100)
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(100)
        }
        runCatching { shot("timeout") }.onFailure { println("timeout shot failed: $it") }
        // What was on screen instead, so a timeout says where the app stood.
        runCatching {
            val texts = rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().flatMap { root ->
                generateSequence(listOf(root)) { level -> level.flatMap { it.children }.ifEmpty { null } }.flatten()
                    .flatMap { n -> (n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().map { it.text }) + n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription).orEmpty() }
            }
            println("on screen at timeout: " + texts.distinct().take(60).joinToString(" | "))
        }
        error("Timed out waiting for $what")
    }

    private fun settleImages(ms: Long = 5_000) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            rule.mainClock.advanceTimeBy(100)
            Thread.sleep(80)
        }
    }

    private fun count(matcher: androidx.compose.ui.test.SemanticsMatcher) = rule.onAllNodes(matcher).fetchSemanticsNodes().size
    private fun clickable() = count(hasClickAction())
    private fun shot(name: String) = rule.onRoot().captureRoboImage("build/screenshots/live_$name.png")

    @Test
    fun walkThrough() {
        rule.mainClock.autoAdvance = false

        // Home cards read "Channel · 2 days ago"; the view count waits for the watch page.
        await("the composed home feed", 90_000) { count(hasText(" ago", substring = true)) >= 1 }
        settleImages()
        println("images after home: ${LiveTestApp.imagesLoaded.get()} loaded, ${LiveTestApp.imagesFailed.get()} failed")
        shot("01_home")

        rule.onAllNodes(hasContentDescription("Search")).onFirst().performClick()
        await("the search page") { count(hasSetTextAction()) > 0 }
        rule.onAllNodes(hasSetTextAction()).onFirst().performClick()
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("lighthouse keeper")
        await("suggestions") { count(hasContentDescription("Use")) >= 3 }
        shot("02_suggestions")

        rule.onAllNodes(hasSetTextAction()).onFirst().performImeAction()
        await("results") { count(hasText(" ago", substring = true)) >= 1 }
        settleImages()
        shot("03_results")

        rule.onAllNodes(hasText(" ago", substring = true)).onFirst().performClick()
        await("the watch page") { count(hasText(" views", substring = true)) > 0 && count(hasText("Share")) > 0 }
        await("related videos") { count(hasText(" ago", substring = true)) >= 1 }
        settleImages()
        shot("04_watch")

        if (count(hasText("Comments")) > 0) {
            rule.onAllNodes(hasText("Comments")).onFirst().performClick()
            await("comments", 30_000) { count(hasText("ago", substring = true)) >= 3 }
            settleImages(2_500)
            shot("05_comments")
            rule.onAllNodes(hasContentDescription("Close")).onFirst().performClick()
        }

        // A link carries nothing but an id; the page has to supply the title before it plays.
        rule.runOnUiThread { rule.activity.route(Intent(Intent.ACTION_VIEW, Uri.parse("https://youtu.be/jNQXAC9IVRw"))) }
        await("the linked video's page") { count(hasText("Me at the zoo")) > 0 && count(hasText(" views", substring = true)) > 0 }
        settleImages(2_500)
        shot("06_link")

        // The player's settings sheet, over the watch page.
        rule.onAllNodes(hasContentDescription("Settings")).onFirst().performClick()
        await("player settings") { count(hasText("Quality")) > 0 }
        settleImages(1_500)
        shot("07_player_settings")
        // Back goes to the focused window, the sheet's own, which closes it and leaves the page.
        androidx.test.espresso.Espresso.pressBack()
        await("the sheet to close") { count(hasText("Loop video")) == 0 }

        // The channel, from the watch page's channel row.
        rule.onAllNodes(hasText("jawed")).onFirst().performClick()
        await("the channel page") { count(hasText("subscribers", substring = true)) > 0 && count(hasText("Videos")) > 0 }
        settleImages()
        shot("08_channel")

        // Its About sheet, from the description's preview row.
        rule.onAllNodes(hasText("about this channel", substring = true, ignoreCase = true)).onFirst().performClick()
        await("the About sheet", 30_000) { count(hasText("CHANNEL DETAILS")) > 0 && count(hasText("Joined", substring = true)) > 0 }
        settleImages(2_000)
        shot("08b_about")
        androidx.test.espresso.Espresso.pressBack()
        await("the About sheet to close") { count(hasText("CHANNEL DETAILS")) == 0 }

        // Shorts, from the tab bar.
        rule.onAllNodes(hasContentDescription("Shorts")).onFirst().performClick()
        await("shorts", 45_000) { count(hasContentDescription("Share")) > 0 }
        settleImages()
        shot("09_shorts")
        println("images at the end: ${LiveTestApp.imagesLoaded.get()} loaded, ${LiveTestApp.imagesFailed.get()} failed")
    }

    /**
     * A shared link, the way a user opens one: the watch page with its real
     * description, chapters, comments and related videos, scrolled to the
     * end. `-Plink=` picks the video; the default has chapters and a long
     * description, which is where odd data tends to live.
     */
    @Test
    fun sharedLink() {
        rule.mainClock.autoAdvance = false
        val link = System.getProperty("oriel.link") ?: "https://youtu.be/JocjKnl4lBs"
        await("the home feed", 90_000) { count(hasText(" ago", substring = true)) >= 1 }
        rule.runOnUiThread { rule.activity.route(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
        await("the linked video's page") { count(hasText(" views", substring = true)) > 0 && count(hasText("Share")) > 0 }
        settleImages(2_500)
        shot("10_shared_link")

        // The description, with its timestamps and links.
        rule.onAllNodes(hasText(" views", substring = true)).onFirst().performClick()
        settleImages(2_000)
        shot("11_shared_description")
        // Back closes the description and leaves the page.
        androidx.test.espresso.Espresso.pressBack()
        await("the watch page after closing the description") { count(hasText(" views", substring = true)) > 0 && count(hasText("Share")) > 0 }
        settleImages(1_000)

        if (count(hasText("Comments")) > 0) {
            rule.onAllNodes(hasText("Comments")).onFirst().performClick()
            await("comments", 30_000) { count(hasText("ago", substring = true)) >= 3 }
            settleImages(2_000)
            shot("12_shared_comments")
            rule.onAllNodes(hasContentDescription("Close")).onFirst().performClick()
            settleImages(1_000)
        }

        // Down through the related videos, loading the next page on the way.
        repeat(8) {
            rule.onRoot().performTouchInput { swipeUp(startY = bottom * 0.85f, endY = bottom * 0.15f) }
            settleImages(1_200)
        }
        shot("13_shared_related")
    }
}
