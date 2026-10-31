package app.oriel.core.stream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Invented tracks, named the way YouTube names them with the app in English. */
class AudioTracksTest {
    private val original = AudioTrackInfo("English (United States) original", "en-US", AudioContent.ORIGINAL, isDefault = false)
    private val autoDub = AudioTrackInfo("Spanish (auto-dubbed)", "es", AudioContent.AUTO_DUBBED, isDefault = true)
    private val dub = AudioTrackInfo("French", "fr", AudioContent.DUBBED, isDefault = false)
    private val tracks = listOf(autoDub, original, dub)

    private fun choose(tracks: List<AudioTrackInfo>, preference: AudioPreference) = AudioTracks.choose(tracks, preference) { it }

    @Test fun preferOriginalPicksTheOriginal() {
        assertEquals(original, choose(tracks, AudioPreference(preferOriginal = true)))
    }

    @Test fun offPicksYouTubesDefault() {
        assertEquals(autoDub, choose(tracks, AudioPreference(preferOriginal = false)))
    }

    @Test fun aSessionLanguageWinsWhenTheVideoHasIt() {
        assertEquals(dub, choose(tracks, AudioPreference(preferOriginal = true, language = "fr")))
        // "es-419" picked earlier still finds plain "es".
        assertEquals(autoDub, choose(tracks, AudioPreference(preferOriginal = true, language = "es-419")))
    }

    @Test fun aSessionLanguageTheVideoLacksFallsBackToTheSetting() {
        assertEquals(original, choose(tracks, AudioPreference(preferOriginal = true, language = "de")))
        assertEquals(autoDub, choose(tracks, AudioPreference(preferOriginal = false, language = "de")))
    }

    @Test fun withinALanguageTheOriginalComesBeforeADescription() {
        val described = AudioTrackInfo("English descriptive", "en-US", AudioContent.DESCRIPTIVE, isDefault = false)
        assertEquals(original, choose(listOf(described, original, autoDub), AudioPreference(language = "en-US")))
    }

    @Test fun nothingToChooseLeavesTheDefault() {
        assertNull(choose(listOf(original), AudioPreference()))
        // No track says it's the original, and none is YouTube's default either.
        val unknown = listOf(AudioTrackInfo("A", "en", AudioContent.UNKNOWN, false), AudioTrackInfo("B", "de", AudioContent.UNKNOWN, false))
        assertNull(choose(unknown, AudioPreference()))
    }

    @Test fun readsXtagsPlainAndEncoded() {
        assertEquals(AudioContent.ORIGINAL, AudioTracks.contentOf("acont=original:lang=en-US"))
        assertEquals(AudioContent.AUTO_DUBBED, AudioTracks.contentOf("acont%3Ddubbed-auto%3Alang%3Des"))
        assertEquals(AudioContent.DUBBED, AudioTracks.contentOf("lang=fr:acont=dubbed"))
        assertEquals(AudioContent.DESCRIPTIVE, AudioTracks.contentOf("acont=descriptive"))
        assertEquals(AudioContent.UNKNOWN, AudioTracks.contentOf("acont=something-new"))
        assertNull(AudioTracks.contentOf("lang=en"))
        assertNull(AudioTracks.contentOf(null))
    }

    @Test fun readsEnglishNamesAsALastResort() {
        assertEquals(AudioContent.ORIGINAL, AudioTracks.contentOfName("English (United States) original"))
        assertEquals(AudioContent.AUTO_DUBBED, AudioTracks.contentOfName("Spanish (auto-dubbed)"))
        assertNull(AudioTracks.contentOfName("Default"))
    }
}
