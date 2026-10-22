package app.oriel.playback

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory

/**
 * Sound-only items play as one progressive stream. Video items (`oriel://av/…`)
 * merge two, since googlevideo serves picture and sound separately above 360p,
 * so the player buffers and seeks them as one. Live items play their HLS
 * manifest, and Auto items (`oriel://abr/…`) the video's HLS ladder.
 */
@OptIn(UnstableApi::class)
class OrielMediaSourceFactory(
    dataSourceFactory: DataSource.Factory,
    liveDataSourceFactory: DataSource.Factory,
    adaptiveDataSourceFactory: DataSource.Factory = liveDataSourceFactory,
    /** HE-AAC over AAC-LC when HLS offers both; see [AudioRenditionOrder]. */
    preferLowAudio: () -> Boolean = { false },
) : MediaSource.Factory {
    private val progressive = ProgressiveMediaSource.Factory(
        dataSourceFactory,
        DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
    )
    private val audioOrder = AudioRenditionOrder(preferLowAudio)
    private val hls = HlsMediaSource.Factory(liveDataSourceFactory).setAllowChunklessPreparation(true).setPlaylistParserFactory(audioOrder)
    private val adaptive = HlsMediaSource.Factory(adaptiveDataSourceFactory).setAllowChunklessPreparation(true).setPlaylistParserFactory(audioOrder)

    init { setLoadErrorHandlingPolicy(OrielLoadErrorPolicy()) }

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        progressive.setDrmSessionManagerProvider(drmSessionManagerProvider)
        hls.setDrmSessionManagerProvider(drmSessionManagerProvider)
        adaptive.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        progressive.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        hls.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        adaptive.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        if (uri?.scheme != "oriel") return progressive.createMediaSource(mediaItem)
        val id = uri.pathSegments.first()
        return when (uri.host) {
            MediaItems.KIND_HLS -> hls.createMediaSource(mediaItem)
            MediaItems.KIND_ABR -> adaptive.createMediaSource(mediaItem)
            MediaItems.KIND_AV -> {
                val height = uri.getQueryParameter("h") ?: MediaItems.DEFAULT_HEIGHT.toString()
                val audio = progressive.createMediaSource(mediaItem.buildUpon().setUri("oriel://${MediaItems.KIND_AUDIO}/$id?vh=$height".toUri()).build())
                val video = progressive.createMediaSource(MediaItem.Builder().setMediaId("$id#video").setUri("oriel://video/$id?h=$height".toUri()).build())
                MergingMediaSource(true, true, audio, video)
            }
            else -> progressive.createMediaSource(mediaItem)
        }
    }
}
