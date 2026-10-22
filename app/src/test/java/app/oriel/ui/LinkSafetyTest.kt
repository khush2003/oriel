package app.oriel.ui

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.TestApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Links from descriptions and channel pages are written by anyone; only web links leave the app. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [35])
class LinkSafetyTest {
    @Test fun onlyWebLinksGoToTheBrowser() {
        assertTrue(isWebLink(Uri.parse("https://example.com/a?b=1")))
        assertTrue(isWebLink(Uri.parse("HTTP://example.com")))
        listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.example;end",
            "javascript:alert(1)",
            "file:///data/data/app.oriel/shared_prefs/account.xml",
            "content://com.example.provider/secret",
            "market://details?id=com.example",
            "data:text/html,<b>hi</b>",
            "https:///no-host",
            "tel:123",
        ).forEach { assertFalse(it, isWebLink(Uri.parse(it))) }
    }

    @Test fun lookalikeHostsArentYouTube() {
        assertTrue(isYouTubeHost("youtube.com"))
        assertTrue(isYouTubeHost("music.youtube.com"))
        assertTrue(isYouTubeHost("YouTube.com"))
        assertFalse(isYouTubeHost("notyoutube.com"))
        assertFalse(isYouTubeHost("youtube.com.example.net"))
        assertFalse(isYouTubeHost("evil-youtube.com"))
    }
}
