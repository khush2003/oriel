package app.oriel.core.stream

import app.oriel.core.YouTube
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.strategy.ClientFailureKind
import com.metrolist.innertubex.sabr.SabrBootstrap
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Small persistent key-value store the resolver keeps its remote cipher config in. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getLong(key: String): Long?
    fun putLong(key: String, value: Long)
}

enum class StreamQuality(val audio: AudioQuality) {
    LOW(AudioQuality.LOW),
    AUTO(AudioQuality.AUTO),
    HIGH(AudioQuality.HIGH),
}

/**
 * How media bytes travel. [CLASSIC] is a plain googlevideo URL read with
 * range requests. [SABR] is YouTube's newer server-driven protocol, which
 * its own players use. [AUTO] prefers classic URLs and falls back to SABR
 * when no client offers one.
 */
enum class StreamProtocol { AUTO, CLASSIC, SABR }

/** What to resolve: one video's audio, optionally with a video stream capped at [videoHeight]. */
data class StreamRequest(
    val videoId: String,
    val quality: StreamQuality = StreamQuality.HIGH,
    val videoHeight: Int? = null,
)

/** A playable stream and everything its media requests must carry. */
class ResolvedStream(
    val videoId: String,
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String?,
    val codecs: String?,
    val bitrate: Int?,
    val sampleRate: Int?,
    val contentLength: Long?,
    val itag: Int,
    val loudnessDb: Double?,
    val clientName: String,
    val profileId: String,
    val requireBoundedRange: Boolean,
    val chunkSize: Long,
    val expiresAtMs: Long,
    val video: VideoStream?,
    val availableHeights: List<Int>,
    val title: String?,
    val author: String?,
    val durationSec: Long?,
    val isLive: Boolean,
    /** Where to report this playback so it counts towards the signed-in user's history. */
    val playbackUrl: String?,
    val watchtimeUrl: String?,
    val cpn: String,
    /** Set when the bytes come over SABR; [url] and the video's URL are then `sabr://` names, not addresses. */
    val sabrAudio: SabrBootstrap? = null,
    val sabrVideo: SabrBootstrap? = null,
    /** When the resolve finished; see [isWarmingUp]. */
    val issuedAtMs: Long = System.currentTimeMillis(),
) {
    val isSabr: Boolean get() = sabrAudio != null

    /**
     * googlevideo refuses a freshly minted URL with 403 for its first few
     * seconds, then serves the very same URL normally. Measured: live 3.2 to
     * 4.3 s after the player response, and asking early doesn't push that
     * back. yt-dlp calls it the web clients' "mandatory wait period before
     * download", tied to preroll ads. A cold resolve spends that long in the
     * cipher and never notices; a warm one finishes in half a second and
     * does. A 403 while this is true means "not yet", not "never": ask again
     * shortly. The window is about twice the slowest measured warm-up: past
     * it, a 403 is a real refusal, and waiting longer only delays recovery.
     */
    fun isWarmingUp(now: Long = System.currentTimeMillis()): Boolean = now - issuedAtMs < WARM_UP_MS

    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAtMs

    /** Live streams and some premieres come as one HLS manifest carrying both audio and video. */
    val isHls: Boolean get() = mimeType == "application/x-mpegURL"
    val codecLabel: String get() = codecs ?: mimeType?.substringAfter('/') ?: "audio"

    override fun toString(): String = "ResolvedStream($videoId, itag=$itag, $codecLabel, ${bitrate?.div(1000)}kbps, client=$clientName, video=${video?.height}${if (isSabr) ", sabr" else ""})"

    companion object {
        /** How long after a resolve a 403 can still mean the URL isn't live yet. */
        const val WARM_UP_MS = 8_000L
    }
}

class VideoStream(
    val url: String,
    val itag: Int?,
    val width: Int?,
    val height: Int?,
    val mimeType: String?,
    val codecs: String?,
    val bitrate: Int?,
    val contentLength: Long?,
)

class StreamUnavailableException(val reason: StreamResolveException.Reason?, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns a videoId into a playable googlevideo URL.
 *
 * Extraction itself is InnerTubeX's: its client catalog and cipher tiers.
 * No proof-of-origin tokens are minted, so clients that require them are
 * left out. What lives here is everything that makes it feel
 * instant: results are cached until shortly before the URL expires, concurrent
 * asks for the same track share one extraction, upcoming tracks are resolved
 * ahead of time, and a client googlevideo refused is benched for that track.
 */
class StreamResolver(
    private val yt: YouTube,
    private val store: KeyValueStore? = null,
    playerCacheDir: File? = null,
    private val logger: InnerTubeLogger = InnerTubeLogger.NONE,
) {
    /** Ask for Premium formats (256 kbps audio) when signed in; YouTube grants them only to Premium accounts. */
    @Volatile
    var premium: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            dropStreams()
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /**
     * Resolved streams, most recently used last; the oldest go past [MAX_STREAMS]
     * and expired ones as they're looked up. Every access holds its lock.
     */
    private val cache = object : LinkedHashMap<StreamRequest, ResolvedStream>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<StreamRequest, ResolvedStream>?) = size > MAX_STREAMS
    }
    /**
     * Bumped whenever what's cached stops applying (another account, another
     * protocol or Premium setting): an extraction begun before keeps its result
     * to its own callers rather than caching it for everyone after.
     */
    @Volatile
    private var generation = 0
    private val inflight = ConcurrentHashMap<StreamRequest, Deferred<ResolvedStream>>()
    /**
     * The streams behind every URL handed out, for the data sources (chunk
     * size, warm-up) and refusal handling. Least recently used go first, so
     * the video playing now never loses its entry to a run of prefetches.
     */
    private val byUrl: MutableMap<String, ResolvedStream> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ResolvedStream>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedStream>?) = size > MAX_URLS
        },
    )
    /** Recent failures, so the audio and video loaders retrying one video don't each extract it again. */
    private val failures = ConcurrentHashMap<StreamRequest, Pair<Long, Throwable>>()
    private val excluded = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val playerDir = playerCacheDir?.apply { mkdirs() }

    /** Reads SABR formats for data sources, on a client without a total request timeout. */
    val sabr = SabrSource(yt.streamingHttp)

    /** Applies to resolves from now on; changing it drops what's cached. */
    @Volatile
    var protocol: StreamProtocol = StreamProtocol.AUTO
        set(value) {
            if (field == value) return
            field = value
            dropStreams()
        }

    /**
     * The cipher runs on YouTube's own player script alone, never on code or configs from
     * anyone else. InnerTubeX can also take a "player config" table and, from it, a ready-made
     * preprocessed player fetched from a third party's GitHub repository (via jsDelivr), which it
     * runs in QuickJS and caches on disk: whoever controls that repository or CDN would have
     * their JavaScript run in the app's process next to the sign-in cookie. Passing no config
     * store turns all of that off; the bundled yt-dlp solver preprocesses YouTube's player here.
     */
    private val cipher = YouTubeCipherService(yt.http, remotePlayerConfigStore = null, logger = logger)
    private val health = ClientHealth()
    /** One for the resolver's life, wrapped in a disk copy of the watch page's config. */
    private val configParser = YtConfigParserImpl(yt.http, yt.innerTube, logger = logger, cipherService = cipher).let { parser ->
        if (store == null) parser else DiskCachedConfigParser(parser, store, ::configIdentity)
    }

    private val extractor = InnerTubeExtractor(
        configParser = configParser,
        cipherService = cipher,
        innerTube = yt.innerTube,
        clientHealthMonitor = health,
        logger = logger,
    )

    /** The locale, and with [login] the account (a hash of its cookie, never the cookie), a watch page answered for. */
    private fun configIdentity(login: Boolean): String {
        val locale = yt.innerTube.locale
        val account = yt.cookie?.takeIf { login && it.isNotBlank() }?.let { cookie ->
            MessageDigest.getInstance("SHA-256").digest(cookie.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        return "${locale.gl}:${locale.hl}:${account.orEmpty()}"
    }

    init {
        if (playerDir != null) {
            scope.launch {
                forgetUntrustedPlayers(playerDir)
                cipher.setPreprocessedPlayerCache(
                    read = { key -> playerFile(playerDir, key)?.takeIf { it.isFile }?.readText() },
                    write = { key, value -> writePlayer(key, value) },
                )
            }
        }
    }

    /**
     * Earlier versions cached preprocessed players fetched from a third party next to the ones
     * made here, under the same names. They're deleted once, so none of them is ever run again.
     */
    private fun forgetUntrustedPlayers(dir: File) {
        if (store == null || store.getLong(KEY_PLAYERS_LOCAL_ONLY) != null) return
        dir.listFiles()?.forEach { it.delete() }
        store.putLong(KEY_PLAYERS_LOCAL_ONLY, 1L)
    }

    private fun writePlayer(key: String, value: String?) {
        val dir = playerDir ?: return
        val file = playerFile(dir, key) ?: return
        if (value == null) { file.delete(); return }
        val tmp = File(dir, "$key.tmp")
        tmp.writeText(value)
        tmp.renameTo(file)
        // Players rotate every few days; keep only the newest few.
        dir.listFiles()?.filterNot { it.name.endsWith(".tmp") }?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
    }

    /** Pays the cold costs (visitor data, player config, cipher solver) before the first play. */
    fun prewarm() {
        scope.launch {
            runCatching {
                yt.ensureVisitorData()
                extractor.prewarm()
            }
        }
    }

    /** A cached, unexpired stream for [request], without waiting. */
    fun cached(request: StreamRequest): ResolvedStream? = synchronized(cache) {
        val stream = cache[request] ?: return null
        if (stream.isExpired) null.also { cache.remove(request) } else stream
    }

    fun streamForUrl(url: String): ResolvedStream? = byUrl[url]

    /**
     * Resolves [request], sharing one extraction among everyone who asks for it at once.
     *
     * The extraction runs in the resolver's own scope rather than the first caller's: a loader
     * that is cancelled (its item replaced, a short scrolled past) only stops waiting, and the
     * others sharing the work still get the stream. A whole pass is held to [RESOLVE_TIMEOUT_MS];
     * past that it fails as a network failure, which the player retries, instead of keeping the
     * spinner up while one wedged request after another times out.
     */
    suspend fun resolve(request: StreamRequest): ResolvedStream {
        cached(request)?.let { return it }
        failures[request]?.let { (at, error) ->
            if (System.currentTimeMillis() - at < FAILURE_TTL_MS) throw error
            failures.remove(request)
        }
        // Lazy, and started once it's in the map: work that finished before it was even put
        // there would leave its result behind for the next caller after its removal ran.
        val work = inflight.computeIfAbsent(request) {
            val started = generation
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    val stream = try {
                        withTimeout(RESOLVE_TIMEOUT_MS) { extract(request) }
                    } catch (e: TimeoutCancellationException) {
                        throw StreamUnavailableException(
                            StreamResolveException.Reason.NETWORK,
                            friendlyMessage(StreamResolveException.Reason.NETWORK, null),
                            IOException("Resolving ${request.videoId} took over ${RESOLVE_TIMEOUT_MS / 1000} s", e),
                        )
                    }
                    synchronized(cache) {
                        if (generation == started) {
                            cache[request] = stream
                            failures.remove(request)
                            byUrl[stream.url] = stream
                            stream.video?.url?.let { byUrl[it] = stream }
                        }
                    }
                    stream
                } catch (t: Throwable) {
                    if (t !is CancellationException && generation == started) failures[request] = System.currentTimeMillis() to t
                    throw t
                }
            }.also { d -> d.invokeOnCompletion { inflight.remove(request, d) } }
        }
        work.start()
        return work.await()
    }

    /** For data sources, which run on loader threads and can afford to block. */
    fun resolveBlocking(request: StreamRequest): ResolvedStream = runBlocking { resolve(request) }

    /** Starts resolving [request] in the background so a later [resolve] finds it ready. */
    fun prefetch(request: StreamRequest) {
        if (cached(request) != null || inflight.containsKey(request)) return
        scope.launch { runCatching { resolve(request) } }
    }

    fun invalidate(videoId: String) {
        synchronized(cache) { cache.keys.removeAll { it.videoId == videoId } }
        failures.keys.removeAll { it.videoId == videoId }
    }

    /**
     * googlevideo refused [url] (401/403) mid-playback: forget it, bench the
     * client that minted it for this track, and let the next resolve pick another.
     */
    fun onRefused(url: String): String? {
        val stream = byUrl.remove(url) ?: return null
        health.recordFailure(stream.clientId, ClientFailureKind.MEDIA_FORBIDDEN)
        synchronized(cache) { cache.entries.removeAll { it.value === stream } }
        excluded.getOrPut(stream.videoId) { ConcurrentHashMap() }[stream.profileId] = System.currentTimeMillis() + EXCLUDE_MS
        scope.launch { runCatching { cipher.refreshAfterStreamRejection() } }
        return stream.videoId
    }

    /**
     * [videoId] is playing: the client that resolved it works now, which
     * only playback can tell (a playable answer can still be refused by the CDN).
     */
    fun onPlaying(videoId: String) {
        val stream = synchronized(cache) { cache.entries.firstOrNull { it.key.videoId == videoId }?.value } ?: return
        health.recordSuccess(stream.clientId)
    }

    /** InnerTubeX's manifest id of the client behind [this]; its profile id adds whether a PO token was used. */
    private val ResolvedStream.clientId: String get() = profileId.substringBefore("__")

    /**
     * Forgets every stream resolved so far, and lets go of extractions under
     * way: their results reach whoever is already waiting, but aren't cached
     * or shared with anyone asking from now on.
     */
    private fun dropStreams() {
        synchronized(cache) {
            generation++
            cache.clear()
            byUrl.clear()
        }
        inflight.clear()
    }

    fun onSessionChanged() {
        dropStreams()
        failures.clear()
        excluded.clear()
        prewarm()
    }

    private fun excludedFor(videoId: String): Set<String> {
        val entries = excluded[videoId] ?: return emptySet()
        val now = System.currentTimeMillis()
        entries.entries.removeAll { it.value <= now }
        return entries.keys.toSet()
    }

    /** The extractor's raw answer for [hints], for research probes and tests. */
    internal suspend fun extractRaw(videoId: String, hints: ContentHints, quality: StreamQuality = StreamQuality.HIGH) =
        extractor.extract(videoId = videoId, hints = hints, excludedClients = emptySet(), audioQuality = quality.audio, clientPlaybackNonce = generateClientPlaybackNonce())

    private suspend fun extract(request: StreamRequest): ResolvedStream {
        yt.ensureVisitorData()
        val wantVideo = request.videoHeight != null
        val cpn = generateClientPlaybackNonce()
        suspend fun attempt(sabr: Boolean) = extractor.extract(
            videoId = request.videoId,
            hints = ContentHints(wantVideo = wantVideo, maxVideoHeight = request.videoHeight, sabrFirst = sabr)
                .withStreamCapabilities(allowHls = true, allowSabr = sabr, allowBoundedRange = true)
                .withPremium(premium && yt.isSignedIn),
            excludedClients = excludedFor(request.videoId),
            audioQuality = request.quality.audio,
            clientPlaybackNonce = cpn,
        )
        suspend fun pick() = when (protocol) {
            StreamProtocol.CLASSIC -> attempt(sabr = false)
            StreamProtocol.SABR -> attempt(sabr = true)
            // Classic URLs first, the longest-proven path; SABR when no client offers a usable one.
            // Not when YouTube couldn't be reached at all: SABR would make the same failing requests.
            StreamProtocol.AUTO -> {
                val classic = try {
                    attempt(sabr = false)
                } catch (e: StreamResolveException) {
                    if (e.isUnreachable) throw e
                    runCatching { attempt(sabr = true) }.getOrNull() ?: throw e
                }
                val usable = classic != null && (!wantVideo || classic.videoUrl != null || classic.mimeType == "application/x-mpegURL")
                if (usable) classic else runCatching { attempt(sabr = true) }.getOrNull() ?: classic
            }
        }
        val stream = try {
            try {
                pick()
            } catch (e: StreamResolveException) {
                // Every client this video could use was benched after a refusal. A refusal says that
                // URL went bad, not that the client can never play the video: try them all again
                // rather than calling it unplayable for ten minutes.
                if (excludedFor(request.videoId).isEmpty()) throw e
                excluded.remove(request.videoId)
                pick()
            }
        } catch (e: StreamResolveException) {
            // InnerTubeX files every unexpected failure (a parse error, a bot check) under NETWORK.
            // Only a failure that is a connection problem should be retried like one.
            if (e.reason == StreamResolveException.Reason.NETWORK && !e.isUnreachable) {
                throw StreamUnavailableException(StreamResolveException.Reason.UNKNOWN, describe(e), e)
            }
            throw StreamUnavailableException(e.reason, if (e.isUnreachable) friendlyMessage(e.reason, e.message) else describe(e), e)
        } ?: throw StreamUnavailableException(null, "No playable stream for this video")

        val hls = stream.mimeType == "application/x-mpegURL"
        if (wantVideo && stream.videoUrl == null && stream.sabrVideoBootstrap == null && !hls) {
            throw StreamUnavailableException(StreamResolveException.Reason.NO_PLAYABLE_STREAM, "No video stream for this video")
        }
        val metadata = stream.mediaMetadata
        val sabrAudio = stream.sabrBootstrap
        val sabrVideo = stream.sabrVideoBootstrap?.takeIf { wantVideo }
        // SABR has no URL to name; these stand in, one per format, so caches and lookups still key on them.
        val videoUrl = if (sabrAudio != null) sabrVideo?.let { "sabr://${request.videoId}/v/${stream.videoItag}" } else stream.videoUrl
        return ResolvedStream(
            videoId = request.videoId,
            url = if (sabrAudio != null) "sabr://${request.videoId}/a/${stream.itag}" else stream.audioUrl,
            headers = stream.headers,
            mimeType = stream.mimeType,
            codecs = stream.codecs,
            bitrate = stream.bitrate,
            sampleRate = stream.sampleRate,
            contentLength = stream.contentLengthBytes,
            itag = stream.itag,
            loudnessDb = stream.loudnessDb ?: stream.perceptualLoudnessDb,
            clientName = stream.clientName,
            profileId = stream.profileId,
            requireBoundedRange = stream.requireBoundedRange || stream.useRangeChunks,
            chunkSize = stream.rangeChunkSizeBytes.takeIf { it > 0 } ?: DEFAULT_CHUNK,
            // Leave a margin: a URL that expires mid-buffer costs a stall and a re-resolve.
            expiresAtMs = (stream.expiresAt?.toEpochMilliseconds() ?: (System.currentTimeMillis() + DEFAULT_TTL_MS)) - EXPIRY_MARGIN_MS,
            video = videoUrl?.let {
                VideoStream(it, stream.videoItag, stream.videoWidth, stream.videoHeight, stream.videoMimeType, stream.videoCodecs, stream.videoBitrate, stream.videoContentLengthBytes)
            },
            availableHeights = stream.availableVideoHeights.distinct().sorted(),
            title = metadata?.title,
            author = metadata?.author,
            durationSec = metadata?.durationSeconds,
            isLive = metadata?.isLive == true || hls,
            playbackUrl = stream.playbackTracking?.playbackUrl,
            watchtimeUrl = stream.playbackTracking?.watchtimeUrl,
            cpn = stream.playbackTracking?.clientPlaybackNonce ?: cpn,
            sabrAudio = sabrAudio,
            sabrVideo = sabrVideo,
        )
    }

    /** YouTube couldn't be reached: a connection failure, not an answer, is behind this. */
    private val StreamResolveException.isUnreachable: Boolean
        get() = reason == StreamResolveException.Reason.NETWORK && generateSequence(cause) { it.cause }.any { it is IOException }

    companion object {
        internal const val BOT_CHECK = "YouTube is asking this network to confirm it isn't a bot. Try another network, or sign in."
        internal const val SIGN_IN_WANTED = "YouTube wants a sign-in to play this. The video may be private, or YouTube may be asking this network to confirm it isn't a bot."
        internal const val PRIVATE = "This video is private."
        internal const val REMOVED = "This video has been removed."
        internal const val NOT_IN_COUNTRY = "This video isn't available in your country."

        /**
         * Why [e] failed, in words for the user. InnerTubeX (0.10) keeps YouTube's own reason text
         * to itself: its messages are fixed ("This track is unavailable.") and each attempt's
         * outcome carries only the playability status ("playability:LOGIN_REQUIRED"). So the
         * message is searched for YouTube's wording in case a version passes it on. A bare
         * LOGIN_REQUIRED is either a private video or the bot check, so the message names both.
         * That is only reported: nothing here tries to get past it.
         */
        internal fun describe(e: StreamResolveException): String {
            val text = e.message.orEmpty().lowercase()
            val statuses = e.diagnostics?.attempts.orEmpty().map { it.outcome }.filter { it.startsWith(PLAYABILITY) }.map { it.removePrefix(PLAYABILITY).uppercase() }
            return when {
                BOT_WORDS.any { it in text } -> BOT_CHECK
                "private" in text -> PRIVATE
                REMOVED_WORDS.any { it in text } -> REMOVED
                "country" in text || "region" in text -> NOT_IN_COUNTRY
                "LOGIN_REQUIRED" in statuses && e.reason != StreamResolveException.Reason.AGE_RESTRICTED &&
                    e.reason != StreamResolveException.Reason.UNAVAILABLE -> SIGN_IN_WANTED
                e.reason == StreamResolveException.Reason.NETWORK -> friendlyMessage(StreamResolveException.Reason.UNKNOWN, null)
                else -> friendlyMessage(e.reason, e.message)
            }
        }

        private fun friendlyMessage(reason: StreamResolveException.Reason, fallback: String?): String = when (reason) {
            StreamResolveException.Reason.AGE_RESTRICTED -> "This video is age-restricted. Sign in to play it."
            StreamResolveException.Reason.UNAVAILABLE -> "This video isn't available in your region or was removed."
            StreamResolveException.Reason.NETWORK -> "Couldn't reach YouTube. Check your connection."
            StreamResolveException.Reason.NO_MUSIC_VIDEO -> "No video stream for this video."
            else -> fallback ?: "This video can't be played right now."
        }

        private const val PLAYABILITY = "playability:"
        private val BOT_WORDS = listOf("not a bot", "confirm you're not", "confirm you\u2019re not")
        private val REMOVED_WORDS = listOf("removed", "terminated", "deleted")

        private const val KEY_PLAYERS_LOCAL_ONLY = "players.local_only"
        private val PLAYER_KEY = Regex("[A-Za-z0-9_-]{1,128}")

        /** [key]'s file in [dir]; null for a name that isn't a plain file name, so none can reach outside it. */
        internal fun playerFile(dir: File, key: String): File? = if (PLAYER_KEY.matches(key)) File(dir, key) else null

        private const val EXCLUDE_MS = 10 * 60_000L
        private const val DEFAULT_TTL_MS = 5 * 60 * 60_000L
        private const val EXPIRY_MARGIN_MS = 10 * 60_000L
        private const val DEFAULT_CHUNK = 1L shl 20
        private const val MAX_URLS = 512
        private const val MAX_STREAMS = 100
        private const val FAILURE_TTL_MS = 5_000L
        private const val RESOLVE_TIMEOUT_MS = 25_000L
    }
}
