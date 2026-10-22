package app.oriel.playback

import app.oriel.playback.StreamDataSources.Companion.PRECACHE_AUDIO
import app.oriel.playback.StreamDataSources.Companion.PRECACHE_VIDEO
import app.oriel.playback.StreamDataSources.Companion.precacheBytes
import org.junit.Assert.assertEquals
import org.junit.Test

class PrecacheBytesTest {
    @Test fun unknownBitrateKeepsTheFloor() {
        assertEquals(PRECACHE_VIDEO, precacheBytes(null, PRECACHE_VIDEO))
        assertEquals(PRECACHE_AUDIO, precacheBytes(0, PRECACHE_AUDIO))
    }

    @Test fun lowBitratesKeepTheFloor() {
        // 160 kbps audio, 720p H.264.
        assertEquals(PRECACHE_AUDIO, precacheBytes(160_000, PRECACHE_AUDIO))
        assertEquals(PRECACHE_VIDEO, precacheBytes(1_500_000, PRECACHE_VIDEO))
    }

    @Test fun highBitratesCoverTheStartAndAreCapped() {
        // 1440p VP9 at 10 Mbps: three seconds.
        assertEquals(10_000_000 / 8L * 3, precacheBytes(10_000_000, PRECACHE_VIDEO))
        // 4K60 at 40 Mbps would be 15 MB: held to 8.
        assertEquals(8L * 1024 * 1024, precacheBytes(40_000_000, PRECACHE_VIDEO))
    }
}
