package app.oriel.playback

import app.oriel.util.CrashLog
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.oriel.AppGraph
import app.oriel.core.stream.ResolvedStream
import app.oriel.core.stream.StreamRequest
import app.oriel.core.stream.StreamUnavailableException
import com.metrolist.innertubex.extraction.StreamResolveException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A video that can't be resolved to a stream; the message is user-facing. */
class StreamResolveIOException(val reason: StreamUnavailableException) : IOException(reason.message, reason)

/** A progressive request found the video is live: the item must be replayed as HLS. */
class LiveStreamIOException(val videoId: String) : IOException("Live stream")

/** YouTube couldn't be reached to resolve the stream: retried, but fewer times than a dropped read (each try is a full extraction). */
class ResolveNetworkIOException(cause: StreamUnavailableException) : IOException(cause.message, cause)

/** Asking again can't produce this item's media (no picture, a stream that ended); the message is user-facing. */
class UnplayableIOException(message: String) : IOException(message)

/**
 * The data-source chain every byte of playback goes through:
 *
 *   oriel:// URI → resolve (or find a complete cached copy, skipping the network)
 *     → stream cache (LRU, write-through)
 *     → chunked range requests → OkHttp, or the SABR reader for `sabr://` names
 *
 * Cache keys carry the itag (`a:<id>:<itag>`, `v:<id>:<itag>`), so bytes of
 * one format are never spliced with another's when a later resolve picks a
 * different client or quality. Live HLS skips the caches entirely: its
 * segments are never replayed and would only evict real content.
 */
@OptIn(UnstableApi::class)
class StreamDataSources(private val graph: AppGraph) {
    // Media gives up on a silent socket sooner than the API client does: eight seconds without a
    // byte is a dead connection on a train, and the loader's retry (OrielLoadErrorPolicy) picks up
    // from the same byte on a fresh one. Same connection pool, so nothing is re-handshaken.
    private val http = OkHttpDataSource.Factory(
        graph.okHttp.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build(),
    )

    /**
     * Network bytes: SABR names to the SABR reader, googlevideo URLs as chunked
     * range requests, each of which waits out a fresh URL's warm-up.
     */
    private val chunked = DataSource.Factory {
        val requests = WarmUpDataSource(http.createDataSource(), graph.resolver::streamForUrl)
        RoutingDataSource(SabrDataSource(graph.resolver), ChunkedDataSource(requests, ::chunkSizeFor))
    }

    private val streamCached = CacheDataSource.Factory()
        .setCache(graph.caches.stream)
        .setUpstreamDataSourceFactory(chunked)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    val playback: DataSource.Factory = ResolvingDataSource.Factory(streamCached, PlaybackResolver())

    /** Headers of the live stream last resolved, which its segment requests need too. */
    private val liveHeaders = ConcurrentHashMap<String, Map<String, String>>()

    val live: DataSource.Factory = ResolvingDataSource.Factory(http, LiveResolver())

    private fun chunkSizeFor(spec: DataSpec): Long {
        val host = spec.uri.host ?: return 0
        if (!host.endsWith("googlevideo.com")) return 0
        val stream = graph.resolver.streamForUrl(spec.uri.toString()) ?: return 0
        // InnerTubeX asks 512 KB (ANDROID_VR) or 1 MB (IOS) pieces for the clients whose URLs need
        // bounded ranges, without saying why that small. Each piece is a request that waits on the
        // last, so on a slow link they made stop-start buffering. yt-dlp reads these same clients'
        // URLs in 10 MiB ranges; 4 MiB keeps a seek's wasted bytes small at a fraction of the requests.
        return if (stream.requireBoundedRange) maxOf(stream.chunkSize, BOUNDED_CHUNK) else DEFAULT_CHUNK
    }

    private fun quality() = graph.settings.streamQuality(graph.network.metered.value)

    private fun resolve(request: StreamRequest): ResolvedStream = try {
        graph.resolver.resolveBlocking(request)
    } catch (e: StreamUnavailableException) {
        // Not reaching YouTube says nothing about the video: it's retried like a dropped
        // connection, where a resolve failure would skip to the next video.
        if (e.reason == StreamResolveException.Reason.NETWORK) throw ResolveNetworkIOException(e)
        CrashLog.nonFatal(graph.context, "Resolving ${request.videoId}", e)
        throw StreamResolveIOException(e)
    }

    private fun DataSpec.to(url: String, key: String?, stream: ResolvedStream): DataSpec = buildUpon()
        .setUri(url.toUri())
        .apply { if (key != null) setKey(key) }
        .setHttpRequestHeaders(httpRequestHeaders + stream.headers)
        .build()

    private inner class PlaybackResolver : ResolvingDataSource.Resolver {
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            val uri = dataSpec.uri
            if (uri.scheme != "oriel") return dataSpec
            val id = uri.pathSegments.firstOrNull() ?: throw UnplayableIOException("Malformed media URI")
            return when (uri.host) {
                "video" -> {
                    val height = uri.getQueryParameter("h")?.toIntOrNull() ?: MediaItems.DEFAULT_HEIGHT
                    val stream = resolve(StreamRequest(id, quality(), height))
                    if (stream.isHls) throw LiveStreamIOException(id)
                    val video = stream.video ?: throw UnplayableIOException("No picture for this video")
                    dataSpec.to(video.url, "v:$id:${video.itag}", stream)
                }
                else -> {
                    val height = uri.getQueryParameter("vh")?.toIntOrNull()
                    if (height == null) completeCachedKey("a:$id:")?.let { return dataSpec.buildUpon().setKey(it).build() }
                    val stream = resolve(StreamRequest(id, quality(), height))
                    if (stream.isHls) throw LiveStreamIOException(id)
                    dataSpec.to(stream.url, "a:$id:${stream.itag}", stream)
                }
            }
        }
    }

    private inner class LiveResolver : ResolvingDataSource.Resolver {
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            val uri = dataSpec.uri
            if (uri.scheme != "oriel") {
                // Variant playlists and segments: same session, same headers as the manifest.
                val headers = liveHeaders.values.lastOrNull() ?: return dataSpec
                return dataSpec.buildUpon().setHttpRequestHeaders(headers + dataSpec.httpRequestHeaders).build()
            }
            val id = uri.pathSegments.firstOrNull() ?: throw UnplayableIOException("Malformed media URI")
            val height = uri.getQueryParameter("h")?.toIntOrNull() ?: MediaItems.DEFAULT_HEIGHT
            val stream = resolve(StreamRequest(id, quality(), height))
            if (!stream.isHls) throw UnplayableIOException("This stream has ended")
            liveHeaders.clear()
            liveHeaders[id] = stream.headers
            return dataSpec.to(stream.url, null, stream)
        }
    }

    /** A key under which the stream cache holds a whole stream, so it can play with no network at all. */
    private fun completeCachedKey(prefix: String): String? {
        val cache = graph.caches.stream
        return cache.keys.firstOrNull { key ->
            key.startsWith(prefix) && run {
                val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
                length > 0 && cache.isCached(key, 0, length)
            }
        }
    }

    /**
     * Fetches the opening of a video into the stream cache, so the switch to it
     * starts from local bytes. Blocking; call from IO.
     */
    fun precache(videoId: String, height: Int?) {
        val stream = runCatching { graph.resolver.resolveBlocking(StreamRequest(videoId, quality(), height)) }.getOrNull() ?: return
        if (stream.isHls) return
        fun warm(url: String, key: String, bytes: Long) {
            val spec = DataSpec.Builder().setUri(url.toUri()).setKey(key).setPosition(0).setLength(bytes).setHttpRequestHeaders(stream.headers).build()
            runCatching { CacheWriter(streamCached.createDataSource(), spec, null, null).cache() }
        }
        warm(stream.url, "a:$videoId:${stream.itag}", precacheBytes(stream.bitrate, PRECACHE_AUDIO))
        stream.video?.let { warm(it.url, "v:$videoId:${it.itag}", precacheBytes(it.bitrate, PRECACHE_VIDEO)) }
    }

    internal companion object {
        private const val DEFAULT_CHUNK = 10L * 1024 * 1024
        private const val BOUNDED_CHUNK = 4L * 1024 * 1024
        const val PRECACHE_AUDIO = 256L * 1024
        const val PRECACHE_VIDEO = 1024L * 1024
        /** Twice the 1.5 s the player waits for before starting ([Abr.loadControl]), with the stream's index in front. */
        private const val PRECACHE_SECONDS = 3L
        private const val PRECACHE_MAX = 8L * 1024 * 1024

        /**
         * How much of a stream's opening to cache ahead: enough to start on at its bitrate. A fixed
         * megabyte was most of a second of 1080p VP9 but a third of one at 4K, so the next video
         * still waited on the network at the qualities that need it most. Never less than [floor].
         */
        fun precacheBytes(bitrate: Int?, floor: Long): Long =
            if (bitrate == null || bitrate <= 0) floor else (bitrate / 8L * PRECACHE_SECONDS).coerceIn(floor, maxOf(floor, PRECACHE_MAX))
    }
}
