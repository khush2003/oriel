package app.oriel.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import app.oriel.core.stream.AudioContent
import app.oriel.core.stream.AudioPreference
import app.oriel.core.stream.AudioTrackInfo
import app.oriel.core.stream.AudioTracks
import java.io.ByteArrayInputStream

/**
 * Which of YouTube's HLS audio renditions plays.
 *
 * The codec: the ladder lists HE-AAC (itag 233, about 48 kbps) and AAC-LC (itag 234, about
 * 128 kbps), declares neither's bitrate, and pairs every variant from 360p up with the AAC-LC
 * one. Media3 picks alternate audio on its own, and with nothing to compare it took the first
 * listed: HE-AAC, under a 4K picture. This puts AAC-LC first, or HE-AAC when [preferLow] (Sound
 * quality set to Low, or the data saver on mobile data), so the setting decides rather than the
 * playlist's order.
 *
 * The language: a video with dubs lists one rendition per language and codec, and marks as
 * DEFAULT the one YouTube would start in, often a machine dub in the app's language. Each is
 * tagged here with what it is (original or dub, from YouTube's xtags), and DEFAULT moves to the
 * track [preference] asks for (see [AudioTracks.choose]). Media3 ranks the DEFAULT flag above
 * the phone's locale, so that is what plays; the player's Audio track menu can still pick any.
 */
@OptIn(UnstableApi::class)
class AudioRenditionOrder(
    private val preference: () -> AudioPreference = { AudioPreference() },
    private val preferLow: () -> Boolean,
) : HlsPlaylistParserFactory {
    private val base = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> = wrap(base.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> = wrap(base.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private fun wrap(parser: ParsingLoadable.Parser<HlsPlaylist>) = ParsingLoadable.Parser { uri, input ->
        // Kept as text too: Media3 drops YouTube's own attributes, which say which track is the original.
        val bytes = input.readBytes()
        val playlist = parser.parse(uri, ByteArrayInputStream(bytes))
        if (playlist is HlsMultivariantPlaylist && playlist.audios.size > 1) arrange(playlist, String(bytes), preferLow(), preference()) else playlist
    }

    companion object {
        private const val HE_AAC = "mp4a.40.5"

        fun reorder(p: HlsMultivariantPlaylist, low: Boolean): HlsMultivariantPlaylist =
            withAudios(p, p.audios.sortedBy { a -> (a.format.codecs?.contains(HE_AAC) == true) != low })

        /** [reorder], after tagging each language's renditions and moving DEFAULT to the one [preference] picks. */
        fun arrange(p: HlsMultivariantPlaylist, text: String, low: Boolean, preference: AudioPreference): HlsMultivariantPlaylist {
            val contents = renditionContents(text)
            // One track per name, as Media3 groups them: a language's two codecs are one choice.
            val tracks = p.audios.distinctBy { it.name }.map { r ->
                r.name to AudioTrackInfo(
                    name = r.name,
                    language = r.format.language,
                    content = contents[r.groupId to r.name] ?: contents[null to r.name] ?: AudioTracks.contentOfName(r.name) ?: AudioContent.UNKNOWN,
                    isDefault = r.format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                )
            }
            val byName = tracks.toMap()
            val chosen = AudioTracks.choose(tracks, preference) { it.second }?.first
            val audios = p.audios.map { r ->
                val info = byName[r.name] ?: return@map r
                val role = when (info.content) {
                    AudioContent.ORIGINAL -> C.ROLE_FLAG_MAIN
                    AudioContent.DUBBED, AudioContent.AUTO_DUBBED -> C.ROLE_FLAG_DUB
                    AudioContent.DESCRIPTIVE -> C.ROLE_FLAG_DESCRIBES_VIDEO
                    AudioContent.SECONDARY -> C.ROLE_FLAG_ALTERNATE
                    AudioContent.UNKNOWN -> 0
                }
                val flags = when {
                    chosen == null -> r.format.selectionFlags
                    r.name == chosen -> r.format.selectionFlags or C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_AUTOSELECT
                    else -> r.format.selectionFlags and C.SELECTION_FLAG_DEFAULT.inv()
                }
                if (role == 0 && flags == r.format.selectionFlags) r
                else HlsMultivariantPlaylist.Rendition(r.url, r.format.buildUpon().setRoleFlags(r.format.roleFlags or role).setSelectionFlags(flags).build(), r.groupId, r.name, r.stableRenditionId)
            }
            return reorder(withAudios(p, audios), low)
        }

        /**
         * What each audio rendition is, by (GROUP-ID, NAME), from the tags YouTube puts on it: a
         * YT-EXT-XTAGS attribute, or the xtags its URI carries (`/xtags/acont%3Doriginal…/` or
         * `?xtags=…`). Renditions without either are left out.
         */
        internal fun renditionContents(text: String): Map<Pair<String?, String>, AudioContent> {
            val out = HashMap<Pair<String?, String>, AudioContent>()
            for (line in text.lineSequence()) {
                if (!line.startsWith("#EXT-X-MEDIA:")) continue
                val attrs = ATTRIBUTE.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].removeSurrounding("\"") }
                if (attrs["TYPE"] != "AUDIO") continue
                val name = attrs["NAME"] ?: continue
                val uri = attrs["URI"].orEmpty()
                val xtags = attrs["YT-EXT-XTAGS"]
                    ?: Regex("/xtags/([^/?]+)").find(uri)?.groupValues?.get(1)
                    ?: Regex("[?&]xtags=([^&]+)").find(uri)?.groupValues?.get(1)
                val content = AudioTracks.contentOf(xtags) ?: continue
                out[attrs["GROUP-ID"] to name] = content
                out[null to name] = content
            }
            return out
        }

        private val ATTRIBUTE = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

        private fun withAudios(p: HlsMultivariantPlaylist, audios: List<HlsMultivariantPlaylist.Rendition>): HlsMultivariantPlaylist {
            if (audios == p.audios) return p
            return HlsMultivariantPlaylist(
                p.baseUri, p.tags, p.variants, p.videos, audios, p.subtitles, p.closedCaptions,
                p.muxedAudioFormat, p.muxedCaptionFormats, p.hasIndependentSegments, p.variableDefinitions, p.sessionKeyDrmInitData, p.contentSteeringInfo,
            )
        }
    }
}
