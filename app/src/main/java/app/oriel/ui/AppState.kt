package app.oriel.ui

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.provider.MediaStore
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.lifecycle.Lifecycle
import app.oriel.AppGraph
import app.oriel.core.YouTube
import app.oriel.core.model.Channel
import app.oriel.core.model.Item
import app.oriel.core.model.Playlist
import app.oriel.core.model.Post
import app.oriel.core.model.Video
import app.oriel.playback.PlayerConnection
import app.oriel.playback.QueueContext
import app.oriel.ui.components.HeldItem
import app.oriel.ui.components.LongPressOrigin
import app.oriel.ui.navigation.ChannelRoute
import app.oriel.ui.navigation.HistoryRoute
import app.oriel.ui.navigation.HomeRoute
import app.oriel.ui.navigation.PlaylistRoute
import app.oriel.ui.navigation.SearchRoute
import app.oriel.ui.navigation.ShortsRoute
import app.oriel.ui.navigation.SubscriptionsRoute
import app.oriel.ui.navigation.Tab
import app.oriel.ui.navigation.YouRoute
import app.oriel.ui.navigation.isRoute
import app.oriel.ui.navigation.tabRoot
import app.oriel.ui.player.WatchModel
import app.oriel.ui.theme.PlayerSpring
import app.oriel.util.ReportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

val LocalAppState = staticCompositionLocalOf<AppState> { error("No AppState") }

/** The player sheet: 0 is the mini player, 1 the full watch page. Fullscreen is landscape video only. */
@Stable
class PlayerSheetState(private val scope: CoroutineScope) {
    // Bounded: a hard flick lands on the dock or the top instead of carrying past it.
    val progress = Animatable(0f).apply { updateBounds(0f, 1f) }
    var fullscreen by mutableStateOf(false)
    val expanded: Boolean get() = progress.targetValue > 0.5f
    /** Which corner the collapsed picture-in-picture window rests in: 0 top-start, 1 top-end, 2 bottom-start, 3 bottom-end. */
    var pipCorner by mutableIntStateOf(3)
    /** The window's size, pinched bigger or smaller, relative to its default. */
    var pipScale by mutableFloatStateOf(1f)

    fun expand() {
        scope.launch { progress.animateTo(1f, PlayerSpring) }
    }

    fun collapse() {
        fullscreen = false
        scope.launch { progress.animateTo(0f, PlayerSpring) }
    }

    suspend fun settle(target: Float, velocity: Float) = progress.animateTo(target, PlayerSpring, initialVelocity = velocity)
}

/** An iOS-style alert: title, message, and stacked actions. */
data class AlertSpec(val title: String, val message: String? = null, val actions: List<AlertAction>)
data class AlertAction(val label: String, val emphasized: Boolean = false, val destructive: Boolean = false, val onClick: () -> Unit = {})

/** The "Report a problem" sheet, opened with what's already known: the kind, the video, the person's words. */
data class ReportRequest(val kind: ReportKind = ReportKind.BROKEN, val videoLink: String? = null, val description: String = "")

/**
 * App-wide UI state and the verbs every screen uses: open a video, a
 * channel, a playlist; show an item's menu; share; subscribe. Screens read
 * it from [LocalAppState] instead of threading callbacks through every card.
 */
@Stable
class AppState(
    val graph: AppGraph,
    val nav: NavHostController,
    val connection: PlayerConnection,
    val scope: CoroutineScope,
    val activity: Activity,
) {
    val sheet = PlayerSheetState(scope)
    val watch = WatchModel(graph, connection, scope)

    var menuItem by mutableStateOf<Item?>(null)
        private set
    var menuHeld by mutableStateOf<HeldItem?>(null)
        private set
    /** Items without a captured hold (the ⋮ button) open as an actions sheet instead. */
    var sheetItem by mutableStateOf<Item?>(null)
    var saveTarget by mutableStateOf<Video?>(null)
    var alert by mutableStateOf<AlertSpec?>(null)
    var shareTarget by mutableStateOf<Item?>(null)
    var report by mutableStateOf<ReportRequest?>(null)

    /** Opens "Report a problem"; a [videoId] fills in the video's link. */
    fun reportProblem(kind: ReportKind = ReportKind.BROKEN, videoId: String? = null) {
        report = ReportRequest(kind, videoId?.let { "https://youtu.be/$it" })
    }

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice = _notice.asStateFlow()

    data class Notice(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null, val id: Long = System.nanoTime())

    fun notice(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        _notice.value = Notice(text, action, onAction)
    }

    fun clearNotice(n: Notice) {
        if (_notice.value?.id == n.id) _notice.value = null
    }

    // ---- opening things ----------------------------------------------------------------

    /** Plays [video] and opens the watch page. */
    fun openVideo(video: Video, context: QueueContext = QueueContext(), positionMs: Long? = null, expand: Boolean = true) {
        if (video.isShort) return openShort(video)
        watch.load(video, context.playlistId)
        if (expand) sheet.expand()
        if (video.title.isNotBlank()) return connection.playVideo(video, context, positionMs)
        // A bare id from a link: the stream warms up while the page fills in the title and
        // channel, so the mini player, notification and history never show a blank video.
        connection.pause()
        connection.warm(video)
        scope.launch {
            val loaded = withTimeoutOrNull(2_500) { watch.state.first { it.video?.id != video.id || !it.loading } }
            if (watch.state.value.video?.id != video.id) return@launch // something else was opened meanwhile
            connection.playVideo(loaded?.video?.takeIf { it.title.isNotBlank() } ?: video, context, positionMs)
        }
    }

    fun playPlaylist(videos: List<Video>, start: Int, context: QueueContext, shuffle: Boolean = false) {
        if (videos.isEmpty()) return
        val first = if (shuffle) null else videos.getOrNull(start)
        first?.let { watch.load(it, context.playlistId) }
        connection.play(videos, start, context, shuffle)
        sheet.expand()
    }

    fun openShort(video: Video, fromLink: Boolean = false) {
        connection.pause()
        navigate(ShortsRoute(startId = video.id), fromLink)
    }

    fun openItem(item: Item) {
        when (item) {
            is Video -> openVideo(item)
            is Channel -> openChannel(item.id)
            is Playlist -> if (item.isMix && item.firstVideoId != null) {
                openVideo(Video(id = item.firstVideoId!!, title = item.title, thumbnail = item.thumbnail), QueueContext(title = item.title, playlistId = item.id, isMix = true))
            } else openPlaylist(item.id)
            is Post -> item.video?.let { openVideo(it) }
        }
    }

    fun openChannel(id: String, params: String? = null, fromLink: Boolean = false) {
        if (sheet.expanded) sheet.collapse()
        navigate(ChannelRoute(id, params), fromLink)
    }

    fun openPlaylist(id: String, fromLink: Boolean = false) {
        if (sheet.expanded) sheet.collapse()
        navigate(PlaylistRoute(id), fromLink)
    }

    fun search(query: String, fromLink: Boolean = false) {
        if (sheet.expanded) sheet.collapse()
        navigate(SearchRoute(query), fromLink)
    }

    /** "Play … on Oriel": the top video result plays; if there isn't one, the search opens instead. */
    fun playFromSearch(query: String) {
        scope.launch {
            val top = runCatching { withContext(Dispatchers.IO) { graph.yt.search(query) } }.getOrNull()
                ?.feed?.items?.filterIsInstance<Video>()?.firstOrNull { !it.isShort && !it.isLive }
            if (top != null) openVideo(top) else search(query, fromLink = true)
        }
    }

    // ---- menus & actions ---------------------------------------------------------------

    /** Opens [item]'s menu: lifted off the page when it was long-pressed, as a sheet otherwise. */
    fun showMenu(item: Item) {
        val held = LongPressOrigin.consume()
        if (held != null) {
            menuHeld = held
            menuItem = item
        } else {
            sheetItem = item
        }
    }

    fun dismissMenu() {
        menuItem = null
        menuHeld = null
    }

    fun share(item: Item) {
        val url = YouTube.shareUrl(item)
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).putExtra(Intent.EXTRA_SUBJECT, item.title)
        activity.startActivity(Intent.createChooser(intent, "Share"))
    }

    fun toggleSubscribe(channel: Channel) {
        val now = graph.actions.toggleSubscribed(channel)
        notice(if (now) "Subscribed to ${channel.title}" else "Unsubscribed from ${channel.title}")
    }

    fun openExternally(url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    // ---- navigation --------------------------------------------------------------------

    /**
     * Pushes [route], unless it's the page already on top (the same channel, not just any
     * channel: channel B opened from channel A stacks on it). A tap on a page that's still
     * sliding in or out is dropped, so a double tap doesn't push twice; [fromLink] lets links
     * through, as they arrive in onNewIntent, before the activity resumes.
     */
    fun navigate(route: Any, fromLink: Boolean = false) {
        val current = nav.currentBackStackEntry
        if (!fromLink && current != null && !current.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (current?.isRoute(route) == true) return
        // A push from here on is a push, even back onto the page a tab switch landed on.
        tabSwitchEntry = null
        nav.navigate(route)
    }

    fun back() {
        // A second tap while the last pop is still settling would pop a page too many, or
        // leave the app from Home.
        val current = nav.currentBackStackEntry ?: return
        if (!current.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (nav.popBackStack()) return
        if (current.tabRoot() != null) activity.moveTaskToBack(true)
    }

    fun selectTab(tab: Tab) {
        if (sheet.expanded) sheet.collapse()
        val route: Any = when (tab) {
            Tab.HOME -> HomeRoute
            Tab.SHORTS -> ShortsRoute()
            Tab.SUBSCRIPTIONS -> SubscriptionsRoute
            Tab.YOU -> YouRoute
        }
        // Search sits over the tabs without being part of one: leaving it for any tab saves it on
        // its own, under Search's destination, which is what openSearch restores. (Saved under
        // Home's, as a tab switch saves, Home restored Search straight back: the tap seemed to do
        // nothing; and a later switch saved over it.)
        val search = nav.currentBackStackEntry?.takeIf { it.destination.hasRoute(SearchRoute::class) }
        search?.let { nav.popBackStack(it.destination.id, inclusive = true, saveState = true) }
        if (search == null && nav.currentBackStackEntry?.tabRoot() == tab) {
            _reselect.value = tab to System.nanoTime()
            return
        }
        // Somewhere down this tab's own stack (a channel, a short opened from the feed): back to
        // its root. Navigating to the tab instead would restore the very stack it's in, and the
        // tap would seem to do nothing.
        val owner = nav.currentBackStack.value.asReversed().firstNotNullOfOrNull { it.tabRoot() }
        if (owner == tab && search != null) {
            // Out of Search to the tab it sat over: the tab comes back as it was left.
            nav.navigate(route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            tabSwitchEntry = nav.currentBackStackEntry?.id
            return
        }
        if (owner == tab) {
            while (nav.currentBackStackEntry?.tabRoot() != tab && nav.popBackStack()) Unit
            return
        }
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        tabSwitchEntry = nav.currentBackStackEntry?.id
    }

    /**
     * The entry a tab switch landed on, which may be a page restored deep in that tab's stack:
     * it crossfades in, as iOS switches tabs, rather than sliding in like a push.
     */
    var tabSwitchEntry: String? = null
        private set

    private val _searchAgain = MutableStateFlow(0L)
    /** When search was asked for while already open, for its field to take focus again. */
    val searchAgain = _searchAgain.asStateFlow()

    fun openSearch() {
        if (sheet.expanded) sheet.collapse()
        if (nav.currentBackStackEntry?.destination?.hasRoute(SearchRoute::class) == true) {
            _searchAgain.value = System.nanoTime()
            return
        }
        nav.navigate(SearchRoute()) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        tabSwitchEntry = nav.currentBackStackEntry?.id
    }

    /** Tapping the tab you're on scrolls it to the top, as iOS does. */
    private val _reselect = MutableStateFlow<Pair<Tab, Long>?>(null)
    val reselect = _reselect.asStateFlow()

    // ---- links -------------------------------------------------------------------------

    /** Opens a youtube.com / youtu.be link or shared text. Returns whether it was understood. */
    fun handleIntent(intent: Intent): Boolean {
        if (intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            val query = intent.getStringExtra(SearchManager.QUERY)?.trim().orEmpty()
            if (query.isEmpty()) return false
            playFromSearch(query)
            return true
        }
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return false
        // Shared text is often a sentence around the link ("Watch this: https://youtu.be/…!"), and
        // sometimes several links: the first YouTube one, without the punctuation after it.
        val urls = URL_IN_TEXT.findAll(text).map { it.value.trimEnd { c -> c in TRAILING } }.toList()
        if (urls.isEmpty()) {
            search(text.trim(), fromLink = true)
            return true
        }
        val url = urls.firstOrNull(::isYouTubeUrl) ?: return false
        return openLink(url)
    }

    /**
     * Opens a YouTube link in the app. Returns false when it isn't one it can open, for the
     * caller to say so or hand it on; one that has to be asked of YouTube first returns true
     * and shows a notice if it leads nowhere.
     */
    fun openLink(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host?.removePrefix("www.")?.removePrefix("m.") ?: return false
        val start = (uri.getQueryParameter("t") ?: uri.getQueryParameter("start"))?.let(::parseTime)
        val segments = uri.pathSegments
        val list = uri.getQueryParameter("list")
        fun video(id: String?, live: Boolean = false): Boolean {
            if (id == null || !VIDEO_ID.matches(id)) return false
            openVideo(Video(id, "", isLive = live), QueueContext(playlistId = list), start)
            return true
        }
        fun playlist(id: String?): Boolean = id?.takeIf { it.isNotBlank() }?.let { openPlaylist(it, fromLink = true); true } ?: false
        if (host == "youtu.be") return video(segments.firstOrNull())
        if (!host.endsWith("youtube.com")) return false
        val first = segments.firstOrNull() ?: return false
        return when {
            first == "watch" -> video(uri.getQueryParameter("v")) || playlist(list)
            first == "shorts" && segments.size > 1 -> {
                val id = segments[1].takeIf { VIDEO_ID.matches(it) } ?: return false
                openShort(Video(id, "", isShort = true), fromLink = true)
                true
            }
            first == "live" && segments.size > 1 -> video(segments[1], live = true)
            first == "playlist" -> playlist(list)
            first == "channel" && segments.size > 1 -> openBrowse(segments[1], null)
            first == "hashtag" && segments.size > 1 -> { search("#" + segments[1], fromLink = true); true }
            first == "results" -> uri.getQueryParameter("search_query")?.takeIf { it.isNotBlank() }?.let { search(it, fromLink = true); true } ?: false
            first == "feed" && segments.size > 1 -> openBrowse("FE" + segments[1], null)
            else -> {
                scope.launch {
                    // @handles, /c/ and /user/ names: ask YouTube where they lead.
                    val resolved = withContext(Dispatchers.IO) { runCatching { graph.yt.resolveUrl(url) }.getOrNull() }
                    val opened = when {
                        resolved == null -> false
                        resolved.browseId != null && openBrowse(resolved.browseId!!, resolved.params) -> true
                        resolved.playlistId != null && resolved.videoId == null -> playlist(resolved.playlistId)
                        else -> video(resolved.videoId)
                    }
                    if (!opened) notice("Couldn't open this link")
                }
                true
            }
        }
    }

    /** A browse page from a link: a channel, a playlist, or one of the app's own places. */
    private fun openBrowse(id: String, params: String?): Boolean {
        when {
            id.startsWith("UC") -> openChannel(id, params, fromLink = true)
            id.startsWith("VL") && id.length > 2 -> openPlaylist(id.removePrefix("VL"), fromLink = true)
            id == "FEsubscriptions" -> selectTab(Tab.SUBSCRIPTIONS)
            id == "FEwhat_to_watch" -> selectTab(Tab.HOME)
            id == "FEhistory" -> navigate(HistoryRoute, fromLink = true)
            id == "FElibrary" || id == "FEyou" -> selectTab(Tab.YOU)
            else -> return false
        }
        return true
    }

    private fun parseTime(t: String): Long? {
        t.toLongOrNull()?.let { return it * 1000 }
        val m = Regex("""(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?""").matchEntire(t) ?: return null
        val (h, mi, s) = m.destructured
        return ((h.toLongOrNull() ?: 0) * 3600 + (mi.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)) * 1000
    }

    private companion object {
        val URL_IN_TEXT = Regex("""https?://\S+""")
        /** What ends a sentence or closes a bracket around a link, never part of a YouTube URL's end. */
        const val TRAILING = ".,)]}>!?\"'»”"
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

        fun isYouTubeUrl(url: String): Boolean {
            val host = url.toUri().host?.removePrefix("www.")?.removePrefix("m.") ?: return false
            return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        }
    }
}
