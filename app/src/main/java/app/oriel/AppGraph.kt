package app.oriel

import app.oriel.data.Telemetry
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import app.oriel.core.YouTube
import app.oriel.core.extras.ReturnDislike
import app.oriel.core.extras.SponsorBlock
import app.oriel.core.net.CredentialGuard
import app.oriel.core.stream.KeyValueStore
import app.oriel.core.stream.StreamResolver
import app.oriel.data.AccountManager
import app.oriel.data.HomeComposer
import app.oriel.data.LibraryActions
import app.oriel.data.LibraryStore
import app.oriel.data.PageDiskCache
import app.oriel.data.Settings
import app.oriel.playback.AdaptiveSources
import app.oriel.playback.MediaCaches
import app.oriel.playback.StreamDataSources
import app.oriel.util.CrashLog
import app.oriel.util.LocaleTags
import app.oriel.util.NetworkMonitor
import app.oriel.util.Scrubber
import app.oriel.util.SkipBadList
import app.oriel.util.VideoCaps
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The app's singletons, built once in [OrielApp] and reachable from any
 * Context. Expensive pieces (the HTTP clients, the stream resolver and its JS
 * engine, media caches) are lazy, and [start] builds them off the main thread,
 * so a cold start's first frame pays only for what it draws.
 */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // SharedPreferences load on first use and block whoever asks first; start them now, off the main thread.
        scope.launch(Dispatchers.IO) {
            PREFS.forEach { runCatching { context.getSharedPreferences(it, Context.MODE_PRIVATE).all } }
        }
        SkipBadList.onDropped = { CrashLog.note(context, it) }
    }

    private val report: (String, Throwable) -> Unit = { where, e -> CrashLog.nonFatal(context, where, e) }

    private val okHttpLazy = lazy {
        OkHttpClient.Builder()
        // Ktor, the media loaders and image loading all queue in this one dispatcher, and
        // time spent queued counts against their timeouts: OkHttp's default of 5 per host
        // made a signed-out home (about 26 calls) time out its own player requests.
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // A dead HTTP/2 connection is noticed within seconds, not at the end of a 20 s read.
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // On every hop of every request (redirects included): the session goes to YouTube and Google only.
        .addNetworkInterceptor(CredentialGuard)
        .apply { testInterceptor?.let(::addInterceptor) }
        .build()
    }
    val okHttp: OkHttpClient by okHttpLazy

    val settings = Settings(context)
    val telemetry = Telemetry(settings, scope)
    val network = NetworkMonitor(context).apply {
        // Pooled connections belong to the network they were opened on; after a switch they
        // only fail, one timeout at a time.
        onSwitch = { if (okHttpLazy.isInitialized()) okHttp.connectionPool.evictAll() }
    }

    private val logger = InnerTubeLogger { event ->
        // Release builds keep warnings alone: the rest narrates every request, video ids included.
        if (event.level != InnerTubeLogLevel.WARN && event.level != InnerTubeLogLevel.ERROR && !BuildConfig.DEBUG) return@InnerTubeLogger
        // Details can carry request headers and URLs, so the session cookie and auth with them;
        // logcat is readable on a user's device, so release builds log the message alone. Debug
        // builds get the details too, and both are scrubbed: a debug build is signed in as well.
        val text = if (BuildConfig.DEBUG && event.details.isNotEmpty()) "${event.tag}: ${event.message} ${event.details}" else "${event.tag}: ${event.message}"
        val line = Scrubber.scrub(text)
        when (event.level) {
            InnerTubeLogLevel.DEBUG -> Log.d(TAG, line)
            InnerTubeLogLevel.INFO -> Log.i(TAG, line)
            else -> Log.w(TAG, line)
        }
    }

    /**
     * Built on first use, which at launch is [start]'s, on IO: loading Ktor and InnerTubeX is the
     * costliest part of a cold start, and the first frame doesn't need them. Whoever gets it
     * first gets it with the locale and the saved session already on it ([adopt]).
     */
    val yt: YouTube by lazy { YouTube(okHttp, logger).also(::adopt) }

    /** [yt] once it's built, for what shouldn't build it (saving the session on the way out). Set under [localeLock]. */
    @Volatile
    private var builtYt: YouTube? = null

    /** Whether [start] ran, so the saved session belongs on the client. Under [localeLock]. */
    private var sessionStarted = false

    /** The country and language [applyLocale] last chose. Under [localeLock]. */
    private var chosenLocale: Pair<String, String>? = null

    private fun adopt(client: YouTube) = synchronized(localeLock) {
        chosenLocale?.let { (country, language) -> client.setLocale(country = country, language = language) }
        if (sessionStarted) restoreSession(client)
        builtYt = client
    }

    private fun restoreSession(client: YouTube) {
        // The visitor identity YouTube issued last time. Signed out, it's what ties one
        // session's watching to the next one's suggestions; and a cold start needn't fetch one.
        if (client.innerTube.visitorData == null) session.getString(KEY_VISITOR, null)?.let { client.innerTube.visitorData = it }
        account.applySession(client)
    }

    val resolver: StreamResolver by lazy {
        StreamResolver(
            yt = yt,
            store = PrefsStore(context),
            playerCacheDir = File(context.filesDir, "players"),
            logger = logger,
        ).apply {
            val s = settings.current
            protocol = s.transport.protocol
            premium = s.premium
        }
    }

    /**
     * For the community services, which aren't YouTube: a client of their own, so nothing added
     * to YouTube's requests (the session cookie above all) can ever reach them.
     */
    private val extrasHttp by lazy {
        HttpClient(OkHttp) {
            engine { preconfigured = okHttp }
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 15_000
            }
            expectSuccess = false
        }
    }
    val sponsorBlock by lazy { SponsorBlock(extrasHttp) }
    val dislikes by lazy { ReturnDislike(extrasHttp) }
    val library = LibraryStore(File(context.filesDir, "library.json"), scope, report)
    val homeComposer by lazy { HomeComposer(yt, library) }
    val account = AccountManager(context, { yt }, { resolver }, library, scope, forgetVisitor = { forgetVisitor() })
    val actions = LibraryActions(this)
    val pages = PageDiskCache(File(context.cacheDir, "pages"), scope, report)
    val caches by lazy { MediaCaches(context, settings) }
    val dataSources by lazy { StreamDataSources(this) }
    val adaptive by lazy { AdaptiveSources(this) }
    @get:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val abr by lazy { app.oriel.playback.Abr(context) }

    private val session = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    /** Done once last run's ANRs and native crashes are on record, for the crash prompt to look. */
    val crashesChecked = CompletableDeferred<Unit>()

    fun start() {
        synchronized(localeLock) {
            sessionStarted = true
            builtYt?.let(::restoreSession)
        }
        applyLocale()
        scope.launch(Dispatchers.IO) { yt }
        // Shown once there's a screen to show them on.
        scope.launch { library.problems.collect { actions.noticeWhenShown(it) } }
        scope.launch(Dispatchers.IO) {
            runCatching {
                CrashLog.recordExits(context)
                // Opt-in counts: the kind and the exception's class name, never a trace or message.
                CrashLog.takeUncounted(context).forEach { telemetry.track("crash", mapOf("kind" to it.kind, "exception" to it.exception)) }
            }
            crashesChecked.complete(Unit)
        }
        // Opt-in usage counts: one "opened" each time the app comes to the foreground.
        scope.launch(Dispatchers.Main) {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_START) telemetry.track("app_open", mapOf("signedIn" to account.signedIn.value))
            })
        }
        account.restore()
        // Pages saved under one account aren't shown to the next, or once signed out.
        scope.launch { account.signedIn.drop(1).collect { pages.clear() } }
        // Visitor data, the player config and the cipher solver are the cold costs of the
        // first play; pay them now, off the main thread, instead of on the first tap.
        scope.launch(Dispatchers.IO) { resolver.prewarm() }
        // The media caches load their index from disk when first built; do that here, off the
        // main thread, rather than in the playback service's onCreate on the first tap.
        scope.launch(Dispatchers.IO) { runCatching { caches; dataSources } }
        // The decoder queries behind Auto's ceiling and the VP9 preference walk every codec on
        // the device; the playback service asks for them on the main thread as it starts.
        scope.launch(Dispatchers.IO) { VideoCaps.hardwareVp9(); VideoCaps.hardwareHeight() }
        scope.launch { settings.state.map { it.transport.protocol }.distinctUntilChanged().collect { resolver.protocol = it } }
        scope.launch { settings.state.map { it.premium }.distinctUntilChanged().collect { resolver.premium = it } }
    }

    /** Keeps what should outlive the process (the visitor identity) for the next launch. */
    fun saveSession() {
        builtYt?.innerTube?.visitorData?.let { session.edit().putString(KEY_VISITOR, it).apply() }
    }

    /** Drops the visitor identity, in memory and on disk, so the next request starts a new one. */
    fun forgetVisitor() = synchronized(localeLock) {
        builtYt?.innerTube?.visitorData = null
        session.edit { remove(KEY_VISITOR) }
    }

    private val _localeVersion = MutableStateFlow(0)

    /** Goes up each time the content country or language changes, for feeds to load themselves again. */
    val localeVersion: StateFlow<Int> = _localeVersion.asStateFlow()

    private val localeLock = Any()
    private var lastLocale: String? = null

    /**
     * Points YouTube at the chosen (or the device's) region and language; cached pages in the old
     * locale are dropped. Called again whenever the settings or the device's configuration
     * change; the same locale again changes nothing.
     */
    fun applyLocale() = synchronized(localeLock) {
        val s = settings.current
        val locale = Locale.getDefault()
        val country = LocaleTags.gl(s.country ?: locale.country)
        val language = LocaleTags.hl(s.language ?: locale.toLanguageTag())
        val applied = "$country|$language"
        if (applied == lastLocale) return@synchronized
        lastLocale = applied
        chosenLocale = country to language
        // Clears the in-memory pages itself when the locale differs from what it has. Not built
        // yet, it takes the locale as it's built.
        builtYt?.setLocale(country = country, language = language)
        // Remembered across launches: a cold start in the same locale keeps the pages on disk, which
        // are what its first frame is made of; a new one (the device's language changed) drops them.
        if (session.getString(KEY_LOCALE, null) == applied) return@synchronized
        val first = !session.contains(KEY_LOCALE)
        session.edit().putString(KEY_LOCALE, applied).apply()
        if (first) return@synchronized
        scope.launch(Dispatchers.IO) {
            pages.clear()
            _localeVersion.update { it + 1 }
        }
    }

    private class PrefsStore(context: Context) : KeyValueStore {
        private val prefs = context.getSharedPreferences("resolver", Context.MODE_PRIVATE)
        override fun getString(key: String) = prefs.getString(key, null)
        override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
        override fun getLong(key: String) = if (prefs.contains(key)) prefs.getLong(key, 0) else null
        override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    }

    companion object {
        private const val TAG = "Oriel"
        private const val KEY_VISITOR = "visitorData"
        private const val KEY_LOCALE = "locale"
        private val PREFS = listOf("account", "session", "resolver", "diagnostics", "abr")

        /** Lets tests run the app offline (or hanging) without touching the network. */
        @androidx.annotation.VisibleForTesting
        var testInterceptor: okhttp3.Interceptor? = null
    }
}
