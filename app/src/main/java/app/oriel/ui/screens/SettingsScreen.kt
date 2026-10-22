package app.oriel.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import app.oriel.ui.components.SearchField
import app.oriel.ui.components.SheetTitle
import app.oriel.ui.components.LocalSheetClose
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.oriel.BuildConfig
import app.oriel.core.extras.SponsorBlock
import app.oriel.data.AudioQuality
import app.oriel.data.Transport
import app.oriel.data.SettingsState
import app.oriel.data.ThemeMode
import app.oriel.data.VideoQuality
import app.oriel.ui.AlertAction
import app.oriel.ui.AlertSpec
import app.oriel.ui.LocalAppState
import app.oriel.ui.components.Avatar
import app.oriel.ui.components.ChoiceRow
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.OrielSheet
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.RowDivider
import app.oriel.ui.components.SegmentedControl
import app.oriel.ui.components.SettingsGroup
import app.oriel.ui.components.SettingsRow
import app.oriel.ui.components.SwitchRow
import app.oriel.ui.navigation.LoginRoute
import app.oriel.ui.player.speedLabel
import app.oriel.util.CrashLog
import app.oriel.util.Format
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** A choice that opens as a sheet of ticked rows, as iOS pushes a picker. */
private class Picker<T>(val title: String, val options: List<T>, val selected: T, val label: (T) -> String, val detail: (T) -> String? = { null }, val footer: String? = null, val onSelect: (T) -> Unit)

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** Settings as an iOS grouped list. */
@Composable
fun SettingsScreen() {
    val app = LocalAppState.current
    val graph = app.graph
    val s by graph.settings.state.collectAsState()
    val signedIn by graph.account.signedIn.collectAsState()
    val account by graph.account.account.collectAsState()
    val syncing by graph.account.syncing.collectAsState()
    val expired by graph.account.sessionExpired.collectAsState()
    var picker by remember { mutableStateOf<Picker<*>?>(null) }
    var licences by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Counted off the main thread: it reads the crash files.
    var crashes by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // The report sheet can clear what's kept: counted again when it closes.
    androidx.compose.runtime.LaunchedEffect(app.report) { if (app.report == null) crashes = withContext(Dispatchers.IO) { CrashLog.crashCount(context) } }
    androidx.compose.runtime.LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { runCatching { graph.caches.stream.cacheSpace }.getOrNull() } }
    fun set(t: (SettingsState) -> SettingsState) = graph.settings.update(t)

    PageScaffold(title = "Settings", onBack = app::back) {
        if (signedIn && expired) item(key = "expired") { SessionExpiredCard(onSignIn = { app.navigate(LoginRoute) }) }
        item(key = "account") {
            SettingsGroup(header = "Account", footer = if (signedIn) "Your Google sign-in stays on this device and is only ever sent to YouTube." else "Oriel works fully without an account. Signing in brings your recommendations, subscriptions, likes and playlists, and lets likes and saves reach YouTube.") {
                if (signedIn) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(account?.avatar, 44.dp, name = account?.name)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(account?.name ?: "Signed in", style = MaterialTheme.typography.titleMedium)
                            (account?.email ?: account?.handle)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    RowDivider()
                    SettingsRow("Sync library now", OrielIcons.Repeat, value = if (syncing) "Syncing…" else null, enabled = !syncing, onClick = { syncLibrary(app) })
                    RowDivider()
                    SettingsRow("Sign out", OrielIcons.SignOut, destructive = true, onClick = {
                        app.alert = AlertSpec("Sign out?", "Your library on this device stays. Recommendations go back to Oriel's own.", listOf(
                            AlertAction("Cancel"),
                            AlertAction("Sign out", destructive = true) { graph.account.signOut() },
                        ))
                    })
                } else {
                    SettingsRow("Sign in with Google", OrielIcons.SignIn, chevron = true, onClick = { app.navigate(LoginRoute) })
                }
            }
        }
        item(key = "quality") {
            SettingsGroup(header = "Video quality", footer = "Auto moves up and down with your connection, as YouTube's does: up to your screen's resolution (as far as this device decodes it in hardware), on Wi-Fi and mobile data alike. The data saver holds it to 480p. Every quality a video has, up to 4K, is on the player.") {
                SettingsRow("On Wi-Fi", OrielIcons.Quality, value = s.videoWifi.label, chevron = true, onClick = {
                    picker = Picker("On Wi-Fi", VideoQuality.entries, s.videoWifi, { it.label }) { v -> set { it.copy(videoWifi = v) } }
                })
                RowDivider()
                SettingsRow("On mobile data", OrielIcons.Quality, value = s.videoCellular.label, chevron = true, onClick = {
                    picker = Picker("On mobile data", VideoQuality.entries, s.videoCellular, { it.label }) { v -> set { it.copy(videoCellular = v) } }
                })
                RowDivider()
                SettingsRow("Sound quality", OrielIcons.AudioOnly, value = s.audioQuality.label, chevron = true, onClick = {
                    picker = Picker("Sound quality", AudioQuality.entries, s.audioQuality, { it.label }, { it.detail }, "YouTube serves 256 kbps audio only to Premium accounts. Sign in and turn on Premium formats under Streaming to ask for it.") { v -> set { it.copy(audioQuality = v) } }
                })
                RowDivider()
                SwitchRow("Data saver", s.dataSaver, { v -> set { it.copy(dataSaver = v) } }, OrielIcons.Download, subtitle = "Lower quality and no prefetching everywhere")
            }
        }
        item(key = "streaming") {
            SettingsGroup(header = "Streaming") {
                SettingsRow("Protocol", OrielIcons.Stream, value = s.transport.label, chevron = true, onClick = {
                    picker = Picker("Protocol", Transport.entries, s.transport, { it.label }, { it.detail }, "SABR is how YouTube's own players stream. Oriel reads it as an ordinary file, so seeking and the cache work the same either way.") { v -> set { it.copy(transport = v) } }
                })
                if (signedIn) {
                    RowDivider()
                    SwitchRow("Premium formats", s.premium, { v -> set { it.copy(premium = v) } }, OrielIcons.Verified, subtitle = "256 kbps audio where YouTube offers it to a Premium account")
                }
            }
        }
        item(key = "playback") {
            SettingsGroup(header = "Playback") {
                SwitchRow("Background play", s.backgroundPlay, { v -> set { it.copy(backgroundPlay = v) } }, OrielIcons.AudioOnly, subtitle = "Keep the sound going with the screen off or in another app")
                RowDivider()
                SwitchRow("Picture-in-picture", s.autoPip, { v -> set { it.copy(autoPip = v) } }, OrielIcons.Pip, subtitle = "Shrink the video into a window when you leave the app")
                RowDivider()
                SwitchRow("Autoplay", s.autoplay, { v -> set { it.copy(autoplay = v) } }, OrielIcons.Play, subtitle = "Play a related video when the queue ends")
                RowDivider()
                SwitchRow("Resume where you left off", s.resumePlayback, { v -> set { it.copy(resumePlayback = v) } }, OrielIcons.History)
                RowDivider()
                SettingsRow("Default speed", OrielIcons.Speed, value = speedLabel(s.defaultSpeed), chevron = true, onClick = {
                    picker = Picker("Default speed", SPEEDS, s.defaultSpeed, { speedLabel(it) }) { v -> set { it.copy(defaultSpeed = v) } }
                })
                RowDivider()
                SettingsRow("Double-tap to seek", OrielIcons.Next, value = "${s.doubleTapSeekSec} s", chevron = true, onClick = {
                    picker = Picker("Double-tap to seek", listOf(5, 10, 15, 20, 30), s.doubleTapSeekSec, { "$it seconds" }) { v -> set { it.copy(doubleTapSeekSec = v) } }
                })
                RowDivider()
                SwitchRow("Captions", s.captions, { v -> set { it.copy(captions = v) } }, OrielIcons.Captions, subtitle = "Turn captions on when a video has them")
                RowDivider()
                SwitchRow("Prefetch what's next", s.prefetchNext, { v -> set { it.copy(prefetchNext = v) } }, OrielIcons.Queue, subtitle = "Start the next video instantly")
            }
        }
        item(key = "community") {
            SettingsGroup(
                header = "Community",
                footer = "SponsorBlock (sponsor.ajay.app) and Return YouTube Dislike (returnyoutubedislikeapi.com) are community services. SponsorBlock is asked by the first 4 characters of a hash of the video id; Return YouTube Dislike receives the video id. Dislike counts are estimates.",
            ) {
                SwitchRow("SponsorBlock", s.sponsorBlock, { v -> set { it.copy(sponsorBlock = v) } }, OrielIcons.Next, subtitle = "Skip sponsor reads, intros and reminders")
                if (s.sponsorBlock) {
                    RowDivider()
                    SettingsRow("Skip", OrielIcons.Tune, value = "${s.sponsorCategories.size} kinds", chevron = true, onClick = { picker = CATEGORY_PICKER })
                }
                RowDivider()
                SwitchRow("Return YouTube Dislike", s.returnDislike, { v -> set { it.copy(returnDislike = v) } }, OrielIcons.Dislike)
            }
        }
        item(key = "appearance") {
            SettingsGroup(header = "Appearance") {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    SegmentedControl(ThemeMode.entries, s.theme, { it.label }, { v -> set { it.copy(theme = v) } }, Modifier.weight(1f))
                }
                RowDivider(inset = false)
                SwitchRow("Liquid glass", s.liquidGlass, { v -> set { it.copy(liquidGlass = v) } }, OrielIcons.Fill, subtitle = if (android.os.Build.VERSION.SDK_INT < 31) "Needs Android 12; frosted bars are used instead" else "Refracting glass bars and buttons")
                RowDivider()
                SwitchRow("Reduce motion", s.reduceAnimation, { v -> set { it.copy(reduceAnimation = v) } }, OrielIcons.Speed)
                RowDivider()
                SwitchRow("Reduce transparency", s.reduceBlur, { v -> set { it.copy(reduceBlur = v) } }, OrielIcons.Fill)
                RowDivider()
                SwitchRow("High refresh rate", s.highRefreshRate, { v -> set { it.copy(highRefreshRate = v) } }, OrielIcons.Speed)
            }
        }
        item(key = "privacy") {
            SettingsGroup(header = "Privacy & storage", footer = cacheBytes?.let { "Video cache: ${Format.bytes(it)} of ${s.streamCacheMb} MB. Played videos are kept so replays and seeking back cost nothing." }) {
                SwitchRow("Pause watch history", s.pauseHistory, { v -> set { it.copy(pauseHistory = v) } }, OrielIcons.History)
                RowDivider()
                SwitchRow("Pause search history", s.pauseSearchHistory, { v -> set { it.copy(pauseSearchHistory = v) } }, OrielIcons.Search)
                RowDivider()
                SwitchRow("Remember the queue", s.persistQueue, { v -> set { it.copy(persistQueue = v) } }, OrielIcons.Queue)
                RowDivider()
                if (graph.telemetry.available) {
                    SwitchRow(
                        "Share anonymous usage", s.telemetry == true, { v -> set { it.copy(telemetry = v) } }, OrielIcons.Insights,
                        subtitle = "Counts like app opens, features used and crashes, with app and Android version. Never what you watch or search.",
                    )
                    RowDivider()
                }
                SettingsRow("Video cache size", OrielIcons.Library, value = "${s.streamCacheMb} MB", chevron = true, onClick = {
                    picker = Picker("Video cache size", listOf(256, 512, 1024, 2048, 4096), s.streamCacheMb, { if (it >= 1024) "${it / 1024} GB" else "$it MB" }, footer = "Takes effect the next time Oriel starts.") { v -> set { it.copy(streamCacheMb = v) } }
                })
                RowDivider()
                SettingsRow("Clear search history", OrielIcons.Delete, onClick = {
                    app.alert = AlertSpec("Clear search history?", "This clears the searches Oriel remembers on this device.", listOf(
                        AlertAction("Cancel"),
                        AlertAction("Clear", destructive = true) { graph.library.clearSearches(); app.notice("Search history cleared") },
                    ))
                })
                RowDivider()
                SettingsRow("Clear caches", OrielIcons.Delete, destructive = true, onClick = {
                    app.alert = AlertSpec("Clear caches?", "Played videos, pages and images are fetched again as they're needed. Your library and history stay.", listOf(
                        AlertAction("Cancel"),
                        AlertAction("Clear", destructive = true) {
                            app.scope.launch {
                                withContext(Dispatchers.IO) {
                                    graph.caches.clearStreamCache()
                                    graph.pages.clear()
                                    SingletonImageLoader.get(context).let { it.memoryCache?.clear(); it.diskCache?.clear() }
                                }
                                graph.yt.clearCache()
                                cacheBytes = 0
                                app.notice("Caches cleared")
                            }
                        },
                    ))
                })
            }
        }
        item(key = "region") {
            SettingsGroup(header = "Region & language", footer = "What YouTube treats as your country and language, for trending, topics and search.") {
                SettingsRow("Content country", OrielIcons.Language, value = s.country?.let { Locale.Builder().setRegion(it).build().displayCountry } ?: "Automatic", chevron = true, onClick = {
                    val countries = listOf<String?>(null) + Locale.getISOCountries().sortedBy { Locale.Builder().setRegion(it).build().displayCountry }
                    picker = Picker("Content country", countries, s.country, { it?.let { c -> Locale.Builder().setRegion(c).build().displayCountry } ?: "Automatic" }) { v -> set { it.copy(country = v) }; graph.applyLocale() }
                })
                RowDivider()
                SettingsRow("Language", OrielIcons.Language, value = s.language?.let { Locale.forLanguageTag(it).displayName } ?: "Automatic", chevron = true, onClick = {
                    val langs = listOf<String?>(null, "en", "es", "pt", "fr", "de", "it", "nl", "pl", "tr", "ru", "uk", "ar", "hi", "bn", "id", "vi", "th", "ja", "ko", "zh-Hans", "zh-Hant")
                    picker = Picker("Language", langs, s.language, { it?.let { l -> Locale.forLanguageTag(l).displayName } ?: "Automatic" }) { v -> set { it.copy(language = v) }; graph.applyLocale() }
                })
            }
        }
        item(key = "about") {
            SettingsGroup(
                header = "About",
                footer = "$COPYRIGHT. Free software under the GNU GPL v3 with attribution terms (see NOTICE in the source). Its interface is adapted from BitChord (GPL-3.0). Oriel isn't affiliated with or endorsed by YouTube or Google; using it may not comply with YouTube's Terms of Service.",
            ) {
                SettingsRow("Version", OrielIcons.Settings, value = BuildConfig.VERSION_NAME)
                RowDivider()
                // Credit required by NOTICE (GPL-3.0 section 7(b)); forks must keep it.
                SettingsRow("Made by khush2003", OrielIcons.Person, subtitle = SOURCE_URL.removePrefix("https://"), chevron = true, onClick = { app.openExternally(SOURCE_URL) })
                RowDivider()
                // Crashes and errors are kept on the phone until a report is sent; see CrashLog.
                SettingsRow(
                    "Report a problem", OrielIcons.Report,
                    subtitle = when (crashes) { 0 -> "No crashes recorded"; 1 -> "1 crash recorded"; else -> "$crashes crashes recorded" },
                    chevron = true,
                    onClick = { app.reportProblem() },
                )
                RowDivider()
                SettingsRow("Licences", OrielIcons.Library, chevron = true, onClick = { licences = true })
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    (picker as Picker<Any?>?)?.let { p ->
        // Long lists (countries, languages) are lazy and searchable: built whole, they stuttered on open.
        if (p.options.size > 30) return@let LongPickerSheet(p) { picker = null }
        OrielSheet({ picker = null }) {
            val close = LocalSheetClose.current
            SheetTitle(p.title)
            if (p === CATEGORY_PICKER) {
                SettingsGroup {
                    SponsorBlock.DEFAULT_CATEGORIES.plus(listOf("preview", "filler")).forEachIndexed { i, c ->
                        if (i > 0) RowDivider(inset = false)
                        ChoiceRow(SponsorBlock.label(c), c in s.sponsorCategories, multi = true) {
                            set { st -> st.copy(sponsorCategories = if (c in st.sponsorCategories) st.sponsorCategories - c else st.sponsorCategories + c) }
                        }
                    }
                }
            } else {
                SettingsGroup(footer = p.footer) {
                    p.options.forEachIndexed { i, o ->
                        if (i > 0) RowDivider(inset = false)
                        ChoiceRow(p.label(o), o == p.selected, detail = p.detail(o)) { p.onSelect(o); close {} }
                    }
                }
            }
        }
    }
    if (licences) OrielSheet({ licences = false }) {
        SheetTitle("Licences")
        SettingsGroup {
            LICENCES.forEachIndexed { i, (name, licence) ->
                if (i > 0) RowDivider(inset = false)
                SettingsRow(name, null, subtitle = licence)
            }
        }
    }
}

@Composable
private fun LongPickerSheet(p: Picker<Any?>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query, p) { if (query.isBlank()) p.options else p.options.filter { p.label(it).contains(query.trim(), ignoreCase = true) } }
    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = p.options.indexOf(p.selected).coerceAtLeast(0))
    OrielSheet(onDismiss, scrollable = false, maxHeight = 640.dp) {
        val close = LocalSheetClose.current
        SheetTitle(p.title)
        SearchField(query, { query = it }, { }, Modifier.padding(horizontal = PAGE_GUTTER, vertical = 6.dp), placeholder = "Search")
        androidx.compose.foundation.lazy.LazyColumn(state = list, modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = PAGE_GUTTER, vertical = 8.dp)) {
            items(shown.size) { i ->
                val o = shown[i]
                val first = i == 0
                val last = i == shown.lastIndex
                // One grouped card drawn row by row: rounded only at its two ends.
                val shape = RoundedCornerShape(topStart = if (first) 14.dp else 0.dp, topEnd = if (first) 14.dp else 0.dp, bottomStart = if (last) 14.dp else 0.dp, bottomEnd = if (last) 14.dp else 0.dp)
                Column(Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    if (!first) RowDivider(inset = false)
                    ChoiceRow(p.label(o), o == p.selected, detail = p.detail(o)) { p.onSelect(o); close {} }
                }
            }
        }
    }
}

private val CATEGORY_PICKER = Picker<Any?>("Skip these", emptyList(), null, { "" }) {}

private const val COPYRIGHT = "Oriel © 2026 khush2003"
private const val SOURCE_URL = "https://github.com/khush2003/oriel"

private val LICENCES = listOf(
    COPYRIGHT to "GNU GPL v3.0 only, with attribution terms (NOTICE)",
    "BitChord (interface design and components)" to "GNU GPL v3.0",
    "Kyant0 backdrop (liquid glass), © 2025 Kyant0" to "Apache License 2.0",
    "InnerTubeX (stream resolution)" to "GNU GPL v3.0",
    "Echo-Music, Convx (glass and overscroll, via BitChord)" to "GNU GPL v3.0",
    "AndroidX, Jetpack Compose, Media3" to "Apache License 2.0",
    "Coil" to "Apache License 2.0",
    "Ktor, kotlinx.serialization, kotlinx.coroutines" to "Apache License 2.0",
    "OkHttp" to "Apache License 2.0",
    "Inter typeface, © 2016 The Inter Project Authors" to "SIL Open Font License 1.1",
    "SponsorBlock data" to "CC BY-NC-SA 4.0",
)
