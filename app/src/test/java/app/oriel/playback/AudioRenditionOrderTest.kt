package app.oriel.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import app.oriel.TestApp

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [35])
class AudioRenditionOrderTest {
    // The shape of YouTube's ladder, with placeholder URLs: HE-AAC listed first, as YouTube lists it.
    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:URI="https://example.org/a/233.m3u8",TYPE=AUDIO,GROUP-ID="233",NAME="Default",DEFAULT=YES,AUTOSELECT=YES
        #EXT-X-MEDIA:URI="https://example.org/a/234.m3u8",TYPE=AUDIO,GROUP-ID="234",NAME="Default",DEFAULT=YES,AUTOSELECT=YES
        #EXT-X-STREAM-INF:BANDWIDTH=300000,CODECS="avc1.4D4015,mp4a.40.5",RESOLUTION=426x240,AUDIO="233"
        https://example.org/v/240.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1763000,CODECS="vp09.00.40.08,mp4a.40.2",RESOLUTION=1920x1080,AUDIO="234"
        https://example.org/v/1080.m3u8
    """.trimIndent()

    private fun parse(low: Boolean): HlsMultivariantPlaylist =
        AudioRenditionOrder { low }.createPlaylistParser().parse(Uri.parse("https://example.org/master.m3u8"), master.byteInputStream()) as HlsMultivariantPlaylist

    @Test fun standardAudioComesFirst() {
        assertEquals(listOf("234", "233"), parse(low = false).audios.map { it.groupId })
    }

    @Test fun lowAudioWhenAskedFor() {
        assertEquals(listOf("233", "234"), parse(low = true).audios.map { it.groupId })
    }

    @Test fun variantsAreUntouched() {
        assertEquals(2, parse(low = false).variants.size)
    }
}
