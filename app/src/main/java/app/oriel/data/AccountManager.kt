package app.oriel.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.core.content.edit
import app.oriel.core.YTException
import app.oriel.core.YouTube
import app.oriel.core.model.AccountInfo
import app.oriel.core.model.Channel
import app.oriel.core.model.Video
import app.oriel.core.stream.StreamResolver
import app.oriel.util.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * The signed-in YouTube session: the youtube.com cookie captured by the login
 * WebView, and the account it belongs to.
 *
 * The cookie is the credential. It stays in app-private storage (left out of
 * backups and device transfers), sealed with a key held in the Android Keystore ([SecretBox]),
 * is never logged, and goes only to YouTube's own hosts.
 */
class AccountManager(
    context: Context,
    /** Built off the main thread at launch; [restore] gets by without it. */
    private val client: () -> YouTube,
    private val resolver: () -> StreamResolver,
    private val library: LibraryStore,
    private val scope: CoroutineScope,
    /** Forgets the session's visitor identity (in memory and on disk) when signing out. */
    private val forgetVisitor: () -> Unit = {},
    private val box: SecretBox = SecretBox.keystore(KEY_ALIAS),
) {
    /** How a [sync] went, for a sync the person asked for to say so when it didn't. */
    enum class SyncResult { DONE, PARTIAL, FAILED, SKIPPED }

    private val yt: YouTube get() = client()
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val _account = MutableStateFlow<AccountInfo?>(null)
    val account: StateFlow<AccountInfo?> = _account.asStateFlow()
    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()
    private val _sessionExpired = MutableStateFlow(false)

    /** True when YouTube no longer accepts the saved sign-in: the person needs to sign in again. */
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    /**
     * Goes up with each sign-in and sign-out. Work that started under one session checks it
     * before writing, so a sync or refresh still running can't bring an account back after
     * signing out, or file one account's library under the next.
     */
    private val session = AtomicInteger()

    /**
     * The saved session as the UI sees it, from the prefs alone: the client may still be being
     * built, and gets the cookie itself from [applySession].
     */
    fun restore() {
        val stored = prefs.getString(KEY_COOKIE, null) ?: return
        if (!prefs.contains(KEY_SCHEMA)) prefs.edit { putInt(KEY_SCHEMA, if (SecretBox.isSealed(stored)) SCHEMA else 1) }
        // Only a signed-in cookie is ever sealed, so a sealed one says signed in without opening
        // it here, on the main thread; [applySession] opens it as the client is built, off it.
        _signedIn.value = SecretBox.isSealed(stored) || YouTube.isSignedInCookie(stored)
        _account.value = prefs.getString(KEY_ACCOUNT, null)?.let { runCatching { AppJson.decodeFromString<AccountInfo>(it) }.getOrNull()?.takeIf { a -> a.name.isNotBlank() } }
        scope.launch(Dispatchers.IO) {
            if (!SecretBox.isSealed(stored)) sealLegacy()
            refresh()
        }
    }

    /** Puts the saved session on [client], as it's built (before anyone else can use it). */
    fun applySession(client: YouTube) {
        val cookie = savedCookie() ?: return
        client.cookie = cookie
        client.dataSyncId = prefs.getString(KEY_DATASYNC, null)
    }

    /**
     * The saved cookie, opened. One that can't be opened (the keystore lost or invalidated its
     * key, or the value was tampered with) is a session that's gone: it's forgotten, and the app
     * is signed out rather than crashing on every launch.
     */
    private fun savedCookie(): String? = synchronized(prefs) {
        val stored = prefs.getString(KEY_COOKIE, null) ?: return null
        if (!SecretBox.isSealed(stored)) return stored
        // A keystore that's only busy gets a few more tries; still busy, this launch goes without
        // the session but keeps it for the next, rather than signing out.
        repeat(KEYSTORE_TRIES) { attempt ->
            val opened = try {
                box.open(stored)
            } catch (_: SecretBox.Unavailable) {
                if (attempt < KEYSTORE_TRIES - 1) Thread.sleep(KEYSTORE_RETRY_MS)
                return@repeat
            }
            return opened ?: forget()
        }
        return null
    }

    /** The saved session is gone for good: signed out, and nothing of it kept. */
    private fun forget(): String? {
        session.incrementAndGet()
        prefs.edit { clear().putInt(KEY_SCHEMA, SCHEMA) }
        _account.value = null
        _signedIn.value = false
        return null
    }

    /**
     * Earlier versions kept the cookie as plain text; it's sealed in place on the first
     * launch after the update, and the session carries on. If the keystore can't seal it now, it
     * stays as it was (no worse than before) and the next launch tries again.
     */
    private fun sealLegacy() = synchronized(prefs) {
        val stored = prefs.getString(KEY_COOKIE, null) ?: return
        if (SecretBox.isSealed(stored)) return
        if (!YouTube.isSignedInCookie(stored)) return
        val sealed = runCatching { box.seal(stored) }.getOrNull() ?: return
        prefs.edit { putString(KEY_COOKIE, sealed).putInt(KEY_SCHEMA, SCHEMA) }
    }

    suspend fun signIn(cookie: String, dataSyncId: String?): Boolean {
        yt.cookie = cookie
        yt.dataSyncId = dataSyncId
        if (!yt.isSignedIn) {
            yt.cookie = null
            return false
        }
        session.incrementAndGet()
        _sessionExpired.value = false
        // Never written in the clear: if the keystore can't seal it, the session lasts until the app
        // closes and the next launch is signed out.
        val sealed = withContext(Dispatchers.IO) { runCatching { box.seal(cookie) }.getOrNull() }
        // A new session's first sync merges rather than replaces: what was liked here signed out stays.
        prefs.edit {
            putInt(KEY_SCHEMA, SCHEMA).putString(KEY_DATASYNC, dataSyncId).remove(KEY_MERGED).remove(KEY_LAST_SYNC)
            if (sealed != null) putString(KEY_COOKIE, sealed) else remove(KEY_COOKIE)
        }
        lastSync = 0L
        _signedIn.value = true
        resolver().onSessionChanged()
        refresh()
        scope.launch(Dispatchers.IO) { sync() }
        return true
    }

    suspend fun refresh() {
        if (!yt.isSignedIn) return
        val started = session.get()
        val info = try {
            yt.account()
        } catch (e: CancellationException) {
            throw e
        } catch (e: YTException) {
            if (e.status == 401 || e.status == 403) expired(started)
            return
        } catch (_: Exception) {
            // Offline or YouTube busy: says nothing about the session.
            return
        }
        if (info == null) return expired(started)
        if (session.get() != started) return
        _sessionExpired.value = false
        _account.value = info
        prefs.edit { putString(KEY_ACCOUNT, AppJson.encodeToString(AccountInfo.serializer(), info)) }
    }

    private fun expired(started: Int) {
        if (session.get() == started && yt.isSignedIn) _sessionExpired.value = true
    }

    /**
     * Pulls liked videos, Watch Later and subscriptions into the local
     * library, so their state shows everywhere, including what changed on
     * YouTube's site or apps since.
     *
     * The first sync of a session only adds: likes, Watch later and follows
     * made here while signed out are kept. Later ones mirror the account,
     * except for what was changed here while they ran.
     */
    suspend fun sync(): SyncResult {
        if (!yt.isSignedIn || !_syncing.compareAndSet(false, true)) return SyncResult.SKIPPED
        val started = session.get()
        fun current() = session.get() == started
        val keepLocal = !prefs.getBoolean(KEY_MERGED, false)
        library.beginSync()
        try {
            val likes = runCatching {
                val (videos, complete) = wholePlaylist(YouTube.LIKED_VIDEOS)
                if (current()) library.mergeRemoteLikes(videos, complete, keepLocal)
            }
            val watchLater = runCatching {
                val (videos, complete) = wholePlaylist(YouTube.WATCH_LATER)
                if (current()) library.mergeRemoteWatchLater(videos, complete, keepLocal)
            }
            val subscriptions = runCatching {
                val channels = ArrayList<Channel>()
                var feed = yt.browse(YouTube.SUBSCRIBED_CHANNELS, fresh = true)
                channels += feed.items.filterIsInstance<Channel>()
                var guard = 0
                while (feed.continuation != null && guard++ < 20) {
                    feed = yt.more(feed.continuation!!)
                    channels += feed.items.filterIsInstance<Channel>()
                }
                // Cut off by the guard, the list is only part of them: add, don't replace. A complete
                // empty list is the account following no one, unfollowed elsewhere.
                val complete = feed.continuation == null
                if (current() && (channels.isNotEmpty() || complete)) library.mergeRemoteSubscriptions(channels.distinctBy { it.id }, complete, keepLocal)
            }
            val parts = listOf(likes, watchLater, subscriptions)
            parts.mapNotNull { it.exceptionOrNull() }.firstOrNull { it is CancellationException }?.let { throw it }
            val succeeded = parts.count { it.isSuccess }
            if (!current()) return SyncResult.SKIPPED
            if (keepLocal && succeeded == parts.size) prefs.edit { putBoolean(KEY_MERGED, true) }
            // Only a sync that got something counts as one; otherwise the next chance tries again.
            if (succeeded > 0) {
                lastSync = System.currentTimeMillis()
                prefs.edit { putLong(KEY_LAST_SYNC, lastSync) }
            }
            return when (succeeded) {
                parts.size -> SyncResult.DONE
                0 -> SyncResult.FAILED
                else -> SyncResult.PARTIAL
            }
        } finally {
            _syncing.value = false
        }
    }

    /** Kept across launches, so a cold start doesn't sync everything again minutes after the last one. */
    private var lastSync = prefs.getLong(KEY_LAST_SYNC, 0L)
    private var staleSync: Job? = null

    /** [sync] again when the app comes back after a while: likes and follows change on other devices too. */
    fun syncIfStale() {
        if (!yt.isSignedIn || System.currentTimeMillis() - lastSync < SYNC_EVERY_MS || staleSync?.isActive == true) return
        // After what's on screen has loaded, not competing with it.
        staleSync = scope.launch(Dispatchers.IO) {
            delay(5_000)
            sync()
        }
    }

    /** A playlist's videos, up to a few thousand, and whether that was all of them. */
    private suspend fun wholePlaylist(id: String): Pair<List<Video>, Boolean> {
        val videos = ArrayList<Video>()
        val page = yt.playlist(id, fresh = true)
        videos += page.videos
        var token = page.continuation
        var guard = 0
        while (token != null && guard++ < 30) {
            val next = yt.more(token)
            videos += next.items.filterIsInstance<Video>()
            token = next.continuation
        }
        return videos to (token == null)
    }

    fun signOut() {
        session.incrementAndGet()
        staleSync?.cancel()
        staleSync = null
        prefs.edit { clear().putInt(KEY_SCHEMA, SCHEMA) }
        yt.cookie = null
        yt.dataSyncId = null
        // The visitor identity was used alongside the account: the signed-out session starts a new one.
        forgetVisitor()
        _account.value = null
        _signedIn.value = false
        _sessionExpired.value = false
        // Also whatever a sign-in cut short (the process killed mid-way) left in the WebView.
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().apply { removeAllCookies(null); flush() }
        resolver().onSessionChanged()
    }

    companion object {
        private const val KEYSTORE_TRIES = 3
        private const val KEYSTORE_RETRY_MS = 150L
        private const val KEY_COOKIE = "cookie"
        /** The Android Keystore key the cookie is sealed with. */
        private const val KEY_ALIAS = "oriel.account.cookie"
        private const val KEY_DATASYNC = "datasync"
        private const val KEY_ACCOUNT = "account"
        private const val KEY_LAST_SYNC = "lastSync"
        /** Set once a session's first sync (the one that merges) has gone through. */
        private const val KEY_MERGED = "merged"
        private const val SYNC_EVERY_MS = 30 * 60_000L
        /** The version of what's kept in these prefs (see [app.oriel.util.Schema]); written with them. */
        private const val KEY_SCHEMA = "schema"
        /** 2: the cookie is sealed ([SecretBox]); 1 kept it as plain text, and is sealed on first read. */
        private const val SCHEMA = 2

        /** Google's sign-in, returning to youtube.com once the session cookie is set. */
        const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252F"
    }
}
