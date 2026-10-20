package app.oriel.core

import com.metrolist.innertubex.models.YouTubeClient
import java.util.Locale
import kotlin.random.Random

/**
 * Where YouTube wants one video's viewing reported, from a signed-in player
 * response: the start ping and the watch-time pings. They're what files a
 * video in the account's history, with its progress bar, and what
 * recommendations learn from.
 */
class PlaybackTracking internal constructor(
    internal val playbackUrl: String,
    internal val watchtimeUrl: String?,
    /** The client the player was asked as; the pings go out as the same one. */
    internal val client: YouTubeClient,
)

/** One watch-time ping: the stretches of media played since the last one, and where playback is. */
data class WatchPing(
    val startTimes: String,
    val endTimes: String,
    val currentMediaTime: String,
    val realTime: String,
    val final: Boolean,
    val playing: Boolean,
)

/**
 * One viewing of one video, kept the way YouTube's web player reports it: a
 * start ping, then watch-time pings with the media played since the last
 * (`st`/`et`, comma-separated stretches, so seeks show), the position (`cmt`)
 * and the seconds since the viewing began (`rt`). Pings fall due 10, 20 and
 * 30 seconds in and every 40 seconds after, as the web player's do, plus one
 * on each pause and a final one when the viewing ends.
 *
 * Only bookkeeping lives here; [YouTube.reportStart] and
 * [YouTube.reportWatchtime] send what it produces.
 */
class WatchReport(val videoId: String, private val clock: () -> Long = System::currentTimeMillis) {
    /** The viewing's client playback nonce, the same on every ping. */
    val cpn: String = randomCpn()
    private val startedAt = clock()
    private var nextDueSec = 10.0
    private var spanStart: Double? = null
    private var lastPosition = 0.0
    private var lastSeenAt = startedAt
    private val starts = ArrayList<Double>()
    private val ends = ArrayList<Double>()

    /** Where playback was when last seen, in media seconds. */
    val position: Double get() = lastPosition

    /**
     * What the player is doing now. Call it about once a second while playing
     * and on every play or pause; a jump that time passing doesn't explain is
     * a seek, and closes the stretch where it left off.
     */
    fun observe(positionSec: Double, playing: Boolean) {
        val now = clock()
        val open = spanStart
        if (open != null) {
            val expected = lastPosition + (now - lastSeenAt) / 1000.0
            val seeked = positionSec < lastPosition - 0.5 || positionSec > expected + SEEK_SLACK_SEC
            if (seeked || !playing) {
                close(open, if (seeked) lastPosition else positionSec)
                spanStart = if (playing) positionSec else null
            }
        } else if (playing) {
            spanStart = positionSec
        }
        lastPosition = positionSec
        lastSeenAt = now
    }

    /** Whether a periodic ping has fallen due. */
    fun due(): Boolean = realSeconds() >= nextDueSec

    /**
     * The ping for everything played since the last one, or null when nothing
     * was (a paused viewing has nothing to add until it's [final]).
     */
    fun ping(final: Boolean = false): WatchPing? {
        val rt = realSeconds()
        while (nextDueSec <= rt) nextDueSec += if (nextDueSec < 30) 10.0 else 40.0
        val open = spanStart
        if (open != null) {
            close(open, lastPosition)
            spanStart = if (final) null else lastPosition
        }
        if (starts.isEmpty() && !final) return null
        val ping = WatchPing(
            startTimes = starts.joinToString(",") { it.fmt() },
            endTimes = ends.joinToString(",") { it.fmt() },
            currentMediaTime = lastPosition.fmt(),
            realTime = rt.fmt(),
            final = final,
            playing = open != null && !final,
        )
        starts.clear()
        ends.clear()
        return ping
    }

    private fun close(from: Double, to: Double) {
        if (to - from < 0.05) return
        starts += from
        ends += to
    }

    private fun realSeconds() = (clock() - startedAt) / 1000.0

    companion object {
        /** Ticks are late by up to a second or two under load; more than that is a seek. */
        private const val SEEK_SLACK_SEC = 3.0
        private const val CPN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

        /** A fresh 16-character playback nonce, drawn as the web player draws it. */
        fun randomCpn(): String = String(CharArray(16) { CPN_ALPHABET[Random.nextInt(64)] })
    }
}

/** Media or real seconds as the stats endpoints take them. */
internal fun Double.fmt(): String = String.format(Locale.US, "%.3f", this)
