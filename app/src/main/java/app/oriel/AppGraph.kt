package app.oriel

import app.oriel.data.Telemetry
import android.content.Context
import android.util.Log
import app.oriel.core.YouTube
import app.oriel.core.extras.ReturnDislike
import app.oriel.core.extras.SponsorBlock
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
import app.oriel.util.NetworkMonitor
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
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
 * Context. Expensive pieces (the stream resolver and its JS engine, media
 * caches) are lazy, so a cold start pays only for what
 * the first screen needs.
 */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val okHttp: OkHttpClient = OkHttpClient.Builder()
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
        .apply { testInterceptor?.let(::addInterceptor) }
        .build()

    val settings = Settings(context)
    val telemetry = Telemetry(settings, scope)
    val network = NetworkMonitor(context).apply {
        // Pooled connections belong to the network they were opened on; after a switch they
        // only fail, one timeout at a time.
        onSwitch = { okHttp.connectionPool.evictAll() }
    }

    private val logger = InnerTubeLogger { event ->
        if (event.level == InnerTubeLogLevel.DEBUG && !BuildConfig.DEBUG) return@InnerTubeLogger
        // Details can carry request headers and URLs, so the session cookie and auth with them;
        // logcat is readable on a user's device, so release builds log the message alone.
        val line = "${event.tag}: ${event.message}" + if (event.details.isEmpty() || !BuildConfig.DEBUG) "" else " ${event.details}"
        when (event.level) {
            InnerTubeLogLevel.DEBUG -> Log.d(TAG, line)
            InnerTubeLogLevel.INFO -> Log.i(TAG, line)
            else -> Log.w(TAG, line)
        }
    }

    val yt = YouTube(okHttp, logger)

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

    val sponsorBlock by lazy { SponsorBlock(yt.http) }
    val dislikes by lazy { ReturnDislike(yt.http) }
    val library = LibraryStore(File(context.filesDir, "library.json"), scope)
    val homeComposer by lazy { HomeComposer(yt, library) }
    val account = AccountManager(context, yt, { resolver }, library, scope)
    val actions = LibraryActions(this)
    val pages = PageDiskCache(File(context.cacheDir, "pages"))
    val caches by lazy { MediaCaches(context, settings) }
    val dataSources by lazy { StreamDataSources(this) }
    val adaptive by lazy { AdaptiveSources(this) }
    @get:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val abr by lazy { app.oriel.playback.Abr(context) }

    private val session = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun start() {
        applyLocale()
        // Opt-in usage counts: one "opened" each time the app comes to the foreground.
        scope.launch(Dispatchers.Main) {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_START) telemetry.track("app_open", mapOf("signedIn" to account.signedIn.value))
            })
        }
        // The visitor identity YouTube issued last time. Signed out, it's what ties one
        // session's watching to the next one's suggestions; and a cold start needn't fetch one.
        if (yt.innerTube.visitorData == null) session.getString(KEY_VISITOR, null)?.let { yt.innerTube.visitorData = it }
        account.restore()
        // Pages saved under one account aren't shown to the next, or once signed out.
        scope.launch { account.signedIn.drop(1).collect { pages.clear() } }
        // Visitor data, the player config and the cipher solver are the cold costs of the
        // first play; pay them now, off the main thread, instead of on the first tap.
        scope.launch(Dispatchers.IO) { resolver.prewarm() }
        // The media caches load their index from disk when first built; do that here, off the
        // main thread, rather than in the playback service's onCreate on the first tap.
        scope.launch(Dispatchers.IO) { runCatching { caches; dataSources } }
        scope.launch { settings.state.map { it.transport.protocol }.distinctUntilChanged().collect { resolver.protocol = it } }
        scope.launch { settings.state.map { it.premium }.distinctUntilChanged().collect { resolver.premium = it } }
    }

    /** Keeps what should outlive the process (the visitor identity) for the next launch. */
    fun saveSession() {
        yt.innerTube.visitorData?.let { session.edit().putString(KEY_VISITOR, it).apply() }
    }

    private val _localeVersion = MutableStateFlow(0)

    /** Goes up each time the content country or language changes, for feeds to load themselves again. */
    val localeVersion: StateFlow<Int> = _localeVersion.asStateFlow()

    /** Points YouTube at the chosen (or the device's) region and language; cached pages in the old locale are dropped. */
    fun applyLocale() {
        val s = settings.current
        val locale = Locale.getDefault()
        val country = s.country ?: locale.country.ifEmpty { "US" }
        val language = s.language ?: locale.toLanguageTag()
        yt.setLocale(country = country, language = language)
        yt.clearCache()
        // Remembered across launches: a cold start in the same locale keeps the pages on disk, which
        // are what its first frame is made of; a new one (the device's language changed) drops them.
        val applied = "$country|$language"
        if (session.getString(KEY_LOCALE, null) == applied) return
        val first = !session.contains(KEY_LOCALE)
        session.edit().putString(KEY_LOCALE, applied).apply()
        if (first) return
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

        /** Lets tests run the app offline (or hanging) without touching the network. */
        @androidx.annotation.VisibleForTesting
        var testInterceptor: okhttp3.Interceptor? = null
    }
}
