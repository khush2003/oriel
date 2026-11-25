package app.oriel.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * A small LRU of parsed pages with per-entry expiry and in-flight de-duplication.
 *
 * A load runs on the cache's own scope, not its first caller's: when that
 * caller goes away (its screen closed, its search superseded), the others
 * waiting on the same page still get it, and it's still kept for next time.
 */
internal class PageCache(private val capacity: Int) {
    private class Entry(val value: Any?, val expiresAt: Long)

    private val map = object : LinkedHashMap<String, Entry>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > capacity
    }
    private val lock = Any()
    private val inflight = HashMap<String, Deferred<Any?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Bumped by [clear], so a load begun before it (another account, another locale) isn't kept after. */
    private var generation = 0

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> get(key: String, ttlMs: Long, load: suspend () -> T): T {
        val deferred = synchronized(lock) {
            map[key]?.let { if (it.expiresAt > System.currentTimeMillis()) return it.value as T else map.remove(key) }
            inflight[key] ?: run {
                val started = generation
                // The lock is held until this is registered, so the load's own removal below comes after.
                lateinit var pending: Deferred<Any?>
                pending = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        load().also { value ->
                            synchronized(lock) { if (generation == started && inflight[key] === pending) map[key] = Entry(value, System.currentTimeMillis() + ttlMs) }
                        }
                    } finally {
                        synchronized(lock) { if (inflight[key] === pending) inflight.remove(key) }
                    }
                }
                pending.also { inflight[key] = it; it.start() }
            }
        }
        return deferred.await() as T
    }

    fun remove(key: String) { synchronized(lock) { map.remove(key); inflight.remove(key) } }
    fun removeWhere(predicate: (String) -> Boolean) { synchronized(lock) { map.keys.removeAll(predicate); inflight.keys.removeAll(predicate) } }
    fun clear() {
        synchronized(lock) {
            generation++
            map.clear()
            inflight.clear()
        }
    }
}
