package app.oriel.ui

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import androidx.compose.ui.test.click
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.test.swipe
import app.oriel.core.model.ChannelRef
import app.oriel.core.model.ShortInfo
import app.oriel.ui.components.ChipsRow
import app.oriel.ui.navigation.ShortsRoute
import app.oriel.ui.screens.ShortsModel
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.TestApp
import app.oriel.core.model.Feed
import app.oriel.data.ThemeMode
import app.oriel.playback.PlayerConnection
import app.oriel.ui.components.IosSwitch
import app.oriel.ui.components.SegmentedControl
import app.oriel.ui.navigation.HistoryRoute
import app.oriel.ui.navigation.SettingsRoute
import app.oriel.ui.theme.OrielTheme
import app.oriel.ui.theme.LocalLiquidMotion
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.awt.Color as AwtColor
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Frame strips of the liquid interactions, to lay beside the HTML mock's (design/liquid): each
 * one is driven with the same events at the same times as the mock's capture script, the clock
 * held and stepped a frame at a time, and caught at the same instants and crops (in dp, which
 * is the mock's px). Written to /tmp/liquid-compare/app_<name>.png. Only with -Pframes=true:
 * it's a tool for looking, not a check.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class LiquidMotionStripTest {
    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val graph get() = app.graph
    private var appState: AppState? = null
    private val state get() = appState!!
    private val density = 2f // xhdpi

    @Before fun gate() {
        assumeTrue("frame strips (-Pframes=true)", System.getProperty("oriel.frames") == "true")
        // The screen capture (which takes in a sheet's window) only writes when recording.
        System.setProperty("roborazzi.test.record", "true")
    }

    private fun step(ms: Long) {
        if (ms <= 0) return
        Thread.sleep(4)
        ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)
        rule.mainClock.advanceTimeBy(ms, ignoreFrameDuration = true)
    }

    private fun settle(rounds: Int = 16) = repeat(rounds) { Thread.sleep(20); step(120) }

    private fun start(theme: ThemeMode = ThemeMode.DARK, playing: Boolean = true, setup: suspend (AppState) -> Unit = {}) {
        rule.mainClock.autoAdvance = false
        graph.pages.write("home-false", Feed.serializer(), Fixtures.homeFeed)
        rule.setContent { AppFrame(theme, playing, setup) }
        settle(28)
    }

    @Composable
    private fun AppFrame(theme: ThemeMode, playing: Boolean, setup: suspend (AppState) -> Unit) {
        OrielTheme(theme, reduceAnimation = false, liquidGlass = true) { DeviceSlop {
            val nav = rememberNavController()
            val scope = rememberCoroutineScope()
            val activity = androidx.activity.compose.LocalActivity.current!!
            val connection = remember { PlayerConnection(activity, graph) }
            val state = remember { AppState(graph, nav, connection, scope, activity) }
            appState = state
            LaunchedEffect(Unit) {
                if (playing) {
                    state.watch.preview(Fixtures.watch, Fixtures.comments, Fixtures.storyboard)
                    connection.preview(listOf(Fixtures.watch.video!!) + Fixtures.videos.drop(1).take(3), 0, playing = true, positionMs = 300_000)
                }
                nav.currentBackStackEntryFlow.first().lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
                setup(state)
            }
            OrielRoot(state, inPip = false)
        } }
    }

    /** Robolectric's touch slop is twice a phone's (16 dp); the mock's is a phone's 8. Liquid motion on, as these strips are of it. */
    @Composable
    private fun DeviceSlop(content: @Composable () -> Unit) {
        val base = LocalViewConfiguration.current
        val slop = with(LocalDensity.current) { 8.dp.toPx() }
        val config = remember(base, slop) { object : ViewConfiguration by base { override val touchSlop = slop } }
        CompositionLocalProvider(LocalViewConfiguration provides config, LocalLiquidMotion provides true, content = content)
    }

    // --- events, in dp like the mock ---

    private class Ev(val t: Long, val run: () -> Unit)

    private fun px(p: Offset) = Offset(p.x * density, p.y * density)
    /** The topmost window's root: a sheet's dialog when one is up. */
    private fun top() = rule.onAllNodes(isRoot()).onLast()
    private fun down(t: Long, x: Float, y: Float) = Ev(t) { top().performTouchInput { down(px(Offset(x, y))) } }
    private fun move(t: Long, x: Float, y: Float) = Ev(t) { top().performTouchInput { moveTo(px(Offset(x, y)), delayMillis = 0) } }
    private fun up(t: Long) = Ev(t) { top().performTouchInput { up() } }
    private fun at(t: Long, block: () -> Unit) = Ev(t) { rule.runOnUiThread(block) }

    /** The mock's tap: down 60 ms before, up at 0. */
    private fun tap(x: Float, y: Float) = listOf(down(-60, x, y), up(0))
    private fun tap(r: Rect) = tap(r.center.x, r.center.y)

    /** The mock's drag: down at 0, a move every 16 ms and one at [dur], up at [upAt]. */
    private fun drag(x: Float, y: Float, dx: Float, dy: Float, dur: Long, upAt: Long = dur): List<Ev> = buildList {
        add(down(0, x, y))
        var t = 16L
        while (t < dur) { add(move(t, x + dx * t / dur, y + dy * t / dur)); t += 16 }
        add(move(dur, x + dx, y + dy))
        add(up(upAt))
    }

    private fun pad(r: Rect, p: Float) = Rect(r.left - p, r.top - p, r.right + p, r.bottom + p)
    private val full = Rect(0f, 0f, 412f, 915f)

    /** Bounds of a node in dp. */
    private fun bounds(matcher: SemanticsMatcher): Rect {
        val b = rule.onAllNodes(matcher, useUnmergedTree = false).onFirst().fetchSemanticsNode().boundsInRoot
        return Rect(b.left / density, b.top / density, b.right / density, b.bottom / density)
    }

    // --- the strip ---

    private fun strip(name: String, crop: Rect, scale: Float, events: List<Ev>, caps: List<Long> = CAPS) {
        val shots = ArrayList<Pair<Long, BufferedImage>>()
        val start = minOf(0L, events.minOfOrNull { it.t } ?: 0L)
        val end = caps.max()
        // A frame every 16 ms, as the mock's clock, plus each event's and capture's own instant.
        val times = (generateSequence(start) { it + 16 }.takeWhile { it < end } + events.map { it.t } + caps).toSortedSet().toList()
        for ((i, t) in times.withIndex()) {
            events.filter { it.t == t }.forEach { it.run() }
            if (t in caps) shots += t to grab(crop, scale)
            times.getOrNull(i + 1)?.let { step(it - t) }
        }
        write(name, crop, scale, shots)
    }

    private fun grab(crop: Rect, scale: Float): BufferedImage {
        // The whole screen, dialogs (sheets) included.
        val file = File.createTempFile("strip", ".png")
        captureScreenRoboImage(file.path)
        val screen = ImageIO.read(file).also { file.delete() }
        val l = (crop.left * density).toInt().coerceIn(0, screen.width - 1)
        val tp = (crop.top * density).toInt().coerceIn(0, screen.height - 1)
        val w = (crop.width * density).toInt().coerceAtMost(screen.width - l).coerceAtLeast(1)
        val h = (crop.height * density).toInt().coerceAtMost(screen.height - tp).coerceAtLeast(1)
        val src = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { drawImage(screen.getSubimage(l, tp, w, h), 0, 0, null); dispose() } }
        val ow = Math.round(w / density * scale).coerceAtLeast(1)
        val oh = Math.round(h / density * scale).coerceAtLeast(1)
        if (ow == w && oh == h) return src
        return BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB).also { out ->
            out.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                drawImage(src, 0, 0, ow, oh, null)
                dispose()
            }
        }
    }

    /** Laid out as the mock's stitch.py does: a title, then each frame under its time. */
    private fun write(name: String, crop: Rect, scale: Float, shots: List<Pair<Long, BufferedImage>>) {
        val gap = 8; val lab = 22; val head = 28
        val w = gap + shots.sumOf { it.second.width + gap }
        val h = head + lab + shots.maxOf { it.second.height } + gap
        val out = BufferedImage(maxOf(w, 300), h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            color = AwtColor(40, 40, 46); fillRect(0, 0, out.width, out.height)
            font = Font(Font.SANS_SERIF, Font.BOLD, 16); color = AwtColor(255, 220, 120)
            drawString("app_$name  crop=[${crop.left}, ${crop.top}, ${crop.width}, ${crop.height}]  scale=$scale", gap, 20)
            font = Font(Font.SANS_SERIF, Font.PLAIN, 14); color = AwtColor(230, 230, 230)
            var x = gap
            for ((t, im) in shots) {
                drawString("t=${t}ms", x, head + 16)
                drawImage(im, x, head + lab, null)
                x += im.width + gap
            }
            dispose()
        }
        val dir = File("/tmp/liquid-compare").apply { mkdirs() }
        ImageIO.write(out, "png", File(dir, "app_$name.png"))
    }

    // --- the interactions, as the mock's run.js drives them ---

    private val tabBand = Rect(0f, 780f, 412f, 915f)

    @Test fun tabSwitch() {
        start(playing = false)
        strip("tab_switch", tabBand, 2f, tap(bounds(hasContentDescription("Subscriptions").or(hasText("Subscriptions")))))
    }

    @Test fun searchTap() {
        start(playing = false)
        strip("search_tap", tabBand, 2f, tap(bounds(hasContentDescription("Search"))))
    }

    /** Scrolls a feed card up to where the mock's first card sits (its top at 161 dp). */
    private fun feedCard(title: String): Rect {
        val before = bounds(hasText(title))
        val dy = before.top - 161f
        rule.onRoot().performTouchInput { swipe(px(Offset(206f, 700f)), px(Offset(206f, 700f - dy)), 1500) }
        settle(12)
        return bounds(hasText(title))
    }

    @Test fun cardPress() {
        start(playing = false)
        val card = feedCard(Fixtures.videos[4].title)
        val x = card.left + card.width * 0.75f
        val y = card.top + card.width * 9f / 16f * 0.4f
        strip("card_press", pad(card, 16f), 1.5f, drag(x, y, 24f, 16f, 150, 200))
    }

    /** On its own: signed out, Home's chip row doesn't show the chosen topic (HomeScreen passes a token where a topic name is wanted). */
    /**
     * The same press with the finger kept inside the touch slop: the mock's (24, 16) drag is
     * taken by Home's list here (a vertical scrollable claims any vertical move past the slop),
     * so this one shows the squish, its follow and the release.
     */
    @Test fun cardHold() {
        start(playing = false)
        val card = feedCard(Fixtures.videos[4].title)
        val x = card.left + card.width * 0.75f
        val y = card.top + card.width * 9f / 16f * 0.4f
        strip("card_hold", pad(card, 16f), 1.5f, drag(x, y, 5f, 3f, 150, 200))
    }

    @Test fun chipSwitch() {
        startControls()
        val chip = bounds(hasText("News"))
        strip("chip_switch", Rect(0f, chip.center.y - 35f, 412f, chip.center.y + 35f), 2f, tap(chip))
    }

    @Composable
    private fun ControlsFrame() {
        OrielTheme(ThemeMode.DARK, reduceAnimation = false, liquidGlass = true) { DeviceSlop {
            var on by remember { mutableStateOf(false) }
            var chip by remember { mutableStateOf("All") }
            var segment by remember { mutableStateOf("Auto") }
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 120.dp)) {
                Row(Modifier.padding(start = 329.dp)) {
                    Box(Modifier.onGloballyPositioned { switchBounds = it.boundsInRoot() }) { IosSwitch(on, onChange = { on = it }) }
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp).onGloballyPositioned { segBounds = it.boundsInRoot() }) {
                    SegmentedControl(listOf("Auto", "High", "Data saver"), segment, { it }, { segment = it })
                }
                // Home's chips, as signed out Home shows them.
                Box(Modifier.onGloballyPositioned { chipsBounds = it.boundsInRoot() }) {
                    ChipsRow(Fixtures.homeFeed.chips.map { it.copy(selected = it.title == chip) }, onSelect = { chip = it.title })
                }
            }
        } }
    }

    private var chipsBounds = Rect.Zero

    private var switchBounds = Rect.Zero
    private var segBounds = Rect.Zero
    private fun dp(r: Rect) = Rect(r.left / density, r.top / density, r.right / density, r.bottom / density)

    private fun startControls() {
        rule.mainClock.autoAdvance = false
        rule.setContent { ControlsFrame() }
        settle(8)
    }

    @Test fun toggle() {
        startControls()
        val r = dp(switchBounds)
        strip("toggle", pad(r, 16f), 4f, tap(r))
    }

    @Test fun toggleDrag() {
        startControls()
        val r = dp(switchBounds)
        strip("toggle_drag", pad(r, 16f), 4f, drag(r.left + 15.5f, r.top + 15.5f, 20f, 0f, 150))
    }

    @Test fun segmented() {
        startControls()
        val r = dp(segBounds)
        strip("segmented", pad(r, 12f), 2f, tap(r.left + r.width * 5f / 6f, r.center.y))
    }

    @Test fun miniTap() {
        start(setup = { it.sheet.progress.snapTo(0f) })
        val r = bounds(hasContentDescription("Video window"))
        strip("mini_tap", full, 0.5f, tap(r.left + r.width / 6f, r.center.y))
    }

    @Test fun miniDrag() {
        start(setup = { it.sheet.progress.snapTo(0f) })
        val r = bounds(hasContentDescription("Video window"))
        strip("mini_drag", full, 0.5f, drag(r.left + r.width * 0.3f, r.center.y, 0f, -420f, 200))
    }

    @Test fun collapse() {
        start(setup = { it.sheet.progress.snapTo(1f) })
        strip("collapse", full, 0.5f, listOf(at(0) { state.sheet.collapse() }))
    }

    @Test fun collapseDrag() {
        start(setup = { it.sheet.progress.snapTo(1f) })
        strip("collapse_drag", full, 0.5f, drag(206f, 116f, 0f, 420f, 200))
    }

    /** The player's settings, from its overlay button, as the mock opens chapters from its button. */
    private fun showControls() {
        rule.onRoot().performTouchInput { click(Offset(width * 0.5f, 116f * density)) }
        settle(4)
    }

    /**
     * A card's More: its sheet grows out of the button. (The mock's is the chapters sheet out of
     * its button on the watch page; the player's overlay buttons aren't drawn without a picture here.)
     */
    private fun moreButton(): Rect {
        feedCard(Fixtures.videos[4].title)
        return bounds(hasContentDescription("More").and(SemanticsMatcher("near the card") { n -> n.boundsInRoot.top / density in 150f..500f }))
    }

    @Test fun sheet() {
        start(playing = false)
        strip("sheet", full, 0.5f, tap(moreButton()))
    }

    @Test fun sheetDrag() {
        start(playing = false)
        val more = moreButton()
        top().performTouchInput { click(px(more.center)) }
        settle(14)
        // From the sheet's first row (its list is at its top, so the drag is the sheet's).
        val row = bounds(hasText("Play next"))
        strip("sheet_drag", full, 0.5f, drag(206f, row.center.y, 0f, 160f, 200))
    }

    @Test fun pullToRefresh() {
        start(playing = false)
        strip("ptr", Rect(0f, 0f, 412f, 450f), 1f, drag(206f, 300f, 0f, 240f, 300), CAPS + listOf(800L, 1300L))
    }

    @Test fun push() {
        start(playing = false)
        strip("push", full, 0.5f, listOf(at(0) { state.navigate(SettingsRoute) }))
    }

    /** The system's back gesture from the left edge, as the mock's edge drag: 10 to 230 dp in 200 ms, let go there. */
    @Test fun backSwipe() {
        start(playing = false, setup = { it.navigate(HistoryRoute) })
        settle(8)
        val dispatcher = (state.activity as ComponentActivity).onBackPressedDispatcher
        val events = buildList {
            add(at(0) { dispatcher.dispatchOnBackStarted(BackEventCompat(10f * density, 450f * density, 0f, BackEventCompat.EDGE_LEFT)) })
            var t = 16L
            while (t <= 200) {
                val x = 10f + 220f * minOf(t, 200L) / 200f
                add(at(t) { dispatcher.dispatchOnBackProgressed(BackEventCompat(x * density, 450f * density, x / 412f, BackEventCompat.EDGE_LEFT)) })
                t += 16
            }
            add(at(200) { dispatcher.onBackPressed() })
        }
        strip("back_swipe", full, 0.5f, events)
    }

    @Test fun contextMenu() {
        start(playing = false)
        val title = feedCard(Fixtures.videos[4].title)
        // Down a long press before 0, so the menu opens at 0 as in the mock.
        val hold = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        strip("context_menu", full, 0.5f, listOf(down(-hold, title.center.x, title.bottom - 24f)))
    }

    @Test fun like() {
        val short = Fixtures.shorts[0]
        ShortsModel.previewInfo = mapOf(short.id to ShortInfo(short.id, short.title, ChannelRef("Harbor Workshop", "UCfixture0", "https://yt3.ggpht.com/fixture-avatar0=s88"), likeCount = "12K", commentsCount = "318"))
        start(playing = false, setup = { it.navigate(ShortsRoute(startId = short.id)) })
        settle(12)
        val heart = bounds(hasContentDescription("Like", substring = true))
        val button = Rect(heart.center.x - 24f, heart.top, heart.center.x + 24f, heart.top + 48f)
        try {
            strip("like", pad(button, 50f), 3f, tap(button))
        } finally {
            ShortsModel.previewInfo = emptyMap()
        }
    }

    @Test fun handoff() {
        start(setup = { it.sheet.progress.snapTo(0f); it.sheet.pipCorner = 0 })
        val card = feedCard(Fixtures.videos[5].title)
        strip("handoff", full, 0.5f, tap(card.center.x, card.top + card.width * 9f / 32f))
    }

    /** Nothing playing: the player grows out of the tapped card's thumbnail, not out of an empty dock. */
    @Test fun handoffCold() {
        start(playing = false)
        val card = feedCard(Fixtures.videos[5].title)
        strip("handoff_cold", full, 0.5f, tap(card.center.x, card.top + card.width * 9f / 32f))
    }

    /** The watch page's picture pushed up into fullscreen: 180 dp up in 200 ms and let go. */
    @Test fun fullscreenUp() {
        start(setup = { it.sheet.progress.snapTo(1f) })
        strip("fullscreen_up", full, 0.5f, drag(110f, 150f, 0f, -140f, 200), CAPS + listOf(800L))
    }

    /** In fullscreen, the picture pulled down 240 dp in 200 ms and let go: back to the watch page. */
    @Test fun fullscreenDown() {
        start(setup = { it.sheet.progress.snapTo(1f); it.sheet.fullscreen = true })
        strip("fullscreen_down", full, 0.5f, drag(206f, 300f, 0f, 240f, 200), CAPS + listOf(800L))
    }

    /** The fullscreen button's flight in and out, without a finger. */
    @Test fun fullscreenButton() {
        start(setup = { it.sheet.progress.snapTo(1f) })
        strip("fullscreen_button", full, 0.5f, listOf(at(0) { state.sheet.fullscreen = true }, at(700) { state.sheet.fullscreen = false }), listOf(0L, 50L, 100L, 200L, 300L, 600L, 750L, 800L, 900L, 1000L, 1300L))
    }

    /** The Comments card on the watch page opens its panel out of itself, and Close shrinks it back in. */
    @Test fun commentsPanel() {
        start(setup = { it.sheet.progress.snapTo(1f) })
        val card = bounds(hasText("Comments"))
        strip("comments_panel", full, 0.5f, tap(card.center.x, card.center.y) + listOf(at(700) {
            rule.onAllNodes(hasContentDescription("Close")).onLast().performClickSafely()
        }), listOf(0L, 50L, 100L, 150L, 200L, 300L, 600L, 750L, 800L, 900L, 1100L))
    }

    /** In the comments, a comment's replies grow out of its row, and Back shrinks them back in. */
    @Test fun repliesPanel() {
        start(setup = { it.sheet.progress.snapTo(1f) })
        top().performTouchInput { click(px(bounds(hasText("Comments")).center)) }
        settle(14)
        val chip = bounds(hasText("replies", substring = true))
        strip("replies_panel", full, 0.5f, tap(chip) + listOf(at(700) { state.watch.closeReplies() }), listOf(0L, 50L, 100L, 150L, 200L, 300L, 600L, 750L, 800L, 900L, 1100L))
    }

    /** The description card's panel, dragged down from its header to close it. */
    @Test fun descriptionPanelDrag() {
        start(setup = { it.sheet.progress.snapTo(1f) })
        top().performTouchInput { click(px(bounds(hasText("views", substring = true)).center)) }
        settle(14)
        val header = bounds(hasText("Description"))
        strip("description_drag", full, 0.5f, drag(206f, header.center.y, 0f, 260f, 200))
    }

    /** The window dragged until more than half of it is past the right edge: it shrinks and dims there. */
    @Test fun miniAway() {
        start(setup = { it.sheet.progress.snapTo(0f) })
        val r = bounds(hasContentDescription("Video window"))
        strip("mini_away", full, 0.5f, drag(r.center.x, r.center.y, 140f, 0f, 400), CAPS + listOf(800L))
    }

    /** A Short on Home's shelf: the Shorts page grows out of its poster. */
    @Test fun shortOpen() {
        start(playing = false)
        repeat(3) {
            rule.onRoot().performTouchInput { swipe(px(Offset(206f, 700f)), px(Offset(206f, 250f)), 1500) }
            settle(12)
        }
        // The card (its poster over its title), tapped on the poster.
        val card = bounds(hasText(Fixtures.shorts[0].title))
        strip("short_open", full, 0.5f, tap(card.center.x, card.top + 120f))
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickSafely() {
        performTouchInput { click() }
    }

    private companion object {
        val CAPS = listOf(0L, 50L, 100L, 150L, 200L, 300L, 400L, 600L)
    }
}
