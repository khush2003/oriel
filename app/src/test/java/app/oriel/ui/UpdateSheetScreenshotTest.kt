package app.oriel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.data.ThemeMode
import app.oriel.ui.screens.UpdateActions
import app.oriel.ui.screens.UpdateSheet
import app.oriel.ui.theme.OrielTheme
import app.oriel.update.Release
import app.oriel.update.ReleaseAsset
import app.oriel.update.UpdateStep
import app.oriel.update.Updates
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The update offer, its download and a failed verification, dark and light. The release is invented. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = app.oriel.TestApp::class, sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class UpdateSheetScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val apk = ReleaseAsset("Oriel-9.1.0-arm64-v8a.apk", 5_696_090, "https://github.com/khush2003/oriel/releases/download/v9.1.0/Oriel-9.1.0-arm64-v8a.apk")
    private val release = Release(
        tag = "v9.1.0",
        version = "9.1.0",
        prerelease = false,
        notes = "## What's new\n- **Shorts** remember where you left off\n- Search suggestions appear as you type\n- Picture-in-picture keeps the [captions](https://github.com/khush2003/oriel/issues/1) on\n\n## Fixes\n- The queue no longer jumps after a reorder",
        assets = listOf(apk),
    )

    private val log = mutableListOf<String>()
    private val actions = UpdateActions(
        update = { log += "update" }, later = { log += "later" }, skip = { log += "skip" }, cancel = { log += "cancel" }, openSettings = { log += "settings" },
    )

    private fun shoot(name: String, theme: ThemeMode, step: UpdateStep) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            OrielTheme(theme, reduceAnimation = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    UpdateSheet(release, apk, step, actions)
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        captureScreenRoboImage("build/screenshots/c_update_$name.png")
    }

    @Test fun offerDark() = shoot("offer_dark", ThemeMode.DARK, UpdateStep.Offer)
    @Test fun offerLight() = shoot("offer_light", ThemeMode.LIGHT, UpdateStep.Offer)
    @Test fun downloadingDark() = shoot("downloading_dark", ThemeMode.DARK, UpdateStep.Downloading(2_200_000, 5_696_090))
    @Test fun unverifiedLight() = shoot("unverified_light", ThemeMode.LIGHT, UpdateStep.Failed(Updates.UNVERIFIED))
    @Test fun permissionLight() = shoot("permission_light", ThemeMode.LIGHT, UpdateStep.NeedsPermission)

    @Test fun buttonsDoWhatTheySay() {
        shoot("offer_dark", ThemeMode.DARK, UpdateStep.Offer)
        rule.mainClock.autoAdvance = true
        rule.onNode(hasText("Update")).performClick()
        assertEquals(listOf("update"), log)
    }
}
