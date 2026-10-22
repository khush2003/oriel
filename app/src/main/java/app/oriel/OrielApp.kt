package app.oriel

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import app.oriel.util.CrashLog
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

open class OrielApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        graph = AppGraph(this)
        startGraph(graph)
    }

    // The device's language or region can change while the app runs; feeds follow it.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::graph.isInitialized) graph.applyLocale()
    }

    /** Warms the session and the stream resolver; tests replace this to stay offline. */
    protected open fun startGraph(graph: AppGraph) = graph.start()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.okHttp })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(256L * 1024 * 1024).build() }
            .crossfade(220)
            .build()
}

val Context.graph: AppGraph get() = (applicationContext as OrielApp).graph
