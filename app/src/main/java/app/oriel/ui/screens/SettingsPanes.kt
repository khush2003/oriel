package app.oriel.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.oriel.data.AudioQuality
import app.oriel.data.Transport
import app.oriel.data.ThemeMode
import app.oriel.data.VideoQuality
import app.oriel.ui.AlertAction
import app.oriel.ui.AlertSpec
import app.oriel.ui.components.OrielIcons
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
import app.oriel.ui.components.GlassPane
import app.oriel.ui.components.Glyphs
import app.oriel.ui.components.GlassRow
import app.oriel.ui.components.PaneHeader
import app.oriel.ui.components.PickerRow
import app.oriel.ui.components.SegmentTile
import app.oriel.ui.components.TileRow
import app.oriel.ui.components.ToggleTile
import app.oriel.ui.components.ValuePill
import app.oriel.ui.components.drawWash
import app.oriel.ui.components.paneType
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.oriel.update.Updates
import app.oriel.util.Format
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

@Composable
internal fun QualitySettings(p: SettingsPage) {
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
internal fun PlaybackSettings(p: SettingsPage) {
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
internal fun CommunitySettings(p: SettingsPage) {
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
internal fun AppearanceSettings(p: SettingsPage) {
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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun PrivacySettings(p: SettingsPage) {
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
internal fun RegionSettings(p: SettingsPage) {
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
internal fun UpdateSettings() {
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

/** What the two liquid switches do, under the Appearance group. */
private const val LIQUID_FOOTER = "Liquid glass: bars, sheets and panes bend and light up what's behind them. Off, they're a plain frosted blur.\nLiquid motion: controls stretch and settle like drops as you use them.\nReduce transparency: no blur at all; every surface is solid."
