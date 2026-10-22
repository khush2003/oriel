package app.oriel

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Binder
import app.oriel.playback.PlaybackService
import coil3.EventListener
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.allowHardware
import coil3.imageDecoderEnabled
import coil3.request.SuccessResult
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.absoluteValue
import org.robolectric.Shadows

/** Offline app for screenshot tests: no session warm-up, and artwork drawn locally. */
class TestApp : OrielApp() {
    override fun onCreate() {
        // Offline: every request fails at once, or with [hang] never answers (for loading states).
        AppGraph.testInterceptor = okhttp3.Interceptor {
            if (hang) Thread.sleep(120_000)
            throw java.io.IOException("offline test")
        }
        // Controls that reach for the player (speed, seeking) bind the service; give it an inert binder, as below.
        Shadows.shadowOf(this).setComponentNameAndServiceForBindService(ComponentName(this, PlaybackService::class.java), Binder())
        super.onCreate()
    }

    override fun startGraph(graph: AppGraph) {
        // The first-run liquid motion question would otherwise pop up over any frame left running a few seconds.
        graph.settings.update { it.copy(liquidMotionAsked = true) }
    }

    companion object {
        @Volatile var hang = false
        val started = AtomicInteger()
        val loaded = AtomicInteger()
        val failed = AtomicInteger()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).components { add(FakeArt) }
            .eventListener(object : EventListener() {
                override fun onStart(request: ImageRequest) { started.incrementAndGet() }
                override fun onSuccess(request: ImageRequest, result: SuccessResult) { loaded.incrementAndGet() }
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    if (failed.getAndIncrement() < 3) println("fake image failed: ${request.data} -> ${result.throwable}")
                }
            })
            .build()
}

/**
 * The real app against the network, for the live end-to-end run. Nothing plays
 * under Robolectric, so it skips the stream-resolver warm-up, and binding the
 * playback service yields an inert binder: Media3's connect request goes
 * nowhere and the controller simply stays pending. (By default Robolectric
 * reports a null component name, which Media3 dereferences.)
 */
class LiveTestApp : OrielApp() {
    override fun onCreate() {
        AppGraph.testInterceptor = null
        org.robolectric.shadows.ShadowLog.stream = System.out
        Shadows.shadowOf(this).setComponentNameAndServiceForBindService(ComponentName(this, PlaybackService::class.java), Binder())
        super.onCreate()
    }

    override fun startGraph(graph: AppGraph) {
        // Crossfades run on wall time, which the paused test clock never lets finish drawing.
        graph.settings.update { it.copy(reduceAnimation = true) }
        graph.applyLocale()
    }

    // The real network loader, minus hardware bitmaps (Robolectric can't draw them), counting outcomes.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        super.newImageLoader(context).newBuilder()
            .allowHardware(false)
            // Robolectric has no ImageDecoder; BitmapFactory decodes real JPEGs and WebPs.
            .imageDecoderEnabled(false)
            .eventListener(object : EventListener() {
                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    imagesLoaded.incrementAndGet()
                }

                override fun onError(request: ImageRequest, result: ErrorResult) {
                    if (imagesFailed.getAndIncrement() < 3) println("image failed: ${request.data} -> ${result.throwable}")
                }
            })
            .build()

    companion object {
        val imagesLoaded = AtomicInteger()
        val imagesFailed = AtomicInteger()
    }
}

/**
 * Generates a cover per URL: a two-hue gradient with a soft disc, keyed on the
 * URL without its size suffix so every size of one cover matches.
 */
object FakeArt : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data.toString()
        // googleusercontent sizes follow '='; ytimg sizes are the last path segment.
        val key = if ('=' in url) url.substringBefore('=') else url.substringBeforeLast('/')
        // Keys differ by a character or two; spread them so neighbouring covers get distinct hues.
        val seed = (key.hashCode() * -0x61c88647).let { it xor (it ushr 16) }.absoluteValue
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = (seed % 360).toFloat()
        val a = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.62f, 0.92f))
        val b = android.graphics.Color.HSVToColor(floatArrayOf((hue + 48) % 360, 0.75f, 0.38f))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), a, b, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        val cx = size * (0.3f + (seed % 40) / 100f)
        val cy = size * (0.35f + (seed / 40 % 30) / 100f)
        paint.shader = RadialGradient(cx, cy, size * 0.45f, android.graphics.Color.argb(150, 255, 255, 255), android.graphics.Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, size * 0.45f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = android.graphics.Color.argb(70, 255, 255, 255)
        repeat(3) { i -> canvas.drawCircle(size * 0.72f, size * 0.74f, size * (0.12f + i * 0.08f), paint) }
        return SuccessResult(bitmap.asImage(), chain.request, DataSource.MEMORY)
    }
}
