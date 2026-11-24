package app.oriel.data

import app.oriel.util.AppJson
import app.oriel.util.AtomicJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Last-known pages on disk, for stale-while-revalidate.
 *
 * Home and Explore render from here on a cold start — the first frame has
 * content instead of a spinner — while the network copy loads behind it.
 *
 * Pages are versioned by directory (`v<N>` under [root]): when the page models
 * change shape, [VERSION] goes up and the old pages are deleted rather than
 * migrated, since they're only a cache.
 *
 * Each profile has its own pages (`v<N>/<profile>`), so switching to one
 * shows its last Home at once rather than the account just left's.
 *
 * [clear] and [use] start a new [generation]; a page loaded before it is
 * dropped when written, so a load that was in flight can't bring back pages of
 * an account or a locale that was just left. Callers capture [generation] when
 * their load starts and pass it to [write].
 */
class PageDiskCache(
    private val root: File,
    scope: CoroutineScope,
    private val report: (String, Throwable) -> Unit = { _, _ -> },
    profileId: String,
) {
    private val versioned = File(root, "v$VERSION")
    @Volatile
    private var dir = File(versioned, profileId)
    private val io = CoroutineScope(scope.coroutineContext + Dispatchers.IO)
    private val lock = Any()
    private val gen = AtomicLong()

    /** Goes up with each [clear]. */
    val generation: Long get() = gen.get()

    init {
        io.launch {
            runCatching {
                // Other versions' pages, and the unversioned ones from before, aren't readable as these.
                root.listFiles { f -> f.name != versioned.name }?.forEach { it.deleteRecursively() }
                versioned.mkdirs()
            }
        }
    }

    fun <T> read(key: String, serializer: KSerializer<T>): T? = runCatching {
        AtomicJson.read(file(key))?.let { AppJson.decodeFromString(serializer, it) }
    }.getOrNull()

    /** When [key] was last written, or null if it never was. */
    fun savedAt(key: String): Long? = file(key).takeIf { it.isFile }?.lastModified()

    /** [key]'s file, inside [dir] whatever the key holds: one that isn't a plain name is hashed. */
    internal fun file(key: String): File = File(dir, fileName(key))

    /** Saves [value] unless the cache was cleared since [generation] (by default, now). */
    fun <T> write(key: String, serializer: KSerializer<T>, value: T, generation: Long = this.generation) {
        val text = runCatching { AppJson.encodeToString(serializer, value) }.getOrElse { report("Page cache encode", it); return }
        AtomicJson.persist(io, "Page cache save $key", report) {
            synchronized(lock) {
                if (generation != gen.get()) return@synchronized
                dir.mkdirs()
                AtomicJson.write(file(key), text)
            }
        }
    }

    /** Drops the pages of every profile: they're in a locale that's no longer used. */
    fun clear() = synchronized(lock) {
        gen.incrementAndGet()
        versioned.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** Reads and writes [profileId]'s pages from here on; a load begun for the profile before is dropped. */
    fun use(profileId: String) = synchronized(lock) {
        gen.incrementAndGet()
        dir = File(versioned, profileId)
    }

    /** Deletes [profileId]'s pages, as it's removed or signed out. */
    fun delete(profileId: String) = synchronized(lock) {
        val gone = File(versioned, profileId)
        if (gone == dir) gen.incrementAndGet()
        gone.deleteRecursively()
    }

    companion object {
        /** Bump when [app.oriel.core.model.Feed] or another cached page changes in a way old pages can't decode into. */
        const val VERSION = 2

        private val PLAIN = Regex("[A-Za-z0-9_-]{1,100}")

        internal fun fileName(key: String): String =
            if (PLAIN.matches(key)) "$key.json"
            else "h-" + java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json"
    }
}
