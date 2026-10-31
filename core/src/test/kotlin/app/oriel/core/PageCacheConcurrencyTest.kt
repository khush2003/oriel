package app.oriel.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class PageCacheConcurrencyTest {
    @Test fun refreshDoesNotJoinTheOlderRequestOrLetItOverwriteTheFreshPage() = runBlocking {
        withTimeout(5_000) {
            val cache = PageCache(4)
            val started = CompletableDeferred<Unit>()
            val oldPage = CompletableDeferred<String>()
            val old = async(start = CoroutineStart.UNDISPATCHED) {
                cache.get("home", 60_000) { started.complete(Unit); oldPage.await() }
            }
            started.await()
            cache.remove("home")
            assertEquals("fresh", cache.get("home", 60_000) { "fresh" })
            oldPage.complete("old")
            assertEquals("old", old.await())
            assertEquals("fresh", cache.get<String>("home", 60_000) { error("already cached") })
        }
    }

    @Test fun anOldCompletionDoesNotRemoveTheNewRequestBeingShared() = runBlocking {
        withTimeout(5_000) {
            val cache = PageCache(4)
            val oldPage = CompletableDeferred<String>()
            val newPage = CompletableDeferred<String>()
            val oldStarted = CompletableDeferred<Unit>()
            val newStarted = CompletableDeferred<Unit>()
            val old = async { cache.get("home", 60_000) { oldStarted.complete(Unit); oldPage.await() } }
            oldStarted.await()
            cache.remove("home")
            val fresh = async { cache.get("home", 60_000) { newStarted.complete(Unit); newPage.await() } }
            newStarted.await()
            oldPage.complete("old")
            old.await()
            val shared = async(start = CoroutineStart.UNDISPATCHED) { cache.get<String>("home", 60_000) { error("must share the fresh load") } }
            newPage.complete("fresh")
            assertEquals("fresh", fresh.await())
            assertEquals("fresh", shared.await())
        }
    }

    @Test fun accountChangeDoesNotReuseOrCacheThePreviousAccountsLoad() = runBlocking {
        withTimeout(5_000) {
            val cache = PageCache(4)
            val oldPage = CompletableDeferred<String>()
            val started = CompletableDeferred<Unit>()
            val old = async { cache.get("home", 60_000) { started.complete(Unit); oldPage.await() } }
            started.await()
            cache.clear()
            assertEquals("account-b", cache.get("home", 60_000) { "account-b" })
            oldPage.complete("account-a")
            old.await()
            assertEquals("account-b", cache.get<String>("home", 60_000) { error("already cached") })
        }
    }
}
