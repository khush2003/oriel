package app.oriel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.core.model.AccountInfo
import app.oriel.data.Profile
import app.oriel.data.ProfileKind
import app.oriel.data.ThemeMode
import app.oriel.ui.glass.glassSurface
import app.oriel.ui.screens.AccountCardContent
import app.oriel.ui.theme.OrielTheme
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The floating account card: an account in use, another signed out, the Guest; managing; a switch under way. People are invented. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = app.oriel.TestApp::class, sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class AccountSwitcherScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val profiles = listOf(
        Profile("guest", ProfileKind.GUEST),
        Profile("a", ProfileKind.ACCOUNT, credential = "a", info = AccountInfo(name = "Avery Invented", handle = "@avery.invented"), addedAt = 1),
        Profile("b", ProfileKind.ACCOUNT, info = AccountInfo(name = "Sam Madeup", handle = "@sammadeup"), addedAt = 2),
        Profile("c", ProfileKind.ACCOUNT, credential = "c", info = AccountInfo(name = "Rin Example", email = "rin@example.com"), addedAt = 3),
    )

    private val log = mutableListOf<String>()

    private fun shoot(
        name: String,
        theme: ThemeMode,
        active: String = "a",
        signedIn: Boolean = true,
        expired: Boolean = false,
        managing: Boolean = false,
        switchingTo: String? = null,
    ) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OrielTheme(theme, reduceAnimation = true) {
                // The page under the card, dimmed as the scrim dims it.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).background(Color.Black.copy(alpha = 0.3f))) {
                    Column(Modifier.padding(top = 32.dp, start = 12.dp, end = 12.dp).fillMaxWidth().glassSurface(RoundedCornerShape(28.dp))) {
                        AccountCardContent(
                            profiles, active, signedIn = signedIn, expired = expired, managing = managing, switchingTo = switchingTo, canAdd = true,
                            onSwitch = { log += "switch:$it" }, onSignIn = { log += "signin" }, onSignOut = { log += "signout:${it.id}" },
                            onAdd = { log += "add" }, onManage = { log += "manage" }, onSettings = { log += "settings" },
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        captureScreenRoboImage("build/screenshots/c_accounts_$name.png")
    }

    @Test fun dark() = shoot("dark", ThemeMode.DARK)
    @Test fun light() = shoot("light", ThemeMode.LIGHT)
    @Test fun guestDark() = shoot("guest_dark", ThemeMode.DARK, active = "guest", signedIn = false)
    @Test fun expiredLight() = shoot("expired_light", ThemeMode.LIGHT, expired = true)
    @Test fun managingDark() = shoot("managing_dark", ThemeMode.DARK, managing = true)
    @Test fun switchingLight() = shoot("switching_light", ThemeMode.LIGHT, switchingTo = "c")

    @Test fun rowsDoWhatTheySay() {
        shoot("dark", ThemeMode.DARK)
        rule.mainClock.autoAdvance = true
        rule.onNode(hasText("Rin Example")).performClick()
        rule.onNode(hasText("Add account")).performClick()
        rule.onNode(hasText("Manage accounts")).performClick()
        rule.onNode(hasText("Settings")).performClick()
        assertEquals(listOf("switch:c", "add", "manage", "settings"), log)
    }

    @Test fun managingSignsOut() {
        shoot("managing_dark", ThemeMode.DARK, managing = true)
        rule.mainClock.autoAdvance = true
        rule.onNode(hasClickLabel("Sign out of Sam Madeup")).performClick()
        rule.onNode(hasClickLabel("Sign out of Avery Invented")).performClick()
        assertEquals(listOf("signout:b", "signout:a"), log)
    }

    private fun hasClickLabel(label: String) = SemanticsMatcher("click label $label") {
        it.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == label
    }
}
