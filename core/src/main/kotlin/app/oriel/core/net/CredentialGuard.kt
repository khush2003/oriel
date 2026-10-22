package app.oriel.core.net

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * The last word on where the sign-in credential may go: every request the app's one OkHttp
 * client makes (InnerTube, the cipher's script downloads, media, images, the community services)
 * passes through this as a network interceptor, so it sees each hop of a redirect too. Anything
 * not going over HTTPS to YouTube's or Google's own hosts loses its cookie and auth headers.
 *
 * Needed because neither redirect follower is careful enough on its own: OkHttp drops only
 * `Authorization` when a redirect changes host, and a `Cookie` header set by hand rides along;
 * and stream headers (which carry the cookie for some clients) are put on whatever URL a
 * manifest names.
 */
object CredentialGuard : Interceptor {
    private val CREDENTIAL_HEADERS = listOf(
        "Cookie",
        "Authorization",
        "X-Goog-AuthUser",
        "X-Goog-PageId",
        "X-Youtube-Identity-Token",
    )

    /** Registrable domains whose hosts the session belongs to; each also allows its subdomains. */
    private val TRUSTED_DOMAINS = listOf(
        "youtube.com",
        "youtube-nocookie.com",
        "youtu.be",
        "googlevideo.com",
        "google.com",
        "googleapis.com",
    )

    fun isTrusted(url: HttpUrl): Boolean {
        if (!url.isHttps || url.port != 443) return false
        val host = url.host.lowercase()
        return TRUSTED_DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    /** [request] as it may go out: unchanged to a trusted host, without credentials anywhere else. */
    fun guard(request: Request): Request {
        if (isTrusted(request.url)) return request
        if (CREDENTIAL_HEADERS.none { request.header(it) != null }) return request
        return request.newBuilder().apply { CREDENTIAL_HEADERS.forEach { removeHeader(it) } }.build()
    }

    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(guard(chain.request()))
}
