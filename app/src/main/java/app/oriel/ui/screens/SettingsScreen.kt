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
import app.oriel.data.SettingsState
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
import app.oriel.ui.components.CategoryTile
import app.oriel.ui.components.GlassPane
import app.oriel.ui.components.GlassChoiceRow
import app.oriel.ui.components.Glyphs
import app.oriel.ui.components.GlassRow
import app.oriel.ui.components.GlyphDisc
import app.oriel.ui.components.PaneHeader
import app.oriel.ui.components.TileRow
import app.oriel.ui.components.washPageColor
import app.oriel.ui.AppState
import app.oriel.AppGraph
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextOverflow
import app.oriel.update.Updates
import app.oriel.util.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** A choice that opens as a sheet of ticked rows, as iOS pushes a picker. */
internal class Picker<T>(val title: String, val options: List<T>, val selected: T, val label: (T) -> String, val detail: (T) -> String? = { null }, val footer: String? = null, val onSelect: (T) -> Unit)

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
internal class SettingsPage(val app: AppState, val s: SettingsState, val pick: (Picker<*>) -> Unit) {
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

internal fun countryName(code: String): String = Locale.Builder().setRegion(code).build().displayCountry

internal val CATEGORY_PICKER = Picker<Any?>("Skip these", emptyList(), null, { "" }) {}

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
