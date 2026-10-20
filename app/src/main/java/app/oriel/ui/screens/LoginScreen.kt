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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.oriel.ui.components.ActivityIndicator
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.MessageState
import androidx.activity.compose.BackHandler
import app.oriel.data.AccountManager
import app.oriel.ui.LocalAppState
import app.oriel.ui.components.FloatingTopBar
import app.oriel.ui.glass.LocalAppBackdrop
import app.oriel.ui.components.topBarContentPadding
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
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var finishing by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var failed by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    // System back walks the sign-in pages back first, as the bar's chevron does.
    BackHandler(enabled = canGoBack && !finishing) { webView?.goBack() }

    DisposableEffect(Unit) { onDispose { webView?.destroy() } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(top = topBarContentPadding()),
            factory = { context ->
                CookieManager.getInstance().removeAllCookies(null)
                WebView(context).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Google refuses sign-in in WebViews it recognises; "; wv" is the marker it looks for.
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

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
                            if (finishing || !url.startsWith("https://www.youtube.com") && !url.startsWith("https://m.youtube.com")) return
                            val cookie = CookieManager.getInstance().getCookie("https://www.youtube.com") ?: return
                            if ("SAPISID" !in cookie) return
                            finishing = true
                            view.evaluateJavascript("(function(){try{return ytcfg.get('DATASYNC_ID')}catch(e){return null}})()") { raw ->
                                val dataSyncId = raw?.trim('"')?.takeUnless { it == "null" || it.isBlank() }
                                scope.launch {
                                    val ok = app.graph.account.signIn(cookie, dataSyncId)
                                    CookieManager.getInstance().removeAllCookies(null)
                                    if (ok) {
                                        app.notice("Signed in")
                                        app.back()
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
            FloatingTopBar(onBack = { if (webView?.canGoBack() == true) webView?.goBack() else app.back() }, title = "Sign in", showTitle = true)
        }
    }
}
