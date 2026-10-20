package app.oriel.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.TestApp
import app.oriel.core.YouTube
import app.oriel.core.stream.StreamProtocol
import app.oriel.core.stream.StreamQuality
import app.oriel.core.stream.StreamRequest
import app.oriel.core.stream.StreamResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * SABR through the app's own data source into Media3's WebM extractor, the
 * way ExoPlayer drives it: read the header and cues, take samples, then seek
 * by reopening at the byte the cues name. Decoding can't run here, but every
 * byte the decoders would get is checked to arrive and line up in time.
 * Opt-in: `-Plive=true`.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [35])
class SabrPlaybackTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun gate() = assumeTrue("live run (-Plive=true)", System.getProperty("oriel.live") == "true")
    }

    private class Samples : ExtractorOutput, TrackOutput {
        var seekMap: SeekMap? = null
        var format: Format? = null
        val times = ArrayList<Long>()
        var bytes = 0L
        private val scratch = ByteArray(64 * 1024)

        override fun track(id: Int, type: Int): TrackOutput = this
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { this.seekMap = seekMap }
        override fun format(format: Format) { this.format = format }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val n = input.read(scratch, 0, minOf(length, scratch.size))
            if (n == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw java.io.EOFException()
            }
            return n
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            times += timeUs
            bytes += size
        }
    }

    /** Runs [extractor] from [position] until [samples] has [count] more samples, reopening on seeks as ExoPlayer does. */
    private fun pump(source: DataSource, uri: String, extractor: Extractor, samples: Samples, position: Long, count: Int) {
        val target = samples.times.size + count
        var at = position
        val holder = PositionHolder()
        while (samples.times.size < target) {
            val length = source.open(DataSpec.Builder().setUri(uri).setPosition(at).build())
            val input = DefaultExtractorInput(source, at, length)
            var result = Extractor.RESULT_CONTINUE
            try {
                while (result == Extractor.RESULT_CONTINUE && samples.times.size < target) result = extractor.read(input, holder)
            } finally {
                source.close()
            }
            when (result) {
                Extractor.RESULT_SEEK -> at = holder.position
                Extractor.RESULT_END_OF_INPUT -> return
                else -> return
            }
        }
    }

    @Test fun sabrPlaysAndSeeksThroughTheExtractor() = runBlocking {
        val yt = YouTube().apply { setLocale("US", "en") }
        val resolver = StreamResolver(yt).apply { protocol = StreamProtocol.SABR }
        val id = System.getProperty("oriel.ids")?.substringBefore(',') ?: "dQw4w9WgXcQ"
        val stream = resolver.resolve(StreamRequest(id, StreamQuality.HIGH, 360))
        println("SABRPLAY $stream audio=${stream.url} video=${stream.video?.url}")
        assertTrue("resolved over SABR", stream.isSabr)
        val source = RoutingDataSource(SabrDataSource(resolver), DefaultHttpDataSource.Factory().createDataSource())

        for (uri in listOfNotNull(stream.url, stream.video?.url)) {
            val samples = Samples()
            val extractor = MatroskaExtractor()
            extractor.init(samples)
            val started = System.nanoTime()
            pump(source, uri, extractor, samples, 0, 200)
            assertNotNull("cues give a seek map", samples.seekMap)
            val seekMap = samples.seekMap!!
            println("SABRPLAY $uri: ${samples.format?.sampleMimeType} ${samples.format?.codecs} ${samples.format?.width}x${samples.format?.height} seekable=${seekMap.isSeekable} duration=${seekMap.durationUs / 1000} ms; 200 samples to ${samples.times.last() / 1000} ms in ${(System.nanoTime() - started) / 1_000_000} ms")
            assertTrue(seekMap.isSeekable)
            assertEquals(200, samples.times.size)

            // Seek two-thirds in: reopen at the byte the cues give, as ExoPlayer does.
            val targetUs = seekMap.durationUs * 2 / 3
            val point = seekMap.getSeekPoints(targetUs).first
            extractor.seek(point.position, point.timeUs)
            val before = samples.times.size
            pump(source, uri, extractor, samples, point.position, 50)
            val landed = samples.times[before]
            println("SABRPLAY seek to ${targetUs / 1000} ms: cue ${point.timeUs / 1000} ms at byte ${point.position}, first sample ${landed / 1000} ms")
            assertEquals(50, samples.times.size - before)
            assertTrue("lands on the cue", abs(landed - point.timeUs) < 1_000_000)
            assertTrue("in time order", samples.times.subList(before, samples.times.size).zipWithNext().all { (a, b) -> b >= a - 100_000 })
        }
    }
}
