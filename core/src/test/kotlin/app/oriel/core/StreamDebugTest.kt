package app.oriel.core

import app.oriel.core.stream.StreamQuality
import app.oriel.core.stream.StreamRequest
import app.oriel.core.stream.StreamResolver
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class StreamDebugTest {
    @Test fun debug() = runBlocking {
        if (System.getProperty("oriel.live") != "true") return@runBlocking
        val logger = InnerTubeLogger { e -> if (e.level != InnerTubeLogLevel.DEBUG) println("[${e.level} ${e.tag}] ${e.message} ${e.details}") }
        val yt = YouTube(logger = logger).apply { setLocale("US", "en") }
        val resolver = StreamResolver(yt, logger = logger)
        for (req in (System.getProperty("oriel.streams") ?: "dQw4w9WgXcQ@720,jfKfPfyJRdk@720,kJQP7kiw5Fk@1080").split(",").map { s -> StreamRequest(s.substringBefore('@'), StreamQuality.HIGH, s.substringAfter('@', "").toIntOrNull()) }) {
            println("==== $req")
            println(runCatching { resolver.resolve(req) }.fold({ "OK $it hls=${it.isHls} live=${it.isLive} heights=${it.availableHeights} v=${it.video?.height}/${it.video?.codecs} dur=${it.durationSec} tracking=${it.playbackUrl != null}" }, { "FAIL $it" }))
        }
    }
}
