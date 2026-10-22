package app.oriel.security

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.TestApp
import app.oriel.ui.screens.LoginNavigation
import app.oriel.ui.screens.loginNavigation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The sign-in WebView loads Google's sign-in and YouTube only, and a page can't launch anything by itself. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [35])
class LoginNavigationTest {
    private fun nav(url: String, main: Boolean = true) = loginNavigation(Uri.parse(url), main)

    @Test fun signInHostsLoad() {
        listOf(
            "https://accounts.google.com/ServiceLogin",
            "https://accounts.youtube.com/accounts/SetSID",
            "https://www.youtube.com/",
            "https://m.youtube.com/",
            "https://consent.youtube.com/m",
            "https://consent.google.co.uk/",
            // Google sets the session on its country domain and YouTube's on the way back.
            "https://accounts.google.co.th/accounts/SetSID",
            "https://www.google.com.au/accounts/SetSID",
            "https://accounts.google.de/accounts/SetSID",
            "https://youtube.com/signin?action_handle_signin=true",
            "https://www.youtube.com/signin?action_handle_signin=true",
        ).forEach { assertEquals(it, LoginNavigation.LOAD, nav(it)) }
    }

    @Test fun otherWebPagesGoToTheBrowser() {
        assertEquals(LoginNavigation.EXTERNAL, nav("https://example.com/help"))
        // Plain http is never loaded in the sign-in WebView, even for Google's own hosts.
        assertEquals(LoginNavigation.EXTERNAL, nav("http://accounts.google.com/"))
    }

    @Test fun lookalikesDontLoad() {
        listOf(
            "https://accounts.google.com.example.net/",
            "https://evilgoogle.com/",
            "https://www.youtube.com.example.net/",
            "https://notyoutube.com/",
            "https://google.co.th.example.net/",
            "https://mygoogle.co.th/",
            "https://youtube.com.example.net/",
        ).forEach { assertEquals(it, LoginNavigation.EXTERNAL, nav(it)) }
    }

    @Test fun otherSchemesAreDropped() {
        listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.example;end",
            "javascript:alert(1)",
            "data:text/html,<b>hi</b>",
            "file:///data/data/app.oriel/shared_prefs/account.xml",
            "content://com.example.provider/x",
            "market://details?id=com.example",
            "https:///no-host",
        ).forEach { assertEquals(it, LoginNavigation.BLOCK, nav(it)) }
    }

    @Test fun framesCantOpenTheBrowser() {
        assertEquals(LoginNavigation.BLOCK, nav("https://example.com/", main = false))
        assertEquals(LoginNavigation.LOAD, nav("https://accounts.google.com/frame", main = false))
    }
}
