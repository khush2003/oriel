package app.oriel.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import kotlin.math.min

/**
 * When a load fails, whether to try the same request again and how soon.
 *
 * Media3's default retries nearly everything three times, a second apart and
 * then two, which is wrong both ways for Oriel. Some failures can't get
 * better by asking again: an expired or refused googlevideo URL (the resolver
 * would hand back the same one), a video that turned out to be live, a video
 * that can't be played at all. Those go straight to the player's error
 * handling, which re-resolves, switches to HLS or moves on, in a fraction of
 * the time three doomed retries take. A SABR read only belongs there when
 * the server refused it; one whose connection dropped is the network's. Everything else is the network being
 * the network (a dropped packet, a stalled tunnel, a cell handover), and
 * those retry quickly at first and keep at it for half a minute before the
 * player is told, so a choppy connection reads as a moment's buffering rather
 * than an error.
 */
@OptIn(UnstableApi::class)
class OrielLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(RETRIES) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        if (isFinal(loadErrorInfo.exception)) return C.TIME_UNSET
        // A failed resolve already spent seconds on several clients; two more tries, spaced past
        // the resolver's memory of the failure, then the player's own recovery takes over.
        if (generateSequence<Throwable>(loadErrorInfo.exception) { it.cause }.any { it is ResolveNetworkIOException }) {
            return if (loadErrorInfo.errorCount > RESOLVE_RETRIES) C.TIME_UNSET else RESOLVE_DELAY_MS * loadErrorInfo.errorCount
        }
        val fallback = super.getRetryDelayMsFor(loadErrorInfo)
        if (fallback == C.TIME_UNSET) return C.TIME_UNSET
        // 250 ms, 500, 1 s, 2 s, then every 4 s.
        val n = (loadErrorInfo.errorCount - 1).coerceIn(0, 4)
        return min(FIRST_DELAY_MS shl n, MAX_DELAY_MS)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = RETRIES

    companion object {
        private const val RETRIES = 10
        private const val FIRST_DELAY_MS = 250L
        private const val MAX_DELAY_MS = 4_000L
        private const val RESOLVE_RETRIES = 2
        /** Past the resolver's five seconds of remembering a failure, so a retry really asks again. */
        private const val RESOLVE_DELAY_MS = 5_500L

        /** Failures that asking again can't fix; see the class comment. */
        fun isFinal(error: Throwable): Boolean = generateSequence(error) { it.cause }.any { e ->
            e is LiveStreamIOException || e is StreamResolveIOException || e is UnplayableIOException || e is AdaptiveUnavailableIOException ||
                (e is SabrIOException && e.refused) ||
                (e is HttpDataSource.InvalidResponseCodeException && e.responseCode in FINAL_HTTP)
        }

        private val FINAL_HTTP = setOf(401, 403, 404, 410, 416)
    }
}
