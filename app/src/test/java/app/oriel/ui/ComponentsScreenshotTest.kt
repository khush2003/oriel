package app.oriel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.data.ThemeMode
import app.oriel.ui.components.BottomTab
import app.oriel.ui.components.ChoiceRow
import app.oriel.ui.components.FilterChip
import app.oriel.ui.components.FloatingTabBar
import app.oriel.ui.components.FloatingTopBar
import app.oriel.ui.components.NewPlaylistDialog
import app.oriel.ui.components.NoticeBanner
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.PillAction
import app.oriel.ui.components.SectionHeader
import app.oriel.ui.components.SegmentedControl
import app.oriel.ui.components.SettingsGroup
import app.oriel.ui.components.SheetAction
import app.oriel.ui.components.SheetTitle
import app.oriel.ui.components.SubscribeButton
import app.oriel.ui.components.TabStrip
import app.oriel.ui.components.TextAction
import app.oriel.ui.components.TextPromptDialog
import app.oriel.ui.theme.OrielTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shared components on their own: the tab bar and its glyphs, bars, sheets' headers and alerts, light and dark, RTL and 200% type. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = app.oriel.TestApp::class, sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class ComponentsScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val tabs = listOf(
        BottomTab("Home", OrielIcons.Home, OrielIcons.HomeSelected),
        BottomTab("Shorts", OrielIcons.Shorts, OrielIcons.ShortsSelected),
        BottomTab("Subscriptions", OrielIcons.Subscriptions, OrielIcons.SubscriptionsSelected),
        BottomTab("You", OrielIcons.You, OrielIcons.YouSelected),
    )

    @Composable
    private fun Frame(theme: ThemeMode, rtl: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        OrielTheme(theme, reduceAnimation = true) {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 12.dp)) { content() }
            }
        }
    }

    @Composable
    private fun Gallery() {
        var tab by remember { mutableIntStateOf(2) }
        var segment by remember { mutableStateOf("Week") }
        var strip by remember { mutableIntStateOf(1) }
        // The eight tab glyphs at their size in the bar, and large to see the drawing.
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            tabs.forEach { t ->
                Icon(t.icon, null, Modifier.size(25.dp))
                Icon(t.selectedIcon, null, Modifier.size(25.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tabs.forEach { t ->
                Icon(t.icon, null, Modifier.size(44.dp))
                Icon(t.selectedIcon, null, Modifier.size(44.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        FloatingTopBar(onBack = {}, title = "A playlist with a long name that runs on", showTitle = true, actions = {
            PillAction(OrielIcons.Reorder, "Reorder") {}
            PillAction(OrielIcons.Edit, "Rename") {}
            PillAction(OrielIcons.Delete, "Delete playlist") {}
        })
        SheetTitle("Save to a playlist with a long name", onBack = {}, trailing = { SheetAction("New playlist") {} })
        SectionHeader("History", onMore = {})
        TabStrip(listOf("Home", "Videos", "Shorts", "Live", "Playlists", "Posts"), strip, { strip = it })
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("All", true) {}
            FilterChip("Music", false) {}
            FilterChip("This week", false, leading = OrielIcons.Close, role = Role.Button) {}
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            SegmentedControl(listOf("Today", "Week", "Month"), segment, { it }, { segment = it }, Modifier.weight(1f))
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SubscribeButton(false) {}
            SubscribeButton(true) {}
            TextAction("Clear", {})
        }
        SettingsGroup(header = "On this device", top = 10.dp) {
            ChoiceRow("Watch later", true, multi = true) {}
            ChoiceRow("Cabin inspiration", false, detail = "5", multi = true) {}
            ChoiceRow("Not with this filter", false, enabled = false) {}
        }
        Spacer(Modifier.height(10.dp))
        NoticeBanner("Added to queue", action = "Undo", onAction = {})
        Spacer(Modifier.height(12.dp))
        FloatingTabBar(tabs, tab, { tab = it }, searchSelected = false, onSearch = {})
    }

    private fun shoot(name: String, theme: ThemeMode, rtl: Boolean = false, fontScale: Float = 1f) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(theme, rtl, fontScale) { Gallery() } }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/screenshots/c_$name.png")
    }

    @Test fun componentsDark() = shoot("components_dark", ThemeMode.DARK)
    @Test fun componentsLight() = shoot("components_light", ThemeMode.LIGHT)
    @Test fun componentsRtl() = shoot("components_rtl", ThemeMode.DARK, rtl = true)
    @Test fun componentsLargeFont() = shoot("components_font200", ThemeMode.LIGHT, fontScale = 2f)

    /** Tabs read as tabs, the chosen one as selected, with the label as the only name. */
    @Test fun tabBarSemantics() {
        rule.setContent { Frame(ThemeMode.DARK) { FloatingTabBar(tabs, 1, {}, searchSelected = false, onSearch = {}) } }
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        assertEquals("tabs", 5, rule.onAllNodes(tab).fetchSemanticsNodes().size)
        assertEquals("chosen", 1, rule.onAllNodes(tab.and(isSelected()).and(hasText("Shorts"))).fetchSemanticsNodes().size)
    }

    /** One of several is a radio button; one of a set that each toggle is a checkbox; disabled doesn't click. */
    @Test fun choiceRowRoles() {
        var clicks = 0
        rule.setContent {
            Frame(ThemeMode.DARK) {
                ChoiceRow("Radio", true) {}
                ChoiceRow("Check", true, multi = true) {}
                ChoiceRow("Off", false, enabled = false) { clicks++ }
            }
        }
        assertEquals(1, rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton).and(hasText("Radio"))).fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox).and(hasText("Check"))).fetchSemanticsNodes().size)
        rule.onNode(hasText("Off")).performClick()
        assertEquals(0, clicks)
    }

    private fun alerts(name: String, theme: ThemeMode) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Frame(theme) {
                TextPromptDialog("Rename playlist", "Cabin inspiration", "Save", onDismiss = {}, onConfirm = {})
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        captureScreenRoboImage("build/screenshots/c_$name.png")
    }

    @Test fun alertDark() = alerts("alert_dark", ThemeMode.DARK)
    @Test fun alertLight() = alerts("alert_light", ThemeMode.LIGHT)

    @Test fun newPlaylistLight() {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(ThemeMode.LIGHT) { NewPlaylistDialog(signedIn = true, onDismiss = {}) { _, _, _ -> } } }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        captureScreenRoboImage("build/screenshots/c_new_playlist_light.png")
    }
}
