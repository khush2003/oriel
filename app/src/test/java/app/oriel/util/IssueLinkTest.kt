package app.oriel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/** The prefilled GitHub issue link: form, fields, and cutting a report that won't fit. Data is invented. */
class IssueLinkTest {
    private fun params(url: String): Map<String, String> {
        assertTrue(url, url.startsWith(IssueLink.NEW_ISSUE + "?"))
        return url.substringAfter('?').split('&').associate { p -> p.substringBefore('=') to URLDecoder.decode(p.substringAfter('='), "UTF-8") }
    }

    private fun build(kind: ReportKind = ReportKind.BROKEN, description: String = "The tab bar vanished.", link: String? = null, diagnostics: String? = "App: Oriel\nDevice: Fakephone", signature: String? = null) =
        IssueLink.build(kind, description, link, "0.9.2-alpha (16)", "Fakephone One, Android 16", diagnostics, signature)

    @Test fun aBugFillsTheBugForm() {
        val link = build()
        assertFalse(link.truncated)
        val p = params(link.url)
        assertEquals("bug_report.yml", p["template"])
        assertEquals("The tab bar vanished.", p["title"])
        assertEquals("The tab bar vanished.", p["what"])
        assertEquals("0.9.2-alpha (16)", p["version"])
        assertEquals("Fakephone One, Android 16", p["device"])
        assertEquals("```text\nApp: Oriel\nDevice: Fakephone\n```", p["report"])
        // Spaces travel as %20, never as '+'.
        assertFalse(link.url.contains('+'))
    }

    @Test fun aPlaybackProblemFillsThePlaybackForm() {
        val p = params(build(ReportKind.PLAYBACK, "Stops at 0:03", link = "https://youtu.be/dQw4w9WgXcQ").url)
        assertEquals("playback_problem.yml", p["template"])
        assertEquals("https://youtu.be/dQw4w9WgXcQ", p["link"])
        assertEquals("Stops at 0:03", p["what"])
        assertEquals("0.9.2-alpha (16), Fakephone One, Android 16", p["version"])
        assertEquals("Playback: Stops at 0:03", p["title"])
        assertEquals("App: Oriel\nDevice: Fakephone", p["report"])
        assertNull(p["device"])
    }

    @Test fun aCrashIsTitledByItsException() {
        val p = params(build(ReportKind.CRASH, "", signature = "IllegalStateException @ ui.Foo.bar(Foo.kt:1)").url)
        assertEquals("Crash: IllegalStateException", p["title"])
        assertEquals("Oriel closed unexpectedly.", p["what"])
    }

    @Test fun withoutDiagnosticsThereIsNoReportField() {
        assertNull(params(build(diagnostics = null).url)["report"])
    }

    @Test fun aLongReportIsCutToFitAndSaysWhereTheRestIs() {
        val lines = (1..600).map { "\tat app.oriel.Frame$it.run(Frame.kt:$it) ünïcødé 🎬" }
        val report = lines.joinToString("\n")
        val link = build(diagnostics = report)
        assertTrue(link.truncated)
        assertTrue("${link.url.length}", link.url.length <= IssueLink.MAX_URL)
        val shown = params(link.url)["report"]!!
        assertTrue(shown.startsWith("```text\n\tat app.oriel.Frame1.run"))
        assertTrue(shown.endsWith(IssueLink.PASTE_NOTE + "\n```"))
        // Cut at a line's end, and never through an emoji's two halves.
        val body = shown.removePrefix("```text\n").substringBefore("\n…\n")
        assertTrue(body.lines().all { it in lines })
        assertFalse(shown.contains('�'))
        assertFalse(shown.contains('?'))
    }

    @Test fun aReportThatJustFitsIsNotCut() {
        // Broken by dots: one unbroken run this long would be scrubbed as a token.
        val report = "fit.".repeat(1_500)
        val link = build(diagnostics = report)
        assertFalse(link.truncated)
        assertTrue(params(link.url)["report"]!!.contains(report))
    }

    @Test fun aVeryLongDescriptionIsCutToo() {
        val link = build(description = "Everything froze. ".repeat(2_000), diagnostics = "App: Oriel")
        assertTrue(link.truncated)
        assertTrue("${link.url.length}", link.url.length <= IssueLink.MAX_URL)
        assertTrue(params(link.url)["what"]!!.endsWith("…"))
        assertTrue(params(link.url)["title"]!!.length <= 72)
    }

    @Test fun fieldsAreScrubbed() {
        val p = params(build(description = "Signed in as someone@example.com", diagnostics = "Cookie: SAPISID=fake.cookie").url)
        assertEquals("Signed in as [email]", p["what"])
        assertEquals("```text\nCookie: [removed]\n```", p["report"])
    }
}
