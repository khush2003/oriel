package app.oriel.core

import app.oriel.core.stream.StreamResolver
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrVideoStream
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/** Research probe: what a SABR extraction and its first chunks look like. Opt in with -Pprobe=true. */
class SabrProbeTest {
    @Test fun sabr() = runBlocking {
        if (System.getProperty("oriel.probe") != "true") return@runBlocking
        val yt = YouTube().apply { setLocale("US", "en") }
        val resolver = StreamResolver(yt)
        val id = System.getProperty("oriel.ids")?.substringBefore(',') ?: "dQw4w9WgXcQ"
        for ((label, hints) in listOf(
            "classic" to ContentHints(wantVideo = true, maxVideoHeight = 720).withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true),
            "sabrFirst" to ContentHints(wantVideo = true, maxVideoHeight = 720, sabrFirst = true).withStreamCapabilities(allowHls = false, allowSabr = true, allowBoundedRange = true),
        )) {
            val s = runCatching { resolver.extractRaw(id, hints) }.getOrElse { println("SABRPROBE $label FAIL ${it.message}"); null } ?: continue
            println("SABRPROBE $label client=${s.clientName} itag=${s.itag} ${s.mimeType} url=${s.audioUrl.take(40)} video=${s.videoItag}/${s.videoHeight} sabr=${s.sabrBootstrap != null} sabrVideo=${s.sabrVideoBootstrap != null} heights=${s.availableVideoHeights}")
            val b = s.sabrBootstrap ?: continue
            println("SABRPROBE bootstrap client=${b.clientName}/${b.clientVersion} audio=${b.audioFormat} mime=${b.mimeType} len=${b.contentLengthBytes} dur=${b.durationMs} variants=${b.videoVariants.map { "${it.height}p/${it.mimeType}" }}")
            val audio = withTimeout(60_000) { SabrAudioStream(yt.http, b).chunks().take(6).toList() }
            audio.firstOrNull { it.isInitialization }?.let { init ->
                val hex = init.data.joinToString("") { "%02x".format(it) }
                println("SABRPROBE init ids: cues=${hex.indexOf("1c53bb6b") / 2} seekhead=${hex.indexOf("114d9b74") / 2} tracks=${hex.indexOf("1654ae6b") / 2} info=${hex.indexOf("1549a966") / 2} cuepoints=${Regex("bb[89a][0-9a-f]b3").findAll(hex).count()}")
                java.io.File("build/sabr_init.webm").writeBytes(init.data)
            }
            audio.forEach { c ->
                val head = c.data.take(8).joinToString("") { "%02x".format(it) }
                println("SABRPROBE audio init=${c.isInitialization} seq=${c.sequenceNumber} range=${c.startRange}-${c.endRangeExclusive} t=${c.startMs}+${c.durationMs} bytes=${c.data.size} head=$head")
            }
            val vb = s.sabrVideoBootstrap ?: b
            val video = withTimeout(60_000) { runCatching { SabrVideoStream(yt.http, vb).chunks().take(4).toList() }.getOrElse { println("SABRPROBE video FAIL ${it.message}"); emptyList() } }
            video.forEach { c -> println("SABRPROBE video init=${c.isInitialization} seq=${c.sequenceNumber} range=${c.startRange}-${c.endRangeExclusive} t=${c.startMs}+${c.durationMs} bytes=${c.data.size}") }
        }
    }
}
