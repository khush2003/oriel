package app.oriel.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.TestApp
import app.oriel.core.stream.ResolvedStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * googlevideo answers 403 for a fresh URL's first few seconds: the data
 * source must wait that out on the same URL, and only that. A 403 on an old
 * URL, or any other status, still reaches the player's error handling.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [35])
class WarmUpDataSourceTest {
    private val url = "https://rr1---sn-test.googlevideo.com/videoplayback?id=1"

    // Robolectric freezes the wall clock in app code; sleeping advances this one instead.
    private var now = 1_000_000L
    private val slept = mutableListOf<Long>()
    private fun source(upstream: HttpDataSource, stream: () -> ResolvedStream?) =
        WarmUpDataSource(upstream, { stream() }, clock = { now }, sleep = { slept += it; now += it })

    @Test fun freshUrlIsAskedAgainUntilLive() {
        val upstream = Refusing(refusals = 2)
        val fresh = stream(issuedAgoMs = 0)
        assertEquals(1_000L, source(upstream) { fresh }.open(spec()))
        assertEquals(3, upstream.opens)
        // Two half-second waits, no more.
        assertEquals(listOf(500L, 500L), slept)
    }

    @Test fun oldUrlRefusalGoesToThePlayer() {
        val upstream = Refusing(refusals = 1)
        val old = stream(issuedAgoMs = ResolvedStream.WARM_UP_MS + 1_000)
        assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) { source(upstream) { old }.open(spec()) }
        assertEquals(1, upstream.opens)
        assertTrue(slept.isEmpty())
    }

    @Test fun otherStatusesAndUnknownUrlsAreNotRetried() {
        val gone = Refusing(refusals = 1, code = 410)
        val fresh = stream(0)
        assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) { source(gone) { fresh }.open(spec()) }
        assertEquals(1, gone.opens)
        val unknown = Refusing(refusals = 1)
        assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) { source(unknown) { null }.open(spec()) }
        assertEquals(1, unknown.opens)
    }

    @Test fun waitingStopsAtTheEndOfTheWarmUp() {
        val upstream = Refusing(refusals = Int.MAX_VALUE)
        // Issued so long ago that only a second of warm-up is left: two waits, then the 403 stands.
        val late = stream(issuedAgoMs = ResolvedStream.WARM_UP_MS - 1_000)
        assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) { source(upstream) { late }.open(spec()) }
        assertEquals(3, upstream.opens)
        assertEquals(listOf(500L, 500L), slept)
    }

    private fun spec() = DataSpec(Uri.parse(url))

    private fun stream(issuedAgoMs: Long) = ResolvedStream(
        videoId = "test", url = url, headers = emptyMap(), mimeType = "audio/webm", codecs = null, bitrate = null,
        sampleRate = null, contentLength = 1_000, itag = 251, loudnessDb = null, clientName = "TEST", profileId = "TEST",
        requireBoundedRange = false, chunkSize = 0, expiresAtMs = Long.MAX_VALUE, video = null, availableHeights = emptyList(),
        title = null, author = null, durationSec = null, isLive = false, playbackUrl = null, watchtimeUrl = null, cpn = "cpn",
        issuedAtMs = now - issuedAgoMs,
    )

    /** Answers [code] to the first [refusals] opens, then serves 1000 bytes. */
    private class Refusing(private val refusals: Int, private val code: Int = 403) : HttpDataSource {
        var opens = 0
        private var spec: DataSpec? = null

        override fun open(dataSpec: DataSpec): Long {
            opens++
            if (opens <= refusals) throw HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), dataSpec, ByteArray(0))
            spec = dataSpec
            return 1_000
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun close() { spec = null }
        override fun getUri(): Uri? = spec?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
        override fun getResponseCode(): Int = if (spec != null) 206 else -1
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun setRequestProperty(name: String, value: String) = Unit
        override fun clearRequestProperty(name: String) = Unit
        override fun clearAllRequestProperties() = Unit
    }
}
