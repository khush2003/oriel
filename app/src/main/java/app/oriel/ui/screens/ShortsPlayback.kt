package app.oriel.ui.screens

import app.oriel.util.CrashLog
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import app.oriel.AppGraph
import app.oriel.core.model.Video
import app.oriel.playback.OrielMediaSourceFactory
import app.oriel.playback.MediaItems
import app.oriel.playback.OrielLoadErrorPolicy
import app.oriel.playback.StreamResolveIOException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The Shorts player: its own ExoPlayer over Oriel's pipeline, looping one short at a time,
 * with a preload manager readying its neighbours.
 *
 * Plays YouTube's HLS ladder, since fresh progressive URLs refuse reads for their first
 * seconds; a short the ladder refuses falls back to progressive. Next short: two seconds
 * loaded; the one after: tracks selected.
 */
@OptIn(UnstableApi::class)
internal class ShortsPlayback(context: android.content.Context, private val graph: AppGraph, private var maxHeight: Int) {
    private var current = 0
    private val items = HashMap<String, androidx.media3.common.MediaItem>()
    private val ranked = HashMap<String, Int>()

    private val control = object : androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
        override fun getTargetPreloadStatus(index: Int): DefaultPreloadManager.PreloadStatus = when (index - current) {
            1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(2_000L)
            2 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
            -1 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
            else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        }
    }

    private val sources = OrielMediaSourceFactory(graph.dataSources.playback, graph.dataSources.live, graph.adaptive.dataSource, graph::audioPreference) { graph.settings.lowAudio(graph.network.metered.value) }
    private val builder = DefaultPreloadManager.Builder(context, control)
        .setMediaSourceFactory(sources)
        // Starts on half a second of picture: a short is a swipe away from being skipped.
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(10_000, 30_000, 500, 1_500).build())
        .setBandwidthMeter(graph.abr.meter)
    val preload: DefaultPreloadManager = builder.build()
    val player: ExoPlayer = builder.buildExoPlayer(
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true),
    ).apply {
        repeatMode = Player.REPEAT_MODE_ONE
        trackSelectionParameters = trackSelectionParameters.buildUpon().setMaxVideoSize(maxHeight, maxHeight).build()
        addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) = recover(error)
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) currentMediaItem?.mediaId?.let(attempts::remove) }
        })
    }

    private val attempts = HashMap<String, Int>()

    /** The person paused this short: nothing but a tap, or a new short, plays it again. */
    var held = false

    /** The short that failed past every retry, and why, for the page to say so. */
    val failed = MutableStateFlow<ShortFailure?>(null)

    fun togglePlay(): Boolean {
        held = player.isPlaying
        if (held) player.pause() else { if (failed.value != null) retry() else player.play() }
        return !held
    }

    fun retry() {
        val id = player.currentMediaItem?.mediaId ?: return
        attempts.remove(id)
        failed.value = null
        held = false
        player.prepare()
        player.play()
    }

    fun resume() { if (!held && player.mediaItemCount > 0) player.play() }
    private val retryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    /**
     * A failed short is never left stuck. A refused URL benches the client that minted it and
     * asks for a fresh one, as the main player does. A dropped connection waits for the network
     * and tries again from where it was, backing off. A refusal or a broken ladder moves the
     * short to its progressive stream (and the preload map with it). Past three tries it
     * steps to the progressive stream regardless, then just retries in place. A progressive
     * stream that can't be played at all (private, removed, a bot check) says why at once.
     */
    private fun recover(error: androidx.media3.common.PlaybackException) {
        val item = player.currentMediaItem ?: return
        val id = item.mediaId
        val n = (attempts[id] ?: 0) + 1
        attempts[id] = n
        val causes = generateSequence(error.cause) { it.cause }.toList()
        val refused = causes.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull { it.responseCode in REFUSED_HTTP }
        if (refused != null) {
            graph.resolver.onRefused(refused.dataSpec.uri.toString())
            graph.resolver.invalidate(id)
        }
        val network = error.errorCode in 2000..2999 && error.errorCode != androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        val abr = MediaItems.kind(item) == MediaItems.KIND_ABR
        // Waiting can't fix these: a ladder goes to its progressive stream at once, and a
        // progressive stream fails, except that a refusal gets fresh URLs a few times first.
        val final = OrielLoadErrorPolicy.isFinal(error)
        if (!abr && ((final && (refused == null || n > 3)) || n > 6)) {
            fail(id, error, causes)
            return
        }
        retryScope.launch {
            when {
                // A fresh URL, at once.
                refused != null && !abr -> Unit
                network && n <= 3 && !final -> {
                    graph.network.online.first { it }
                    delay(400L * n)
                }
                abr -> {
                    val progressive = MediaItems.asProgressive(item)
                    items[id] = progressive
                    if (id in ranked) { preload.remove(item); preload.add(progressive, ranked.getValue(id)) }
                    val at = player.currentPosition
                    player.setMediaItem(progressive, at)
                }
                else -> delay(600L * n)
            }
            if (player.currentMediaItem?.mediaId != id) return@launch
            player.prepare()
            if (!held) player.play()
        }
    }

    private fun fail(id: String, error: androidx.media3.common.PlaybackException, causes: List<Throwable>) {
        val reason = causes.filterIsInstance<StreamResolveIOException>().firstOrNull()?.message
        failed.value = ShortFailure(id, reason ?: "Couldn't play this short.")
        CrashLog.note(graph.context, "Short $id ${error.errorCodeName} ← " + causes.joinToString(" ← ") { "${it::class.simpleName}: ${it.message?.take(160)}" })
    }

    /** Vertical or not, nothing past the quality's long side. */
    fun cap(height: Int) {
        maxHeight = height
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setMaxVideoSize(height, height).build()
    }

    private fun item(id: String) = items.getOrPut(id) { MediaItems.build(Video(id, "", isShort = true), maxHeight, adaptive = true) }

    /** Plays page [index] from its preloaded source when there is one, and readies its neighbours. */
    fun show(ids: List<String>, index: Int) {
        val id = ids.getOrNull(index) ?: return
        current = index
        // Newly listed shorts join the manager, ranked by their place in the pager: only the ones
        // the control readies.
        for (i in (index - 1).coerceAtLeast(0)..minOf(index + 2, ids.lastIndex)) {
            val other = ids[i]
            if (other !in ranked) { ranked[other] = i; preload.add(item(other), i) }
        }
        // And ones well behind are let go, so a long session doesn't hold every short it passed.
        ranked.entries.filter { it.value < index - 3 }.toList().forEach { (other, _) ->
            preload.remove(item(other)); ranked.remove(other); items.remove(other)
        }
        if (player.currentMediaItem?.mediaId != id) {
            val item = item(id)
            val source = preload.getMediaSource(item)
            if (source != null) player.setMediaSource(source) else player.setMediaItem(item)
            player.prepare()
            held = false
            failed.value = null
        }
        // More shorts arriving re-runs this for the same page: a paused one stays paused.
        if (!held) player.play()
        preload.setCurrentPlayingIndex(index)
        preload.invalidate()
    }

    fun release() {
        retryScope.cancel()
        preload.release()
        player.release()
    }
}

/** A short that couldn't be played, and the reason to show. */
internal data class ShortFailure(val id: String, val message: String)

/** googlevideo refusing a URL: expired, minted by a client it no longer trusts, or gone. */
private val REFUSED_HTTP = setOf(401, 403, 410)

@OptIn(UnstableApi::class)
@Composable
internal fun rememberShortsPlayback(graph: AppGraph, maxHeight: Int): ShortsPlayback {
    val context = androidx.compose.ui.platform.LocalContext.current
    val playback = remember { ShortsPlayback(context, graph, maxHeight) }
    DisposableEffect(playback) { onDispose { playback.release() } }
    return playback
}
