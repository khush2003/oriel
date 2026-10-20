package app.oriel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The watch-time bookkeeping behind YouTube history: which stretches were
 * played, when pings fall due, and what a seek or a pause does to them.
 */
class WatchReportTest {
    private var now = 1_000_000L
    private fun report() = WatchReport("test", clock = { now })

    /** Plays from [from] for [seconds] seconds, seen once a second as the player reports it. */
    private fun WatchReport.play(from: Double, seconds: Int) {
        for (i in 0..seconds) {
            observe(from + i, playing = true)
            if (i < seconds) now += 1_000
        }
    }

    @Test fun continuousPlayIsOneStretch() {
        val r = report()
        r.play(0.0, 12)
        assertTrue(r.due(), "the first ping falls due at 10 s")
        val ping = r.ping()!!
        assertEquals("0.000", ping.startTimes)
        assertEquals("12.000", ping.endTimes)
        assertEquals("12.000", ping.currentMediaTime)
        assertEquals("12.000", ping.realTime)
        assertTrue(ping.playing)
        assertFalse(r.due(), "the next one is at 20 s")
    }

    @Test fun pingsFallDueAt10_20_30ThenEvery40() {
        val r = report()
        val dueAt = mutableListOf<Int>()
        for (s in 1..120) {
            now += 1_000
            r.observe(s.toDouble(), playing = true)
            if (r.due()) { dueAt += s; r.ping() }
        }
        assertEquals(listOf(10, 20, 30, 70, 110), dueAt)
    }

    @Test fun aSeekClosesTheStretchWhereItLeftOff() {
        val r = report()
        r.play(0.0, 5)
        now += 1_000
        r.observe(60.0, playing = true)
        r.play(60.0, 3)
        val ping = r.ping()!!
        assertEquals("0.000,60.000", ping.startTimes)
        assertEquals("5.000,63.000", ping.endTimes)
    }

    @Test fun seekingBackIsASeekToo() {
        val r = report()
        r.play(30.0, 10)
        now += 1_000
        r.observe(5.0, playing = true)
        r.play(5.0, 2)
        val ping = r.ping()!!
        assertEquals("30.000,5.000", ping.startTimes)
        assertEquals("40.000,7.000", ping.endTimes)
    }

    @Test fun pausedTimeIsntWatched() {
        val r = report()
        r.play(0.0, 4)
        r.observe(4.0, playing = false)
        now += 30_000
        r.observe(4.0, playing = false)
        val paused = r.ping()!!
        assertEquals("0.000", paused.startTimes)
        assertEquals("4.000", paused.endTimes)
        assertFalse(paused.playing)
        // Nothing played since: no ping until the viewing ends.
        assertNull(r.ping())
        val last = r.ping(final = true)!!
        assertTrue(last.final)
        assertEquals("", last.startTimes)
    }

    @Test fun resumingAfterAPauseStartsANewStretch() {
        val r = report()
        r.play(0.0, 3)
        r.observe(3.0, playing = false)
        r.ping()
        now += 10_000
        r.observe(3.0, playing = true)
        r.play(3.0, 2)
        val ping = r.ping()!!
        assertEquals("3.000", ping.startTimes)
        assertEquals("5.000", ping.endTimes)
    }

    @Test fun cpnIsSixteenWebSafeCharacters() {
        val cpn = WatchReport.randomCpn()
        assertEquals(16, cpn.length)
        assertTrue(cpn.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
}
