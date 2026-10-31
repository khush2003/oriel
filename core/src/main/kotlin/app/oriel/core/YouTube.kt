package app.oriel.core

import app.oriel.core.json.arr
import app.oriel.core.json.at
import app.oriel.core.json.findFirst
import app.oriel.core.json.get
import app.oriel.core.json.int
import app.oriel.core.json.long
import app.oriel.core.json.string
import app.oriel.core.json.text
import app.oriel.core.model.AccountInfo
import app.oriel.core.model.ChannelAbout
import app.oriel.core.model.InboxPage
import app.oriel.core.model.ChannelPage
import app.oriel.core.model.CommentsPage
import app.oriel.core.model.Feed
import app.oriel.core.model.Item
import app.oriel.core.model.LikeStatus
import app.oriel.core.model.Playlist
import app.oriel.core.model.PlaylistPage
import app.oriel.core.model.SearchPage
import app.oriel.core.model.ShortInfo
import app.oriel.core.model.ShortSound
import app.oriel.core.model.SoundPage
import app.oriel.core.model.ShortsBatch
import app.oriel.core.model.Video
import app.oriel.core.model.WatchPage
import app.oriel.core.parse.Pages
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.YouTubeLocale
import com.metrolist.innertubex.utils.sanitizeCookieString
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import kotlin.random.Random

class YTException(val status: Int, message: String) : Exception(message)

/** An HLS master playlist and the user agent its playlists and segments are fetched with. */
data class AdaptiveManifest(val url: String, val userAgent: String)

/** A caption track as the player lists it; [url] serves it as YouTube's json3 timed text. */
data class CaptionTrack(val languageCode: String, val name: String, val isAuto: Boolean, val url: String)

data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** Where a link leads; exactly one of video, browse (channel) or playlist is the destination. */
data class ResolvedUrl(val videoId: String? = null, val browseId: String? = null, val params: String? = null, val playlistId: String? = null)

/**
 * YouTube, as the app sees it: typed pages in, typed actions out.
 *
 * Browsing, search, the watch page and account actions speak the desktop WEB
 * client's InnerTube dialect directly, since that is the client whose
 * responses carry everything youtube.com shows (comments, chapters, Shorts,
 * channel tabs). Stream extraction stays with InnerTubeX, which owns the
 * player clients, cipher and PO-token tiers.
 *
 * Parsed pages sit in a small LRU with per-page expiry and in-flight
 * de-duplication, so going back never waits on the network twice.
 */
class YouTube(
    okHttp: OkHttpClient = OkHttpClient(),
    logger: InnerTubeLogger = InnerTubeLogger.NONE,
) {
    val http: HttpClient = HttpClient(OkHttp) {
        engine { preconfigured = okHttp }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
        expectSuccess = false
    }

    /**
     * For SABR media, which streams segments while its response is still open
     * and stops reading for as long as the player's buffer is full: a total
     * request timeout would cut every long read off mid-video. Only a silent
     * socket (no byte for 15 s while reading) counts as a dead connection.
     */
    val streamingHttp: HttpClient = HttpClient(OkHttp) {
        engine { preconfigured = okHttp }
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
        expectSuccess = false
    }

    /** InnerTubeX's session, shared with the stream resolver so both use one identity. */
    val innerTube = InnerTube(http, logger = logger)

    private val cache = PageCache(capacity = 128)
    private val visitorMutex = Mutex()

    /** Raw cookie header of a signed-in youtube.com session, or null when signed out. */
    var cookie: String?
        get() = innerTube.cookie
        set(value) {
            innerTube.cookie = value
            innerTube.useLoginForBrowse = !value.isNullOrBlank()
            cache.clear()
        }

    var dataSyncId: String?
        get() = innerTube.dataSyncId
        set(value) { innerTube.dataSyncId = value?.substringBefore("||")?.ifBlank { null } }

    private val sapisid: String? get() = sapisidOf(cookie)

    val isSignedIn: Boolean get() = sapisid != null

    fun setLocale(country: String, language: String) {
        val locale = YouTubeLocale(gl = country, hl = language)
        if (innerTube.locale != locale) {
            innerTube.locale = locale
            cache.clear()
        }
    }

    @Volatile
    private var visitorRetryAt = 0L

    /**
     * The session's visitor data, fetched once if there's none yet. A failed
     * fetch isn't tried again for [VISITOR_BACKOFF_MS]; callers go on without
     * it meanwhile instead of each queueing behind the same doomed fetch.
     */
    suspend fun ensureVisitorData(): String? {
        innerTube.visitorData?.let { return it }
        if (System.currentTimeMillis() < visitorRetryAt) return null
        return visitorMutex.withLock {
            innerTube.visitorData?.let { return@withLock it }
            if (System.currentTimeMillis() < visitorRetryAt) return@withLock null
            val fetched = try {
                innerTube.fetchFreshVisitorData()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (fetched == null) visitorRetryAt = System.currentTimeMillis() + VISITOR_BACKOFF_MS
            else innerTube.visitorData = fetched
            fetched
        }
    }

    fun clearCache() = cache.clear()

    // ---- transport -----------------------------------------------------------------------

    private suspend fun HttpResponse.json(): JsonElement {
        val text = bodyAsText()
        if (status.value !in 200..299) {
            val message = runCatching { Json.parseToJsonElement(text).at("error", "message").string }.getOrNull()
            throw YTException(status.value, message ?: "HTTP ${status.value}")
        }
        return Json.parseToJsonElement(text)
    }

    private fun sapisidHash(sapisid: String): String {
        val now = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1").digest("$now $sapisid $ORIGIN".toByteArray())
        return "SAPISIDHASH ${now}_" + digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * One WEB InnerTube call. [body] is merged after the client context.
     *
     * Reads are asked again on a dropped connection or YouTube saying it's
     * busy (408, 5xx, a short 429), twice, about 0.4 s and 1.2 s later: one
     * blip on a train shouldn't be an error screen. A timeout isn't asked
     * again: it already took its full time, and two more would keep an error
     * off the screen for a minute on a dead network. Nor is anything once the
     * call has been at it for [RETRY_BUDGET_MS]. Writes go once, since a
     * create that timed out may still have happened.
     */
    private suspend fun post(endpoint: String, body: JsonObjectBuilder.() -> Unit): JsonElement {
        val retries = if (endpoint in READS) RETRIES else 0
        val started = System.currentTimeMillis()
        fun canRetry(attempt: Int) = attempt < retries && System.currentTimeMillis() - started < RETRY_BUDGET_MS
        var attempt = 0
        while (true) {
            val response = try {
                send(endpoint, body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (isTimeout(e) || !canRetry(attempt)) throw e
                delay(backoff(attempt++))
                continue
            }
            val status = response.status.value
            val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
            val busy = status == 408 || status in 500..599 || (status == 429 && retryAfter != null && retryAfter <= 5)
            if (busy && canRetry(attempt)) {
                delay(maxOf(backoff(attempt++), (retryAfter ?: 0L) * 1_000))
                continue
            }
            val json = response.json()
            if (innerTube.visitorData == null) json.at("responseContext", "visitorData").string?.let { innerTube.visitorData = it }
            return json
        }
    }

    /** Ktor's request, connect and socket timeouts, and the JDK's own (all IOExceptions). */
    private fun isTimeout(e: IOException): Boolean =
        e is HttpRequestTimeoutException || e is InterruptedIOException || e.javaClass.simpleName.contains("Timeout")

    private fun backoff(attempt: Int): Long {
        val base = if (attempt == 0) 400L else 1_200L
        return base + Random.nextLong(base / 2)
    }

    private suspend fun send(endpoint: String, body: JsonObjectBuilder.() -> Unit): HttpResponse {
        // Without visitor data yet, the call goes without; its answer carries one, which [post] keeps.
        val visitor = innerTube.visitorData
        val locale = innerTube.locale
        val auth = sapisid
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB")
                    put("clientVersion", WEB_VERSION)
                    put("hl", locale.hl)
                    put("gl", locale.gl)
                    put("platform", "DESKTOP")
                    put("userAgent", USER_AGENT)
                    visitor?.let { put("visitorData", it) }
                }
                putJsonObject("user") {
                    put("lockedSafetyMode", false)
                    if (auth != null) dataSyncId?.let { put("onBehalfOfUser", it) }
                }
            }
            body()
        }
        return http.post("$API/$endpoint") {
            // A JSON page that hasn't come in 15 s isn't coming; the client's 30 s is for InnerTubeX's player calls.
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
            parameter("prettyPrint", false)
            header("X-Youtube-Client-Name", "1")
            header("X-Youtube-Client-Version", WEB_VERSION)
            header("Origin", ORIGIN)
            header("X-Origin", ORIGIN)
            header(HttpHeaders.Referrer, "$ORIGIN/")
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.AcceptLanguage, "${locale.hl},en;q=0.8")
            visitor?.let { header("X-Goog-Visitor-Id", it) }
            val prefs = "PREF=hl=${locale.hl}&gl=${locale.gl}"
            val cookie = cookie
            header(HttpHeaders.Cookie, if (cookie.isNullOrBlank() || auth == null) prefs else cookie)
            if (auth != null) {
                header(HttpHeaders.Authorization, sapisidHash(auth))
                header("X-Goog-AuthUser", "0")
            }
            setBody(TextContent(payload.toString(), ContentType.Application.Json))
        }
    }

    private suspend fun browseJson(browseId: String? = null, params: String? = null, continuation: String? = null) = post("browse") {
        browseId?.let { put("browseId", it) }
        params?.let { put("params", it) }
        continuation?.let { put("continuation", it) }
    }

    // ---- discovery -----------------------------------------------------------------------

    /**
     * YouTube's own home feed. Signed out, YouTube no longer serves one (it
     * returns "Your watch history is off"), so the [Feed.message] says so and
     * the app composes a home from local history, subscriptions and topics.
     */
    suspend fun home(): Feed = cache.get("home:$isSignedIn", ttlMs = 10 * MINUTE) { Pages.feed(browseJson(HOME)) }

    /** Any browse page as a feed: a topic channel, a "See all", a library list. */
    suspend fun browse(browseId: String, params: String? = null, fresh: Boolean = false): Feed {
        val key = "browse:$browseId:$params"
        if (fresh) cache.remove(key)
        return cache.get(key, ttlMs = 15 * MINUTE) { Pages.feed(browseJson(browseId, params)) }
    }

    /** The next page of any feed, or the reloaded feed for a chip. */
    suspend fun more(token: String): Feed = Pages.continuation(browseJson(continuation = token))

    /** A refresh needs the current page's chip token, not the one issued with yesterday's recommendations. */
    suspend fun homeFilter(title: String, token: String, fresh: Boolean): Feed {
        if (!fresh) return more(token)
        val home = browse(HOME, fresh = true)
        val current = home.chips.firstOrNull { it.title == title }?.token
            ?: throw YTException(0, "This Home filter is no longer available")
        return more(current).copy(chips = home.chips.map { it.copy(selected = it.title == title) })
    }

    suspend fun topic(topic: Topic, fresh: Boolean = false): Feed {
        val key = "topic:${topic.name}"
        if (fresh) cache.remove(key)
        return cache.get(key, ttlMs = 30 * MINUTE) { Pages.feed(browseJson(topic.browseId)) }
    }

    // ---- search --------------------------------------------------------------------------

    suspend fun search(query: String, params: String? = null): SearchPage =
        cache.get("search:$query:$params", ttlMs = 10 * MINUTE) {
            Pages.search(post("search") { put("query", query); params?.let { put("params", it) } })
        }

    suspend fun searchMore(token: String): Feed = Pages.continuation(post("search") { put("continuation", token) })

    suspend fun suggestions(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        val locale = innerTube.locale
        val response = http.get(SUGGEST) {
            parameter("client", "youtube")
            parameter("ds", "yt")
            parameter("hl", locale.hl)
            parameter("gl", locale.gl)
            parameter("q", input)
            header(HttpHeaders.UserAgent, USER_AGENT)
        }
        if (response.status.value !in 200..299) return emptyList()
        return Pages.suggestions(response.bodyAsText())
    }

    // ---- watching ------------------------------------------------------------------------

    /** The watch page: details, chapters, comments entry, related videos and the playlist panel. */
    suspend fun watch(videoId: String, playlistId: String? = null, index: Int? = null, origin: Video? = null, fresh: Boolean = false): WatchPage {
        val key = "watch:$videoId:$playlistId"
        if (fresh) cache.remove(key)
        return cache.get(key, ttlMs = 15 * MINUTE) {
            Pages.watch(
                videoId,
                post("next") {
                    put("videoId", videoId)
                    playlistId?.let { put("playlistId", it) }
                    index?.let { put("index", it) }
                    put("racyCheckOk", true)
                    put("contentCheckOk", true)
                },
                origin,
            )
        }
    }

    /** Related videos beyond the first page. */
    suspend fun relatedMore(token: String): Feed = Pages.continuation(post("next") { put("continuation", token) })

    suspend fun comments(token: String): CommentsPage = Pages.comments(post("next") { put("continuation", token) })

    // ---- channels & playlists ------------------------------------------------------------

    suspend fun channel(channelId: String, params: String? = null, fresh: Boolean = false): ChannelPage {
        val key = "channel:$channelId:$params"
        if (fresh) cache.remove(key)
        return cache.get(key, ttlMs = 15 * MINUTE) { Pages.channel(channelId, browseJson(channelId, params)) }
    }

    /** The signed-in bell inbox; pass back [InboxPage.continuation] for older ones. */
    suspend fun inbox(continuation: String? = null): InboxPage = Pages.inbox(post("notification/get_notification_menu") {
        if (continuation != null) put("ctoken", continuation) else put("notificationsMenuRequestType", "NOTIFICATIONS_MENU_REQUEST_TYPE_INBOX")
    })

    /** How many notifications arrived since the inbox was last opened, as the bell's badge shows. */
    suspend fun unseenNotifications(): Int =
        post("notification/get_unseen_count") {}.findFirst("unseenCount").int ?: 0

    /** The channel's About panel, from [ChannelPage.aboutToken]. */
    suspend fun channelAbout(token: String): ChannelAbout =
        cache.get("about:$token", ttlMs = 60 * MINUTE) { Pages.channelAbout(post("browse") { put("continuation", token) }) }

    /** Searches inside a channel, as its search tab does. */
    suspend fun channelSearch(channelId: String, query: String): Feed = Pages.feed(post("browse") {
        put("browseId", channelId)
        put("params", CHANNEL_SEARCH_PARAMS)
        put("query", query)
    })

    suspend fun playlist(id: String, fresh: Boolean = false): PlaylistPage {
        val plain = id.removePrefix("VL")
        if (fresh) cache.remove("playlist:$plain")
        return cache.get("playlist:$plain", ttlMs = 15 * MINUTE) { Pages.playlist(plain, browseJson("VL$plain")) }
    }

    // ---- shorts --------------------------------------------------------------------------

    /** The next batch of the endless Shorts sequence; pass back [ShortsBatch.continuation] to continue. */
    suspend fun shorts(continuation: String? = null): ShortsBatch =
        Pages.shortsSequence(post("reel/reel_watch_sequence") { put("sequenceParams", continuation ?: SHORTS_SEED) })

    /** The raw Shorts sequence, for research probes. */
    internal suspend fun shortsRaw(continuation: String? = null): JsonElement =
        post("reel/reel_watch_sequence") { put("sequenceParams", continuation ?: SHORTS_SEED) }

    /** Any InnerTube endpoint, raw, for research probes. */
    internal suspend fun raw(endpoint: String, body: JsonObjectBuilder.() -> Unit): JsonElement = post(endpoint, body)

    suspend fun shortInfo(videoId: String): ShortInfo =
        cache.get("short:$videoId", ttlMs = 15 * MINUTE) {
            kotlinx.coroutines.coroutineScope {
                // The sound credit is only in the Shorts player's own overlay; asked alongside, and optional.
                val reel = async {
                    runCatching {
                        post("reel/reel_item_watch") {
                            put("playerRequest", kotlinx.serialization.json.buildJsonObject { put("videoId", videoId) })
                            put("disablePlayerResponse", true)
                        }
                    }.getOrNull()
                }
                val info = Pages.shortInfo(videoId, post("next") { put("videoId", videoId); put("racyCheckOk", true); put("contentCheckOk", true) })
                info.copy(sound = reel.await()?.let(Pages::shortSound))
            }
        }

    /** The shorts that use a sound, from its [ShortSound.token] (and then the page's own continuation). */
    suspend fun soundPage(token: String): SoundPage =
        cache.get("sound:$token", ttlMs = 30 * MINUTE) { Pages.soundPage(post("browse") { put("continuation", token) }) }

    // ---- captions ------------------------------------------------------------------------

    /** What a player response carries besides streams: the caption tracks and the scrub storyboard. */
    private class PlayerExtras(val captions: List<CaptionTrack>, val storyboard: Storyboard?)

    /**
     * The WEB player is bot-checked without a PO token, so this asks the
     * clients InnerTubeX keeps working for playback, in turn, until one
     * answers with the video playable (its captions are then the real list).
     * Each gets [PLAYER_CLIENT_TIMEOUT_MS]. When none answers at all this
     * throws, so the empty result of a network outage isn't kept for an hour.
     */
    private suspend fun playerExtras(videoId: String): PlayerExtras = cache.get("player:$videoId", ttlMs = 60 * MINUTE) {
        ensureVisitorData()
        var captions = emptyList<CaptionTrack>()
        var storyboard: Storyboard? = null
        var answered = false
        for (client in PLAYER_CLIENTS) {
            val root = withTimeoutOrNull(PLAYER_CLIENT_TIMEOUT_MS) {
                runCatching { innerTube.player(client, videoId).json() }.getOrNull()
            } ?: continue
            answered = true
            if (captions.isEmpty()) captions = root.at("captions", "playerCaptionsTracklistRenderer", "captionTracks").arr.mapNotNull { t ->
                val url = t["baseUrl"].string ?: return@mapNotNull null
                CaptionTrack(
                    languageCode = t["languageCode"].string ?: return@mapNotNull null,
                    name = t["name"].text ?: t["languageCode"].string.orEmpty(),
                    isAuto = t["kind"].string == "asr",
                    url = url,
                )
            }
            if (storyboard == null) storyboard = Storyboard.parse(
                root.at("storyboards", "playerStoryboardSpecRenderer", "spec").string,
                root.at("videoDetails", "lengthSeconds").long,
            )
            if (root.at("playabilityStatus", "status").string == "OK") break
        }
        if (!answered) throw IOException("No player client answered for $videoId")
        PlayerExtras(captions, storyboard)
    }

    /** Caption tracks for [videoId]. */
    suspend fun captionTracks(videoId: String): List<CaptionTrack> = playerExtras(videoId).captions

    /** The scrub-preview storyboard of [videoId], or null for live streams and videos without one. */
    suspend fun storyboard(videoId: String): Storyboard? = playerExtras(videoId).storyboard

    /**
     * The HLS master playlist of an on-demand video, for Auto quality, or
     * null when no client offers one (bot-checked, unplayable, or a format
     * only the progressive streams carry).
     *
     * YouTube's iOS-family clients answer every video with one: variants from
     * 144p to 2160p in H.264 and VP9, the sound as separate renditions, all
     * served without a PO token and without the warm-up refusal fresh
     * progressive URLs get (measured on dQw4w9WgXcQ: segment 200 in 84 ms
     * right after the player answer). So the player can move up and down the
     * ladder with the connection, as YouTube's own Auto does.
     */
    suspend fun adaptiveManifest(videoId: String): AdaptiveManifest? = try {
        cache.get("hls:$videoId", ttlMs = 60 * MINUTE) {
            ensureVisitorData()
            var offline: java.io.IOException? = null
            fun CoroutineScope.ask(client: YouTubeClient) = async {
                val answer = runCatching { innerTube.player(client, videoId).json() }
                (answer.exceptionOrNull() as? java.io.IOException)?.let { offline = it }
                val root = answer.getOrNull() ?: return@async null
                if (root.at("playabilityStatus", "status").string != "OK") return@async null
                if (root.at("videoDetails", "isLive").string == "true") return@async null
                val url = root.at("streamingData", "hlsManifestUrl").string ?: return@async null
                AdaptiveManifest(url, client.userAgent)
            }
            // Hedged: the first client alone, the next joining if it hasn't answered in a moment
            // (or answered nothing), and the first manifest from either wins. One after the
            // other, a slow first answer held the picture back by its whole timeout.
            // Boxed, so "every client answered, none with a ladder" isn't mistaken for running out of time.
            val outcome = kotlinx.coroutines.withTimeoutOrNull(HLS_TIMEOUT_MS) {
                kotlinx.coroutines.coroutineScope {
                    val pending = ArrayList<kotlinx.coroutines.Deferred<AdaptiveManifest?>>()
                    var result: AdaptiveManifest? = null
                    for ((i, client) in HLS_CLIENTS.withIndex()) {
                        pending += ask(client)
                        val last = i == HLS_CLIENTS.lastIndex
                        // Wait for an answer from those asked so far: briefly, unless no one else is left to ask.
                        val deadline = if (last) Long.MAX_VALUE else HLS_HEDGE_MS
                        result = kotlinx.coroutines.withTimeoutOrNull(deadline) {
                            while (pending.isNotEmpty()) {
                                val (done, value) = kotlinx.coroutines.selects.select { pending.forEach { d -> d.onAwait { d to it } } }
                                pending.remove(done)
                                if (value != null) return@withTimeoutOrNull value
                            }
                            null
                        }
                        if (result != null) break
                    }
                    pending.forEach { it.cancel() }
                    listOf(result)
                }
            }
            outcome?.first()?.let { return@get it }
            // Not reaching YouTube (or not in time) says nothing about the video; that's for the caller to retry.
            offline?.let { throw it }
            if (outcome == null) throw java.io.IOException("No manifest answer for $videoId within ${HLS_TIMEOUT_MS / 1000} s")
            throw YTException(0, "no adaptive manifest")
        }
    } catch (e: YTException) {
        null
    }

    /** Forgets [videoId]'s manifest, so the next play asks again (after a refusal). */
    fun forgetAdaptiveManifest(videoId: String) = cache.remove("hls:$videoId")

    /** A raw /player answer from [client], for research probes and tests. */
    internal suspend fun rawPlayer(client: YouTubeClient, videoId: String): JsonElement {
        ensureVisitorData()
        return innerTube.player(client, videoId).json()
    }

    /** Cues of [track], optionally machine-translated to [translateTo]. */
    suspend fun captionCues(track: CaptionTrack, translateTo: String? = null): List<Cue> {
        // The track's URL comes from the player response; it's only ever fetched from YouTube.
        require(isYouTubeHttps(track.url)) { "Caption track URL isn't YouTube's" }
        val url = buildString {
            append(track.url.replace(Regex("[&?]fmt=[^&]*"), ""))
            append("&fmt=json3")
            translateTo?.let { append("&tlang=").append(it) }
        }
        val root = http.get(url) { header(HttpHeaders.UserAgent, USER_AGENT) }.json()
        return root["events"].arr.mapNotNull { e ->
            val text = e["segs"].arr.joinToString("") { it["utf8"].string.orEmpty() }.trim()
            if (text.isEmpty()) return@mapNotNull null
            val start = e["tStartMs"].long ?: return@mapNotNull null
            Cue(start, start + (e["dDurationMs"].long ?: 2000L), text)
        }
    }

    // ---- links ---------------------------------------------------------------------------

    /** What a youtube.com link points at (a video, a channel, a playlist), as the app's own links resolve. */
    suspend fun resolveUrl(url: String): ResolvedUrl? = cache.get("resolve:$url", ttlMs = 60 * MINUTE) {
        val endpoint = post("navigation/resolve_url") { put("url", url) }["endpoint"]
        val browse = endpoint["browseEndpoint"]
        val watch = endpoint["watchEndpoint"]
        when {
            watch != null -> ResolvedUrl(videoId = watch["videoId"].string, playlistId = watch["playlistId"].string)
            browse != null -> browse["browseId"].string?.let { id ->
                if (id.startsWith("VL")) ResolvedUrl(playlistId = id.removePrefix("VL")) else ResolvedUrl(browseId = id, params = browse["params"].string)
            }
            else -> null
        }
    }

    // ---- account & actions ---------------------------------------------------------------

    suspend fun account(): AccountInfo? {
        if (!isSignedIn) return null
        return Pages.accountInfo(post("account/account_menu") {})
    }

    suspend fun rate(videoId: String, status: LikeStatus) {
        val endpoint = when (status) {
            LikeStatus.LIKE -> "like/like"
            LikeStatus.DISLIKE -> "like/dislike"
            LikeStatus.INDIFFERENT -> "like/removelike"
        }
        post(endpoint) { putJsonObject("target") { put("videoId", videoId) } }
        cache.removeWhere { it.startsWith("watch:$videoId") || it.startsWith("short:$videoId") || it == "playlist:LL" }
    }

    suspend fun subscribe(channelId: String, subscribe: Boolean) {
        post(if (subscribe) "subscription/subscribe" else "subscription/unsubscribe") { putJsonArray("channelIds") { add(channelId) } }
        cache.removeWhere { it.startsWith("channel:$channelId") || it.startsWith("watch:") || it.startsWith("browse:$SUBSCRIPTIONS") }
    }

    /** Saves someone else's playlist to the library, or removes it. */
    suspend fun savePlaylist(playlistId: String, save: Boolean) {
        post(if (save) "like/like" else "like/removelike") { putJsonObject("target") { put("playlistId", playlistId.removePrefix("VL")) } }
    }

    /** Adds [videoId] to one of the user's playlists; [WATCH_LATER] works here too. */
    suspend fun addToPlaylist(playlistId: String, videoId: String) {
        post("browse/edit_playlist") {
            put("playlistId", playlistId)
            putJsonArray("actions") { add(buildJsonObject { put("addedVideoId", videoId); put("action", "ACTION_ADD_VIDEO") }) }
        }
        cache.remove("playlist:$playlistId")
    }

    suspend fun removeFromPlaylist(playlistId: String, videoId: String) {
        post("browse/edit_playlist") {
            put("playlistId", playlistId)
            putJsonArray("actions") { add(buildJsonObject { put("removedVideoId", videoId); put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID") }) }
        }
        cache.remove("playlist:$playlistId")
    }

    /** The user's playlists for the "Save to" sheet, each with whether it already holds [videoId]. */
    suspend fun saveToOptions(videoId: String): List<Pair<Playlist, Boolean>> =
        Pages.saveToOptions(post("playlist/get_add_to_playlist") { putJsonArray("videoIds") { add(videoId) }; put("excludeWatchLater", false) })

    /** Creates a playlist, optionally seeded with [videoId], and returns its id. */
    suspend fun createPlaylist(title: String, private: Boolean = true, videoId: String? = null): String? = post("playlist/create") {
        put("title", title)
        put("privacyStatus", if (private) "PRIVATE" else "PUBLIC")
        videoId?.let { id -> putJsonArray("videoIds") { add(id) } }
    }.at("playlistId").string

    // ---- playback reporting --------------------------------------------------------------

    /**
     * Where to report a signed-in viewing of [videoId], or null when signed
     * out or no player answered with the URLs.
     *
     * Streams come from clients that play without signing in, and the
     * tracking URLs in those answers file a viewing under nobody. So this
     * asks the player again as the account: as WEB, whose pings are the ones
     * history is built from, then as the TV client, which still answers when
     * WEB is bot-checked.
     */
    suspend fun playbackTracking(videoId: String): PlaybackTracking? {
        if (!isSignedIn) return null
        // A miss isn't kept: the next viewing asks again.
        return try {
            cache.get("tracking:$videoId", ttlMs = 30 * MINUTE) { lookUpTracking(videoId) ?: throw YTException(0, "no tracking") }
        } catch (_: YTException) {
            null
        }
    }

    private suspend fun lookUpTracking(videoId: String): PlaybackTracking? {
        val web = runCatching {
            post("player") { put("videoId", videoId); put("racyCheckOk", true); put("contentCheckOk", true) }
        }.getOrNull()
        tracking(web, YouTubeClient.WEB)?.let { return it }
        ensureVisitorData()
        val tv = runCatching { innerTube.player(YouTubeClient.TVHTML5, videoId).json() }.getOrNull()
        return tracking(tv, YouTubeClient.TVHTML5)
    }

    private fun tracking(root: JsonElement?, client: YouTubeClient): PlaybackTracking? {
        val tracking = root?.at("playbackTracking") ?: return null
        val playback = tracking.at("videostatsPlaybackUrl", "baseUrl").string ?: return null
        return PlaybackTracking(playback, tracking.at("videostatsWatchtimeUrl", "baseUrl").string, client)
    }

    /**
     * The start ping of [report]'s viewing, at [positionSec]. Returns the
     * HTTP status, or null if it couldn't be sent.
     */
    suspend fun reportStart(tracking: PlaybackTracking, report: WatchReport, positionSec: Double, short: Boolean): Int? =
        runCatching {
            innerTube.registerPlayback(tracking.client, statsUrl(tracking.playbackUrl, short), report.cpn, currentMediaTime = positionSec.fmt())
                .status.value
        }.getOrNull()

    /** One watch-time ping of [report]'s viewing. Returns the HTTP status, or null if it couldn't be sent. */
    suspend fun reportWatchtime(tracking: PlaybackTracking, report: WatchReport, ping: WatchPing, short: Boolean): Int? {
        val base = tracking.watchtimeUrl ?: return null
        val url = statsUrl(base, short) + "&state=" + if (ping.playing) "playing" else "paused"
        return runCatching {
            innerTube.registerWatchtimeWithSession(
                client = tracking.client,
                url = url,
                cpn = report.cpn,
                startTimes = ping.startTimes,
                endTimes = ping.endTimes,
                currentMediaTime = ping.currentMediaTime,
                realTime = ping.realTime,
                final = ping.final,
                format = null,
                requestSession = innerTube.sessionSnapshot(),
            ).status.value
        }.getOrNull()
    }

    /** Without `el`, YouTube files a viewing as a Short (yt-dlp found the same); a video says it was a watch page. */
    private fun statsUrl(base: String, short: Boolean): String =
        if (short || Regex("[?&]el=").containsMatchIn(base)) base else "$base&el=detailpage"

    companion object {
        /**
         * Whether [cookie] makes a signed-in session, as [isSignedIn] says once it's set: for
         * knowing at launch, before the client (and its HTTP stack) has been built.
         */
        fun isSignedInCookie(cookie: String?): Boolean = sapisidOf(cookie?.let(::sanitizeCookieString)) != null

        internal fun isYouTubeHttps(url: String): Boolean {
            val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            val host = parsed.host?.lowercase() ?: return false
            return parsed.scheme.equals("https", ignoreCase = true) && parsed.userInfo == null &&
                (parsed.port == -1 || parsed.port == 443) && (host == "youtube.com" || host.endsWith(".youtube.com"))
        }

        private fun sapisidOf(cookie: String?): String? =
            cookie?.split(';')?.map { it.trim() }?.associate { it.substringBefore('=') to it.substringAfter('=', "") }?.let {
                it["SAPISID"] ?: it["__Secure-3PAPISID"] ?: it["__Secure-1PAPISID"]
            }?.ifBlank { null }

        private const val MINUTE = 60_000L
        private const val RETRIES = 2
        /** Past this, a failing read isn't asked again; it's been long enough to show the error. */
        private const val RETRY_BUDGET_MS = 20_000L
        private const val REQUEST_TIMEOUT_MS = 15_000L
        private const val PLAYER_CLIENT_TIMEOUT_MS = 5_000L
        private const val VISITOR_BACKOFF_MS = 30_000L

        /** Endpoints that only read, so asking again can't do anything twice. */
        private val READS = setOf(
            "browse", "next", "search", "player", "reel/reel_watch_sequence", "reel/reel_item_watch", "navigation/resolve_url",
            "account/account_menu", "playlist/get_add_to_playlist",
            "notification/get_notification_menu", "notification/get_unseen_count",
        )
        private const val ORIGIN = "https://www.youtube.com"
        private const val API = "$ORIGIN/youtubei/v1"
        private const val SUGGEST = "https://suggestqueries-clients6.youtube.com/complete/search"
        const val WEB_VERSION = "2.20260708.05.00"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        private const val SHORTS_SEED = "CA8%3D"
        private const val CHANNEL_SEARCH_PARAMS = "EgZzZWFyY2jyBgQKAloA"
        private val HLS_CLIENTS = listOf(YouTubeClient.IOS, YouTubeClient.VISIONOS)
        private const val HLS_HEDGE_MS = 2_500L
        private const val HLS_TIMEOUT_MS = 20_000L
        private val PLAYER_CLIENTS = listOf(YouTubeClient.ANDROID_VR_1_65_10, YouTubeClient.IOS, YouTubeClient.VISIONOS, YouTubeClient.TVHTML5, YouTubeClient.WEB_EMBEDDED_PLAYER)

        const val HOME = "FEwhat_to_watch"
        const val SUBSCRIPTIONS = "FEsubscriptions"
        const val SUBSCRIBED_CHANNELS = "FEchannels"
        const val HISTORY = "FEhistory"
        const val PLAYLISTS = "FEplaylist_aggregation"
        const val WATCH_LATER = "WL"
        const val LIKED_VIDEOS = "LL"

        fun shareUrl(item: Item): String = when (item) {
            is Video -> if (item.isShort) "https://youtube.com/shorts/${item.id}" else "https://youtu.be/${item.id}"
            is Playlist -> "https://www.youtube.com/playlist?list=${item.id}"
            is app.oriel.core.model.Channel -> "https://www.youtube.com/channel/${item.id}"
            is app.oriel.core.model.Post -> "https://www.youtube.com/post/${item.id}"
        }
    }
}

/** YouTube's topic channels, which stand in for the signed-out home and the Explore tabs. */
enum class Topic(val title: String, val browseId: String) {
    MUSIC("Music", "UC-9-kyTW8ZkZNDHQJ6FgpwQ"),
    GAMING("Gaming", "UCOpNcN46UbXVtpKMrmU4Abg"),
    NEWS("News", "UCYfdidRxbB8Qhf0Nx7ioOYw"),
    SPORTS("Sports", "UCEgdi0XIXXZ-qJOFPf4JSKw"),
    LEARNING("Learning", "UCtFRv9O2AHqOZjjynzrv-xg"),
    LIVE("Live", "UC4R8DWoMoI7CAwX8_LjQHig"),
    FASHION("Fashion & Beauty", "UCrpQ4p1Ql_hG8rKXIKM1MOQ"),
}

/**
 * A small LRU of parsed pages with per-entry expiry and in-flight de-duplication.
 *
 * A load runs on the cache's own scope, not its first caller's: when that
 * caller goes away (its screen closed, its search superseded), the others
 * waiting on the same page still get it, and it's still kept for next time.
 */
internal class PageCache(private val capacity: Int) {
    private class Entry(val value: Any?, val expiresAt: Long)

    private val map = object : LinkedHashMap<String, Entry>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > capacity
    }
    private val lock = Any()
    private val inflight = HashMap<String, Deferred<Any?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Bumped by [clear], so a load begun before it (another account, another locale) isn't kept after. */
    private var generation = 0

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> get(key: String, ttlMs: Long, load: suspend () -> T): T {
        val deferred = synchronized(lock) {
            map[key]?.let { if (it.expiresAt > System.currentTimeMillis()) return it.value as T else map.remove(key) }
            inflight[key] ?: run {
                val started = generation
                // The lock is held until this is registered, so the load's own removal below comes after.
                lateinit var pending: Deferred<Any?>
                pending = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        load().also { value ->
                            synchronized(lock) { if (generation == started && inflight[key] === pending) map[key] = Entry(value, System.currentTimeMillis() + ttlMs) }
                        }
                    } finally {
                        synchronized(lock) { if (inflight[key] === pending) inflight.remove(key) }
                    }
                }
                pending.also { inflight[key] = it; it.start() }
            }
        }
        return deferred.await() as T
    }

    fun remove(key: String) { synchronized(lock) { map.remove(key); inflight.remove(key) } }
    fun removeWhere(predicate: (String) -> Boolean) { synchronized(lock) { map.keys.removeAll(predicate); inflight.keys.removeAll(predicate) } }
    fun clear() {
        synchronized(lock) {
            generation++
            map.clear()
            inflight.clear()
        }
    }
}
