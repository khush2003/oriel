package app.oriel.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import kotlin.math.min

/**
 * Reads a googlevideo URL as a sequence of bounded range requests.
 *
 * googlevideo throttles long open-ended range reads from several clients down
 * to roughly real-time; bounded chunks are served at line speed. Callers still
 * see a single stream: chunk boundaries are invisible to the cache and the
 * extractor above.
 */
@OptIn(UnstableApi::class)
class ChunkedDataSource(
    private val upstream: HttpDataSource,
    private val chunkSizeFor: (DataSpec) -> Long,
) : DataSource {
    private var spec: DataSpec? = null
    private var passthrough = false
    private var position = 0L
    private var end = UNSET
    private var chunkSize = 0L
    private var chunkRemaining = 0L
    private var chunkOpen = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val size = chunkSizeFor(dataSpec)
        if (size <= 0) {
            passthrough = true
            return upstream.open(dataSpec)
        }
        passthrough = false
        spec = dataSpec
        position = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else UNSET
        chunkSize = size
        openChunk()
        if (end == UNSET) {
            // A total that doesn't lie past where reading starts is a bad header, not a length.
            totalFromContentRange(upstream.responseHeaders)?.takeIf { it > dataSpec.position }?.let { end = it }
        }
        return if (end != UNSET) end - dataSpec.position else C.LENGTH_UNSET.toLong()
    }

    private fun openChunk() {
        val base = spec ?: error("not opened")
        val length = if (end != UNSET) min(chunkSize, end - position) else chunkSize
        val opened = upstream.open(base.subrange(position - base.position, length))
        chunkRemaining = if (opened != C.LENGTH_UNSET.toLong()) opened else length
        chunkOpen = true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        while (true) {
            if (end != UNSET && position >= end) return C.RESULT_END_OF_INPUT
            if (chunkRemaining <= 0L) {
                if (chunkOpen) {
                    upstream.close()
                    chunkOpen = false
                }
                if (end == UNSET) return C.RESULT_END_OF_INPUT
                openChunk()
            }
            val read = upstream.read(buffer, offset, min(length.toLong(), chunkRemaining).toInt())
            if (read == C.RESULT_END_OF_INPUT) {
                // The server ended this chunk early; with no known end that's the end of the file.
                if (end == UNSET) return C.RESULT_END_OF_INPUT
                chunkRemaining = 0
                continue
            }
            position += read
            chunkRemaining -= read
            return read
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (passthrough || chunkOpen) upstream.close()
        chunkOpen = false
        spec = null
    }

    private fun totalFromContentRange(headers: Map<String, List<String>>): Long? {
        val value = headers.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value?.firstOrNull() ?: return null
        return value.substringAfterLast('/').trim().toLongOrNull()
    }

    private companion object {
        const val UNSET = -1L
    }
}
