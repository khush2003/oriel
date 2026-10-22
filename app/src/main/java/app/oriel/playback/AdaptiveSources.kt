package app.oriel.playback

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.oriel.AppGraph
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.TimeUnit

/** No client offered [videoId] an HLS ladder: Auto plays it from the fixed-quality streams instead. */
class AdaptiveUnavailableIOException(val videoId: String) : IOException("No adaptive stream")

/**
 * Auto quality's bytes: `oriel://abr/<id>` resolves to the video's HLS master
 * playlist ([app.oriel.core.YouTube.adaptiveManifest]), and its variant
 * playlists and segments follow with the same client's user agent. The
 * player's own track selection then moves between the variants as the
 * measured bandwidth allows, capped by [QueueController]'s height for the
 * network, which is what YouTube's Auto does and what the merged progressive
 * streams can't: those are one fixed height until the item is rebuilt.
 *
 * Segments go through the stream cache, so seeking back replays from disk.
 */
@OptIn(UnstableApi::class)
class AdaptiveSources(private val graph: AppGraph) {
    /**
     * The user agent per stream, keyed by the googlevideo `id` that its master playlist, variant
     * playlists and segments all carry in their paths. One shared value let the Shorts player,
     * its preloads and the main player overwrite each other's, and a segment fetched under
     * another client's agent is refused.
     */
    private val agents: MutableMap<String, String> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_AGENTS
        },
    )
    @Volatile private var lastAgent: String? = null

    private fun streamId(uri: android.net.Uri): String? {
        val segments = uri.pathSegments
        val i = segments.indexOf("id")
        return segments.getOrNull(i + 1)?.takeIf { i >= 0 } ?: uri.getQueryParameter("id")
    }

    private val http = OkHttpDataSource.Factory(
        graph.okHttp.newBuilder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build(),
    )

    val dataSource: DataSource.Factory = ResolvingDataSource.Factory(
        CacheDataSource.Factory()
            .setCache(graph.caches.stream)
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
        Resolver(),
    )

    private inner class Resolver : ResolvingDataSource.Resolver {
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            val uri = dataSpec.uri
            if (uri.scheme != "oriel") {
                val ua = streamId(uri)?.let(agents::get) ?: lastAgent ?: return dataSpec
                return dataSpec.buildUpon().setHttpRequestHeaders(dataSpec.httpRequestHeaders + ("User-Agent" to ua)).build()
            }
            val id = uri.pathSegments.firstOrNull() ?: throw IOException("Malformed media URI")
            // A network failure is an IOException as it is and gets retried; a missing ladder is final.
            val manifest = runBlocking { graph.yt.adaptiveManifest(id) } ?: throw AdaptiveUnavailableIOException(id)
            lastAgent = manifest.userAgent
            streamId(manifest.url.toUri())?.let { agents[it] = manifest.userAgent }
            return dataSpec.buildUpon()
                .setUri(manifest.url.toUri())
                .setHttpRequestHeaders(dataSpec.httpRequestHeaders + ("User-Agent" to manifest.userAgent))
                .build()
        }
    }

    private companion object {
        /** Streams whose agent is remembered; the oldest go first, never the one playing. */
        const val MAX_AGENTS = 200
    }
}
