package app.oriel.util

import java.util.Locale

object Format {
    fun duration(totalSeconds: Long): String {
        if (totalSeconds < 0) return "--:--"
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun durationMs(ms: Long): String = duration(ms / 1000)

    fun bytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    /** 1234 → "1.2K", 3400000 → "3.4M", as YouTube abbreviates. */
    fun compact(n: Long): String = when {
        n >= 1_000_000_000 -> trim(n / 1_000_000_000.0) + "B"
        n >= 1_000_000 -> trim(n / 1_000_000.0) + "M"
        n >= 1_000 -> trim(n / 1_000.0) + "K"
        else -> n.toString()
    }

    // One decimal below ten ("1.2K", "3.4M"), whole numbers from ten up ("16M", "446K"), never rounded up.
    private fun trim(v: Double): String = if (v >= 10 || v % 1.0 < 0.05) v.toLong().toString() else String.format(Locale.US, "%.1f", kotlin.math.floor(v * 10) / 10)

    private val AGO = Regex("""(\d+)\s*(second|minute|hour|day|week|month|year)s?\s+ago""")

    /**
     * Rough age in seconds of a "3 days ago" / "Streamed 2 weeks ago" label, so
     * feeds merged from several channels can be put in upload order. Unknown → null.
     */
    fun ageSeconds(published: String?): Long? {
        val m = AGO.find(published ?: return null) ?: return null
        val n = m.groupValues[1].toLong()
        return n * when (m.groupValues[2]) {
            "second" -> 1L
            "minute" -> 60L
            "hour" -> 3_600L
            "day" -> 86_400L
            "week" -> 604_800L
            "month" -> 2_592_000L
            else -> 31_536_000L
        }
    }

    fun ago(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val s = ((now - epochMs) / 1000).coerceAtLeast(0)
        fun plural(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"} ago"
        return when {
            s < 60 -> "Just now"
            s < 3_600 -> plural(s / 60, "minute")
            s < 86_400 -> plural(s / 3_600, "hour")
            s < 604_800 -> plural(s / 86_400, "day")
            s < 2_592_000 -> plural(s / 604_800, "week")
            s < 31_536_000 -> plural(s / 2_592_000, "month")
            else -> plural(s / 31_536_000, "year")
        }
    }
}
