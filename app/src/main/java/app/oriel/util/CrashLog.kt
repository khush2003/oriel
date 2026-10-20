package app.oriel.util

import android.content.Context
import android.os.Build
import app.oriel.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What went wrong, kept on the device so it can be shared from Settings
 * without a computer: the last few crashes in full, and a short trail of
 * playback errors. Nothing leaves the phone unless the user shares it.
 *
 * Reports are scrubbed before they're written: URL queries (which carry the
 * device's IP and signed stream parameters) are cut, and no request headers
 * are ever recorded, so the sign-in cookie can't end up in one.
 */
object CrashLog {
    private const val MAX_CRASHES = 5
    private const val MAX_EVENTS = 40

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** One line in the trail, e.g. a playback error the user saw as a message. */
    fun note(context: Context, line: String) {
        runCatching {
            val file = File(dir(context), "events.txt")
            val lines = (if (file.isFile) file.readLines() else emptyList()) + "${stamp()}  ${scrub(line)}"
            file.writeText(lines.takeLast(MAX_EVENTS).joinToString("\n", postfix = "\n"))
        }
    }

    fun crashCount(context: Context): Int = crashes(context).size

    /** Everything kept, newest crash first, ready to paste into a bug report. */
    fun report(context: Context): String = buildString {
        appendLine("Oriel ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val crashes = crashes(context)
        appendLine()
        appendLine(if (crashes.isEmpty()) "No crashes recorded." else "Crashes (newest first):")
        crashes.forEach { appendLine(); append(it.readText()) }
        val events = File(dir(context), "events.txt")
        if (events.isFile) {
            appendLine()
            appendLine("Recent playback errors:")
            append(events.readText())
        }
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = "${stamp()}  thread=${thread.name}\n${scrub(trace)}\n"
        File(dir(context), "crash-${System.currentTimeMillis()}.txt").writeText(text)
        crashes(context).drop(MAX_CRASHES).forEach { it.delete() }
    }

    private fun crashes(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name }.orEmpty()

    private fun dir(context: Context) = File(context.filesDir, "diagnostics").apply { mkdirs() }

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private val QUERY = Regex("""(https?://[^\s?#"']+)\?[^\s"']*""")

    internal fun scrub(text: String): String = QUERY.replace(text) { "${it.groupValues[1]}?…" }
}
