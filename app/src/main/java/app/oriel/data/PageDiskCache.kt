package app.oriel.data

import app.oriel.util.AppJson
import kotlinx.serialization.KSerializer
import java.io.File

/**
 * Last-known pages on disk, for stale-while-revalidate.
 *
 * Home and Explore render from here on a cold start — the first frame has
 * content instead of a spinner — while the network copy loads behind it.
 */
class PageDiskCache(private val dir: File) {
    init {
        dir.mkdirs()
    }

    fun <T> read(key: String, serializer: KSerializer<T>): T? = runCatching {
        File(dir, "$key.json").takeIf { it.isFile }?.readText()?.let { AppJson.decodeFromString(serializer, it) }
    }.getOrNull()

    /** When [key] was last written, or null if it never was. */
    fun savedAt(key: String): Long? = File(dir, "$key.json").takeIf { it.isFile }?.lastModified()

    fun <T> write(key: String, serializer: KSerializer<T>, value: T) {
        runCatching {
            val tmp = File(dir, "$key.json.tmp")
            tmp.writeText(AppJson.encodeToString(serializer, value))
            tmp.renameTo(File(dir, "$key.json"))
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
