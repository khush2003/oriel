package app.oriel.core

import app.oriel.core.json.arr
import app.oriel.core.json.at
import app.oriel.core.json.get
import app.oriel.core.json.string
import app.oriel.core.stream.ResolvedStream
import app.oriel.core.stream.StreamProtocol
import app.oriel.core.stream.StreamQuality
import app.oriel.core.stream.StreamRequest
import app.oriel.core.stream.StreamResolver
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readRawBytes
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * How fast googlevideo actually serves what the resolver hands out, at the
 * start, middle and end of the file: a URL whose `n` wasn't solved is
 * throttled to about real time, which plays a short song fine and stalls a
 * long video. Opt-in: -Plive=true -Pprobe=true [-Pids=a,b].
 */
class ThroughputProbeTest {
    /** Resolve the same video again and again: does every resolve hand out URLs googlevideo serves? */
    @Test fun repeatedResolves() = runBlocking {
        if (System.getProperty("oriel.live") != "true" || System.getProperty("oriel.probe") != "true") return@runBlocking
        val debug = System.getProperty("oriel.debug") == "true"
        val logger = com.metrolist.innertubex.InnerTubeLogger { e -> if (debug || e.level != com.metrolist.innertubex.InnerTubeLogLevel.DEBUG) println("ITX ${e.level} ${e.tag}: ${e.message} ${e.details}".take(600)) }
        val yt = YouTube(logger = logger).apply { setLocale("US", "en") }
        val id = System.getProperty("oriel.ids")?.substringBefore(',') ?: "dQw4w9WgXcQ"
        val shared = StreamResolver(yt, logger = logger)
        repeat(System.getProperty("oriel.repeat")?.toInt() ?: 8) { i ->
            println("REPEAT ---- $i")
            val resolver = if (i % 2 == 0) shared.also { it.invalidate(id) } else StreamResolver(yt, logger = logger)
            val stream = runCatching { resolver.resolve(StreamRequest(id, StreamQuality.HIGH, 720)) }.getOrElse { println("REPEAT $i FAIL ${it.message}"); return@repeat }
            suspend fun status(url: String, from: Long) = runCatching {
                yt.http.get(url) { stream.headers.forEach { (k, v) -> header(k, v) }; header("Range", "bytes=$from-${from + 65_535}") }.status.value
            }.getOrElse { -1 }
            val len = stream.contentLength ?: 0
            val vlen = stream.video?.contentLength ?: 0
            val audio = listOf(0L, minOf(2_000_000L, len / 2), len * 3 / 4).map { status(stream.url, it) }
            val video = stream.video?.url?.let { u -> listOf(0L, 2_000_000L, vlen * 3 / 4).map { status(u, it) } }
            val q = stream.url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            println("REPEAT $i ${if (i % 2 == 0) "shared" else "fresh "} ${stream.clientName} audio=$audio video=$video n=${q["n"]} (${q["n"]?.length}) itag=${stream.itag}/${stream.video?.itag} spc=${q["spc"] != null} pot=${q["pot"] != null}")
            // Refused: is the URL bad, or was it asked for too soon?
            if (403 in audio || video?.contains(403) == true) {
                for (wait in listOf(2_000L, 6_000L)) {
                    kotlinx.coroutines.delay(wait)
                    println("REPEAT $i after ${wait}ms audio=${status(stream.url, 0)} video=${stream.video?.url?.let { status(it, 0) }}")
                }
            }
        }
    }

    /**
     * How long after the player response googlevideo starts serving its URLs:
     * polled every half second, and asked once after a fixed wait, to see
     * whether asking early pushes the moment back.
     */
    @Test fun activationWindow() = runBlocking {
        if (System.getProperty("oriel.live") != "true" || System.getProperty("oriel.probe") != "true") return@runBlocking
        val answered = java.util.concurrent.atomic.AtomicLong()
        val logger = com.metrolist.innertubex.InnerTubeLogger { e ->
            if (e.message == "player response decoded" && e.details["streamingPresent"] == "true") answered.set(System.nanoTime())
        }
        val yt = YouTube(logger = logger).apply { setLocale("US", "en") }
        val id = System.getProperty("oriel.ids")?.substringBefore(',') ?: "dQw4w9WgXcQ"
        val resolver = StreamResolver(yt, logger = logger)
        resolver.resolve(StreamRequest(id, StreamQuality.HIGH, 720)) // warm the cipher
        for (plan in (System.getProperty("oriel.plans") ?: "poll,poll,once:2500,once:4000,once:5500,poll").split(',')) {
            resolver.invalidate(id)
            val stream = resolver.resolve(StreamRequest(id, StreamQuality.HIGH, 720))
            fun since() = (System.nanoTime() - answered.get()) / 1_000_000
            suspend fun status() = runCatching {
                yt.http.get(stream.url) { stream.headers.forEach { (k, v) -> header(k, v) }; header("Range", "bytes=0-65535") }.status.value
            }.getOrElse { println("WINDOW error ${it::class.simpleName} ${it.message?.take(120)}"); -1 }
            val trail = mutableListOf<String>()
            if (plan == "poll") {
                while (since() < 15_000) {
                    val s = status(); trail += "${since()}:$s"
                    if (s == 206) break
                    kotlinx.coroutines.delay(500)
                }
            } else {
                kotlinx.coroutines.delay((plan.substringAfter(':').toLong() - since()).coerceAtLeast(0))
                trail += "${since()}:${status()}"
            }
            println("WINDOW $plan ${trail.joinToString(" ")}")
            kotlinx.coroutines.delay(1_000)
        }
    }

    /** The n solver itself: the same input through one service several times, and through fresh ones. */
    @Test fun nSolverIsDeterministic() = runBlocking {
        if (System.getProperty("oriel.live") != "true" || System.getProperty("oriel.probe") != "true") return@runBlocking
        val yt = YouTube().apply { setLocale("US", "en") }
        val embed = yt.http.get("https://www.youtube.com/embed/dQw4w9WgXcQ") { header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36") }
        val html = String(embed.readRawBytes())
        val js = Regex("\"jsUrl\":\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: error("no jsUrl")
        val playerUrl = "https://www.youtube.com$js"
        println("NSOLVE player $playerUrl")
        fun service() = com.metrolist.innertubex.cipher.YouTubeCipherService(
            yt.http,
            com.metrolist.innertubex.cipher.RemotePlayerConfigStore(yt.http, object : com.metrolist.innertubex.cipher.PlayerConfigRepository {
                override val enabled = false
                override val sourceUrl = "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
                override val defaultSourceUrl = sourceUrl
                override var cachedJson = ""
                override var cachedAtMs = 0L
                override var cachedSourceUrl = ""
                override var cachedEtag = ""
            }, com.metrolist.innertubex.InnerTubeLogger.NONE),
            com.metrolist.innertubex.InnerTubeLogger.NONE,
        )
        // A real signatureCipher for the embedded client, deciphered repeatedly by one service and by fresh ones.
        val sts = Regex("signatureTimestamp[\\\":]+(\\d+)").find(String(yt.http.get(playerUrl).readRawBytes()))?.groupValues?.get(1)
        val body = """{"videoId":"dQw4w9WgXcQ","context":{"client":{"clientName":"WEB_EMBEDDED_PLAYER","clientVersion":"1.20251015.01.00","hl":"en","gl":"US"},"thirdParty":{"embedUrl":"https://www.youtube.com/"}},"playbackContext":{"contentPlaybackContext":{"signatureTimestamp":$sts}}}"""
        val player = String(yt.http.post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false") {
            header("Content-Type", "application/json"); header("Origin", "https://www.youtube.com"); header("Referer", "https://www.youtube.com/")
            setBody(body)
        }.readRawBytes())
        val cipher = Regex("\"signatureCipher\":\"([^\"]+)\"").find(player)?.groupValues?.get(1)?.replace("\\u0026", "&")
        println("NSOLVE sts=$sts cipher=${cipher?.take(60)} status=${Regex("\"status\":\"([A-Z_]+)\"").find(player)?.groupValues?.get(1)}")
        if (cipher != null) {
            val sigShared = service()
            val outs = List(4) { sigShared.deobfuscateSignatureCipher(playerUrl, cipher)?.let { Regex("[?&](sig|signature)=([^&]+)").find(it)?.groupValues?.get(2)?.take(24) } }
            val fresh = List(2) { service().deobfuscateSignatureCipher(playerUrl, cipher)?.let { Regex("[?&](sig|signature)=([^&]+)").find(it)?.groupValues?.get(2)?.take(24) } }
            println("NSOLVE sig shared=$outs fresh=$fresh")
        }
        val inputs = listOf("hEVwLjNOVKS5_w", "V2-A1ZdFzV7chw", "EvxQwgae4mcDVg")
        val shared = service()
        for (n in inputs) {
            val url = "https://rr1---sn-x.googlevideo.com/videoplayback?expire=1&n=$n&c=WEB"
            val sharedOut = List(4) { Regex("[?&]n=([^&]+)").find(shared.processNParameter(playerUrl, url).orEmpty())?.groupValues?.get(1) }
            val freshOut = List(2) { Regex("[?&]n=([^&]+)").find(service().processNParameter(playerUrl, url).orEmpty())?.groupValues?.get(1) }
            println("NSOLVE $n shared=$sharedOut fresh=$freshOut")
        }
    }

    /**
     * The same signature and n challenges through a fresh cipher service and
     * through one that has already solved others, which is what a long-lived
     * resolver does; any difference is a URL googlevideo will refuse.
     */
    @Test fun cipherReuseIsConsistent() = runBlocking {
        if (System.getProperty("oriel.live") != "true" || System.getProperty("oriel.probe") != "true") return@runBlocking
        val yt = YouTube().apply { setLocale("US", "en") }
        val embed = String(yt.http.get("https://www.youtube.com/embed/dQw4w9WgXcQ") { header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36") }.readRawBytes())
        val playerUrl = "https://www.youtube.com" + (Regex("\"jsUrl\":\"([^\"]+)\"").find(embed)?.groupValues?.get(1) ?: error("no jsUrl"))
        fun service() = com.metrolist.innertubex.cipher.YouTubeCipherService(
            yt.http,
            com.metrolist.innertubex.cipher.RemotePlayerConfigStore(yt.http, object : com.metrolist.innertubex.cipher.PlayerConfigRepository {
                override val enabled = false
                override val sourceUrl = "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
                override val defaultSourceUrl = sourceUrl
                override var cachedJson = ""
                override var cachedAtMs = 0L
                override var cachedSourceUrl = ""
                override var cachedEtag = ""
            }, com.metrolist.innertubex.InnerTubeLogger.NONE),
            com.metrolist.innertubex.InnerTubeLogger.NONE,
        )
        val random = java.util.Random(7)
        fun token(length: Int, alphabet: String) = String(CharArray(length) { alphabet[random.nextInt(alphabet.length)] })
        val b64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        // Two formats sharing one n, as a video and its audio do.
        fun formats(): List<com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format> {
            val n = token(16, b64)
            return listOf(251, 247).map { itag ->
                val url = "https://rr1---sn-ab5l6nrs.googlevideo.com/videoplayback?expire=1999999999&itag=$itag&c=WEB_EMBEDDED_PLAYER&n=$n"
                com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format(
                    itag = itag, mimeType = if (itag == 251) "audio/webm" else "video/webm", width = if (itag == 251) null else 1280,
                    signatureCipher = "s=${java.net.URLEncoder.encode(token(108, b64) + "==", "UTF-8")}&sp=sig&url=${java.net.URLEncoder.encode(url, "UTF-8")}",
                )
            }
        }
        fun solved(out: List<com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format>) = out.map { f ->
            val q = f.url.orEmpty().substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            "${q["sig"]?.take(10)}/${q["n"]}"
        }
        val shared = service()
        var mismatches = 0
        repeat(System.getProperty("oriel.repeat")?.toInt() ?: 5) { i ->
            val input = formats()
            val reused = solved(shared.processFormats(playerUrl, input))
            val fresh = solved(service().processFormats(playerUrl, input))
            if (reused != fresh) mismatches++
            println("REUSE $i ${if (reused == fresh) "same" else "DIFFERENT"} reused=$reused fresh=$fresh")
        }
        println("REUSE mismatches=$mismatches")
    }

    /** What a reported video is (watch page, related, comments) and how far resolving it gets. */
    @Test fun videoProbe() = runBlocking {
        if (System.getProperty("oriel.live") != "true" || System.getProperty("oriel.probe") != "true") return@runBlocking
        val yt = YouTube().apply { setLocale("US", "en") }
        for (id in System.getProperty("oriel.ids")?.split(',') ?: listOf("dQw4w9WgXcQ")) {
            runCatching { yt.watch(id) }.onSuccess { page ->
                val v = page.video
                println("VIDEO $id '${v.title}' by ${v.channel?.name} dur=${v.durationSec}s live=${v.isLive} short=${v.isShort} views=${page.viewCount} chapters=${page.chapters.size} comments=${page.commentsCount}")
                val starts = page.chapters.map { it.startMs }
                println("VIDEO   chapter starts=${starts.map { it / 1000 }} duplicates=${starts.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.map { it / 1000 }}")
                val ids = page.related.map { it.id }
                println("VIDEO   related=${ids.size} duplicates=${ids.groupingBy { it }.eachCount().filterValues { it > 1 }} kinds=${page.related.groupingBy { it::class.simpleName }.eachCount()}")
                page.commentsToken?.let { t ->
                    runCatching { yt.comments(t) }.onSuccess { c ->
                        val cids = c.comments.map { it.id }
                        println("VIDEO   comments=${cids.size} duplicates=${cids.size - cids.toSet().size} sort=${c.sort.map { it.title }}")
                    }.onFailure { println("VIDEO   comments FAIL ${it::class.simpleName}: ${it.message}") }
                }
                page.relatedContinuation?.let { t ->
                    runCatching { yt.relatedMore(t) }.onSuccess { more ->
                        val both = ids + more.items.map { it.id }
                        println("VIDEO   related page 2=${more.items.size} repeats across pages=${both.size - both.toSet().size}")
                    }
                }
            }.onFailure { println("VIDEO $id watch FAIL ${it::class.simpleName}: ${it.message}") }
            runCatching { StreamResolver(yt).resolve(StreamRequest(id, StreamQuality.HIGH, 720)) }
                .onSuccess { println("VIDEO   stream $it live=${it.isLive} hls=${it.isHls}") }
                .onFailure { println("VIDEO   stream FAIL ${it::class.simpleName}: ${it.message}") }
        }
    }

    /** Different videos one after another on one resolver, as the app plays them, then the first again. */
    @Test fun sequence() = runBlocking {
        if (System.getProperty("oriel.live") != "true" || System.getProperty("oriel.probe") != "true") return@runBlocking
        val yt = YouTube().apply { setLocale("US", "en") }
        val resolver = StreamResolver(yt)
        val ids = System.getProperty("oriel.ids")?.split(',') ?: listOf("dQw4w9WgXcQ", "fJ9rUzIMcZQ", "9bZkp7q19f0", "OPf0YbXqDm0", "hTWKbfoikeg", "dQw4w9WgXcQ")
        for ((i, id) in ids.withIndex()) {
            if (i == ids.lastIndex) resolver.invalidate(id)
            val began = System.nanoTime()
            val stream = runCatching { resolver.resolve(StreamRequest(id, StreamQuality.HIGH, 720)) }.getOrElse { println("SEQ $i $id FAIL ${it.message}"); continue }
            val ms = (System.nanoTime() - began) / 1_000_000
            val status = runCatching { yt.http.get(stream.url) { stream.headers.forEach { (k, v) -> header(k, v) }; header("Range", "bytes=0-65535") }.status.value }.getOrElse { -1 }
            println("SEQ $i $id ${stream.clientName}${if (stream.isSabr) " sabr" else ""} HTTP $status in $ms ms")
        }
    }

    /**
     * Whether googlevideo serves a bounded-range client's URL in ranges larger
     * than InnerTubeX's chunk size: each size once, from the start of the video
     * stream and from its middle, after the fresh-URL warm-up.
     */
    @Test fun rangeSizes() = runBlocking {
        if (System.getProperty("oriel.live") != "true" || System.getProperty("oriel.probe") != "true") return@runBlocking
        val yt = YouTube().apply { setLocale("US", "en") }
        val resolver = StreamResolver(yt).apply { protocol = StreamProtocol.CLASSIC }
        val id = System.getProperty("oriel.ids")?.split(',')?.first() ?: "dQw4w9WgXcQ"
        val stream = resolver.resolve(StreamRequest(id, StreamQuality.HIGH, 1080))
        println("RANGE $id ${stream.clientName} bounded=${stream.requireBoundedRange} chunk=${stream.chunkSize}")
        kotlinx.coroutines.delay(ResolvedStream.WARM_UP_MS)
        for ((label, url, length) in listOf(Triple("video", stream.video?.url, stream.video?.contentLength), Triple("audio", stream.url, stream.contentLength))) {
            url ?: continue
            val total = length ?: continue
            for (size in listOf(512L * 1024, 1024L * 1024, 4L * 1024 * 1024, 10L * 1024 * 1024)) for (at in listOf(0L, total / 2)) {
                val end = minOf(at + size, total) - 1
                val began = System.nanoTime()
                val result = runCatching {
                    val response = yt.http.get(url) { stream.headers.forEach { (k, v) -> header(k, v) }; header("Range", "bytes=$at-$end") }
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(64 * 1024)
                    var got = 0L
                    while (true) { val r = channel.readAvailable(buffer); if (r < 0) break; got += r }
                    response.status.value to got
                }
                val ms = (System.nanoTime() - began) / 1_000_000
                result.fold(
                    { (status, got) -> println("RANGE $label ${size / 1024} KiB @${at * 100 / total}% HTTP $status got ${got / 1024}/${(end - at + 1) / 1024} KiB in $ms ms") },
                    { println("RANGE $label ${size / 1024} KiB @${at * 100 / total}% ERR ${it.message?.take(100)}") },
                )
            }
        }
    }

    /** [rangeSizes] for the bounded-range clients' own URLs, read straight from their player answers. */
    @Test fun boundedClientRanges() = runBlocking {
        if (System.getProperty("oriel.live") != "true" || System.getProperty("oriel.probe") != "true") return@runBlocking
        val yt = YouTube().apply { setLocale("US", "en") }
        val id = System.getProperty("oriel.ids")?.split(',')?.first() ?: "dQw4w9WgXcQ"
        for (client in listOf(com.metrolist.innertubex.models.YouTubeClient.ANDROID_VR_1_65_10, com.metrolist.innertubex.models.YouTubeClient.IOS)) {
            val root = yt.rawPlayer(client, id)
            val formats = root.at("streamingData", "adaptiveFormats").arr
            val video = formats.firstOrNull { it["mimeType"].string?.startsWith("video/") == true && it["url"].string != null && (it["contentLength"].string?.toLongOrNull() ?: 0) > 12_000_000 }
            println("BOUNDED ${client.clientName} ${root.at("playabilityStatus", "status").string} video=${video?.get("itag")?.string}")
            val url = video?.get("url")?.string ?: continue
            val total = video["contentLength"].string!!.toLong()
            kotlinx.coroutines.delay(ResolvedStream.WARM_UP_MS)
            for (size in listOf(512L * 1024, 4L * 1024 * 1024, 10L * 1024 * 1024)) for (at in listOf(0L, total / 2)) {
                val end = minOf(at + size, total) - 1
                val began = System.nanoTime()
                val result = runCatching {
                    val response = yt.http.get(url) { header("User-Agent", client.userAgent); header("Range", "bytes=$at-$end") }
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(64 * 1024)
                    var got = 0L
                    while (true) { val r = channel.readAvailable(buffer); if (r < 0) break; got += r }
                    response.status.value to got
                }
                val ms = (System.nanoTime() - began) / 1_000_000
                result.fold(
                    { (status, got) -> println("BOUNDED ${client.clientName} ${size / 1024} KiB @${at * 100 / total}% HTTP $status got ${got / 1024}/${(end - at + 1) / 1024} KiB in $ms ms") },
                    { println("BOUNDED ${client.clientName} ${size / 1024} KiB ERR ${it.message?.take(100)}") },
                )
            }
        }
    }

    @Test fun probe() = runBlocking {
        if (System.getProperty("oriel.live") != "true" || System.getProperty("oriel.probe") != "true") return@runBlocking
        val yt = YouTube().apply { setLocale("US", "en") }
        val resolver = StreamResolver(yt)
        val ids = System.getProperty("oriel.ids")?.split(',') ?: listOf("dQw4w9WgXcQ", "kJQP7kiw5Fk")
        for (protocol in listOf(StreamProtocol.AUTO, StreamProtocol.CLASSIC)) {
            resolver.protocol = protocol
            for (id in ids) {
                val stream = runCatching { resolver.resolve(StreamRequest(id, StreamQuality.HIGH, 1080)) }
                    .onFailure { println("THRU $protocol $id FAIL ${it.message}") }.getOrNull() ?: continue
                println("THRU $protocol $id $stream bounded=${stream.requireBoundedRange} chunk=${stream.chunkSize} dur=${stream.durationSec}s len=${stream.contentLength}")
                if (stream.isSabr) continue
                for ((label, url, length) in listOf(Triple("audio", stream.url, stream.contentLength), Triple("video", stream.video?.url, stream.video?.contentLength))) {
                    url ?: continue
                    val n = Regex("[?&]n=([^&]+)").find(url)?.groupValues?.get(1)
                    val total = length ?: 0L
                    for (fraction in listOf(0.0, 0.5, 0.9)) {
                        val start = (total * fraction).toLong()
                        val size = minOf(2_000_000L, (total - start).coerceAtLeast(1))
                        val began = System.nanoTime()
                        val result = runCatching {
                            val response = yt.http.get(url) {
                                stream.headers.forEach { (k, v) -> header(k, v) }
                                header("Range", "bytes=$start-${start + size - 1}")
                            }
                            val channel = response.bodyAsChannel()
                            val buffer = ByteArray(64 * 1024)
                            var got = 0L
                            while (true) {
                                val r = channel.readAvailable(buffer)
                                if (r < 0) break
                                got += r
                            }
                            response.status.value to got
                        }
                        val ms = (System.nanoTime() - began) / 1_000_000
                        result.fold(
                            { (status, got) -> println("THRU   $label n=${n?.take(12)} @${(fraction * 100).toInt()}% HTTP $status ${got / 1024} KiB in $ms ms = ${if (ms > 0) got * 1000 / 1024 / ms else 0} KiB/s") },
                            { println("THRU   $label @${(fraction * 100).toInt()}% ERR ${it.message}") },
                        )
                    }
                }
            }
        }
    }
}
