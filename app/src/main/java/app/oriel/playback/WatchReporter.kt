package app.oriel.playback

import android.util.Log
import app.oriel.AppGraph
import app.oriel.BuildConfig
import app.oriel.core.PlaybackTracking
import app.oriel.core.WatchReport
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Tells YouTube what a signed-in user watches, as its own player would, so it
 * lands in their YouTube history with its progress bar and shapes their
 * recommendations: a start ping when a viewing begins, watch-time pings as
 * it goes and on pauses, and a final one when it ends. Shorts report too.
 *
 * Nothing is sent signed out or while history is paused. The pings go out
 * one at a time, in order, on the app's scope, so the final one of a viewing
 * still leaves when the player that made it is gone.
 */
class WatchReporter(private val graph: AppGraph, private val clock: () -> Long = System::currentTimeMillis) {
    private class Viewing(val report: WatchReport, val short: Boolean, val tracking: Deferred<PlaybackTracking?>)

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

    /** Ends the current viewing and lets the pings still queued drain; this reporter is done. */
    fun close() {
        finish()
        outbox.close()
    }

    private fun begin(videoId: String, positionMs: Long, short: Boolean) {
        val report = WatchReport(videoId, clock)
        val tracking = graph.scope.async(Dispatchers.IO) { graph.yt.playbackTracking(videoId) }
        val next = Viewing(report, short, tracking)
        viewing = next
        report.observe(positionMs / 1000.0, playing = true)
        val at = positionMs / 1000.0
        outbox.trySend {
            val urls = tracking.await()
            if (urls == null) log(videoId, "no signed-in tracking URLs")
            else log(videoId, "start → ${graph.yt.reportStart(urls, report, at, short)}")
        }
    }

    private fun send(viewing: Viewing, final: Boolean) {
        val ping = viewing.report.ping(final) ?: return
        outbox.trySend {
            val urls = viewing.tracking.await() ?: return@trySend
            val status = graph.yt.reportWatchtime(urls, viewing.report, ping, viewing.short)
            log(viewing.report.videoId, "watchtime ${ping.startTimes}–${ping.endTimes}${if (final) " final" else ""} → $status")
        }
    }

    // Statuses only: never a URL, which carries the session's identifiers.
    // Debug builds only: the ids are a watch history, and logcat isn't the place for one.
    private fun log(videoId: String, line: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "$videoId $line")
    }

    private companion object {
        const val TAG = "WatchReporter"
    }
}
