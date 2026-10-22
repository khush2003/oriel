@file:OptIn(ExperimentalSabrApi::class)

package app.oriel.core.stream

import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrVideoStream
import com.metrolist.innertubex.sabr.findSabrProtocolException
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * YouTube's SABR transport (server ABR over UMP), read as if it were the
 * classic file a googlevideo URL serves.
 *
 * SABR has no byte ranges: the client asks for a format from a point in time
 * and the server streams whole segments. Each segment, though, is an exact
 * slice of the same WebM/MP4 file the classic URL would have served (init,
 * then cluster after cluster), so a reader that knows where segments start
 * can serve any byte position: start from the segment holding it and drop
 * what comes before. The segment starts come from the format's own index
 * (WebM Cues, or an MP4 `sidx`), carried in its initialization segment.
 * Everything above it (the extractor, seeking, the disk cache) then works
 * unchanged.
 */
class SabrSource(private val http: HttpClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val indexes = ConcurrentHashMap<String, SegmentIndex>()

    /**
     * Reads [bootstrap]'s audio, or its selected video when [video], from byte
     * [position] for [length] bytes (all of the rest when null). [key] names
     * the format (one video, one itag) so its index is learned once.
     */
    fun open(key: String, bootstrap: SabrBootstrap, video: Boolean, position: Long, length: Long?): SabrReader =
        SabrReader(this, key, bootstrap, video, position, length)

    /** Total bytes of the format, when YouTube says. */
    fun contentLength(bootstrap: SabrBootstrap, video: Boolean): Long? =
        if (video) bootstrap.selectedVideoContentLengthBytes else bootstrap.contentLengthBytes

    internal fun index(key: String): SegmentIndex? = indexes[key]

    internal fun learn(key: String, init: ByteArray) {
        if (indexes.containsKey(key)) return
        SegmentIndex.parse(init)?.let {
            if (indexes.size > 64) indexes.clear()
            indexes[key] = it
        }
    }

    internal fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    internal fun stream(bootstrap: SabrBootstrap, video: Boolean, startMs: Long) =
        if (video) SabrVideoStream(http, bootstrap, initialPlayerTimeMs = startMs).chunks()
        else SabrAudioStream(http, bootstrap, initialPlayerTimeMs = startMs).chunks()
}

/**
 * A SABR read that couldn't go on. [refused] when the server turned the
 * session down (401/403/410, attestation, a reload, an answer it shouldn't
 * give): a fresh resolve is needed. Otherwise the connection failed, and
 * asking again from the same byte may well work.
 */
class SabrStreamException(message: String, cause: Throwable, val refused: Boolean) : IOException(message, cause)

/**
 * One open read of a SABR format. Blocking, for a loader thread. Chunks are
 * pulled through a small buffer, so a reader the player has stopped draining
 * stops asking the server for more.
 *
 * While the buffer is full nothing is read, sometimes for a minute or more,
 * and the server may drop the connection meanwhile. That, and any other
 * dropped connection, is picked up again here from the byte the read had
 * reached, a few times over, before the player hears of it.
 */
class SabrReader internal constructor(
    private val source: SabrSource,
    private val key: String,
    private val bootstrap: SabrBootstrap,
    private val video: Boolean,
    private var position: Long,
    length: Long?,
) : Closeable {
    private val total = source.contentLength(bootstrap, video)
    private var remaining = length ?: total?.let { (it - position).coerceAtLeast(0) } ?: Long.MAX_VALUE
    private var job: Job? = null
    private var chunks = Channel<SabrChunk>(BUFFERED_CHUNKS)
    private var startMs = -1L
    private var restarts = 0
    private var reconnects = 0
    private var current: ByteArray? = null
    private var offset = 0

    /** Bytes this read will return, if known. */
    val length: Long? = length ?: total?.let { (it - position).coerceAtLeast(0) }

    init {
        start(firstStart())
    }

    /** Where to ask the server to start: the segment holding [position], or a guess a little before it. */
    private fun firstStart(): Long {
        if (position == 0L) return 0L
        source.index(key)?.let { return it.timeAt(position) }
        val bytes = total ?: return 0L
        // Without the index yet, guess from the byte rate, early enough that only a little is dropped.
        return (position.toDouble() / bytes * bootstrap.durationMs - GUESS_MARGIN_MS).toLong().coerceAtLeast(0L)
    }

    private fun start(timeMs: Long) {
        job?.cancel()
        val channel = Channel<SabrChunk>(BUFFERED_CHUNKS)
        chunks = channel
        startMs = timeMs
        job = source.launch {
            try {
                source.stream(bootstrap, video, timeMs).collect { channel.send(it) }
                channel.close()
            } catch (e: CancellationException) {
                channel.close()
                throw e
            } catch (t: Throwable) {
                channel.close(t)
            }
        }
    }

    /** Copies up to [length] bytes into [buffer]; -1 at the end. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0L) return END
        while (current.let { it == null || this.offset >= it.size }) {
            current = null
            val chunk = next() ?: return END
            if (chunk.isInitialization) source.learn(key, chunk.data)
            val end = chunk.startRange + chunk.data.size
            if (end <= position) continue
            if (chunk.startRange > position) {
                restartEarlier()
                continue
            }
            current = chunk.data
            this.offset = (position - chunk.startRange).toInt()
        }
        val data = current!!
        val n = min(min(length.toLong(), remaining), (data.size - this.offset).toLong()).toInt()
        System.arraycopy(data, this.offset, buffer, offset, n)
        this.offset += n
        position += n
        remaining -= n
        return n
    }

    /** The server began past [position] (a guess that was too late): ask again from earlier. */
    private fun restartEarlier() {
        if (startMs == 0L || ++restarts > MAX_RESTARTS) throw IOException("SABR skipped past byte $position")
        val known = source.index(key)?.timeAt(position)?.takeIf { it < startMs }
        start(known ?: (startMs - GUESS_MARGIN_MS * (1L shl restarts)).coerceAtLeast(0L))
    }

    private fun next(): SabrChunk? {
        while (true) {
            val result = runBlocking { chunks.receiveCatching() }
            result.getOrNull()?.let {
                reconnects = 0
                return it
            }
            val cause = result.exceptionOrNull() ?: return null
            val refused = cause.isRefusal()
            if (!refused && ++reconnects <= MAX_RECONNECTS) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MS * reconnects)
                } catch (e: InterruptedException) {
                    throw java.io.InterruptedIOException("SABR read interrupted").apply { initCause(e) }
                }
                start(firstStart())
                continue
            }
            throw SabrStreamException(cause.message ?: "SABR stream failed", cause, refused)
        }
    }

    /** The server's answer, not the connection, ended this read; asking the same session again won't help. */
    private fun Throwable.isRefusal(): Boolean {
        val e = findSabrProtocolException() ?: return false
        val status = e.httpStatusCode
        return e.kind != SabrFailureKind.PROTOCOL || status == null || status !in TRANSIENT_HTTP
    }

    override fun close() {
        job?.cancel()
        job = null
        current = null
    }

    private companion object {
        const val END = -1
        const val BUFFERED_CHUNKS = 2
        const val MAX_RESTARTS = 3
        const val MAX_RECONNECTS = 3
        const val RECONNECT_DELAY_MS = 300L
        val TRANSIENT_HTTP = setOf(408, 425, 429, 500, 502, 503, 504)
        const val GUESS_MARGIN_MS = 15_000L
    }
}

/**
 * Where each segment of a format starts, in time and in bytes, as its own
 * index records it: WebM Cues, or an MP4 `sidx`. YouTube's segments are one
 * cluster (or one fragment) each, so these are exactly SABR's segments.
 */
class SegmentIndex(private val timesMs: LongArray, private val offsets: LongArray) {
    val size: Int get() = offsets.size

    /** Start time of the segment holding byte [position]; 0 for anything before the first. */
    fun timeAt(position: Long): Long {
        var i = offsets.binarySearch(position)
        if (i < 0) i = -i - 2
        return if (i < 0) 0L else timesMs[i]
    }

    fun offsetAt(i: Int): Long = offsets[i]
    fun timeAtIndex(i: Int): Long = timesMs[i]

    companion object {
        fun parse(init: ByteArray): SegmentIndex? =
            (runCatching { parseWebm(init) }.getOrNull() ?: runCatching { parseMp4(init) }.getOrNull())?.takeIf { it.size > 0 }

        private const val EBML = 0x1A45DFA3L
        private const val SEGMENT = 0x18538067L
        private const val INFO = 0x1549A966L
        private const val TIMECODE_SCALE = 0x2AD7B1L
        private const val CUES = 0x1C53BB6BL
        private const val CUE_POINT = 0xBBL
        private const val CUE_TIME = 0xB3L
        private const val CUE_TRACK_POSITIONS = 0xB7L
        private const val CUE_CLUSTER_POSITION = 0xF1L
        private const val CLUSTER = 0x1F43B675L

        private fun parseWebm(b: ByteArray): SegmentIndex? {
            val r = Ebml(b)
            if (r.id() != EBML) return null
            r.skip(r.size())
            if (r.id() != SEGMENT) return null
            r.size()
            val segmentStart = r.pos.toLong()
            var scaleNs = 1_000_000L
            var cues: List<Pair<Long, Long>>? = null
            while (r.pos < b.size) {
                val id = r.id()
                val size = r.size()
                val end = r.pos + size
                when (id) {
                    INFO -> {
                        while (r.pos < end) {
                            val child = r.id()
                            val n = r.size()
                            if (child == TIMECODE_SCALE) scaleNs = r.uint(n.toInt()) else r.skip(n)
                        }
                    }
                    CUES -> {
                        val points = ArrayList<Pair<Long, Long>>()
                        while (r.pos < end) {
                            val child = r.id()
                            val n = r.size()
                            if (child != CUE_POINT) { r.skip(n); continue }
                            val pointEnd = r.pos + n
                            var time = -1L
                            var cluster = -1L
                            while (r.pos < pointEnd) {
                                val field = r.id()
                                val m = r.size()
                                when {
                                    field == CUE_TIME -> time = r.uint(m.toInt())
                                    field == CUE_TRACK_POSITIONS && cluster < 0 -> {
                                        val tpEnd = r.pos + m
                                        while (r.pos < tpEnd) {
                                            val f = r.id()
                                            val k = r.size()
                                            if (f == CUE_CLUSTER_POSITION) cluster = r.uint(k.toInt()) else r.skip(k)
                                        }
                                    }
                                    else -> r.skip(m)
                                }
                            }
                            if (time >= 0 && cluster >= 0) points += time to cluster
                        }
                        cues = points
                    }
                    CLUSTER -> break
                    else -> if (end > b.size) break else r.skip(size)
                }
            }
            val points = cues ?: return null
            return SegmentIndex(
                LongArray(points.size) { points[it].first * scaleNs / 1_000_000L },
                LongArray(points.size) { segmentStart + points[it].second },
            )
        }

        private fun parseMp4(b: ByteArray): SegmentIndex? {
            var pos = 0
            while (pos + 8 <= b.size) {
                var size = u32(b, pos)
                val type = String(b, pos + 4, 4, Charsets.ISO_8859_1)
                var header = 8
                if (size == 1L) { size = u64(b, pos + 8); header = 16 }
                if (size == 0L) size = (b.size - pos).toLong()
                if (type == "sidx") return parseSidx(b, pos + header, pos + size)
                // A box can't be smaller than its header or run past the bytes there are; a size of
                // exactly 2^32 would otherwise wrap to a step of 0 and loop forever.
                if (size < header || size > b.size - pos) return null
                pos += size.toInt()
            }
            return null
        }

        private fun parseSidx(b: ByteArray, start: Int, boxEnd: Long): SegmentIndex {
            var p = start
            val version = b[p].toInt()
            p += 4 // version and flags
            p += 4 // reference ID
            val timescale = u32(b, p); p += 4
            val earliest: Long
            val firstOffset: Long
            if (version == 0) { earliest = u32(b, p); firstOffset = u32(b, p + 4); p += 8 } else { earliest = u64(b, p); firstOffset = u64(b, p + 8); p += 16 }
            p += 2 // reserved
            val count = ((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff); p += 2
            val times = LongArray(count)
            val offsets = LongArray(count)
            var time = earliest
            var offset = boxEnd + firstOffset
            for (i in 0 until count) {
                val size = u32(b, p) and 0x7fffffffL
                val duration = u32(b, p + 4)
                p += 12
                times[i] = time * 1000 / timescale
                offsets[i] = offset
                time += duration
                offset += size
            }
            return SegmentIndex(times, offsets)
        }

        private fun u32(b: ByteArray, p: Int): Long =
            ((b[p].toLong() and 0xff) shl 24) or ((b[p + 1].toLong() and 0xff) shl 16) or ((b[p + 2].toLong() and 0xff) shl 8) or (b[p + 3].toLong() and 0xff)

        private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)
    }

    /** Just enough EBML to walk to the Cues. */
    private class Ebml(private val b: ByteArray) {
        var pos = 0

        fun id(): Long {
            val len = Integer.numberOfLeadingZeros(b[pos].toInt() and 0xff) - 23
            require(len in 1..4) { "bad EBML id" }
            var v = 0L
            repeat(len) { v = (v shl 8) or (b[pos++].toLong() and 0xff) }
            return v
        }

        /** An element's size; an unknown size reads as "to the end of the data". */
        fun size(): Long {
            val first = b[pos].toInt() and 0xff
            val len = Integer.numberOfLeadingZeros(first) - 23
            require(len in 1..8) { "bad EBML size" }
            var v = (first and (0xff shr len)).toLong()
            pos++
            repeat(len - 1) { v = (v shl 8) or (b[pos++].toLong() and 0xff) }
            return if (v == (1L shl (7 * len)) - 1) (b.size - pos).toLong() else v
        }

        fun uint(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 8) or (b[pos++].toLong() and 0xff) }
            return v
        }

        fun skip(n: Long) {
            pos = min(b.size.toLong(), pos + n).toInt()
        }
    }
}
