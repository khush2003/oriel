package app.oriel.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.LiveTestApp
import app.oriel.OrielApp
import app.oriel.core.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Auto quality, played: the player built as [PlaybackService] builds it (the shared [Abr]
 * meter, its track selector and load control, the app's media sources) plays YouTube's live
 * HLS ladder through Media3's fake decoders. Robolectric's clock is advanced by the real time
 * that passes, so loads take as long as the network makes them and the bandwidth meter
 * measures this connection. Records how long the start takes, every rebuffer, and each rung
 * the selector moves to; asserts a quick start, no stall once playing, and that Auto reaches
 * the top of the ladder under the cap.
 *
 * Video only: Robolectric's AudioTrack doesn't advance, which would stall the audio clock,
 * so audio is switched off and the video plays against the standalone clock.
 * Opt-in: `-Plive=true`; `-Pids=…` picks the video.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = LiveTestApp::class, sdk = [35])
class PlaybackQualityTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun gate() = assumeTrue("live run (-Plive=true)", System.getProperty("oriel.live") == "true")

        private const val PLAY_MS = 45_000L
        private const val START_BUDGET_MS = 8_000L
    }

    @get:Rule
    val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    private val graph get() = ApplicationProvider.getApplicationContext<OrielApp>().graph
    private val id = System.getProperty("oriel.ids")?.substringBefore(',') ?: "dQw4w9WgXcQ"

    // Media3 caps Auto at the display's size, as the app leaves it to, so each test brings a real screen.
    @Test @Config(qualifiers = "w411dp-h914dp-420dpi") // 1080 × 2400, a typical phone
    fun phoneScreenReaches1080pWithoutStalling() = play(cap = 1080, vp9 = true)

    @Test @Config(qualifiers = "w1920dp-h1080dp-land-xhdpi") // 3840 × 2160
    fun fourKScreenClimbsTheLadderWithoutStalling() = play(cap = 2160, vp9 = true)

    /** A poor mobile line: Auto drops to what 1.5 Mbps carries and keeps playing. */
    @Test @Config(qualifiers = "w411dp-h914dp-420dpi")
    fun slowLineSettlesLowerWithoutStalling() = play(cap = 1080, vp9 = true, limitKbps = 1_500)

    /** A first guess above the line (the 12 Mbps Wi-Fi default against 8 Mbps): it must step down, not stall. */
    @Test @Config(qualifiers = "w1920dp-h1080dp-land-xhdpi")
    fun overOptimisticStartStepsDownWithoutStalling() = play(cap = 2160, vp9 = true, limitKbps = 8_000)

    /**
     * Audio's rendition. Robolectric's AudioTrack never advances, so audio can't play here,
     * but the choice can be checked: AAC-LC (≈128 kbps), not the HE-AAC (≈48 kbps) listed first.
     */
    @Test @Config(qualifiers = "w411dp-h914dp-420dpi")
    fun audioIsTheStandardRenditionNotTheLowOne() = play(cap = 1080, vp9 = true, audio = true)

    /**
     * Auto's fallback when YouTube offers no HLS ladder or refuses it: the fixed-quality video
     * and audio streams, merged, at the height Auto was at. These URLs need the player's cipher
     * solved, which runs QuickJS (the desktop build here, see app/build.gradle.kts).
     */
    @Test @Config(qualifiers = "w411dp-h914dp-420dpi")
    fun fixedQualityFallbackStartsAndPlaysWithoutStalling() = play(cap = 1080, vp9 = true, adaptive = false)

    private fun play(cap: Int, vp9: Boolean, limitKbps: Int? = null, audio: Boolean = false, adaptive: Boolean = true) {
        val context = ApplicationProvider.getApplicationContext<OrielApp>()
        val display = androidx.media3.common.util.Util.getCurrentDisplayModeSize(context)
        println("PLAYBACK display=${display.x}x${display.y}")
        val item = MediaItems.build(Video(id, "Test"), cap, adaptive = adaptive)
        if (!adaptive) {
            // The app pays the cipher's cold costs at launch (prewarm), long before a fallback.
            val t0 = System.nanoTime()
            // A resolve warms the same pieces; dropping its result makes playback resolve again, warm.
            graph.resolver.resolveBlocking(app.oriel.core.stream.StreamRequest(id, videoHeight = cap))
            graph.resolver.invalidate(id)
            println("PLAYBACK cold resolve ${(System.nanoTime() - t0) / 1_000_000}ms")
        }
        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(OrielMediaSourceFactory(graph.dataSources.playback, graph.dataSources.live, preferLowAudio = { false }, adaptiveDataSourceFactory =
                limitKbps?.let { Throttled(graph.adaptive.dataSource, it) } ?: graph.adaptive.dataSource))
            .setLoadControl(Abr.loadControl())
            .setBandwidthMeter(graph.abr.meter)
            .setTrackSelector(graph.abr.trackSelector(context, preferVp9 = vp9))
            .build()
        if (!audio) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()

        val rungs = ArrayList<Pair<Long, Format>>()
        var segments = 0
        var segmentBytes = 0L
        var segmentMs = 0L
        val began = System.nanoTime()
        fun now() = (System.nanoTime() - began) / 1_000_000
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                rungs += now() to format
            }
            override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA && mediaLoadData.trackType != C.TRACK_TYPE_AUDIO) {
                    segments++; segmentBytes += loadEventInfo.bytesLoaded; segmentMs += loadEventInfo.loadDurationMs
                }
            }
        })

        var startedAt = -1L
        var stallStart = -1L
        val stalls = ArrayList<Long>()
        var error: PlaybackException? = null
        try {
            player.setMediaItem(item)
            player.prepare()
            player.play()
            var last = System.nanoTime()
            val main = shadowOf(Looper.getMainLooper())
            while (true) {
                Thread.sleep(20)
                // Robolectric's clock follows the real one, so loads and the meter see real time.
                val t = System.nanoTime()
                main.idleFor(Duration.ofNanos(t - last))
                last = t
                error = player.playerError
                if (error != null) break
                val playing = player.playbackState == Player.STATE_READY && player.isPlaying
                if (startedAt < 0) {
                    if (playing) startedAt = now()
                    else if (now() > 30_000) break
                    continue
                }
                if (player.playbackState == Player.STATE_BUFFERING && stallStart < 0) stallStart = now()
                if (playing && stallStart >= 0) { stalls += now() - stallStart; stallStart = -1 }
                if (now() - startedAt > PLAY_MS || player.playbackState == Player.STATE_ENDED) break
            }
            if (stallStart >= 0) stalls += now() - stallStart
            if (audio) player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { g ->
                (0 until g.length).forEach { i -> val f = g.getTrackFormat(i); println("PLAYBACK audio option ${f.id} ${f.codecs} ${f.bitrate / 1000}k ${f.sampleRate}Hz ch=${f.channelCount} lang=${f.language} label=${f.label} selected=${g.isTrackSelected(i)} supported=${g.isTrackSupported(i)}") }
            }
            if (audio) {
                val selected = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    .flatMap { g -> (0 until g.length).filter { g.isTrackSelected(it) }.map { g.getTrackFormat(it).codecs } }
                println("PLAYBACK audio selected=$selected")
                assertEquals("AAC-LC, not HE-AAC", listOf("mp4a.40.2"), selected)
                return
            }
            if (!adaptive) {
                val f = player.videoFormat
                println("PLAYBACK fixed $id start=${startedAt}ms stalls=${stalls.size} (${stalls.sum()} ms) played=${player.currentPosition}ms buffered=${player.totalBufferedDuration}ms video=${f?.let { "${minOf(it.width, it.height)}p@${it.bitrate / 1000}k ${it.codecs}" }} stream=${graph.resolver.cached(app.oriel.core.stream.StreamRequest(id, videoHeight = cap))}")
                if (error != null) throw AssertionError("playback failed: ${error.errorCodeName}", error)
                assertTrue("started within ${START_BUDGET_MS} ms (took $startedAt)", startedAt in 0..START_BUDGET_MS)
                assertEquals("no rebuffering once playing: $stalls", 0, stalls.size)
                assertTrue("plays at the asked height: ${f?.height}", f != null && minOf(f.width, f.height) == cap)
                return
            }
            if (audio) println("PLAYBACK audio=${player.audioFormat?.let { "${it.sampleMimeType} ${it.bitrate / 1000}k ${it.sampleRate}Hz" }} position=${player.currentPosition}ms after ${now() - startedAt}ms of wall time")

            val ladder = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
                ?.let { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g.getTrackFormat(it) } }.orEmpty()
            val top = ladder.maxOfOrNull { minOf(it.width, it.height) } ?: 0
            val final = player.videoFormat
            val throughput = if (segmentMs > 0) segmentBytes * 8 / segmentMs / 1000.0 else 0.0
            println("PLAYBACK $id cap=${cap}p limit=${limitKbps ?: "none"} audio=$audio ladder=${ladder.map { "${minOf(it.width, it.height)}p@${it.bitrate / 1000}k" }.distinct().sorted()}")
            println("PLAYBACK start=${startedAt}ms stalls=${stalls.size} (${stalls.sum()} ms) played=${player.currentPosition}ms buffered=${player.totalBufferedDuration}ms")
            println("PLAYBACK rungs=" + rungs.joinToString(" → ") { (t, f) -> "${minOf(f.width, f.height)}p@${f.bitrate / 1000}k(${t}ms)" })
            println("PLAYBACK segments=$segments ${segmentBytes / 1024} KiB, ${"%.1f".format(throughput)} Mbps while loading; meter estimate ${graph.abr.meter.bitrateEstimate / 1000} kbps")

            if (error != null) throw AssertionError("playback failed: ${error.errorCodeName}", error)
            assertTrue("started within ${START_BUDGET_MS} ms (took $startedAt)", startedAt in 0..START_BUDGET_MS)
            assertEquals("no rebuffering once playing: $stalls", 0, stalls.size)
            assertTrue("the ladder reaches the cap or near it: top=$top", top >= minOf(cap, 1080))
            // Auto must end on the best track it may use: the tallest the screen allows, in VP9
            // (more picture per bit than H.264, so bitrates aren't compared across codecs), at
            // that height's highest VP9 bitrate, as far as the measured line carries it.
            fun isVp9(f: Format) = f.codecs.orEmpty().startsWith("vp09")
            val usable = ladder.filter { minOf(it.width, it.height) <= cap && isVp9(it) == vp9 && it.bitrate <= throughput * 1_000_000 * 0.8 }
            val best = usable.maxWithOrNull(compareBy<Format>({ minOf(it.width, it.height) }, { it.bitrate }))
            println("PLAYBACK final=${final?.let { "${minOf(it.width, it.height)}p@${it.bitrate / 1000}k ${it.codecs}" }} best=${best?.let { "${minOf(it.width, it.height)}p@${it.bitrate / 1000}k ${it.codecs}" }}")
            assertTrue("Auto settled on ${final?.codecs} ${final?.bitrate}; the best usable is ${best?.codecs} ${best?.bitrate}", final != null && best != null && final.codecs == best.codecs && final.bitrate >= best.bitrate && minOf(final.width, final.height) == minOf(best.width, best.height))
        } finally {
            player.release()
        }
    }
}

/** Caps everything read through [upstream] at [kbps] together, as one slow line would. */
@OptIn(UnstableApi::class)
private class Throttled(private val upstream: androidx.media3.datasource.DataSource.Factory, kbps: Int) : androidx.media3.datasource.DataSource.Factory {
    private val bytesPerMs = kbps / 8.0
    private var nextFree = System.nanoTime()
    private val lock = Any()

    private fun pace(bytes: Int) {
        val wait = synchronized(lock) {
            val now = System.nanoTime()
            nextFree = maxOf(nextFree, now) + (bytes / bytesPerMs * 1_000_000).toLong()
            nextFree - now
        }
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
    }

    override fun createDataSource(): androidx.media3.datasource.DataSource {
        val inner = upstream.createDataSource()
        return object : androidx.media3.datasource.DataSource by inner {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                inner.read(buffer, offset, minOf(length, 16 * 1024)).also { if (it > 0) pace(it) }
        }
    }
}
