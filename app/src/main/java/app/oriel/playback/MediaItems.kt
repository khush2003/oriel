package app.oriel.playback

import android.os.Bundle
import android.util.LruCache
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import app.oriel.core.model.ChannelRef
import app.oriel.core.model.Video
import app.oriel.util.AppJson
import app.oriel.util.Thumbnails

/**
 * Video ⇄ MediaItem.
 *
 * The URI names what to play, not where; it's resolved to googlevideo URLs
 * only when the player opens it (see [StreamDataSources]), so a playlist of a
 * thousand videos costs nothing until each one is reached:
 *
 * - `oriel://abr/<id>?h=1080`: Auto, YouTube's HLS ladder, the picture adapting to the
 *   connection up to 1080p (see [AdaptiveSources]);
 * - `oriel://av/<id>?h=720`: picture at up to 720p, fixed, merged with the best audio;
 * - `oriel://audio/<id>`: sound only, for listening in the background;
 * - `oriel://hls/<id>`: a live stream's HLS manifest.
 */
object MediaItems {
    const val EXTRA_VIDEO = "oriel.video"

    /** The URI host naming each kind of item; see the class comment. */
    const val KIND_ABR = "abr"
    const val KIND_AV = "av"
    const val KIND_AUDIO = "audio"
    const val KIND_HLS = "hls"

    /** The picture's height when an item names none. */
    const val DEFAULT_HEIGHT = 720

    /**
     * Decoded extras by their JSON. [video] is asked for on the main thread many times a second
     * (the queue, the transition listener, every recomposition that reads the current item), and
     * decoding the same string each time showed up as jank.
     */
    private val decoded = LruCache<String, Video>(512)

    fun build(video: Video, height: Int?, adaptive: Boolean = false): MediaItem {
        val builder = MediaItem.Builder().setMediaId(video.id).setMediaMetadata(metadata(video))
        when {
            video.isLive -> builder.setUri("oriel://$KIND_HLS/${video.id}${height?.let { "?h=$it" } ?: ""}".toUri()).setMimeType(MimeTypes.APPLICATION_M3U8)
            height != null && adaptive -> builder.setUri("oriel://$KIND_ABR/${video.id}?h=$height".toUri()).setMimeType(MimeTypes.APPLICATION_M3U8)
            height != null -> builder.setUri("oriel://$KIND_AV/${video.id}?h=$height".toUri())
            else -> builder.setUri("oriel://$KIND_AUDIO/${video.id}".toUri())
        }
        return builder.build()
    }

    /** The same item, switched to its live HLS manifest (a progressive request found it live). */
    fun asLive(item: MediaItem): MediaItem {
        val video = video(item)?.copy(isLive = true) ?: return item
        val height = item.localConfiguration?.uri?.getQueryParameter("h")?.toIntOrNull()
        return build(video, height)
    }

    /** The same item on the fixed-quality streams (its HLS ladder wasn't offered or was refused). */
    fun asProgressive(item: MediaItem): MediaItem {
        val video = video(item) ?: return item
        return build(video, height(item) ?: DEFAULT_HEIGHT)
    }

    fun metadata(video: Video): MediaMetadata = MediaMetadata.Builder()
        .setTitle(video.title)
        .setArtist(video.channel?.name)
        .setDisplayTitle(video.title)
        .setSubtitle(video.channel?.name)
        .setArtworkUri(Thumbnails.frame(video.id, 720).toUri())
        .setDurationMs(video.durationSec?.let { it * 1000L })
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
        .setExtras(Bundle().apply { putString(EXTRA_VIDEO, AppJson.encodeToString(Video.serializer(), video)) })
        .build()

    fun video(item: MediaItem?): Video? {
        item ?: return null
        item.mediaMetadata.extras?.getString(EXTRA_VIDEO)?.let { json ->
            decoded.get(json)?.let { return it }
            runCatching { AppJson.decodeFromString(Video.serializer(), json) }.getOrNull()?.let { decoded.put(json, it); return it }
        }
        if (item.mediaId.isBlank()) return null
        return Video(
            id = item.mediaId,
            title = item.mediaMetadata.title?.toString() ?: "",
            channel = item.mediaMetadata.artist?.toString()?.let { ChannelRef(it) },
        )
    }

    /** Rebuilds an item that arrived without its URI (from a controller, Android Auto, resumption). */
    fun restore(item: MediaItem, height: Int?): MediaItem =
        if (item.localConfiguration != null) item else video(item)?.let { build(it, height) } ?: item

    fun kind(item: MediaItem?): String? = item?.localConfiguration?.uri?.host

    fun hasPicture(item: MediaItem?): Boolean = kind(item).let { it == KIND_AV || it == KIND_HLS || it == KIND_ABR }

    fun height(item: MediaItem?): Int? = item?.localConfiguration?.uri?.getQueryParameter("h")?.toIntOrNull()
}
