package app.oriel.core

import app.oriel.core.json.at
import app.oriel.core.json.string
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Research probe, not a regression test: whether the clients that answer
 * with an HLS master playlist for an ordinary video give one whose variants
 * span the quality ladder and whose segments actually download, which would
 * let ExoPlayer adapt quality by itself. Opt in with -Pprobe=true; -Pids=a,b.
 */
class HlsProbeTest {
    @Test fun probe() = runBlocking {
        if (System.getProperty("oriel.probe") != "true") return@runBlocking
        val yt = YouTube().apply { setLocale("US", "en") }
        val ids = (System.getProperty("oriel.ids") ?: "dQw4w9WgXcQ").split(",")
        val clients = mapOf("IOS" to YouTubeClient.IOS, "VISIONOS" to YouTubeClient.VISIONOS, "IPADOS" to YouTubeClient.IPADOS)
        for (id in ids) for ((name, client) in clients) {
            runCatching {
                val root = yt.rawPlayer(client, id)
                val status = root.at("playabilityStatus", "status").string
                val master = root.at("streamingData", "hlsManifestUrl").string
                println("HLS $id $name: $status master=${master != null}")
                if (master == null) return@runCatching
                val ua = client.userAgent
                val text = yt.http.get(master) { header("User-Agent", ua) }.bodyAsText()
                val variants = text.lines().filter { it.startsWith("#EXT-X-STREAM-INF") }
                variants.forEach { v ->
                    val res = Regex("RESOLUTION=([0-9x]+)").find(v)?.groupValues?.get(1)
                    val bw = Regex("BANDWIDTH=([0-9]+)").find(v)?.groupValues?.get(1)
                    val codecs = Regex("CODECS=\"([^\"]+)\"").find(v)?.groupValues?.get(1)
                    val fps = Regex("FRAME-RATE=([0-9.]+)").find(v)?.groupValues?.get(1)
                    println("  VARIANT $res bw=$bw fps=$fps codecs=$codecs")
                }
                println("  MEDIA ${text.lines().count { it.startsWith("#EXT-X-MEDIA") }} audio renditions")
                // One variant's playlist and its first segment.
                val lines = text.lines()
                val firstUri = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }.takeIf { it >= 0 }?.let { lines[it + 1] } ?: return@runCatching
                val media = yt.http.get(firstUri) { header("User-Agent", ua) }
                val mediaText = media.bodyAsText()
                println("  VARIANT_PLAYLIST status=${media.status.value} segments=${mediaText.lines().count { it.startsWith("#EXTINF") }} type=${mediaText.lines().firstOrNull { it.startsWith("#EXT-X-PLAYLIST-TYPE") }}")
                val seg = mediaText.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@runCatching
                val started = System.currentTimeMillis()
                val r = yt.http.get(seg) { header("User-Agent", ua) }
                val bytes = r.readRawBytes().size
                println("  SEGMENT status=${r.status.value} bytes=$bytes in ${System.currentTimeMillis() - started} ms")
            }.onFailure { println("HLS $id $name: ERR ${it.javaClass.simpleName}: ${it.message?.take(120)}") }
        }
    }
}
