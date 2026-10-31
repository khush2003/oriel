package app.oriel.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.TestApp
import app.oriel.core.stream.AudioPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A dubbed video's HLS ladder, and which track it starts in. The playlist is invented (placeholder
 * URLs, made-up names), shaped like YouTube's: each language in both AAC codecs, YouTube's own
 * DEFAULT on the auto-dub in the viewer's language, and what each track is in its xtags.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [35], qualifiers = "es")
class AudioTrackChoiceTest {
    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:URI="https://example.org/a/233/xtags/acont%3Doriginal%3Alang%3Den-US/en.m3u8",TYPE=AUDIO,GROUP-ID="233",LANGUAGE="en-US",NAME="English (United States) original",DEFAULT=NO,AUTOSELECT=YES
        #EXT-X-MEDIA:URI="https://example.org/a/234/xtags/acont%3Doriginal%3Alang%3Den-US/en.m3u8",TYPE=AUDIO,GROUP-ID="234",LANGUAGE="en-US",NAME="English (United States) original",DEFAULT=NO,AUTOSELECT=YES
        #EXT-X-MEDIA:URI="https://example.org/a/233/es.m3u8",TYPE=AUDIO,GROUP-ID="233",LANGUAGE="es",NAME="Spanish (auto-dubbed)",DEFAULT=YES,AUTOSELECT=YES,YT-EXT-XTAGS="acont=dubbed-auto:lang=es"
        #EXT-X-MEDIA:URI="https://example.org/a/234/es.m3u8",TYPE=AUDIO,GROUP-ID="234",LANGUAGE="es",NAME="Spanish (auto-dubbed)",DEFAULT=YES,AUTOSELECT=YES,YT-EXT-XTAGS="acont=dubbed-auto:lang=es"
        #EXT-X-MEDIA:URI="https://example.org/a/233/fr.m3u8?xtags=acont%3Ddubbed%3Alang%3Dfr",TYPE=AUDIO,GROUP-ID="233",LANGUAGE="fr",NAME="French",DEFAULT=NO,AUTOSELECT=YES
        #EXT-X-MEDIA:URI="https://example.org/a/234/fr.m3u8?xtags=acont%3Ddubbed%3Alang%3Dfr",TYPE=AUDIO,GROUP-ID="234",LANGUAGE="fr",NAME="French",DEFAULT=NO,AUTOSELECT=YES
        #EXT-X-STREAM-INF:BANDWIDTH=300000,CODECS="avc1.4D4015,mp4a.40.5",RESOLUTION=426x240,AUDIO="233"
        https://example.org/v/240.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1763000,CODECS="vp09.00.40.08,mp4a.40.2",RESOLUTION=1920x1080,AUDIO="234"
        https://example.org/v/1080.m3u8
    """.trimIndent()

    private fun parse(preference: AudioPreference, low: Boolean = false): HlsMultivariantPlaylist =
        AudioRenditionOrder({ preference }) { low }.createPlaylistParser().parse(Uri.parse("https://example.org/master.m3u8"), master.byteInputStream()) as HlsMultivariantPlaylist

    private fun defaults(p: HlsMultivariantPlaylist): Set<String> =
        p.audios.filter { it.format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 }.map { it.name }.toSet()

    @Test fun preferOriginalStartsInTheOriginal() {
        assertEquals(setOf("English (United States) original"), defaults(parse(AudioPreference(preferOriginal = true))))
    }

    @Test fun withoutItYouTubesDefaultStays() {
        assertEquals(setOf("Spanish (auto-dubbed)"), defaults(parse(AudioPreference(preferOriginal = false))))
    }

    @Test fun aLanguagePickedThisSessionComesFirst() {
        assertEquals(setOf("French"), defaults(parse(AudioPreference(preferOriginal = true, language = "fr"))))
    }

    @Test fun eachTrackSaysWhatItIs() {
        val roles = parse(AudioPreference()).audios.associate { it.name to it.format.roleFlags }
        assertEquals(C.ROLE_FLAG_MAIN, roles["English (United States) original"]!! and C.ROLE_FLAG_MAIN)
        assertEquals(C.ROLE_FLAG_DUB, roles["Spanish (auto-dubbed)"]!! and C.ROLE_FLAG_DUB)
        assertEquals(C.ROLE_FLAG_DUB, roles["French"]!! and C.ROLE_FLAG_DUB)
    }

    @Test fun theCodecOrderStillFollowsSoundQuality() {
        assertEquals(listOf("234", "234", "234"), parse(AudioPreference()).audios.take(3).map { it.groupId })
        assertEquals(listOf("233", "233", "233"), parse(AudioPreference(), low = true).audios.take(3).map { it.groupId })
    }

    /**
     * What Media3 then plays, on a phone set to Spanish: the DEFAULT flag outranks the locale, so
     * the original plays, and with the setting off, YouTube's Spanish dub.
     */
    @Test fun theSelectorPlaysTheChosenTrack() {
        assertEquals("English (United States) original", selected(parse(AudioPreference(preferOriginal = true))).label)
        assertEquals("Spanish (auto-dubbed)", selected(parse(AudioPreference(preferOriginal = false))).label)
    }

    @Test fun menuListsOneEntryPerLanguage() {
        val groups = byName(parse(AudioPreference()))
        val tracks = Tracks(groups.mapIndexed { i, g -> Tracks.Group(g, false, IntArray(g.length) { C.FORMAT_HANDLED }, BooleanArray(g.length) { i == 0 && it == 0 }) })
        val options = audioOptions(tracks)
        assertEquals(listOf("English (United States) original", "Spanish (auto-dubbed)", "French"), options.map { it.name })
        assertTrue(options[0].isOriginal && options[0].selected)
        assertFalse(options[1].isOriginal || options[1].selected)
        assertEquals("es", options[1].language)
    }

    /** Media3's HLS period makes one audio group per rendition name, holding each codec's rendition. */
    private fun byName(p: HlsMultivariantPlaylist): List<TrackGroup> =
        p.audios.groupBy { it.name }.map { (name, rs) -> TrackGroup(name, *rs.map { it.format.buildUpon().setSampleMimeType("audio/mp4a-latm").build() }.toTypedArray()) }

    private fun selected(p: HlsMultivariantPlaylist): Format {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val selector = DefaultTrackSelector(context)
        selector.init({}, DefaultBandwidthMeter.Builder(context).build())
        val result = selector.selectTracks(arrayOf(AudioRenderer), TrackGroupArray(*byName(p).toTypedArray()), MediaSource.MediaPeriodId(Any()), Timeline.EMPTY)
        return result.selections[0]!!.getFormat(0)
    }

    private object AudioRenderer : RendererCapabilities {
        override fun getName() = "audio"
        override fun getTrackType() = C.TRACK_TYPE_AUDIO
        override fun supportsFormat(format: Format) = RendererCapabilities.create(C.FORMAT_HANDLED)
        override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SEAMLESS
    }
}
