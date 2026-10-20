package app.oriel.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.LiveTestApp
import app.oriel.OrielApp
import app.oriel.core.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Auto quality end to end, short of decoding (which Robolectric can't do): an
 * Auto item resolves through [AdaptiveSources] to YouTube's HLS ladder, Media3
 * parses it into a video track group spanning the heights, and a variant's
 * playlist and first segment download through the same data source. Also
 * that ExoPlayer itself prepares the item into those tracks.
 * Opt-in: `-Plive=true`; `-Pids=…` picks the video.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = LiveTestApp::class, sdk = [35])
class AdaptivePlaybackTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun gate() = assumeTrue("live run (-Plive=true)", System.getProperty("oriel.live") == "true")
    }

    private val graph get() = ApplicationProvider.getApplicationContext<OrielApp>().graph
    private val id = System.getProperty("oriel.ids")?.substringBefore(',') ?: "dQw4w9WgXcQ"
    private val item get() = MediaItems.build(Video(id, "Test"), 1080, adaptive = true)

    private fun read(spec: DataSpec): ByteArray {
        val source = graph.adaptive.dataSource.createDataSource()
        return DataSourceInputStream(source, spec).use { it.readBytes() }
    }

    @Test fun theLadderResolvesParsesAndDownloads() {
        assertEquals("abr", MediaItems.kind(item))
        val master = item.localConfiguration!!.uri
        val text = read(DataSpec(master))
        val ladder = HlsPlaylistParser().parse(master, text.inputStream()) as HlsMultivariantPlaylist
        val heights = ladder.variants.mapNotNull { v -> v.format.height.takeIf { it > 0 }?.let { minOf(it, v.format.width) } }.distinct().sorted()
        println("ADAPTIVE $id heights=$heights audio=${ladder.audios.size} codecs=${ladder.variants.map { it.format.codecs }.distinct()}")
        ladder.audios.forEach { a -> println("ADAPTIVE audio group=${a.groupId} name=${a.name} codecs=${a.format.codecs} rate=${a.format.sampleRate} ch=${a.format.channelCount} default=${a.format.selectionFlags} url=${a.url?.lastPathSegment}") }
        ladder.variants.forEach { v -> println("ADAPTIVE variant ${v.format.height}p ${v.format.bitrate / 1000}k ${v.format.codecs} audioGroup=${v.audioGroupId}") }
        println("ADAPTIVE master tags: " + text.decodeToString().lines().filter { it.startsWith("#EXT-X-MEDIA") }.joinToString(" || ").take(1500))
        assertTrue("a ladder, not one height: $heights", heights.size >= 4)
        assertTrue("reaches 720p: $heights", heights.any { it >= 720 })

        // A mid-ladder variant: its playlist, then its first segment, with the client's user agent.
        val variant = ladder.variants.filter { it.format.height in 360..720 }.maxByOrNull { it.format.height } ?: ladder.variants.first()
        val playlistBytes = read(DataSpec(variant.url))
        val media = HlsPlaylistParser(ladder, null).parse(variant.url, playlistBytes.inputStream()) as HlsMediaPlaylist
        println("ADAPTIVE variant ${variant.format.width}x${variant.format.height} segments=${media.segments.size} vod=${media.playlistType == HlsMediaPlaylist.PLAYLIST_TYPE_VOD}")
        assertTrue("an on-demand playlist with segments", media.segments.isNotEmpty() && media.hasEndTag)
        val first = media.segments.first()
        val segmentUri = androidx.media3.common.util.UriUtil.resolveToUri(media.baseUri, first.url)
        val started = System.nanoTime()
        val segment = read(DataSpec.Builder().setUri(segmentUri).apply { if (first.byteRangeLength != C.LENGTH_UNSET.toLong()) setPosition(first.byteRangeOffset).setLength(first.byteRangeLength) }.build())
        println("ADAPTIVE first segment ${segment.size / 1024} KiB in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertTrue("the segment carries media", segment.size > 10_000)
    }

    @Test fun exoPlayerPreparesTheLadderIntoTracks() {
        val context = ApplicationProvider.getApplicationContext<OrielApp>()
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(OrielMediaSourceFactory(graph.dataSources.playback, graph.dataSources.live, graph.adaptive.dataSource))
            .build()
        try {
            player.setMediaItem(item)
            player.prepare()
            // Robolectric holds the clock still; real sleeps bound the wait instead, about 30 s.
            var heights = emptyList<Int>()
            for (attempt in 0 until 600) {
                shadowOf(Looper.getMainLooper()).idle()
                val video = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
                if (video != null) {
                    heights = (0 until video.length).map { video.getTrackFormat(it) }.map { minOf(it.width, it.height) }.distinct().sorted()
                    break
                }
                player.playerError?.let { throw AssertionError("player failed: ${it.errorCodeName}", it) }
                Thread.sleep(50)
            }
            println("ADAPTIVE player tracks heights=$heights state=${player.playbackState}")
            assertTrue("ExoPlayer saw the ladder: $heights", heights.size >= 4)
            assertTrue("not ended or failed", player.playbackState != Player.STATE_ENDED && player.playerError == null)
        } finally {
            player.release()
        }
    }
}
