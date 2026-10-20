package app.oriel.core

import app.oriel.core.stream.SabrSource
import app.oriel.core.stream.StreamProtocol
import app.oriel.core.stream.StreamQuality
import app.oriel.core.stream.StreamRequest
import app.oriel.core.stream.StreamResolver
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SABR read as a file, against the real YouTube; runs only with -Plive=true.
 * The bytes must be the very bytes the classic URL serves for the same format,
 * from the start and from any position.
 */
class SabrLiveTest {
    private val live = System.getProperty("oriel.live") == "true"

    @Test fun sabrReadsLikeTheClassicFile() = runBlocking {
        if (!live) return@runBlocking
        val yt = YouTube().apply { setLocale("US", "en") }
        val resolver = StreamResolver(yt)
        val id = System.getProperty("oriel.ids")?.substringBefore(',') ?: "dQw4w9WgXcQ"

        resolver.protocol = StreamProtocol.SABR
        val sabr = resolver.resolve(StreamRequest(id, StreamQuality.HIGH, 360))
        println("SABR $sabr url=${sabr.url} video=${sabr.video?.url} len=${sabr.contentLength}")
        val audio = assertNotNull(sabr.sabrAudio, "resolved over SABR")
        val total = assertNotNull(resolver.sabr.contentLength(audio, video = false))

        // The whole audio from the start, timed.
        val started = System.nanoTime()
        val whole = read(resolver.sabr, "a:$id", audio, video = false, 0, null)
        println("SABR whole audio: ${whole.size} of $total bytes in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertEquals(total, whole.size.toLong())

        // Mid-file opens: with the index learned, and from a fresh source that has to guess.
        for (position in listOf(total / 3, total * 2 / 3 + 12_345)) {
            val learned = read(resolver.sabr, "a:$id", audio, video = false, position, 50_000)
            assertContentEquals(whole.copyOfRange(position.toInt(), position.toInt() + 50_000), learned, "learned index at $position")
            val guessed = read(SabrSource(yt.http), "a:$id", audio, video = false, position, 50_000)
            assertContentEquals(whole.copyOfRange(position.toInt(), position.toInt() + 50_000), guessed, "guess at $position")
        }

        // Video: a slice from the middle.
        val videoBoot = assertNotNull(sabr.sabrVideo, "video over SABR")
        val vTotal = assertNotNull(resolver.sabr.contentLength(videoBoot, video = true))
        val vStart = vTotal / 2
        val vSlice = read(resolver.sabr, "v:$id", videoBoot, video = true, vStart, 100_000)
        assertEquals(100_000, vSlice.size)

        // The same formats over classic URLs, when this network gets them: the bytes must match.
        resolver.protocol = StreamProtocol.CLASSIC
        val classic = runCatching { resolver.resolve(StreamRequest(id, StreamQuality.HIGH, 360)) }.getOrNull()
        println("CLASSIC $classic")
        if (classic != null && classic.itag == sabr.itag) {
            val head = yt.http.get(classic.url) { classic.headers.forEach { (k, v) -> header(k, v) }; header("Range", "bytes=0-99999") }.readRawBytes()
            assertContentEquals(whole.copyOfRange(0, 100_000), head, "audio matches the classic file")
            println("SABR audio matches classic itag ${classic.itag} from ${classic.clientName}")
        }
        val classicVideo = classic?.video
        if (classicVideo != null && classicVideo.itag == sabr.video?.itag) {
            val slice = yt.http.get(classicVideo.url) { classic.headers.forEach { (k, v) -> header(k, v) }; header("Range", "bytes=$vStart-${vStart + 99_999}") }.readRawBytes()
            assertContentEquals(slice, vSlice, "video matches the classic file")
            println("SABR video matches classic itag ${classicVideo.itag} at byte $vStart")
        }
        assertTrue(whole.isNotEmpty())

        // Automatic prefers classic URLs and keeps SABR as the fallback.
        resolver.protocol = StreamProtocol.AUTO
        val auto = resolver.resolve(StreamRequest(id, StreamQuality.HIGH, 360))
        println("AUTO $auto")
        if (classic != null) assertTrue(!auto.isSabr, "Automatic uses classic URLs when there are any")
    }

    private fun read(source: SabrSource, key: String, boot: com.metrolist.innertubex.sabr.SabrBootstrap, video: Boolean, position: Long, length: Long?): ByteArray {
        val out = ByteArrayOutputStream()
        source.open(key, boot, video, position, length).use { r ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = r.read(buf, 0, buf.size)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }
}
