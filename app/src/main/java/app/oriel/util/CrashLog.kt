package app.oriel.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import app.oriel.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * What went wrong, kept on the device so it can be reported from the app without a computer:
 * the last few crashes in full (uncaught exceptions as they happen; ANRs and native crashes
 * read back from Android on the next launch), and a short trail of errors the person saw or
 * that were caught on the way (playback errors, failed resolves and page loads). Nothing leaves
 * the phone unless the person sends a report, or has opted into usage counts, which carry a
 * crash's kind and exception class and nothing more.
 *
 * Everything is scrubbed before it's written (see [Scrubber]), and no request headers are ever
 * recorded, so the sign-in cookie can't end up in a report.
 *
 * The playback service runs in the app's own process (it has no android:process), so the one
 * uncaught-exception handler installed in [app.oriel.OrielApp] covers it too.
 */
object CrashLog {
    private const val MAX_CRASHES = 5
    private const val MAX_EVENTS = 40
    private const val MAX_ANR_AGE_MS = 7 * 24 * 60 * 60_000L
    private const val PREFS = "diagnostics"
    private const val KEY_LAST_EXIT = "lastExit"
    private const val KEY_ASKED = "lastAsked"
    private const val KEY_COUNTED = "lastCounted"

    /** The page on top, set as the UI navigates, so a crash says where it happened. */
    @Volatile var screen: String? = null

    // Trail lines are written off the caller's thread (often the main one), one at a time.
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "CrashLog").apply { isDaemon = true } }

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** One line in the trail, e.g. a playback error the user saw as a message. A repeat counts up instead of adding a line. */
    fun note(context: Context, line: String) {
        val app = context.applicationContext
        val text = Scrubber.scrub(line.replace('\n', ' ').trim())
        io.execute {
            runCatching {
                val file = File(dir(app), "events.txt")
                val lines = if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()
                file.writeText(ErrorTrail.add(lines, ProblemReport.stamp(System.currentTimeMillis()), text, MAX_EVENTS).joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** A caught exception worth knowing about: [where] it was caught, its signature and message, in the trail. */
    fun nonFatal(context: Context, where: String, error: Throwable) {
        val root = generateSequence(error) { it.cause }.take(16).last()
        note(context, "$where: ${CrashSignature.of(error)}${root.message?.let { ": ${it.take(200)}" }.orEmpty()}")
    }

    fun crashCount(context: Context): Int = crashFiles(context).size

    /** The crashes kept, newest first. */
    fun crashes(context: Context): List<CrashRecord> = crashFiles(context).mapNotNull { f ->
        runCatching { CrashRecord.parse(f.readText(), f.name.removePrefix("crash-").substringBefore('.').substringBefore('-').toLongOrNull() ?: f.lastModified()) }.getOrNull()
    }

    /** The error trail, oldest first. */
    fun trail(context: Context): List<String> =
        File(dir(context), "events.txt").takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }.orEmpty()

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    // ---- after a crash ---------------------------------------------------------------------

    /**
     * ANRs and native crashes can't be caught in the act; Android keeps a record of how the
     * process ended, read here on the next launch (Android 11 and up). Only exits newer than
     * the last one looked at are recorded, so each is kept once. Java crashes aren't taken from
     * here: the handler has them already, with more detail.
     */
    fun recordExits(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = prefs(context)
        val since = prefs.getLong(KEY_LAST_EXIT, 0L)
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = runCatching { am.getHistoricalProcessExitReasons(null, 0, 16) }.getOrNull().orEmpty()
        val newest = exits.maxOfOrNull { it.timestamp } ?: return
        val cutoff = maxOf(since, System.currentTimeMillis() - MAX_ANR_AGE_MS)
        exits.filter { it.timestamp > cutoff && (it.reason == ApplicationExitInfo.REASON_ANR || it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) }
            .sortedBy { it.timestamp }
            .forEach { runCatching { writeExit(context, it) } }
        if (newest > since) prefs.edit { putLong(KEY_LAST_EXIT, newest) }
    }

    /** The newest crash if the person hasn't been asked about it yet. */
    fun unasked(context: Context): CrashRecord? {
        val asked = prefs(context).getLong(KEY_ASKED, 0L)
        return crashes(context).firstOrNull()?.takeIf { it.time > asked }
    }

    /** Remembers the person was asked about everything up to [time]; asked once, never again. */
    fun markAsked(context: Context, time: Long) {
        prefs(context).edit { putLong(KEY_ASKED, time) }
    }

    /** Crashes not yet counted (for opt-in usage counts), oldest first; each is handed out once. */
    fun takeUncounted(context: Context): List<CrashRecord> {
        val prefs = prefs(context)
        val counted = prefs.getLong(KEY_COUNTED, 0L)
        val fresh = crashes(context).filter { it.time > counted }.sortedBy { it.time }
        fresh.lastOrNull()?.let { prefs.edit { putLong(KEY_COUNTED, it.time) } }
        return fresh
    }

    // ---- the device, for a report's header -------------------------------------------------

    /** Facts about the build and the device that help with a report; nothing that identifies the person. */
    fun device(context: Context): List<Pair<String, String>> {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val gb = Math.round(mem.totalMem / (1024.0 * 1024 * 1024)).coerceAtLeast(1)
        val metrics = context.resources.displayMetrics
        return listOf(
            "App" to "Oriel ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), ${BuildConfig.BUILD_TYPE}",
            "Installed from" to installSource(context),
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "ABI" to Build.SUPPORTED_ABIS.joinToString(", "),
            "Memory" to "$gb GB, app limit ${am?.largeMemoryClass ?: "?"} MB${if (am?.isLowRamDevice == true) ", low-RAM device" else ""}",
            "Screen" to "${metrics.widthPixels} × ${metrics.heightPixels} px, ${metrics.densityDpi} dpi",
            "Locale" to Locale.getDefault().toLanguageTag(),
        )
    }

    private fun installSource(context: Context): String {
        val pm = context.packageManager
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(context.packageName).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
        }.getOrNull()
        return when (installer) {
            null -> "unknown (adb or a file)"
            "com.android.vending" -> "Play Store"
            "org.fdroid.fdroid", "org.fdroid.basic", "com.looker.droidify", "com.machiav3lli.fdroid" -> "F-Droid ($installer)"
            "com.google.android.packageinstaller", "com.android.packageinstaller" -> "APK file"
            else -> installer
        }
    }

    // ---- files -----------------------------------------------------------------------------

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val now = System.currentTimeMillis()
        val details = buildString {
            appendLine("thread=${thread.name}")
            screen?.let { appendLine("screen=$it") }
            appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine(trace)
        }
        save(context, CrashRecord(now, "crash", CrashSignature.exception(error), Scrubber.scrub(CrashSignature.of(error)), Scrubber.scrub(details)))
    }

    @RequiresApi(30)
    private fun writeExit(context: Context, exit: ApplicationExitInfo) {
        val anr = exit.reason == ApplicationExitInfo.REASON_ANR
        // An ANR's trace is a text dump of every thread; a native crash's is a binary tombstone, not kept.
        val trace = if (anr) runCatching { exit.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull() else null
        val main = trace?.let { ErrorTrail.anrMainThread(it) }
        val details = buildString {
            appendLine("process=${exit.processName}")
            exit.description?.let { appendLine("description=$it") }
            if (!anr) appendLine("signal=${exit.status}")
            appendLine(if (main.isNullOrBlank()) "(no trace)" else main)
        }
        val record = if (anr) CrashRecord(exit.timestamp, "anr", "ANR", CrashSignature.ofAnr(main.orEmpty()), Scrubber.scrub(details))
        else CrashRecord(exit.timestamp, "native", "NativeCrash", "NativeCrash (signal ${exit.status})", Scrubber.scrub(details))
        save(context, record)
    }

    private fun save(context: Context, record: CrashRecord) {
        File(dir(context), "crash-${record.time}-${record.kind}.txt").writeText(record.format())
        crashFiles(context).drop(MAX_CRASHES).forEach { it.delete() }
    }

    private fun crashFiles(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name }.orEmpty()

    private fun dir(context: Context) = File(context.filesDir, "diagnostics").apply { mkdirs() }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
