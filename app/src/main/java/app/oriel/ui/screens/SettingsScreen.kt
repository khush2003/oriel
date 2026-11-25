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
import app.oriel.ui.PageWidth
import app.oriel.ui.components.Avatar
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.OrielSheet
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.navigation.LoginRoute
import app.oriel.ui.navigation.SettingsSectionRoute
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.accent
import app.oriel.ui.theme.accentColor
import app.oriel.ui.theme.LocalAccent
import app.oriel.ui.theme.LocalIsDark
import app.oriel.ui.theme.LocalWash
import androidx.compose.ui.draw.drawBehind
import app.oriel.data.Accent
import app.oriel.data.Wash
import app.oriel.ui.components.CategoryTile
import app.oriel.ui.components.GlassPane
import app.oriel.ui.components.GlassChoiceRow
import app.oriel.ui.components.Glyphs
import app.oriel.ui.components.GlassRow
import app.oriel.ui.components.GlyphDisc
import app.oriel.ui.components.PaneHeader
import app.oriel.ui.components.PickerRow
import app.oriel.ui.components.SegmentTile
import app.oriel.ui.components.TileRow
import app.oriel.ui.components.ToggleTile
import app.oriel.ui.components.ValuePill
import app.oriel.ui.components.drawWash
import app.oriel.ui.components.paneType
import app.oriel.ui.components.washPageColor
import app.oriel.ui.AppState
import app.oriel.AppGraph
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.oriel.update.Updates
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

/** Settings' pages, each pushed from the first one. */
enum class SettingsSection(val title: String) {
    QUALITY("Video & sound"), PLAYBACK("Playback"), COMMUNITY("Community"), APPEARANCE("Appearance"),
    PRIVACY("Privacy & storage"), REGION("Region & language"), UPDATES("Updates"), ABOUT("About"),
}

/**
 * Settings' first page, as iOS's is: who's signed in, then one row per kind of setting, each
 * with what it's set to. Sixty switches in one list read as a wall; a page each reads as a place.
 */
@Composable
fun SettingsScreen() {
    val app = LocalAppState.current
    val graph = app.graph
    val s by graph.settings.state.collectAsState()
    val signedIn by graph.account.signedIn.collectAsState()
    val account by graph.account.account.collectAsState()
    val syncing by graph.account.syncing.collectAsState()
    val expired by graph.account.sessionExpired.collectAsState()
    val profiles by graph.account.profiles.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val updates = remember(context) { Updates.get(context) }
    val update by updates.state.collectAsState()
    fun open(section: SettingsSection) = app.navigate(SettingsSectionRoute(section.name))

    PageScaffold(title = "Settings", onBack = app::back, width = PageWidth.Form, background = washPageColor(), wash = true, largeTitle = false) {
        if (signedIn && expired) item(key = "expired") { SessionExpiredCard(onSignIn = { app.navigate(LoginRoute(reauth = graph.account.active.value.id)) }) }
        item(key = "account") {
            GlassPane(Modifier.padding(top = 12.dp)) {
                if (signedIn) {
                    AccountHero(account?.avatar, account?.name ?: "Signed in", account?.email ?: account?.handle, onClick = { app.openAccounts() })
                    GlassRow("Sync library now", Glyphs.LineSync, value = if (syncing) "Syncing…" else null, enabled = !syncing, onClick = { syncLibrary(app) })
                    GlassRow("Sign out", Glyphs.LineSignOut, destructive = true, onClick = {
                        app.alert = AlertSpec("Sign out?", "This account's library stays on this device for when you sign in again, and you'll be switched to Guest. Recommendations go back to Oriel's own.", listOf(
                            AlertAction("Cancel"),
                            AlertAction("Sign out", destructive = true) { app.scope.launch { graph.account.signOut() } },
                        ))
                    })
                } else {
                    GlassRow("Sign in with Google", subtitle = "Sync likes, subscriptions and playlists", chevron = true, onClick = { app.signIn() }, leading = { GlyphDisc(OrielIcons.You) })
                    if (profiles.any { !it.isGuest }) GlassRow("Accounts", OrielIcons.You, chevron = true, onClick = { app.openAccounts() })
                }
            }
        }
        item(key = "categories") {
            TileRow {
                CategoryTile("Video & sound", "${s.videoWifi.label} on Wi-Fi", Glyphs.LineVideo, Modifier.weight(1f)) { open(SettingsSection.QUALITY) }
                CategoryTile("Playback", if (s.autoplay) "Autoplay on" else "Autoplay off", Glyphs.LinePlay, Modifier.weight(1f)) { open(SettingsSection.PLAYBACK) }
            }
            TileRow {
                CategoryTile("Community", if (s.sponsorBlock) "SponsorBlock on" else "SponsorBlock off", Glyphs.LinePeople, Modifier.weight(1f)) { open(SettingsSection.COMMUNITY) }
                CategoryTile("Appearance", "${s.theme.label} · ${s.accent.label}", Glyphs.LineDrop, Modifier.weight(1f)) { open(SettingsSection.APPEARANCE) }
            }
            TileRow {
                CategoryTile("Privacy & storage", if (s.pauseHistory) "History paused" else "History on", Glyphs.LineLock, Modifier.weight(1f)) { open(SettingsSection.PRIVACY) }
                CategoryTile("Region & language", s.country?.let(::countryName) ?: "Automatic", Glyphs.LineGlobe, Modifier.weight(1f)) { open(SettingsSection.REGION) }
            }
        }
        item(key = "oriel") {
            GlassPane {
                GlassRow("Updates", OrielIcons.Download, value = update.available?.takeIf { update.supported }?.version, chevron = true, onClick = { open(SettingsSection.UPDATES) })
                GlassRow("Report a problem", Glyphs.LineBug, chevron = true, onClick = { app.reportProblem() })
                GlassRow("About", Glyphs.LineInfo, value = BuildConfig.VERSION_NAME, chevron = true, onClick = { open(SettingsSection.ABOUT) })
            }
        }
    }
}

/** Who's signed in, at the head of Settings: the picture, the name, and the way to the other accounts. */
@Composable
private fun AccountHero(avatar: String?, name: String, detail: String?, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Accounts") { haptics.play(Haptic.Tap); onClick() }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(avatar, 60.dp, name = name)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Icon(OrielIcons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
    }
}

/** What a page of settings works with: the app, the settings as they are, and how to change one or ask for a choice. */
private class SettingsPage(val app: AppState, val s: SettingsState, val pick: (Picker<*>) -> Unit) {
    val graph: AppGraph get() = app.graph
    fun set(t: (SettingsState) -> SettingsState) = graph.settings.update(t)
}

/** One of Settings' pages; an unknown [name] (a route from another version) shows the first. */
@Composable
fun SettingsSectionScreen(name: String) {
    val app = LocalAppState.current
    val graph = app.graph
    val section = SettingsSection.entries.firstOrNull { it.name == name } ?: SettingsSection.QUALITY
    val s by graph.settings.state.collectAsState()
    var picker by remember { mutableStateOf<Picker<*>?>(null) }
    var licences by remember { mutableStateOf(false) }
    val page = SettingsPage(app, s) { picker = it }

    PageScaffold(title = section.title, onBack = app::back, width = PageWidth.Form, background = washPageColor(), wash = true, largeTitle = false) {
        when (section) {
            SettingsSection.QUALITY -> item(key = "quality") { QualitySettings(page) }
            SettingsSection.PLAYBACK -> item(key = "playback") { PlaybackSettings(page) }
            SettingsSection.COMMUNITY -> item(key = "community") { CommunitySettings(page) }
            SettingsSection.APPEARANCE -> item(key = "appearance") { AppearanceSettings(page) }
            SettingsSection.PRIVACY -> item(key = "privacy") { PrivacySettings(page) }
            SettingsSection.REGION -> item(key = "region") { RegionSettings(page) }
            SettingsSection.UPDATES -> item(key = "updates") { UpdateSettings() }
            SettingsSection.ABOUT -> item(key = "about") { AboutSettings(page, onLicences = { licences = true }) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    (picker as Picker<Any?>?)?.let { p ->
        // Long lists (countries) are lazy and searchable: built whole, they stutter on open.
        if (p.options.size > 30) return@let LongPickerSheet(p) { picker = null }
        OrielSheet({ picker = null }) {
            val close = LocalSheetClose.current
            SheetTitle(p.title)
            if (p === CATEGORY_PICKER) {
                SheetRows {
                    SponsorBlock.DEFAULT_CATEGORIES.plus(listOf("preview", "filler")).forEach { c ->
                        GlassChoiceRow(SponsorBlock.label(c), c in s.sponsorCategories) {
                            page.set { st -> st.copy(sponsorCategories = if (c in st.sponsorCategories) st.sponsorCategories - c else st.sponsorCategories + c) }
                        }
                    }
                }
            } else {
                SheetRows {
                    p.options.forEach { o ->
                        GlassChoiceRow(p.label(o), o == p.selected, detail = p.detail(o)) { p.onSelect(o); close {} }
                    }
                }
            }
        }
    }
    if (licences) OrielSheet({ licences = false }) {
        SheetTitle("Licences")
        // Under the licence each is used under, named once rather than on every row.
        LICENCES.groupBy { it.licence }.forEach { (licence, works) ->
            PaneHeader(licence)
            GlassPane(glyphs = false) { works.forEach { GlassRow(it.name, subtitle = it.note) } }
        }
    }
}

@Composable
private fun QualitySettings(p: SettingsPage) {
    val s = p.s
    val signedIn by p.graph.account.signedIn.collectAsState()
    val profile by p.graph.account.active.collectAsState()
    PaneHeader("Video quality", info = "Auto: follows your connection, up to what this screen and device can show.\nData saver: holds video to 480p and stops prefetching.\nEvery quality a video has, up to 4K, is always on the player.")
    GlassPane {
        PickerRow("On Wi-Fi", Glyphs.LineVideo, VideoQuality.entries, s.videoWifi, { it.label }) { v -> p.set { it.copy(videoWifi = v) } }
        PickerRow("On mobile data", Glyphs.LineVideo, VideoQuality.entries, s.videoCellular, { it.label }) { v -> p.set { it.copy(videoCellular = v) } }
    }
    TileRow { ToggleTile("Data saver", s.dataSaver, { v -> p.set { it.copy(dataSaver = v) } }, Glyphs.LineDataSaver, Modifier.weight(1f), wide = true) }
    PaneHeader("Sound", info = "Premium formats: 256 kbps audio, which YouTube serves only to Premium accounts. Sign in to ask for it.")
    GlassPane {
        PickerRow("Sound quality", Glyphs.LineHeadphones, AudioQuality.entries, s.audioQuality, { it.label }, { it.detail }) { v -> p.set { it.copy(audioQuality = v) } }
    }
    if (signedIn) TileRow {
        ToggleTile("Premium formats", profile.premiumFormats, { v -> p.graph.account.setPremium(profile.id, v) }, Glyphs.LineBadge, Modifier.weight(1f), wide = true)
    }
    PaneHeader("Streaming", info = "SABR: how YouTube's own players stream. Seeking and the cache work the same either way.")
    GlassPane {
        PickerRow("Protocol", Glyphs.LineLayers, Transport.entries, s.transport, { it.label }, { it.detail }) { v -> p.set { it.copy(transport = v) } }
    }
}

/** A speed as a segment has room for: "1×" where the player says "Normal". */
private fun speedShort(speed: Float): String = if (speed == 1f) "1×" else speed.toString().removeSuffix(".0")

@Composable
private fun PlaybackSettings(p: SettingsPage) {
    val s = p.s
    PaneHeader("While you watch", info = "Background play: sound carries on with the screen off or in another app.\nAutoplay: plays a related video when the queue ends.\nResume: picks a video up where you left it.\nPrefetch next: readies the next video so it starts at once.\nPrefer original audio: the language a video was made in, not an auto-dub.")
    TileRow {
        ToggleTile("Background play", s.backgroundPlay, { v -> p.set { it.copy(backgroundPlay = v) } }, Glyphs.LineHeadphones, Modifier.weight(1f))
        ToggleTile("Picture-in-picture", s.autoPip, { v -> p.set { it.copy(autoPip = v) } }, Glyphs.LinePip, Modifier.weight(1f))
    }
    TileRow {
        ToggleTile("Autoplay", s.autoplay, { v -> p.set { it.copy(autoplay = v) } }, Glyphs.LinePlay, Modifier.weight(1f))
        ToggleTile("Resume", s.resumePlayback, { v -> p.set { it.copy(resumePlayback = v) } }, Glyphs.LineClock, Modifier.weight(1f))
    }
    TileRow {
        ToggleTile("Prefetch next", s.prefetchNext, { v -> p.set { it.copy(prefetchNext = v) } }, Glyphs.LineQueue, Modifier.weight(1f))
        ToggleTile("Captions", s.captions, { v -> p.set { it.copy(captions = v) } }, Glyphs.LineCaptions, Modifier.weight(1f))
    }
    // Changing it outranks a track picked in the player earlier this session.
    TileRow {
        ToggleTile("Prefer original audio", s.preferOriginalAudio, { v -> p.graph.sessionAudio = null; p.set { it.copy(preferOriginalAudio = v) } }, Glyphs.LineGlobe, Modifier.weight(1f), wide = true)
    }
    SegmentTile("Default speed", SPEEDS, s.defaultSpeed, ::speedShort) { v -> p.set { it.copy(defaultSpeed = v) } }
    SegmentTile("Double-tap to seek", listOf(5, 10, 15, 20, 30), s.doubleTapSeekSec, { "$it s" }) { v -> p.set { it.copy(doubleTapSeekSec = v) } }
}

@Composable
private fun CommunitySettings(p: SettingsPage) {
    val s = p.s
    PaneHeader("Community services", info = "SponsorBlock: skips sponsor segments. sponsor.ajay.app is asked by the first 4 characters of a hash of the video id.\nReturn YouTube Dislike: estimated dislike counts. returnyoutubedislikeapi.com receives the video id.")
    TileRow {
        ToggleTile("SponsorBlock", s.sponsorBlock, { v -> p.set { it.copy(sponsorBlock = v) } }, Glyphs.LineNext, Modifier.weight(1f))
        ToggleTile("Return YouTube Dislike", s.returnDislike, { v -> p.set { it.copy(returnDislike = v) } }, OrielIcons.Dislike, Modifier.weight(1f))
    }
    if (s.sponsorBlock) GlassPane {
        GlassRow("Skip", Glyphs.LineSliders, onClick = { p.pick(CATEGORY_PICKER) }) { ValuePill("${s.sponsorCategories.size} kinds") }
    }
}

@Composable
private fun AppearanceSettings(p: SettingsPage) {
    val s = p.s
    GlassPane(Modifier.padding(top = 12.dp), dividers = false) {
        Text("Theme", style = paneType(15f, 600), modifier = Modifier.padding(start = 16.dp, top = 13.dp))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemeMode.entries.forEach { mode -> ThemeCard(mode, selected = s.theme == mode, modifier = Modifier.weight(1f)) { p.set { it.copy(theme = mode) } } }
        }
    }
    GlassPane(dividers = false) {
        Text("Accent", style = paneType(15f, 600), modifier = Modifier.padding(start = 16.dp, top = 13.dp))
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Accent.entries.forEach { a -> AccentSwatch(a, selected = s.accent == a) { p.set { it.copy(accent = a) } } }
        }
    }
    SegmentTile("Backdrop colour", Wash.entries, s.wash, { it.label }) { v -> p.set { it.copy(wash = v) } }
    PaneHeader("Glass and motion", LIQUID_FOOTER)
    TileRow {
        ToggleTile(
            "Liquid glass", s.liquidGlass && !s.reduceBlur, { v -> p.set { it.copy(liquidGlass = v) } }, Glyphs.LineDrop, Modifier.weight(1f),
            detail = if (s.reduceBlur) "Off with Reduce transparency" else null, enabled = !s.reduceBlur,
        )
        ToggleTile(
            "Liquid motion", s.liquidMotion && !s.reduceAnimation, { v -> p.set { it.copy(liquidMotion = v, liquidMotionAsked = true) } }, Glyphs.LineMotion, Modifier.weight(1f),
            detail = if (s.reduceAnimation) "Off with Reduce motion" else null, enabled = !s.reduceAnimation,
        )
    }
    TileRow {
        ToggleTile("Reduce motion", s.reduceAnimation, { v -> p.set { it.copy(reduceAnimation = v) } }, Glyphs.LineGauge, Modifier.weight(1f))
        ToggleTile("Reduce transparency", s.reduceBlur, { v -> p.set { it.copy(reduceBlur = v) } }, Glyphs.LineLayers, Modifier.weight(1f))
    }
    TileRow {
        ToggleTile("True black", s.trueBlack, { v -> p.set { it.copy(trueBlack = v) } }, Glyphs.LineMoon, Modifier.weight(1f))
        ToggleTile("High refresh rate", s.highRefreshRate, { v -> p.set { it.copy(highRefreshRate = v) } }, Glyphs.LineRefresh, Modifier.weight(1f))
    }
}

/** A theme as a small picture of the app in it: a page, a card and a bar; System is Modifier.weight(1f) of each. */
@Composable
private fun ThemeCard(mode: ThemeMode, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val ring = accent()
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).selectable(selected, role = Role.RadioButton) { haptics.play(Haptic.Select); onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.56f)
                .border(if (selected) 2.5.dp else 1.dp, if (selected) ring else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(if (selected) 5.dp else 1.dp).clip(RoundedCornerShape(if (selected) 11.dp else 15.dp)),
        ) {
            when (mode) {
                ThemeMode.DARK -> MiniApp(dark = true)
                ThemeMode.LIGHT -> MiniApp(dark = false)
                ThemeMode.SYSTEM -> Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { MiniApp(dark = false) }
                    Box(Modifier.weight(1f)) { MiniApp(dark = true) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(mode.label, style = paneType(13.5f, 600), color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 1f else 0.58f))
        Spacer(Modifier.height(2.dp))
    }
}

/** The app in miniature, in the accent and backdrop chosen now: its wash, two tiles, a pane and the tab bar. */
@Composable
private fun MiniApp(dark: Boolean) {
    val page = if (dark) Color.Black else Color(0xFFEDEDF2)
    val glass = if (dark) Color.White.copy(alpha = 0.13f) else Color.White.copy(alpha = 0.85f)
    val ink = if (dark) Color.White else Color.Black
    val accent = accentColor(LocalAccent.current, dark)
    val wash = LocalWash.current
    Column(Modifier.fillMaxSize().background(page).drawBehind { drawWash(accent, dark, wash) }.padding(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.padding(top = 2.dp, bottom = 1.dp).fillMaxWidth(0.5f).height(5.dp).clip(CircleShape).background(ink.copy(alpha = 0.85f)))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(2) { i ->
                Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(6.dp)).background(glass).padding(4.dp), contentAlignment = Alignment.TopEnd) {
                    Box(Modifier.size(11.dp, 6.dp).clip(CircleShape).background(if (i == 0) accent else ink.copy(alpha = 0.18f)))
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1.3f).clip(RoundedCornerShape(6.dp)).background(glass))
        Box(Modifier.padding(top = 1.dp).fillMaxWidth().height(10.dp).clip(CircleShape).background(glass))
    }
}

@Composable
private fun AccentSwatch(option: Accent, selected: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val colour = accentColor(option, LocalIsDark.current)
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .selectable(selected, role = Role.RadioButton) { haptics.play(Haptic.Select); onClick() }
            .semantics { contentDescription = option.label },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(40.dp).border(2.5.dp, colour, CircleShape))
        Box(Modifier.size(if (selected) 28.dp else 32.dp).clip(CircleShape).background(colour).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
    }
}

@Composable
private fun PrivacySettings(p: SettingsPage) {
    val s = p.s
    val app = p.app
    val graph = p.graph
    val context = androidx.compose.ui.platform.LocalContext.current
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { runCatching { graph.caches.stream.cacheSpace }.getOrNull() } }
    PaneHeader("History", info = if (graph.telemetry.available) "Anonymous usage: counts like app opens, features used and crashes, with app and Android version. Never what you watch or search." else null)
    TileRow {
        ToggleTile("Pause watch history", s.pauseHistory, { v -> p.set { it.copy(pauseHistory = v) } }, Glyphs.LineClock, Modifier.weight(1f))
        ToggleTile("Pause search history", s.pauseSearchHistory, { v -> p.set { it.copy(pauseSearchHistory = v) } }, Glyphs.LineSearch, Modifier.weight(1f))
    }
    TileRow {
        ToggleTile("Remember the queue", s.persistQueue, { v -> p.set { it.copy(persistQueue = v) } }, Glyphs.LineQueue, Modifier.weight(1f), wide = !graph.telemetry.available)
        if (graph.telemetry.available) ToggleTile("Share anonymous usage", s.telemetry == true, { v -> p.set { it.copy(telemetry = v) } }, Glyphs.LineShield, Modifier.weight(1f))
    }
    SegmentTile("Video cache size", listOf(256, 512, 1024, 2048, 4096), s.streamCacheMb, { if (it >= 1024) "${it / 1024} GB" else "$it MB" }, info = (cacheBytes?.let { "In use: ${Format.bytes(it)} of ${s.streamCacheMb} MB.\n" } ?: "") + "Played videos are kept so replays and seeking back cost nothing. A new size takes effect the next time Oriel starts.") { v -> p.set { it.copy(streamCacheMb = v) } }
    GlassPane {
        GlassRow("Clear search history", Glyphs.LineTrash, onClick = {
            app.alert = AlertSpec("Clear search history?", "This clears the searches Oriel remembers on this device.", listOf(
                AlertAction("Cancel"),
                AlertAction("Clear", destructive = true) { graph.library.clearSearches(); app.notice("Search history cleared") },
            ))
        })
        GlassRow("Clear caches", Glyphs.LineTrash, destructive = true, onClick = {
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

@Composable
private fun RegionSettings(p: SettingsPage) {
    val s = p.s
    val langs = listOf<String?>(null, "en", "es", "pt", "fr", "de", "it", "nl", "pl", "tr", "ru", "uk", "ar", "hi", "bn", "id", "vi", "th", "ja", "ko", "zh-Hans", "zh-Hant")
    PaneHeader("What YouTube shows you", info = "What YouTube treats as your country and language, for trending, topics and search.")
    GlassPane {
        PickerRow("Content country", Glyphs.LineGlobe, listOf(s.country), s.country, { it?.let(::countryName) ?: "Automatic" }, onLong = {
            // Each name worked out once: sorting by a fresh Locale per comparison stalls the tap.
            val names = Locale.getISOCountries().associateWith(::countryName)
            val countries = listOf<String?>(null) + names.keys.sortedBy { names[it] }
            p.pick(Picker("Content country", countries, s.country, { it?.let { c -> names[c] } ?: "Automatic" }) { v -> p.set { it.copy(country = v) }; p.graph.applyLocale() })
        }) {}
        PickerRow("Language", OrielIcons.Comments, langs, s.language, { it?.let { l -> Locale.forLanguageTag(l).displayName } ?: "Automatic" }) { v -> p.set { it.copy(language = v) }; p.graph.applyLocale() }
    }
}

@Composable
private fun UpdateSettings() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val updates = remember(context) { Updates.get(context) }
    val update by updates.state.collectAsState()
    if (!update.supported) {
        GlassPane(Modifier.padding(top = 12.dp)) { GlassRow("Updates are off in debug builds", OrielIcons.Download) }
        return
    }
    PaneHeader("Releases", info = "Checking: once a day, on Oriel's GitHub page (api.github.com). Only the app's version is sent.\nInstalling: only an update that matches the release's checksums and is signed with this copy's key.\nBeta updates: test versions before they're released.")
    GlassPane {
        update.available?.let { r -> GlassRow("Update available", OrielIcons.Download, value = r.version, chevron = true, onClick = updates::open) }
        GlassRow(
            "Check for updates", Glyphs.LineSync,
            subtitle = if (update.lastChecked > 0) "Last checked ${android.text.format.DateUtils.getRelativeTimeSpanString(update.lastChecked, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)}" else null,
            value = if (update.checking) "Checking…" else null,
            enabled = !update.checking,
            onClick = updates::checkNow,
        )
    }
    TileRow {
        ToggleTile("Check automatically", update.autoCheck, updates::setAutoCheck, Glyphs.LineClock, Modifier.weight(1f))
        ToggleTile("Beta updates", update.betaUpdates, updates::setBetaUpdates, OrielIcons.Shorts, Modifier.weight(1f))
    }
}

@Composable
private fun AboutSettings(p: SettingsPage, onLicences: () -> Unit) {
    val app = p.app
    val context = androidx.compose.ui.platform.LocalContext.current
    // Counted off the main thread: it reads the crash files.
    var crashes by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // The report sheet can clear what's kept: counted again when it closes.
    androidx.compose.runtime.LaunchedEffect(app.report) { if (app.report == null) crashes = withContext(Dispatchers.IO) { CrashLog.crashCount(context) } }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AppMark(84.dp)
        Spacer(Modifier.height(12.dp))
        Text("Oriel", style = MaterialTheme.typography.headlineMedium)
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    GlassPane {
        // Credit required by NOTICE (GPL-3.0 section 7(b)); forks must keep it.
        GlassRow("Made by khush2003", OrielIcons.You, subtitle = SOURCE_URL.removePrefix("https://"), chevron = true, onClick = { app.openExternally(SOURCE_URL) })
        // Crashes and errors are kept on the phone until a report is sent; see CrashLog.
        GlassRow(
            "Report a problem", Glyphs.LineBug,
            subtitle = when (crashes) { 0 -> "No crashes recorded"; 1 -> "1 crash recorded"; else -> "$crashes crashes recorded" },
            chevron = true,
            onClick = { app.reportProblem() },
        )
        GlassRow("Licences", Glyphs.LineBox, chevron = true, onClick = onLicences)
    }
    Text(
        "$COPYRIGHT. Free software under the GNU GPL v3; see Licences. Not affiliated with or endorsed by YouTube or Google.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 8.dp),
    )
}

/** The app's own icon, drawn from the launcher's layers. */
@Composable
private fun AppMark(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.23f)), contentAlignment = Alignment.Center) {
        // The layers are 108 dp with the mark in the middle 72, as adaptive icons are: shown at 1.5x.
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(app.oriel.R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(app.oriel.R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(size * 1.5f))
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
        SearchField(query, { query = it }, { }, Modifier.padding(horizontal = PAGE_GUTTER, vertical = 6.dp), placeholder = "Search", shape = RoundedCornerShape(50), fill = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
        androidx.compose.foundation.lazy.LazyColumn(state = list, modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
            items(shown.size, key = { shown[it].toString() }, contentType = { "choice" }) { i ->
                val o = shown[i]
                GlassChoiceRow(p.label(o), o == p.selected, detail = p.detail(o)) { p.onSelect(o); close {} }
            }
        }
    }
}

/** A sheet's rows, straight on the sheet: it's the surface, so they need no pane of their own. */
@Composable
private fun SheetRows(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) = Column(Modifier.padding(horizontal = 8.dp).fillMaxWidth(), content = content)

private fun countryName(code: String): String = Locale.Builder().setRegion(code).build().displayCountry

private val CATEGORY_PICKER = Picker<Any?>("Skip these", emptyList(), null, { "" }) {}

private const val COPYRIGHT = "Oriel © 2026 khush2003"
private const val SOURCE_URL = "https://github.com/khush2003/oriel"

private class Licensed(val name: String, val note: String?, val licence: String)

private val LICENCES = listOf(
    Licensed("Oriel", "© 2026 khush2003", "GNU GPL v3.0 only, with attribution terms (NOTICE)"),
    Licensed("BitChord", "Interface design and components", "GNU GPL v3.0"),
    Licensed("InnerTubeX", "Stream resolution", "GNU GPL v3.0"),
    Licensed("Echo-Music, Convx", "Glass and overscroll, via BitChord", "GNU GPL v3.0"),
    Licensed("Kyant0 backdrop", "Liquid glass, © 2025 Kyant0", "Apache License 2.0"),
    Licensed("AndroidX, Jetpack Compose, Media3", null, "Apache License 2.0"),
    Licensed("Coil", null, "Apache License 2.0"),
    Licensed("Ktor", null, "Apache License 2.0"),
    Licensed("kotlinx.serialization, kotlinx.coroutines", null, "Apache License 2.0"),
    Licensed("OkHttp", null, "Apache License 2.0"),
    Licensed("quickjs-kt", "With QuickJS (MIT), via InnerTubeX", "Apache License 2.0"),
    Licensed("Inter typeface", "© 2016 The Inter Project Authors", "SIL Open Font License 1.1"),
    Licensed("SponsorBlock data", null, "CC BY-NC-SA 4.0"),
)

/** What the two liquid switches do, under the Appearance group. */
private const val LIQUID_FOOTER = "Liquid glass: bars, sheets and panes bend and light up what's behind them. Off, they're a plain frosted blur.\nLiquid motion: controls stretch and settle like drops as you use them.\nReduce transparency: no blur at all; every surface is solid."
