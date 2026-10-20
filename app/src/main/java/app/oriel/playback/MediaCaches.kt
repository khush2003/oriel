package app.oriel.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import app.oriel.data.Settings
import java.io.File

/**
 * [stream] is an LRU of everything played: replays and backward seeks never
 * touch the network, and an evicted track simply streams again.
 */
@OptIn(UnstableApi::class)
class MediaCaches(context: Context, settings: Settings) {
    val database = StandaloneDatabaseProvider(context)

    val stream = SimpleCache(
        File(context.cacheDir, "media"),
        LeastRecentlyUsedCacheEvictor(settings.current.streamCacheMb.coerceAtLeast(128) * 1024L * 1024L),
        database,
    )

    fun streamCacheBytes(): Long = stream.cacheSpace

    fun clearStreamCache() {
        stream.keys.toList().forEach { runCatching { stream.removeResource(it) } }
    }
}
