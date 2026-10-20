package app.oriel.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import app.oriel.util.VideoCaps
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

/**
 * How Auto picks a rung, shared by the main player and Shorts.
 *
 * Media3's defaults start every session on a guess from the country (often 360p-720p on a
 * fast line) and climb only after ten seconds buffered. Here the first pick comes from
 * what this network actually measured last time (kept per Wi-Fi and per mobile), and a
 * higher rung is taken after five seconds in hand, so a video settles at its best within
 * moments rather than half a minute. One meter serves both players, so a Short's
 * measurement helps the next video and the other way round.
 */
@UnstableApi
class Abr(context: Context) {
    private val prefs = context.getSharedPreferences("abr", Context.MODE_PRIVATE)

    val meter: DefaultBandwidthMeter = DefaultBandwidthMeter.Builder(context).apply {
        val wifi = prefs.getLong(KEY_WIFI, DEFAULT_WIFI)
        val cell = prefs.getLong(KEY_CELL, DEFAULT_CELL)
        for (t in WIFI_TYPES) setInitialBitrateEstimate(t, wifi)
        for (t in CELL_TYPES) setInitialBitrateEstimate(t, cell)
        // A switch between Wi-Fi and mobile starts from that network's own estimate.
        setResetOnNetworkTypeChange(true)
    }.build()

    /**
     * Media3 adapts within one codec, and given H.264 and VP9 ladders that both stop at the
     * screen's height it takes H.264, which has more rungs. YouTube's VP9 carries more than
     * twice the bitrate at the same height, so where VP9 decodes in hardware it's asked for
     * first. Above 1080p the ladder is VP9 alone, so a 1440p or 4K screen gets it either way.
     */
    fun trackSelector(context: Context, preferVp9: Boolean = VideoCaps.hardwareVp9()): DefaultTrackSelector =
        DefaultTrackSelector(
            context,
            AdaptiveTrackSelection.Factory(
                /* minDurationForQualityIncreaseMs = */ 5_000,
                /* maxDurationForQualityDecreaseMs = */ 20_000,
                /* minDurationToRetainAfterDiscardMs = */ 20_000,
                /* bandwidthFraction = */ 0.8f,
            ),
        ).apply {
            if (preferVp9) setParameters(buildUponParameters().setPreferredVideoMimeTypes(MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_H264))
        }

    /** Remembers this network's measured bandwidth for the next session's first pick. */
    fun save(metered: Boolean) {
        val estimate = meter.bitrateEstimate
        if (estimate <= 0) return
        // A little under what was measured: a first pick that stalls costs more than one a rung low.
        prefs.edit().putLong(if (metered) KEY_CELL else KEY_WIFI, (estimate * 0.85).toLong().coerceIn(MIN, MAX)).apply()
    }

    companion object {
        private const val KEY_WIFI = "wifi"
        private const val KEY_CELL = "cell"
        private const val DEFAULT_WIFI = 12_000_000L
        private const val DEFAULT_CELL = 5_000_000L
        private const val MIN = 1_000_000L
        private const val MAX = 80_000_000L
        private val WIFI_TYPES = intArrayOf(C.NETWORK_TYPE_WIFI, C.NETWORK_TYPE_ETHERNET, C.NETWORK_TYPE_OTHER, C.NETWORK_TYPE_UNKNOWN)
        private val CELL_TYPES = intArrayOf(C.NETWORK_TYPE_5G_SA, C.NETWORK_TYPE_5G_NSA, C.NETWORK_TYPE_4G, C.NETWORK_TYPE_3G, C.NETWORK_TYPE_2G, C.NETWORK_TYPE_CELLULAR_UNKNOWN)

        /**
         * Starts on a second and a half, then buffers a minute ahead, bounded by bytes as well
         * as time: at 1440p and up, a time-only bound of two minutes was hundreds of MB of heap.
         * After a stall, three seconds in hand before resuming. Fifteen seconds behind are kept,
         * so a ten-second skip back plays from memory.
         */
        fun loadControl(): LoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 60_000, 1_500, 3_000)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .setBackBuffer(15_000, true)
            .build()
    }
}
