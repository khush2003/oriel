package app.oriel.playback

import android.util.Log
import app.oriel.AppGraph
import app.oriel.BuildConfig
import app.oriel.core.PlaybackTracking
import app.oriel.core.WatchReport
import app.oriel.data.AccountManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tells YouTube what a signed-in user watches, as its own player would, so it
 * lands in their YouTube history with its progress bar and shapes their
 * recommendations: a start ping when a viewing begins, watch-time pings as
 * it goes and on pauses, and a final one when it ends. Shorts report too.
 *
 * Nothing is sent signed out or while history is paused. The pings go out
 * one at a time, in order, on the app's scope, so the final one of a viewing
 * still leaves when the player that made it is gone.
 *
 * A viewing belongs to the account it began under: its pings carry that [AccountManager.epoch]
 * and are dropped once another account is in use, never sent with its sign-in.
 */
class WatchReporter(private val graph: AppGraph, private val clock: () -> Long = System::currentTimeMillis) {
    private class Viewing(val report: WatchReport, val short: Boolean, val tracking: Deferred<PlaybackTracking?>, val epoch: Int)

    private var viewing: Viewing? = null
    private val outbox = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        graph.scope.launch(Dispatchers.IO) { for (send in outbox) runCatching { send() } }
    }

    /** [videoId] is playing at [positionMs]. Call on play and about once a second after. */
    fun playing(videoId: String, positionMs: Long, short: Boolean = false) {
        val current = viewing
        if (current?.report?.videoId != videoId) {
            finish()
            if (!graph.yt.isSignedIn || graph.settings.current.pauseHistory) return
            begin(videoId, positionMs, short)
            return
        }
        if (graph.settings.current.pauseHistory) { viewing = null; return }
        current.report.observe(positionMs / 1000.0, playing = true)
        if (current.report.due()) send(current, final = false)
    }

    /** Playback paused at [positionMs]: the stretch played so far goes out now. */
    fun paused(positionMs: Long) {
        val current = viewing ?: return
        current.report.observe(positionMs / 1000.0, playing = false)
        send(current, final = false)
    }

    /** The viewing ended (another video, Shorts paged on, the player closed): its final ping. */
    fun finish() {
        val current = viewing ?: return
        viewing = null
        current.report.observe(current.report.position, playing = false)
        send(current, final = true)
    }

    /** Ends the current viewing and waits, up to [timeoutMs], for the pings queued before it to go out. */
    suspend fun drain(timeoutMs: Long) {
        finish()
        val sent = CompletableDeferred<Unit>()
        if (outbox.trySend { sent.complete(Unit) }.isFailure) return
        withTimeoutOrNull(timeoutMs) { sent.await() }
    }

    /** Ends the current viewing and lets the pings still queued drain; this reporter is done. */
    fun close() {
        finish()
        outbox.close()
    }

    private fun begin(videoId: String, positionMs: Long, short: Boolean) {
        val report = WatchReport(videoId, clock)
        val epoch = graph.account.epoch
        val tracking = graph.scope.async(Dispatchers.IO) { if (graph.account.epoch == epoch) retrying({ it != null || graph.account.epoch != epoch }) { graph.yt.playbackTracking(videoId) } else null }
        val next = Viewing(report, short, tracking, epoch)
        viewing = next
        report.observe(positionMs / 1000.0, playing = true)
        val at = positionMs / 1000.0
        outbox.trySend {
            val urls = tracking.await()
            if (graph.account.epoch != epoch) return@trySend
            if (urls == null) log(videoId, "no signed-in tracking URLs")
            else log(videoId, "start → ${retrying({ delivered(it) || graph.account.epoch != epoch }) { graph.yt.reportStart(urls, report, at, short) }}")
        }
    }

    private fun send(viewing: Viewing, final: Boolean) {
        val ping = viewing.report.ping(final) ?: return
        outbox.trySend {
            val urls = viewing.tracking.await() ?: return@trySend
            if (graph.account.epoch != viewing.epoch) return@trySend
            // Not asked again once the account has changed: the ping would go out as the next one.
            val status = retrying({ delivered(it) || graph.account.epoch != viewing.epoch }) { graph.yt.reportWatchtime(urls, viewing.report, ping, viewing.short) }
            log(viewing.report.videoId, "watchtime ${ping.startTimes}–${ping.endTimes}${if (final) " final" else ""} → $status")
        }
    }

    /**
     * [block] again, twice at most with a pause, while [ok] says it didn't land: a lost ping
     * costs the video its place in YouTube's history.
     */
    private suspend fun <T> retrying(ok: (T) -> Boolean, block: suspend () -> T): T {
        var result = block()
        var tries = 0
        while (!ok(result) && tries < RETRIES) {
            delay(RETRY_DELAY_MS * (++tries))
            result = block()
        }
        return result
    }

    private fun delivered(status: Int?) = status != null && status in 200..299

    // Statuses only: never a URL, which carries the session's identifiers.
    // Debug builds only: the ids are a watch history, and logcat isn't the place for one.
    private fun log(videoId: String, line: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "$videoId $line")
    }

    private companion object {
        const val TAG = "WatchReporter"
        const val RETRIES = 2
        const val RETRY_DELAY_MS = 1_000L
    }
}
