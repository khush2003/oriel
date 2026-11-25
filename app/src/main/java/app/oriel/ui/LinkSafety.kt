package app.oriel.ui

import android.net.Uri

/** youtube.com or one of its subdomains, never a lookalike such as notyoutube.com. */
internal fun isYouTubeHost(host: String): Boolean {
    val h = host.lowercase()
    return h == "youtube.com" || h.endsWith(".youtube.com")
}

/** A link a browser should open: http(s) with a host, nothing that reaches into other apps. */
internal fun isWebLink(uri: Uri): Boolean =
    (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) && !uri.host.isNullOrBlank()
