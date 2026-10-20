package app.oriel.util

import app.oriel.data.SettingsState
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the person is reporting; each kind opens its own GitHub issue form. */
enum class ReportKind(val label: String, val template: String) {
    BROKEN("Something's broken", "bug_report.yml"),
    PLAYBACK("A video won't play", "playback_problem.yml"),
    CRASH("The app crashed", "bug_report.yml"),
}

/**
 * One crash as kept on the phone: an uncaught exception ("crash"), or, read back on the next
 * launch, an ANR ("anr") or a native crash ("native"). [exception] is the class name alone,
 * the only part of it usage counts may carry; [signature] adds the first frame of Oriel's own.
 */
data class CrashRecord(val time: Long, val kind: String, val exception: String, val signature: String, val details: String) {
    /** The file it's kept in: a few `key=value` lines, `---`, then the scrubbed trace. */
    fun format(): String = buildString {
        appendLine("time=$time")
        appendLine("kind=$kind")
        appendLine("exception=$exception")
        appendLine("signature=$signature")
        appendLine("---")
        append(details)
    }

    companion object {
        /** Reads [format]'s text back; crashes kept before it existed are just a stamp line and a trace. */
        fun parse(text: String, fallbackTime: Long): CrashRecord {
            val split = text.indexOf("\n---\n")
            if (split < 0 || !text.startsWith("time=")) {
                val trace = text.lines().drop(1).joinToString("\n")
                val exception = trace.lineSequence().firstOrNull { it.isNotBlank() }?.substringBefore(':')?.substringAfterLast('.')?.trim().orEmpty().ifEmpty { "Unknown" }
                return CrashRecord(fallbackTime, "crash", exception, exception, text)
            }
            val fields = text.substring(0, split).lines().associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val exception = fields["exception"].orEmpty().ifEmpty { "Unknown" }
            return CrashRecord(
                time = fields["time"]?.toLongOrNull() ?: fallbackTime,
                kind = fields["kind"].orEmpty().ifEmpty { "crash" },
                exception = exception,
                signature = fields["signature"].orEmpty().ifEmpty { exception },
                details = text.substring(split + 5),
            )
        }
    }
}

/** A crash's short name: the root exception's class and the first frame in Oriel's own code. */
object CrashSignature {
    fun exception(t: Throwable): String = root(t).javaClass.let { it.simpleName.ifEmpty { it.name } }

    fun of(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.take(16).toList()
        // The innermost cause says what broke; Oriel's frame nearest to it says where.
        val frame = chain.asReversed().firstNotNullOfOrNull { e -> e.stackTrace.firstOrNull { it.className.startsWith(OWN) } }
        return withFrame(exception(t), frame?.let { "${it.className.removePrefix(OWN)}.${it.methodName}(${it.fileName ?: "?"}:${it.lineNumber})" })
    }

    /** For an ANR: the first of Oriel's frames in the main thread's stack, as `at app.oriel.…(File.kt:12)` lines give it. */
    fun ofAnr(mainThread: String): String {
        val frame = ANR_FRAME.find(mainThread)?.let { "${it.groupValues[1].removePrefix(OWN)}(${it.groupValues[2]})" }
        return withFrame("ANR", frame)
    }

    private fun withFrame(exception: String, frame: String?) = if (frame == null) exception else "$exception @ $frame"

    private fun root(t: Throwable): Throwable = generateSequence(t) { it.cause }.take(16).last()

    private const val OWN = "app.oriel."
    private val ANR_FRAME = Regex("""at (app\.oriel\.[^\s(]+)\(([^)]*)\)""")
}

/** The trail of recent errors: one line each, newest last; a repeat moves to the end and counts up rather than filling it. */
object ErrorTrail {
    private val COUNTED = Regex("""^(.*)  \(×(\d+)\)$""")

    fun add(lines: List<String>, stamp: String, text: String, max: Int): List<String> {
        var count = 1
        val kept = lines.filter { line ->
            val body = line.substringAfter("  ", line)
            val m = COUNTED.matchEntire(body)
            val same = (m?.groupValues?.get(1) ?: body) == text
            if (same) count += m?.groupValues?.get(2)?.toIntOrNull() ?: 1
            !same
        }
        return (kept + ("$stamp  $text" + if (count > 1) "  (×$count)" else "")).takeLast(max)
    }

    /** An ANR's trace is every thread; the main thread's stack (and the reason line) is what's worth keeping. */
    fun anrMainThread(trace: String, maxLines: Int = 60): String {
        val lines = trace.lines()
        val subject = lines.firstOrNull { it.startsWith("Subject:") }
        val start = lines.indexOfFirst { it.startsWith("\"main\"") }
        val main = if (start < 0) lines.take(maxLines) else {
            val end = (start + 1 until lines.size).firstOrNull { lines[it].isBlank() } ?: lines.size
            lines.subList(start, end).take(maxLines)
        }
        return (listOfNotNull(subject) + main).joinToString("\n")
    }
}

/** Everything that goes into one report; [header] and [settings] are label to value. */
data class ReportInput(
    val kind: ReportKind,
    val description: String,
    val videoLink: String?,
    val includeDiagnostics: Boolean,
    val header: List<Pair<String, String>>,
    val settings: List<Pair<String, String>>,
    /** Newest first. */
    val crashes: List<CrashRecord>,
    /** Oldest first, as kept. */
    val trail: List<String>,
)

/**
 * The text of a problem report, as shared or previewed. Everything in it is scrubbed (see
 * [Scrubber]), the person's own words included, and it's capped so a share intent never fails
 * on size: the newest crash comes first and the oldest are the ones cut.
 */
object ProblemReport {
    const val MAX_CHARS = 48_000
    private const val MAX_CRASH_CHARS = 8_000
    private const val MAX_TRAIL_CHARS = 12_000

    fun compose(input: ReportInput, maxChars: Int = MAX_CHARS): String {
        val text = buildString {
            appendLine("Oriel problem report: ${input.kind.label}")
            appendLine()
            appendLine("What happened:")
            appendLine(input.description.trim().ifEmpty { "(not described)" })
            input.videoLink?.trim()?.takeIf { it.isNotEmpty() }?.let { appendLine(); appendLine("Video: $it") }
            appendLine()
            if (input.includeDiagnostics) append(diagnostics(input, maxChars - length))
            else appendLine("Diagnostics not included.")
        }
        return cap(Scrubber.scrub(text).trimEnd() + "\n", maxChars)
    }

    /** The diagnostics alone (device, settings, crashes, errors), for the issue form's report field. */
    fun diagnostics(input: ReportInput, maxChars: Int = MAX_CHARS): String {
        val head = buildString {
            input.header.forEach { (k, v) -> appendLine("$k: $v") }
            if (input.settings.isNotEmpty()) {
                appendLine()
                appendLine("Settings:")
                input.settings.forEach { (k, v) -> appendLine("  $k: $v") }
            }
        }
        val trail = buildString {
            appendLine()
            if (input.trail.isEmpty()) appendLine("No recent errors.")
            else {
                appendLine("Recent errors (oldest first):")
                // The newest lines matter most: if the trail is long, its start goes.
                var shown = input.trail
                val budget = minOf(MAX_TRAIL_CHARS, maxChars / 4)
                while (shown.sumOf { it.length + 1 } > budget && shown.size > 1) shown = shown.drop(1)
                shown.forEach { appendLine(it) }
            }
        }
        val crashBudget = maxChars - head.length - trail.length - 200
        val crashes = buildString {
            appendLine()
            if (input.crashes.isEmpty()) { appendLine("No crashes recorded."); return@buildString }
            appendLine("Crashes (newest first):")
            input.crashes.forEachIndexed { i, c ->
                val title = "\n[${i + 1}] ${stamp(c.time)} · ${c.kind} · ${c.signature}\n"
                // The newest crash is always in, cut to what room there is; older ones only whole.
                val room = if (i == 0) (crashBudget - length - title.length - 120).coerceIn(600, MAX_CRASH_CHARS) else MAX_CRASH_CHARS
                val entry = title + cap(c.details.trimEnd(), room).trimEnd() + "\n"
                if (i > 0 && length + entry.length > crashBudget) {
                    appendLine()
                    appendLine("(${input.crashes.size - i} older crash${if (input.crashes.size - i == 1) "" else "es"} left out to keep the report short)")
                    return@buildString
                }
                append(entry)
            }
        }
        return cap(Scrubber.scrub(head + crashes + trail), maxChars)
    }

    /** The settings that help diagnose a problem; never the account, only whether there is one. */
    fun settingsSummary(s: SettingsState, signedIn: Boolean, telemetryAvailable: Boolean): List<Pair<String, String>> = listOf(
        "Video on Wi-Fi" to s.videoWifi.label,
        "Video on mobile data" to s.videoCellular.label,
        "Sound quality" to s.audioQuality.label,
        "Data saver" to onOff(s.dataSaver),
        "Protocol" to s.transport.label,
        "Premium formats" to onOff(s.premium),
        "Liquid glass" to onOff(s.liquidGlass),
        "Reduce motion" to onOff(s.reduceAnimation),
        "Reduce transparency" to onOff(s.reduceBlur),
        "Signed in" to if (signedIn) "yes" else "no",
        "SponsorBlock" to onOff(s.sponsorBlock),
        "Return YouTube Dislike" to onOff(s.returnDislike),
        "Usage counts" to when {
            !telemetryAvailable -> "not in this build"
            s.telemetry == null -> "not asked yet"
            else -> onOff(s.telemetry)
        },
    )

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    internal fun cap(text: String, max: Int): String {
        if (text.length <= max) return text
        val marker = "\n… (cut to fit)\n"
        return text.take((max - marker.length).coerceAtLeast(0)) + marker
    }

    fun stamp(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))
}

/**
 * The link that opens a new GitHub issue with the matching form filled in (the form's field ids
 * as query parameters; see .github/ISSUE_TEMPLATE). Browsers and GitHub stop at a URL of a few
 * thousand characters, so a report that doesn't fit is cut, with a line asking for the full one
 * to be pasted from the clipboard; [Link.truncated] says the caller must put it there.
 */
object IssueLink {
    const val NEW_ISSUE = "https://github.com/khush2003/oriel/issues/new"
    const val MAX_URL = 7_000
    const val PASTE_NOTE = "[Cut to fit in a link. Oriel copied the full report: paste it here in place of this part.]"
    private const val MAX_DESCRIPTION_ENCODED = 2_500

    data class Link(val url: String, val truncated: Boolean)

    fun build(
        kind: ReportKind,
        description: String,
        videoLink: String?,
        version: String,
        device: String,
        diagnostics: String?,
        crashSignature: String? = null,
        maxLength: Int = MAX_URL,
    ): Link {
        var truncated = false
        val said = Scrubber.scrub(description.trim())
        val what = fit(said, "…", MAX_DESCRIPTION_ENCODED).also { if (it != said) truncated = true }
        val link = videoLink?.trim()?.takeIf { it.isNotEmpty() }?.take(300)?.let(Scrubber::scrub)
        val fields = buildList {
            add("template" to kind.template)
            add("title" to title(kind, said, crashSignature))
            when (kind) {
                ReportKind.PLAYBACK -> {
                    if (link != null) add("link" to link)
                    add("what" to what)
                    add("version" to "$version, $device")
                }
                else -> {
                    add("what" to when {
                        kind == ReportKind.CRASH && what.isEmpty() -> "Oriel closed unexpectedly."
                        link != null -> "$what\n\nVideo: $link".trim()
                        else -> what
                    })
                    add("version" to version)
                    add("device" to device)
                }
            }
        }
        val report = diagnostics?.let(Scrubber::scrub)?.trimEnd()
        if (report == null) return Link(url(fields), truncated)
        // The playback form shows its report field as text already; the bug form's also takes
        // screenshots, so the report goes in a code block there.
        val (open, close) = if (kind == ReportKind.PLAYBACK) "" to "" else "```text\n" to "\n```"
        val whole = url(fields + ("report" to open + report + close))
        if (whole.length <= maxLength) return Link(whole, truncated)
        val budget = maxLength - url(fields + ("report" to open + close)).length
        val cut = fit(report, "\n…\n$PASTE_NOTE", budget, atLine = true)
        return Link(url(fields + ("report" to open + cut + close)), true)
    }

    private fun title(kind: ReportKind, description: String, signature: String?): String {
        val first = description.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val short = if (first.length > 72) first.take(71).trimEnd() + "…" else first
        return when (kind) {
            ReportKind.CRASH -> "Crash: " + (signature?.substringBefore(" @ ") ?: short.ifEmpty { "Oriel closed unexpectedly" })
            ReportKind.PLAYBACK -> "Playback: " + short.ifEmpty { "a video won't play" }
            ReportKind.BROKEN -> short
        }
    }

    /**
     * The longest start of [text] that, with [suffix] after it, encodes to at most [budget]
     * characters; [text] itself when it fits. [atLine] cuts at a line end when one is near.
     */
    internal fun fit(text: String, suffix: String, budget: Int, atLine: Boolean = false): String {
        if (encode(text).length <= budget) return text
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (encode(text.take(safe(text, mid)) + suffix).length <= budget) lo = mid else hi = mid - 1
        }
        var n = safe(text, lo)
        if (atLine) text.lastIndexOf('\n', n - 1).takeIf { it > n / 2 }?.let { n = it }
        if (n <= 0) return if (encode(suffix.trimStart()).length <= budget) suffix.trimStart() else ""
        return text.take(n).trimEnd() + suffix
    }

    /** Never ends between the two halves of a surrogate pair. */
    private fun safe(text: String, n: Int) = if (n in 1..text.length && text[n - 1].isHighSurrogate()) n - 1 else n

    private fun url(fields: List<Pair<String, String>>) = NEW_ISSUE + "?" + fields.joinToString("&") { (k, v) -> "$k=${encode(v)}" }

    // Spaces as %20: a '+' isn't read as a space everywhere a link may pass through.
    internal fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
