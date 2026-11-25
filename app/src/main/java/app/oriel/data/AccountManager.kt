package app.oriel.data

import android.webkit.CookieManager
import android.webkit.WebStorage
import app.oriel.core.YTException
import app.oriel.core.YouTube
import app.oriel.core.model.AccountInfo
import app.oriel.core.model.Channel
import app.oriel.core.model.Video
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * The profiles kept on this device (a Guest, and the YouTube accounts signed in here) and the one
 * in use: its sign-in on the client, its library active, its account shown.
 *
 * Each account's sign-in is the youtube.com cookie the login WebView captured for it alone. The
 * cookies are the credentials: kept in app-private storage (left out of backups and device
 * transfers), sealed with a key held in the Android Keystore ([SecretBox]), never logged, and sent
 * only to YouTube's own hosts. Accounts never share one, and only the profile in use makes requests.
 *
 * Switching is a warm restart of everything that belongs to a profile ([switchTo]); [epoch] goes up
 * with each one and with each sign-in and sign-out, and work begun under one session checks it
 * before it writes or sends, so nothing of one account ever lands in, or goes out as, another.
 */
class AccountManager(
    private val store: ProfileStore,
    /** Built off the main thread at launch; [restore] gets by without it. */
    private val client: () -> YouTube,
    /** The client if it's been built, for what shouldn't build it. */
    private val builtClient: () -> YouTube?,
    /** Tells the stream resolver the session changed, and whether this account asks for Premium formats. */
    private val onSessionChanged: (premium: Boolean) -> Unit,
    private val library: ActiveLibrary,
    private val pages: PageDiskCache,
    private val scope: CoroutineScope,
    /** Forgets what a sign-in left in the WebView. Main thread. */
    private val clearWebSession: () -> Unit = ::clearWebView,
    /** Where [SwitchListener]s and [clearWebSession] are called. */
    private val main: CoroutineDispatcher = Dispatchers.Main,
) {
    /** How a [sync] went, for a sync the person asked for to say so when it didn't. */
    enum class SyncResult { DONE, PARTIAL, FAILED, SKIPPED }

    /** How a sign-in from the login page went. */
    enum class SignInResult { ADDED, SIGNED_IN_AGAIN, SWITCHED_TO_EXISTING, FAILED, TOO_MANY }

    /**
     * Told around a switch, on the main thread: [leaving] before anything of the profile being
     * left is let go (stop playing, write what's unsaved, let queued requests go out), [arrived]
     * once the next one is in place.
     */
    interface SwitchListener {
        suspend fun leaving() {}
        fun arrived() {}
    }

    private val yt: YouTube get() = client()

    private val _profiles = MutableStateFlow(store.index.profiles)
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()
    private val _active = MutableStateFlow(store.index.activeProfile)
    val active: StateFlow<Profile> = _active.asStateFlow()

    private val _account = MutableStateFlow<AccountInfo?>(null)
    /** The active profile's account, once known; null for the Guest. */
    val account: StateFlow<AccountInfo?> = _account.asStateFlow()
    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()
    private val _switching = MutableStateFlow(false)
    /** True while a switch between profiles is under way. */
    val switching: StateFlow<Boolean> = _switching.asStateFlow()
    private val _sessionExpired = MutableStateFlow(false)

    /** True when YouTube no longer accepts the active profile's sign-in: the person needs to sign in again. */
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    private val epochCounter = AtomicInteger()

    /** Goes up with each switch, sign-in and sign-out; see the class comment. */
    val epoch: Int get() = epochCounter.get()

    /** Sign-ins the keystore couldn't seal: they last until the app closes, and are never written in the clear. */
    private val unsealed = ConcurrentHashMap<String, String>()

    private val listeners = CopyOnWriteArrayList<SwitchListener>()
    fun addSwitchListener(listener: SwitchListener) = listeners.add(listener)
    fun removeSwitchListener(listener: SwitchListener) = listeners.remove(listener)

    /** One switch, sign-in or sign-out at a time. */
    private val changing = Mutex()

    /** Puts the index as it is now on the flows; [signedIn] when the active profile's sign-in is usable. */
    private fun publish(signedIn: Boolean = _signedIn.value) {
        val index = store.index
        val active = index.activeProfile
        _profiles.value = index.profiles
        _active.value = active
        _account.value = active.info?.takeIf { !active.isGuest }
        _signedIn.value = signedIn && active.signedIn
    }

    private fun hasSignIn(profile: Profile): Boolean = profile.credential?.let { unsealed.containsKey(it) || store.hasCookie(it) } == true

    /**
     * The saved profiles as the UI sees them, from the prefs alone: the client may still be being
     * built, and takes the cookie itself in [applySession].
     */
    fun restore() {
        val active = store.index.activeProfile
        publish(signedIn = hasSignIn(active))
        scope.launch(Dispatchers.IO) {
            store.index.profiles.mapNotNull { it.credential }.forEach(store::sealPlain)
            store.sweep(pages::delete)
            refresh()
        }
    }

    /** Puts the active profile's session on [client], as it's built (before anyone else can use it). */
    fun applySession(client: YouTube) {
        val profile = store.index.activeProfile
        val cookie = try {
            cookieOf(profile)
        } catch (_: KeystoreBusy) {
            // This launch goes without the session but keeps it for the next, rather than signing out.
            null
        }
        client.setSession(cookie, profile.dataSyncId, store.visitor(profile.id))
    }

    private class KeystoreBusy : Exception()

    /**
     * [profile]'s cookie, opened; null when it has none. One that can't ever be opened (the
     * keystore lost or invalidated its key, or the value was tampered with) is a sign-in that's
     * gone: the profile is signed out, its library kept. Throws [KeystoreBusy] when it can't be opened just now.
     */
    private fun cookieOf(profile: Profile): String? {
        val credential = profile.credential ?: return null
        unsealed[credential]?.let { return it }
        return when (val opened = store.open(credential)) {
            is ProfileStore.Opened.Cookie -> opened.value
            ProfileStore.Opened.Busy -> throw KeystoreBusy()
            ProfileStore.Opened.Gone -> {
                if (profile.id == store.index.active) epochCounter.incrementAndGet()
                store.update(cookies = mapOf(credential to null)) { it.change(profile.id) { p -> p.copy(credential = null, merged = false, lastSync = 0L) } }
                pages.delete(profile.id)
                publish(signedIn = false)
                null
            }
        }
    }

    val canAddAccount: Boolean get() = _profiles.value.count { !it.isGuest } < ProfileStore.MAX_ACCOUNTS

    /**
     * Takes the session the login page captured. An account already here (by its DATASYNC_ID, or
     * the profile being signed in again to, [reauth], if it never had one) is signed in again with its
     * library as it was; another is added. Either way it becomes the profile in use.
     */
    suspend fun signIn(cookie: String, dataSyncId: String?, reauth: String? = null): SignInResult = changing.withLock {
        if (!YouTube.isSignedInCookie(cookie)) return SignInResult.FAILED
        val index = store.index
        val known = dataSyncId?.let { id -> index.profiles.firstOrNull { !it.isGuest && it.dataSyncId == id } }
            ?: reauth?.let(index::profile)?.takeIf { !it.isGuest && it.dataSyncId == null }
        if (known == null && index.profiles.count { !it.isGuest } >= ProfileStore.MAX_ACCOUNTS) return SignInResult.TOO_MANY
        val id = known?.id ?: store.newId()
        val credential = known?.credential ?: id
        // Never written in the clear: if the keystore can't seal it, the session lasts until the app
        // closes and the next launch has this profile signed out.
        val sealed = withContext(Dispatchers.IO) { store.seal(cookie) }
        if (sealed == null) unsealed[credential] = cookie else unsealed.remove(credential)
        // A sign-in's first sync only adds: what was liked here meanwhile (signed out, or expired) stays.
        val profile = (known ?: Profile(id = id, kind = ProfileKind.ACCOUNT, addedAt = System.currentTimeMillis()))
            .copy(credential = credential, dataSyncId = dataSyncId ?: known?.dataSyncId, merged = false, lastSync = 0L)
        store.update(cookies = mapOf(credential to sealed)) { idx -> if (known != null) idx.with(profile) else idx.copy(profiles = idx.profiles + profile) }
        withContext(NonCancellable) {
            if (profile.id == _active.value.id) {
                epochCounter.incrementAndGet()
                withContext(Dispatchers.IO) {
                    yt.setSession(cookie, profile.dataSyncId, store.visitor(profile.id))
                    onSessionChanged(profile.premiumFormats)
                }
                _sessionExpired.value = false
                publish(signedIn = true)
            } else {
                enter(profile, cookie)
            }
        }
        scope.launch(Dispatchers.IO) {
            refresh()
            sync()
        }
        when {
            known == null -> SignInResult.ADDED
            reauth != null && reauth != known.id -> SignInResult.SWITCHED_TO_EXISTING
            else -> SignInResult.SIGNED_IN_AGAIN
        }
    }

    /**
     * Makes [profileId] the profile in use. Returns false when it couldn't be (its sign-in can't be
     * opened just now); nothing has changed then.
     */
    suspend fun switchTo(profileId: String): Boolean = changing.withLock {
        val target = store.index.profile(profileId) ?: return false
        if (target.id == _active.value.id) return true
        val cookie = try {
            withContext(Dispatchers.IO) { cookieOf(target) }
        } catch (_: KeystoreBusy) {
            return false
        }
        // Its sign-in may have just been found gone, and the profile signed out.
        withContext(NonCancellable) { enter(store.index.profile(profileId) ?: target, cookie) }
        scope.launch(Dispatchers.IO) {
            refresh()
            syncIfStale()
        }
        true
    }

    /**
     * The switch itself, under [changing] and not to be cancelled part-way. A process death
     * anywhere in it leaves one profile or the other whole: the index's `active` is the only
     * pointer to which, and it's written once the rest is in place.
     */
    private suspend fun enter(target: Profile, cookie: String?) {
        _switching.value = true
        try {
            withContext(main) {
                listeners.forEach { l -> withTimeoutOrNull(LEAVING_MS) { l.leaving() } }
            }
            // From here, nothing begun under the profile being left may write or send.
            epochCounter.incrementAndGet()
            syncWork?.cancel()
            staleSync?.cancel()
            withContext(Dispatchers.IO) {
                val left = _active.value
                builtClient()?.innerTube?.visitorData?.let { store.putVisitor(left.id, it) }
                yt.setSession(cookie, target.dataSyncId, store.visitor(target.id))
                pages.use(target.id)
                onSessionChanged(target.premiumFormats)
                library.use(target.id)
                store.update { it.copy(active = target.id) }
            }
            _sessionExpired.value = false
            publish(signedIn = cookie != null)
            // Again, now that everything is the next profile's: what began while it was half one and
            // half the other (the session already new, the library or [active] still old) is let go too.
            epochCounter.incrementAndGet()
            withContext(main) { listeners.forEach { it.arrived() } }
        } finally {
            _switching.value = false
        }
    }

    /**
     * Signs [profileId] out. With [keepLibrary] its library stays on the device, to be picked up
     * again by signing in to the same account; without, the profile and everything of it here is
     * deleted. Signing out the profile in use moves to the Guest first.
     */
    suspend fun signOut(profileId: String = _active.value.id, keepLibrary: Boolean = true) = changing.withLock {
        val profile = store.index.profile(profileId) ?: return@withLock
        if (profile.isGuest) return@withLock
        withContext(NonCancellable) {
            if (profile.id == _active.value.id) enter(store.index.profiles.first { it.isGuest }, null)
            profile.credential?.let(unsealed::remove)
            val cookies = profile.credential?.let { mapOf(it to null) }.orEmpty()
            withContext(Dispatchers.IO) {
                // The visitor identity was used alongside the account: it goes with the sign-in.
                store.putVisitor(profile.id, null)
                if (keepLibrary) {
                    store.update(cookies) { it.change(profile.id) { p -> p.copy(credential = null, merged = false, lastSync = 0L) } }
                    // Its pages were the account's (its Home, its history): gone with the sign-in.
                    pages.delete(profile.id)
                } else {
                    // Tombstoned first, so a process death before the files are gone still deletes them next launch.
                    store.update(cookies) { it.copy(profiles = it.profiles.filterNot { p -> p.id == profile.id }, removed = it.removed + profile.id) }
                    store.sweep(pages::delete)
                }
            }
            publish()
            // Also whatever a sign-in cut short (the process killed mid-way) left in the WebView.
            withContext(main) { clearWebSession() }
        }
    }

    /** Whether Premium formats are asked for while [profileId] is in use. */
    fun setPremium(profileId: String, premium: Boolean) {
        store.update { it.change(profileId) { p -> p.copy(premiumFormats = premium) } }
        if (profileId == _active.value.id) onSessionChanged(premium)
        publish()
    }

    suspend fun refresh() {
        if (_switching.value || !yt.isSignedIn) return
        val started = epoch
        val profileId = _active.value.id
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
        if (epoch != started) return
        _sessionExpired.value = false
        store.update { it.change(profileId) { p -> p.copy(info = info) } }
        publish()
    }

    private fun expired(started: Int) {
        if (epoch == started && yt.isSignedIn) _sessionExpired.value = true
    }

    @Volatile
    private var syncWork: Deferred<SyncResult>? = null

    /**
     * Pulls liked videos, Watch Later and subscriptions into the active profile's
     * library, so their state shows everywhere, including what changed on
     * YouTube's site or apps since.
     *
     * The first sync of a sign-in only adds: likes, Watch later and follows
     * made here meanwhile are kept. Later ones mirror the account,
     * except for what was changed here while they ran.
     *
     * A switch cancels it; what it fetched is written to the library it began with, or nowhere.
     */
    suspend fun sync(): SyncResult {
        if (_switching.value || !yt.isSignedIn || !_syncing.compareAndSet(false, true)) return SyncResult.SKIPPED
        val work = scope.async(Dispatchers.IO) { syncNow() }
        syncWork = work
        return try {
            work.await()
        } catch (e: CancellationException) {
            // Cancelled by a switch, not by the caller: the caller only hears it was skipped.
            if (!currentCoroutineContext().isActive) throw e
            SyncResult.SKIPPED
        } finally {
            _syncing.value = false
        }
    }

    private suspend fun syncNow(): SyncResult {
        val started = epoch
        fun current() = epoch == started
        val profile = _active.value
        val lib = library.store
        val keepLocal = !profile.merged
        lib.beginSync()
        val likes = runCatching {
            val (videos, complete) = wholePlaylist(YouTube.LIKED_VIDEOS)
            if (current()) lib.mergeRemoteLikes(videos, complete, keepLocal)
        }
        val watchLater = runCatching {
            val (videos, complete) = wholePlaylist(YouTube.WATCH_LATER)
            if (current()) lib.mergeRemoteWatchLater(videos, complete, keepLocal)
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
            if (current() && (channels.isNotEmpty() || complete)) lib.mergeRemoteSubscriptions(channels.distinctBy { it.id }, complete, keepLocal)
        }
        val parts = listOf(likes, watchLater, subscriptions)
        parts.mapNotNull { it.exceptionOrNull() }.firstOrNull { it is CancellationException }?.let { throw it }
        val succeeded = parts.count { it.isSuccess }
        if (!current()) return SyncResult.SKIPPED
        // Only a sync that got something counts as one; otherwise the next chance tries again.
        if (succeeded > 0) {
            val merged = profile.merged || succeeded == parts.size
            store.update { it.change(profile.id) { p -> p.copy(merged = merged, lastSync = System.currentTimeMillis()) } }
            publish()
        }
        return when (succeeded) {
            parts.size -> SyncResult.DONE
            0 -> SyncResult.FAILED
            else -> SyncResult.PARTIAL
        }
    }

    private var staleSync: Job? = null

    /** [sync] again when the app comes back after a while: likes and follows change on other devices too. */
    fun syncIfStale() {
        if (!yt.isSignedIn || System.currentTimeMillis() - _active.value.lastSync < SYNC_EVERY_MS || staleSync?.isActive == true) return
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

    /** Keeps the active profile's visitor identity for the next launch. */
    fun saveVisitor() {
        // Half way through a switch, the client's visitor id may already be the next profile's.
        if (_switching.value) return
        builtClient()?.innerTube?.visitorData?.let { store.putVisitor(_active.value.id, it) }
    }

    companion object {
        private const val SYNC_EVERY_MS = 30 * 60_000L
        /** How long each [SwitchListener.leaving] may take before the switch goes on without it. */
        private const val LEAVING_MS = 2_000L

        /** Google's sign-in, returning to youtube.com once the session cookie is set. */
        const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252F"

        private fun clearWebView() {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().apply { removeAllCookies(null); flush() }
        }
    }
}
