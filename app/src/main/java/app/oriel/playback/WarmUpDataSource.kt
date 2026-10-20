package app.oriel.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import app.oriel.core.stream.ResolvedStream
import java.io.InterruptedIOException
import kotlin.math.min

/**
 * Waits out googlevideo's warm-up: a URL minted moments ago answers 403 until
 * it goes live a few seconds later ([ResolvedStream.isWarmingUp]). Without
 * this the player takes that 403 for a refusal, re-resolves, gets another
 * brand-new URL that isn't live either, and gives up on a video that would
 * have played. Here the same request is simply asked again every half second
 * until the URL is live or old enough that a 403 means it.
 *
 * Blocks the loader thread while it waits, which is what loader threads are
 * for; cancelling the load interrupts the wait.
 */
@OptIn(UnstableApi::class)
class WarmUpDataSource(
    private val upstream: HttpDataSource,
    private val streamFor: (String) -> ResolvedStream?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : HttpDataSource {
    override fun open(dataSpec: DataSpec): Long {
        // Bounded by count as well as by the clock, so no clock oddity can make it spin forever.
        var attempts = 0
        while (true) {
            try {
                return upstream.open(dataSpec)
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                if (e.responseCode != 403 || ++attempts > MAX_ATTEMPTS) throw e
                val stream = streamFor(dataSpec.uri.toString()) ?: throw e
                val now = clock()
                if (!stream.isWarmingUp(now)) throw e
                upstream.close()
                try {
                    sleep(min(STEP_MS, stream.issuedAtMs + ResolvedStream.WARM_UP_MS - now).coerceAtLeast(1))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Cancelled while the URL warmed up")
                }
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)
    override fun close() = upstream.close()
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun getResponseCode(): Int = upstream.responseCode
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)
    override fun setRequestProperty(name: String, value: String) = upstream.setRequestProperty(name, value)
    override fun clearRequestProperty(name: String) = upstream.clearRequestProperty(name)
    override fun clearAllRequestProperties() = upstream.clearAllRequestProperties()

    private companion object {
        const val STEP_MS = 500L
        const val MAX_ATTEMPTS = (ResolvedStream.WARM_UP_MS / STEP_MS + 2).toInt()
    }
}
