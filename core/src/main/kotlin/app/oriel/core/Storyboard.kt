package app.oriel.core

/**
 * YouTube's scrub previews: sprite sheets of evenly spaced frames, in a few
 * sizes. The player response describes them in one spec string,
 *
 *     baseUrl|w#h#count#cols#rows#intervalMs#name#sigh|…
 *
 * smallest level first. In the base URL `$L` is the level's index and `$N`
 * its name; a name of `M$M` numbers the sheets (M0, M1, …), while the
 * smallest level is a single sheet called `default`. An interval of 0 means
 * the frames are spread evenly over the whole video.
 */
data class Storyboard(val levels: List<Level>, val durationMs: Long) {
    data class Level(
        val index: Int,
        val baseUrl: String,
        val width: Int,
        val height: Int,
        val count: Int,
        val columns: Int,
        val rows: Int,
        val intervalMs: Long,
        val name: String,
        val sigh: String,
    ) {
        val perSheet: Int get() = columns * rows
        val sheetCount: Int get() = (count + perSheet - 1) / perSheet

        fun sheetUrl(sheet: Int): String =
            baseUrl.replace("\$L", index.toString()).replace("\$N", name.replace("\$M", sheet.toString())) + "&sigh=" + sigh
    }

    /** One frame: the sheet it's on and where it sits in it, in the sheet's pixels. */
    data class Frame(val url: String, val x: Int, val y: Int, val width: Int, val height: Int, val sheet: Int)

    /** The sharpest level; previews are drawn larger than any of them. */
    val best: Level get() = levels.last()

    fun frameAt(positionMs: Long, level: Level = best): Frame {
        val interval = if (level.intervalMs > 0) level.intervalMs else (durationMs / level.count).coerceAtLeast(1)
        val i = (positionMs.coerceAtLeast(0) / interval).toInt().coerceIn(0, level.count - 1)
        val sheet = i / level.perSheet
        val cell = i % level.perSheet
        return Frame(level.sheetUrl(sheet), (cell % level.columns) * level.width, (cell / level.columns) * level.height, level.width, level.height, sheet)
    }

    companion object {
        fun parse(spec: String?, durationSec: Long?): Storyboard? {
            val parts = spec?.split('|') ?: return null
            if (parts.size < 2 || durationSec == null || durationSec <= 0) return null
            val base = parts[0]
            val levels = parts.drop(1).mapIndexedNotNull { i, level ->
                val f = level.split('#')
                if (f.size != 8) return@mapIndexedNotNull null
                val (w, h, count, cols, rows) = f.take(5).map { it.toIntOrNull() ?: return@mapIndexedNotNull null }
                if (w <= 0 || h <= 0 || count <= 0 || cols <= 0 || rows <= 0) return@mapIndexedNotNull null
                Level(i, base, w, h, count, cols, rows, f[5].toLongOrNull() ?: 0L, f[6], f[7])
            }
            return if (levels.isEmpty()) null else Storyboard(levels.sortedBy { it.width }, durationSec * 1000)
        }
    }
}
