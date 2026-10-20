package app.oriel.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable

/**
 * Which of YouTube's two HLS audio renditions plays. The ladder lists HE-AAC (itag 233,
 * about 48 kbps) and AAC-LC (itag 234, about 128 kbps), declares neither's bitrate, and
 * pairs every variant from 360p up with the AAC-LC one. Media3 picks alternate audio on its
 * own, and with nothing to compare it took the first listed: HE-AAC, under a 4K picture.
 * This puts AAC-LC first, or HE-AAC when [preferLow] (Sound quality set to Low, or the data
 * saver on mobile data), so the setting decides rather than the playlist's order.
 */
@OptIn(UnstableApi::class)
class AudioRenditionOrder(private val preferLow: () -> Boolean) : HlsPlaylistParserFactory {
    private val base = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> = wrap(base.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> = wrap(base.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private fun wrap(parser: ParsingLoadable.Parser<HlsPlaylist>) = ParsingLoadable.Parser { uri, input ->
        val playlist = parser.parse(uri, input)
        if (playlist is HlsMultivariantPlaylist && playlist.audios.size > 1) reorder(playlist, preferLow()) else playlist
    }

    companion object {
        private const val HE_AAC = "mp4a.40.5"

        fun reorder(p: HlsMultivariantPlaylist, low: Boolean): HlsMultivariantPlaylist {
            val audios = p.audios.sortedBy { a -> (a.format.codecs?.contains(HE_AAC) == true) != low }
            if (audios == p.audios) return p
            return HlsMultivariantPlaylist(
                p.baseUri, p.tags, p.variants, p.videos, audios, p.subtitles, p.closedCaptions,
                p.muxedAudioFormat, p.muxedCaptionFormats, p.hasIndependentSegments, p.variableDefinitions, p.sessionKeyDrmInitData, p.contentSteeringInfo,
            )
        }
    }
}
