package app.oriel.util

import app.oriel.data.SettingsState
import app.oriel.data.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The report composer and the pieces it's built from. All data is invented. */
class ProblemReportTest {
    private val header = listOf("App" to "Oriel 0.9.2-alpha (16), release", "Device" to "Fakephone One", "Android" to "16 (API 36)")

    private fun crash(time: Long, signature: String, details: String = "java.lang.IllegalStateException: boom\n\tat app.oriel.Foo.bar(Foo.kt:1)\n") =
        CrashRecord(time, "crash", signature.substringBefore(" @ "), signature, details)

    private fun input(
        include: Boolean = true,
        crashes: List<CrashRecord> = listOf(crash(2_000, "IllegalStateException @ ui.Newer.run(Newer.kt:2)"), crash(1_000, "NullPointerException @ ui.Older.run(Older.kt:1)")),
        trail: List<String> = listOf("2026-10-20 10:00:00  dQw4w9WgXcQ ERROR_CODE_IO_BAD_HTTP_STATUS HTTP 403 rr1---sn-fake.googlevideo.com"),
        description: String = "The video stops after a few seconds.",
    ) = ReportInput(
        kind = ReportKind.PLAYBACK,
        description = description,
        videoLink = "https://youtu.be/dQw4w9WgXcQ",
        includeDiagnostics = include,
        header = header,
        settings = ProblemReport.settingsSummary(SettingsState(transport = Transport.SABR), signedIn = true, telemetryAvailable = true),
        crashes = crashes,
        trail = trail,
    )

    @Test fun reportHasEverythingInOrder() {
        val text = ProblemReport.compose(input())
        val order = listOf(
            "Oriel problem report: A video won't play",
            "The video stops after a few seconds.",
            "Video: https://youtu.be/dQw4w9WgXcQ",
            "App: Oriel 0.9.2-alpha (16), release",
            "Protocol: SABR",
            "Crashes (newest first):",
            "IllegalStateException @ ui.Newer.run(Newer.kt:2)",
            "NullPointerException @ ui.Older.run(Older.kt:1)",
            "Recent errors (oldest first):",
            "HTTP 403 rr1---sn-fake.googlevideo.com",
        )
        var at = -1
        order.forEach { part ->
            val i = text.indexOf(part)
            assertTrue("missing or out of order: $part\n$text", i > at)
            at = i
        }
    }

    @Test fun withoutDiagnosticsOnlyTheirWordsAndTheLinkGo() {
        val text = ProblemReport.compose(input(include = false))
        assertTrue(text.contains("The video stops after a few seconds."))
        assertTrue(text.contains("https://youtu.be/dQw4w9WgXcQ"))
        assertTrue(text.contains("Diagnostics not included."))
        assertFalse(text.contains("Fakephone"))
        assertFalse(text.contains("IllegalStateException"))
        assertFalse(text.contains("HTTP 403"))
    }

    @Test fun settingsSayWhetherSignedInButNeverWho() {
        val s = ProblemReport.settingsSummary(SettingsState(telemetry = null), signedIn = true, telemetryAvailable = true).toMap()
        assertEquals("yes", s["Signed in"])
        assertEquals("not asked yet", s["Usage counts"])
        assertEquals("on", s["SponsorBlock"])
        assertEquals("not in this build", ProblemReport.settingsSummary(SettingsState(), false, telemetryAvailable = false).toMap()["Usage counts"])
        assertEquals("no", ProblemReport.settingsSummary(SettingsState(), false, false).toMap()["Signed in"])
    }

    @Test fun theirOwnWordsAreScrubbedToo() {
        val text = ProblemReport.compose(input(description = "Mail me at someone@example.com, see https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=fakeshare"))
        assertFalse(text.contains("someone@example.com"))
        assertFalse(text.contains("si=fakeshare"))
        assertTrue(text.contains("watch?v=dQw4w9WgXcQ"))
    }

    @Test fun aHugeReportIsCappedAndKeepsTheNewestCrash() {
        val big = "\tat app.oriel.Deep.frame(Deep.kt:1)\n".repeat(400)
        val crashes = (5 downTo 1).map { crash(it * 1_000L, "IllegalStateException @ ui.Crash$it.run(C.kt:$it)", big) }
        val trail = (1..40).map { "2026-10-20 10:00:${"%02d".format(it % 60)}  line $it " + "x".repeat(250) }
        val text = ProblemReport.compose(input(crashes = crashes, trail = trail), maxChars = 20_000)
        assertTrue("${text.length}", text.length <= 20_000)
        assertTrue(text.contains("ui.Crash5.run"))
        assertTrue(text.contains("left out to keep the report short"))
        // The newest errors outlast the oldest.
        assertTrue(text.contains("line 40 "))
        assertTrue(text.contains("Recent errors"))
    }

    @Test fun crashRecordsRoundTrip() {
        val r = CrashRecord(1_729_000_000_000, "anr", "ANR", "ANR @ ui.Foo.bar(Foo.kt:3)", "process=app.oriel\nSubject: Input dispatching timed out\n")
        assertEquals(r, CrashRecord.parse(r.format(), 0))
    }

    @Test fun crashesKeptBeforeTheNewFormatStillRead() {
        val legacy = "2026-10-01 09:00:00  thread=main\njava.lang.IllegalArgumentException: bad\n\tat app.oriel.Foo.bar(Foo.kt:1)\n"
        val r = CrashRecord.parse(legacy, 42)
        assertEquals(42L, r.time)
        assertEquals("crash", r.kind)
        assertEquals("IllegalArgumentException", r.exception)
    }

    @Test fun signatureIsTheRootCauseAndOurFirstFrame() {
        val root = IllegalStateException("inner").apply {
            stackTrace = arrayOf(
                StackTraceElement("java.util.ArrayList", "get", "ArrayList.java", 437),
                StackTraceElement("app.oriel.ui.screens.FeedModel", "reload", "FeedModel.kt", 131),
            )
        }
        val outer = RuntimeException("outer", root).apply {
            stackTrace = arrayOf(StackTraceElement("app.oriel.AppGraph", "start", "AppGraph.kt", 9))
        }
        assertEquals("IllegalStateException @ ui.screens.FeedModel.reload(FeedModel.kt:131)", CrashSignature.of(outer))
        assertEquals("IllegalStateException", CrashSignature.exception(outer))
        val foreign = NullPointerException().apply { stackTrace = arrayOf(StackTraceElement("java.lang.String", "length", null, -1)) }
        assertEquals("NullPointerException", CrashSignature.of(foreign))
    }

    @Test fun anrKeepsTheMainThreadAndFindsOurFrame() {
        val trace = """
            ----- pid 1234 at 2026-10-20 10:00:00 -----
            Subject: Input dispatching timed out (fake window)

            "Signal Catcher" daemon prio=10 tid=7 Runnable
              at java.lang.Object.wait(Native method)

            "main" prio=5 tid=1 Sleeping
              at java.lang.Thread.sleep(Native method)
              at app.oriel.ui.player.VideoPlayer.slow(VideoPlayer.kt:77)
              at android.os.Looper.loop(Looper.java:288)

            "RenderThread" prio=7 tid=12 Native
              at app.oriel.Other.frame(Other.kt:1)
        """.trimIndent()
        val main = ErrorTrail.anrMainThread(trace)
        assertTrue(main.startsWith("Subject: Input dispatching timed out"))
        assertTrue(main.contains("VideoPlayer.slow"))
        assertFalse(main.contains("Signal Catcher"))
        assertFalse(main.contains("RenderThread"))
        assertEquals("ANR @ ui.player.VideoPlayer.slow(VideoPlayer.kt:77)", CrashSignature.ofAnr(main))
        assertEquals("ANR", CrashSignature.ofAnr("\"main\" prio=5\n  at android.os.Looper.loop(Looper.java:1)"))
    }

    @Test fun repeatedTrailLinesCountUpInsteadOfFilling() {
        var lines = emptyList<String>()
        lines = ErrorTrail.add(lines, "t1", "Loading home: YTException", 3)
        lines = ErrorTrail.add(lines, "t2", "other", 3)
        lines = ErrorTrail.add(lines, "t3", "Loading home: YTException", 3)
        assertEquals(listOf("t2  other", "t3  Loading home: YTException  (×2)"), lines)
        lines = ErrorTrail.add(lines, "t4", "Loading home: YTException", 3)
        assertEquals(listOf("t2  other", "t4  Loading home: YTException  (×3)"), lines)
        lines = ErrorTrail.add(lines, "t5", "a", 3)
        lines = ErrorTrail.add(lines, "t6", "b", 3)
        assertEquals(listOf("t4  Loading home: YTException  (×3)", "t5  a", "t6  b"), lines)
    }
}
