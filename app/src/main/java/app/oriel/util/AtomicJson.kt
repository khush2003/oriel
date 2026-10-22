package app.oriel.util

import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Whole-or-nothing text files: written next to the target, synced to the disk, then renamed over
 * it, so a crash or a full disk mid-write leaves the previous version rather than half a file.
 */
object AtomicJson {
    /** How long a failed write waits before its one retry. */
    const val RETRY_MS = 5_000L

    private val retrying: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The file's text, or null if there's none. Throws on an IO failure, which isn't the same as no file. */
    fun read(file: File): String? {
        val atomic = AtomicFile(file)
        // AtomicFile restores an interrupted write's backup on read; only then is a missing base file "none".
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return atomic.readFully().decodeToString()
    }

    /** Replaces [file] with [text]. Throws if it couldn't, leaving the old file as it was. */
    fun write(file: File, text: String) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(text.encodeToByteArray())
            atomic.finishWrite(out)
        } catch (e: Throwable) {
            atomic.failWrite(out)
            throw e
        }
    }

    /**
     * Runs [attempt] (which should write what's current when it runs), and if it fails, reports
     * it and tries once more about [RETRY_MS] later: a full disk or a busy one often clears.
     * One retry per [where] is pending at a time. Returns whether the first attempt went through.
     */
    fun persist(scope: CoroutineScope, where: String, report: (String, Throwable) -> Unit, attempt: () -> Unit): Boolean {
        val first = runCatching(attempt)
        val error = first.exceptionOrNull() ?: return true
        report(where, error)
        if (retrying.add(where)) {
            scope.launch {
                try {
                    delay(RETRY_MS)
                    runCatching(attempt).onFailure { report("$where (retry)", it) }
                } finally {
                    retrying.remove(where)
                }
            }
        }
        return false
    }
}
