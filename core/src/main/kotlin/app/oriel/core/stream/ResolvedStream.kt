package app.oriel.core.stream

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.sabr.SabrBootstrap

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
     * googlevideo answers a freshly minted URL with 403 for its first seconds (measured 3.2–4.3 s;
     * yt-dlp sees the same), then serves it. While true, a 403 means "not yet"; the window is
     * about twice the slowest measured.
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
