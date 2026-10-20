package app.oriel.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import app.oriel.core.stream.SabrReader
import app.oriel.core.stream.SabrStreamException
import app.oriel.core.stream.StreamResolver
import java.io.IOException

/**
 * A SABR read failed; [url] names the format, so the resolver can bench the
 * client that offered it. Only a [refused] read needs that: one whose
 * connection kept dropping is retried like any other network failure.
 */
class SabrIOException(val url: String, cause: Throwable, val refused: Boolean = true) : IOException(cause.message ?: "SABR stream failed", cause)

/**
 * Serves a `sabr://<id>/<a|v>/<itag>` name as the file the classic URL would
 * have served, byte for byte, so the extractor and the caches above it can't
 * tell the difference. The bootstrap comes from the resolve that minted the name.
 */
@OptIn(UnstableApi::class)
class SabrDataSource(private val resolver: StreamResolver) : BaseDataSource(true) {
    private var reader: SabrReader? = null
    private var uri: Uri? = null
    private var url = ""

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        url = dataSpec.uri.toString()
        transferInitializing(dataSpec)
        val stream = resolver.streamForUrl(url) ?: throw SabrIOException(url, IOException("This SABR session has ended"))
        val video = dataSpec.uri.pathSegments.firstOrNull() == "v"
        val bootstrap = (if (video) stream.sabrVideo else stream.sabrAudio) ?: throw SabrIOException(url, IOException("No SABR format"))
        val length = dataSpec.length.takeIf { it != C.LENGTH_UNSET.toLong() }
        reader = resolver.sabr.open(url, bootstrap, video, dataSpec.position, length)
        transferStarted(dataSpec)
        return reader?.length ?: C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val r = reader ?: throw IOException("Not opened")
        val n = try {
            r.read(buffer, offset, length)
        } catch (e: IOException) {
            throw SabrIOException(url, e, refused = (e as? SabrStreamException)?.refused ?: true)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val r = reader ?: return
        reader = null
        r.close()
        transferEnded()
    }
}

/** Sends `sabr://` names to [sabr] and everything else to [other]. */
@OptIn(UnstableApi::class)
class RoutingDataSource(private val sabr: DataSource, private val other: DataSource) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        sabr.addTransferListener(transferListener)
        other.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = if (dataSpec.uri.scheme == "sabr") sabr else other
        current = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (current ?: throw IOException("Not opened")).read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        val target = current ?: return
        current = null
        target.close()
    }
}
