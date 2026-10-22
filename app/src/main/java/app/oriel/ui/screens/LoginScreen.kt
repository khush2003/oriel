package app.oriel.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.oriel.ui.components.ActivityIndicator
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.MessageState
import androidx.activity.compose.BackHandler
import androidx.core.net.toUri
import app.oriel.data.AccountManager
import app.oriel.ui.LocalAppState
import app.oriel.ui.components.FloatingTopBar
import app.oriel.ui.glass.LocalAppBackdrop
import app.oriel.ui.components.topBarContentPadding
import app.oriel.ui.navigation.LoginRoute
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Google's own sign-in page in a WebView. When it lands back on YouTube
 * signed in, the session cookie and the account's DATASYNC_ID are kept in
 * app-private storage and the page closes. Oriel never sees the password.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen() {
    val app = LocalAppState.current
    var loading by remember { mutableStateOf(true) }
    var finishing by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var failed by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    // System back walks the sign-in pages back first, as the bar's chevron does.
    BackHandler(enabled = canGoBack && !finishing) { webView?.goBack() }
    // Once the cookie is in, the sign-in is finishing on its own: leaving now would only confuse it.
    BackHandler(enabled = finishing) {}

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            CookieManager.getInstance().removeAllCookies(null)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(top = topBarContentPadding()),
            factory = { context ->
                CookieManager.getInstance().removeAllCookies(null)
                WebView(context).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowContentAccess = false
                    settings.allowFileAccess = false
                    // Google refuses sign-in in WebViews it recognises; "; wv" is the marker it looks for.
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        // Only Google's sign-in and YouTube load here; any other link opens in the browser.
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url
                            if (url.scheme == "https" && isSignInHost(url.host)) return false
                            if (url.scheme == "https" || url.scheme == "http") app.openExternally(url.toString())
                            return true
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            loading = true
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                            // Only the page itself failing counts, not one of its images or scripts.
                            if (request.isForMainFrame) { failed = true; loading = false }
                        }

                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            canGoBack = view.canGoBack()
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            loading = false
                            canGoBack = view.canGoBack()
                            val page = url.toUri()
                            if (finishing || page.scheme != "https" || (page.host != "www.youtube.com" && page.host != "m.youtube.com")) return
                            val cookie = CookieManager.getInstance().getCookie("https://www.youtube.com") ?: return
                            if ("SAPISID" !in cookie) return
                            finishing = true
                            view.evaluateJavascript("(function(){try{return ytcfg.get('DATASYNC_ID')}catch(e){return null}})()") { raw ->
                                val dataSyncId = raw?.trim('"')?.takeUnless { it == "null" || it.isBlank() }
                                // In the app's scope, not this screen's: it must finish (or fail) as a whole, whatever the screen does.
                                app.graph.scope.launch(Dispatchers.Main.immediate) {
                                    val ok = try {
                                        app.graph.account.signIn(cookie, dataSyncId)
                                    } finally {
                                        CookieManager.getInstance().removeAllCookies(null)
                                    }
                                    if (ok) {
                                        app.notice("Signed in")
                                        if (app.nav.currentBackStackEntry?.destination?.hasRoute(LoginRoute::class) == true) app.back()
                                    } else {
                                        finishing = false
                                        app.notice("Sign-in didn't complete. Try again.")
                                    }
                                }
                            }
                        }
                    }
                    loadUrl(AccountManager.LOGIN_URL)
                }
            },
        )
        if (failed) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = topBarContentPadding()), contentAlignment = Alignment.Center) {
                MessageState(
                    "Couldn't reach Google's sign-in page. Check your connection and try again.",
                    icon = OrielIcons.Offline, action = "Try again",
                    onAction = { failed = false; loading = true; webView?.reload() },
                )
            }
        } else if (loading || finishing) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                ActivityIndicator(size = 28.dp)
                if (finishing) Text("Signing in…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            }
        }
        // A WebView can't be sampled as glass; the bar falls back to its frosted fill.
        CompositionLocalProvider(LocalAppBackdrop provides null) {
            FloatingTopBar(
                onBack = {
                    when {
                        finishing -> {}
                        webView?.canGoBack() == true -> webView?.goBack()
                        else -> app.back()
                    }
                },
                title = "Sign in",
                showTitle = true,
            )
        }
    }
}

private val CONSENT_HOST = Regex("""consent\.(youtube\.com|google\.[a-z]{2,3}(\.[a-z]{2})?)""")

/** Google's sign-in and its consent pages, and YouTube, where it returns. */
private fun isSignInHost(host: String?): Boolean = host != null && (
    host == "accounts.google.com" || host == "google.com" || host.endsWith(".google.com") ||
        host == "www.youtube.com" || host == "m.youtube.com" ||
        // Where Google sets the youtube.com session on its way back.
        host == "accounts.youtube.com" ||
        CONSENT_HOST.matches(host)
    )
