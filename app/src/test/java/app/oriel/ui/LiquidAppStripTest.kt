package app.oriel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.TestApp
import app.oriel.core.model.Channel
import app.oriel.core.model.ChannelPage
import app.oriel.core.model.ChannelTab
import app.oriel.core.model.Feed
import app.oriel.core.model.Shelf
import app.oriel.core.model.ShelfKind
import app.oriel.data.ThemeMode
import app.oriel.playback.PlayerConnection
import app.oriel.ui.components.PillSize
import app.oriel.ui.components.SubscribePill
import app.oriel.ui.components.TabStrip
import app.oriel.ui.navigation.ChannelRoute
import app.oriel.ui.navigation.SettingsRoute
import app.oriel.ui.screens.ChannelModel
import app.oriel.ui.screens.LiquidMotionPrompt
import app.oriel.ui.theme.LocalLiquidMotion
import app.oriel.ui.theme.OrielTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.flow.first
import org.junit.After
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
 * Frame strips of the liquid pieces outside the player: the channel page, search's droplet, the
 * alert bloom, the large-title stretch and the first-run question's preview. Driven and caught
 * as [LiquidMotionStripTest]'s are, written to /tmp/liquid-compare2/app_<name>.png. Only with
 * -Pframes=true: it's a tool for looking, not a check.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class LiquidAppStripTest {
    @get:Rule
    val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val graph get() = app.graph
    private var appState: AppState? = null
    private val state get() = appState!!
    private val density = 2f // xhdpi

    @Before fun gate() {
        assumeTrue("frame strips (-Pframes=true)", System.getProperty("oriel.frames") == "true")
        System.setProperty("roborazzi.test.record", "true")
    }

    @After fun clear() { ChannelModel.previewPage = null }

    private fun step(ms: Long) {
        if (ms <= 0) return
        Thread.sleep(4)
        ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)
        rule.mainClock.advanceTimeBy(ms, ignoreFrameDuration = true)
    }

    private fun settle(rounds: Int = 16) = repeat(rounds) { Thread.sleep(20); step(120) }

    private fun start(theme: ThemeMode = ThemeMode.DARK, setup: suspend (AppState) -> Unit = {}) {
        rule.mainClock.autoAdvance = false
        graph.pages.write("home-false", Feed.serializer(), Fixtures.homeFeed)
        rule.setContent { AppFrame(theme, setup) }
        settle(28)
    }

    @Composable
    private fun AppFrame(theme: ThemeMode, setup: suspend (AppState) -> Unit) {
        OrielTheme(theme, reduceAnimation = false, liquidGlass = true, liquidMotion = true) { DeviceSlop {
            val nav = rememberNavController()
            val scope = rememberCoroutineScope()
            val activity = androidx.activity.compose.LocalActivity.current!!
            val connection = remember { PlayerConnection(activity, graph) }
            val s = remember { AppState(graph, nav, connection, scope, activity) }
            appState = s
            LaunchedEffect(Unit) {
                nav.currentBackStackEntryFlow.first().lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
                setup(s)
            }
            OrielRoot(s, inPip = false)
        } }
    }

    /** A phone's 8 dp touch slop (Robolectric's is 16), and liquid motion on explicitly. */
    @Composable
    private fun DeviceSlop(content: @Composable () -> Unit) {
        val base = LocalViewConfiguration.current
        val slop = with(LocalDensity.current) { 8.dp.toPx() }
        val config = remember(base, slop) { object : ViewConfiguration by base { override val touchSlop = slop } }
        CompositionLocalProvider(LocalViewConfiguration provides config, LocalLiquidMotion provides true, content = content)
    }

    // --- events, in dp ---

    private class Ev(val t: Long, val run: () -> Unit)

    private fun px(p: Offset) = Offset(p.x * density, p.y * density)
    private fun top() = rule.onAllNodes(isRoot()).onLast()
    private fun down(t: Long, x: Float, y: Float) = Ev(t) { top().performTouchInput { down(px(Offset(x, y))) } }
    private fun move(t: Long, x: Float, y: Float) = Ev(t) { top().performTouchInput { moveTo(px(Offset(x, y)), delayMillis = 0) } }
    private fun up(t: Long) = Ev(t) { top().performTouchInput { up() } }
    private fun at(t: Long, block: () -> Unit) = Ev(t) { rule.runOnUiThread(block) }

    private fun tap(x: Float, y: Float) = listOf(down(-60, x, y), up(0))
    private fun tap(r: Rect) = tap(r.center.x, r.center.y)

    private fun drag(x: Float, y: Float, dx: Float, dy: Float, dur: Long, upAt: Long = dur): List<Ev> = buildList {
        add(down(0, x, y))
        var t = 16L
        while (t < dur) { add(move(t, x + dx * t / dur, y + dy * t / dur)); t += 16 }
        add(move(dur, x + dx, y + dy))
        add(up(upAt))
    }

    private fun pad(r: Rect, p: Float) = Rect(r.left - p, r.top - p, r.right + p, r.bottom + p)
    private val full = Rect(0f, 0f, 412f, 915f)

    private fun bounds(matcher: SemanticsMatcher): Rect {
        val b = rule.onAllNodes(matcher, useUnmergedTree = false).onFirst().fetchSemanticsNode().boundsInRoot
        return Rect(b.left / density, b.top / density, b.right / density, b.bottom / density)
    }

    private fun dp(r: Rect) = Rect(r.left / density, r.top / density, r.right / density, r.bottom / density)

    // --- the strip ---

    private fun strip(name: String, crop: Rect, scale: Float, events: List<Ev>, caps: List<Long> = CAPS) {
        val shots = ArrayList<Pair<Long, BufferedImage>>()
        val start = minOf(0L, events.minOfOrNull { it.t } ?: 0L)
        val end = caps.max()
        val times = (generateSequence(start) { it + 16 }.takeWhile { it < end } + events.map { it.t } + caps).toSortedSet().toList()
        for ((i, t) in times.withIndex()) {
            events.filter { it.t == t }.forEach { it.run() }
            if (t in caps) shots += t to grab(crop, scale)
            times.getOrNull(i + 1)?.let { step(it - t) }
        }
        write(name, crop, scale, shots)
    }

    private fun grab(crop: Rect, scale: Float): BufferedImage {
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
        val dir = File("/tmp/liquid-compare2").apply { mkdirs() }
        ImageIO.write(out, "png", File(dir, "app_$name.png"))
    }

    // --- channel ---

    private var tabsBounds = Rect.Zero
    private var pillBounds = Rect.Zero

    private fun startControls() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OrielTheme(ThemeMode.DARK, reduceAnimation = false, liquidGlass = true, liquidMotion = true) { DeviceSlop {
                var tab by remember { mutableStateOf(0) }
                var subscribed by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 120.dp)) {
                    Box(Modifier.fillMaxWidth().onGloballyPositioned { tabsBounds = it.boundsInRoot() }) {
                        TabStrip(CHANNEL_TABS.map { it.title }, tab, { tab = it })
                    }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 40.dp).onGloballyPositioned { pillBounds = it.boundsInRoot() }) {
                        SubscribePill(subscribed, { subscribed = !subscribed }, Modifier.fillMaxWidth(), size = PillSize.Medium)
                    }
                }
            } }
        }
        settle(8)
    }

    @Test fun channelTabs() {
        startControls()
        val r = dp(tabsBounds)
        strip("channel_tabs", pad(r, 8f), 2f, tap(bounds(hasText("Playlists"))))
    }

    @Test fun subscribe() {
        startControls()
        val r = dp(pillBounds)
        strip("subscribe", pad(r, 8f), 2f, tap(r))
    }

    @Test fun unsubscribe() {
        startControls()
        val r = dp(pillBounds)
        rule.onAllNodes(isRoot()).onLast().performTouchInput { click(px(r.center)) }
        settle(10)
        strip("unsubscribe", pad(r, 8f), 2f, tap(r))
    }

    private fun startChannel() {
        val c = Fixtures.subscriptions[0]
        ChannelModel.previewPage = ChannelPage(
            Channel(c.id, c.title, c.avatar, handle = "@harborworkshop", subscribersText = c.subscribersText, videosText = "212 videos", verified = true),
            banner = "https://yt3.ggpht.com/fixture-banner0=w1060",
            tabs = CHANNEL_TABS, feed = Feed(shelves = listOf(Shelf(items = Fixtures.videos, kind = ShelfKind.FEED))), selectedTab = "Home",
        )
        start(setup = { it.navigate(ChannelRoute(c.id)) })
        settle(10)
    }

    /** The avatar and name flowing up into the bar as the page scrolls. */
    @Test fun channelScroll() {
        startChannel()
        strip("channel_scroll", Rect(0f, 0f, 412f, 460f), 1f, drag(206f, 600f, 0f, -300f, 600), caps = listOf(0L, 150, 300, 450, 600, 900))
    }

    /** The banner's rubber stretch on a pull past the top. */
    @Test fun channelPull() {
        startChannel()
        strip("channel_pull", Rect(0f, 0f, 412f, 460f), 1f, drag(206f, 400f, 0f, 260f, 400), caps = listOf(0L, 200, 400, 550, 800, 1100))
    }

    // --- search, alerts, titles, the prompt ---

    private val tabBand = Rect(0f, 780f, 412f, 915f)

    /** The tab bar draws in to a circle and the Search droplet stretches into the field, at the bottom. */
    @Test fun searchMorph() {
        start()
        strip("search_morph", tabBand, 1.5f, tap(bounds(hasContentDescription("Search"))), caps = listOf(0L, 50, 100, 160, 240, 340, 480, 700))
    }

    /** And back: the circle (the current tab) tapped, the field flows back into the droplet. */
    @Test fun searchClose() {
        start()
        val search = bounds(hasContentDescription("Search"))
        rule.onAllNodes(hasContentDescription("Search")).onFirst().performClick()
        settle(12)
        strip("search_close", tabBand, 1.5f, tap(40f, search.center.y), caps = listOf(0L, 50, 100, 160, 240, 340, 480, 700))
    }

    @Test fun alertBloom() {
        start()
        strip(
            "alert_bloom", Rect(40f, 300f, 372f, 620f), 1f,
            listOf(at(0) { state.alert = AlertSpec("Remove from history?", "It'll go from every device you're signed in on.", listOf(AlertAction("Cancel"), AlertAction("Remove", destructive = true))) }),
            caps = listOf(0L, 32, 64, 100, 150, 220, 320, 500),
        )
    }

    @Test fun alertClose() {
        start(setup = { it.alert = AlertSpec("Remove from history?", "It'll go from every device you're signed in on.", listOf(AlertAction("Cancel"), AlertAction("Remove", destructive = true))) })
        settle(8)
        strip("alert_close", Rect(40f, 300f, 372f, 620f), 1f, tap(bounds(hasText("Cancel"))), caps = listOf(0L, 32, 64, 100, 150, 220, 320))
    }

    @Test fun titleStretch() {
        start(setup = { it.navigate(SettingsRoute) })
        settle(8)
        strip("title_stretch", Rect(0f, 0f, 412f, 300f), 1f, drag(206f, 300f, 0f, 260f, 400), caps = listOf(0L, 200, 400, 550, 800))
    }

    @Test fun promptPreview() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OrielTheme(ThemeMode.DARK, reduceAnimation = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { LiquidMotionPrompt {} }
            }
        }
        // The first trip starts 1.1 s in; catch the bloom, then the drop's crossing.
        strip("prompt_preview", Rect(40f, 270f, 372f, 650f), 1.5f, emptyList(), caps = listOf(0L, 60, 140, 400, 1100, 1160, 1220, 1300, 1400, 1600))
    }

    private companion object {
        val CAPS = listOf(0L, 50, 100, 150, 200, 300, 450, 700)
        val CHANNEL_TABS = listOf(ChannelTab("Home", "p0"), ChannelTab("Videos", "p1"), ChannelTab("Shorts", "p2"), ChannelTab("Playlists", "p3"))
    }
}
