package app.oriel.util

/**
 * Takes out of diagnostic text what must never be in a problem report, which may end up in a
 * public GitHub issue: URL queries (they carry the device's IP and signed stream parameters),
 * the path of googlevideo URLs (manifests carry the same parameters as path segments), IP
 * addresses, cookies and auth headers, account and visitor identifiers, email addresses, and long
 * tokens (visitor data, continuations, hashes). Video ids stay, as `?v=` on a watch link too: a
 * playback report is useless without them.
 *
 * Over-removal is the safe side: a header line loses everything after its name.
 */
object Scrubber {
    private val HEADER = Regex(
        // The name may be quoted, as headers are when a request is printed as JSON.
        """(?im)\b(cookie|set-cookie|authorization|x-goog-authuser|x-goog-visitor-id|x-youtube-identity-token|x-goog-pageid)(["']?\s*[:=]\s*)[^\r\n]*""",
    )
    private val AUTH_SCHEME = Regex("""(?i)\b(Bearer|SAPISIDHASH|SAPISID1PHASH|SAPISID3PHASH)\s+[^\s,;"']+""")
    private val COOKIE = Regex(
        """\b(__Secure-[A-Za-z0-9_-]+|__Host-[A-Za-z0-9_-]+|SAPISID|APISID|SSID|HSID|SID|SIDCC|LOGIN_INFO|VISITOR_INFO1_LIVE|VISITOR_PRIVACY_METADATA|YSC|NID|OSID|LSID|SMSV|ACCOUNT_CHOOSER|AEC)=[^;\s"',}]*""",
    )
    /** Identifiers of the account or the visitor, too short for [BLOB] to catch. */
    private val KEYED = Regex(
        """(?i)\b(visitorData|visitor_data|dataSyncId|datasync_id|onBehalfOfUser|delegatedSessionId|identityToken)(["']?\s*[:=]\s*["']?)[^\s"',&}\[]+""",
    )
    private val GOOGLEVIDEO_PATH = Regex("""(https?://[^/\s"'?#]*googlevideo\.com/[^/\s"'?#]+)/[^\s"'?#]+""")
    private val QUERY = Regex("""(https?://[^\s?#"']+)\?([^\s"']*)""")
    private val VIDEO_PARAM = Regex("""(?:^|&)v=([A-Za-z0-9_-]{11})(?=&|#|$)""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")
    // Visitor data, continuation and session tokens are long runs of base64 (often URL-encoded);
    // nothing a report needs runs that long without a dot or a space.
    private val BLOB = Regex("""[A-Za-z0-9+/_%-]{61,}=*""")
    private val IPV4 = Regex("""\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b""")

    fun scrub(text: String): String {
        var t = HEADER.replace(text) { "${it.groupValues[1]}${it.groupValues[2]}[removed]" }
        t = AUTH_SCHEME.replace(t) { "${it.groupValues[1]} [removed]" }
        t = COOKIE.replace(t) { "${it.groupValues[1]}=[removed]" }
        t = GOOGLEVIDEO_PATH.replace(t) { "${it.groupValues[1]}/…" }
        t = QUERY.replace(t) { m ->
            val query = m.groupValues[2]
            val id = VIDEO_PARAM.find(query)?.groupValues?.get(1)
            when {
                id == null -> "${m.groupValues[1]}?…"
                query == "v=$id" -> "${m.groupValues[1]}?v=$id"
                else -> "${m.groupValues[1]}?v=$id&…"
            }
        }
        t = EMAIL.replace(t, "[email]")
        t = BLOB.replace(t, "[token]")
        t = KEYED.replace(t) { "${it.groupValues[1]}${it.groupValues[2]}[removed]" }
        t = IPV4.replace(t, "[ip]")
        return t
    }
}
