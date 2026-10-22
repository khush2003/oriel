package app.oriel.core.extras

import app.oriel.core.json.arr
import app.oriel.core.json.get
import app.oriel.core.json.long
import app.oriel.core.json.string
import io.ktor.client.HttpClient
import com.metrolist.innertubex.bodyAsTextLimited
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.security.MessageDigest
import java.util.Collections

/** The most recently used [CACHE_SIZE] answers, so a long session's worth of videos isn't all kept. */
private fun <T> lruCache(): MutableMap<String, T> = Collections.synchronizedMap(
    object : LinkedHashMap<String, T>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) = size > CACHE_SIZE
    },
)

private const val CACHE_SIZE = 200

/**
 * A community service's answer: its status, and its body if it succeeded and fits in [maxBytes].
 * These servers aren't YouTube's, so nothing they send is trusted to be small: the body is read
 * as it streams and given up on past the limit, rather than held whole in memory first.
 */
private suspend fun HttpClient.getLimited(url: String, maxBytes: Int, block: HttpRequestBuilder.() -> Unit = {}): Pair<Int, String?> =
    prepareGet(url, block).execute { r -> r.status.value to (if (r.status.value in 200..299) r.bodyAsTextLimited(maxBytes) else null) }

/** A stretch of a video SponsorBlock users marked as a sponsor read, intro, self-promotion… */
data class Segment(val startMs: Long, val endMs: Long, val category: String)

/**
 * SponsorBlock (sponsor.ajay.app), queried the private way: only the first
 * four hex digits of the video id's SHA-256 leave the device, and the
 * matching video is picked out of the bucket locally.
 */
class SponsorBlock(private val http: HttpClient) {
    private val cache = lruCache<List<Segment>>()

    suspend fun segments(videoId: String, categories: Set<String> = DEFAULT_CATEGORIES): List<Segment> {
        // The answer depends on the categories asked for: changing them in settings mustn't get the old list.
        val key = videoId + ":" + categories.sorted().joinToString(",")
        cache[key]?.let { return it }
        val prefix = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray()).joinToString("") { "%02x".format(it) }.take(4)
        val (status, body) = http.getLimited("$API/api/skipSegments/$prefix", MAX_BODY_BYTES) {
            parameter("categories", categories.joinToString(",", "[", "]") { "\"$it\"" })
            parameter("actionTypes", "[\"skip\"]")
        }
        // 404 means nobody has submitted a segment for any video in the bucket.
        if (status == 404) return emptyList<Segment>().also { cache[key] = it }
        if (body == null) return emptyList()
        val segments = parse(body, videoId, categories)
        cache[key] = segments
        return segments
    }

    companion object {
        private const val API = "https://sponsor.ajay.app"
        /** A bucket holds every video whose hash starts the same way; a few hundred KB at most. */
        private const val MAX_BODY_BYTES = 2 * 1024 * 1024
        private const val MAX_SEGMENTS = 64
        /** No video is longer than this; a segment past it is nonsense, not a skip. */
        private const val MAX_TIME_SEC = 7 * 24 * 60 * 60.0

        /**
         * [videoId]'s segments in a bucket [body]. The answer comes from a community server, so
         * only sane ones are kept: finite times, start before end, within a plausible length, in
         * the categories asked for, and not so many that one answer could keep skipping forever.
         */
        internal fun parse(body: String, videoId: String, categories: Set<String>): List<Segment> {
            val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
            return root.arr.firstOrNull { it["videoID"].string == videoId }?.get("segments").arr.mapNotNull { s ->
                val range = s["segment"].arr
                val start = (range.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                val end = (range.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start || end > MAX_TIME_SEC) return@mapNotNull null
                val category = s["category"].string ?: "sponsor"
                if (category !in categories) return@mapNotNull null
                Segment((start * 1000).toLong(), (end * 1000).toLong(), category)
            }.sortedBy { it.startMs }.take(MAX_SEGMENTS)
        }
        val DEFAULT_CATEGORIES = setOf("sponsor", "selfpromo", "interaction", "intro", "outro", "music_offtopic")

        fun label(category: String): String = when (category) {
            "sponsor" -> "Sponsor"
            "selfpromo" -> "Self-promotion"
            "interaction" -> "Interaction reminder"
            "intro" -> "Intro"
            "outro" -> "Endcards"
            "music_offtopic" -> "Non-music section"
            "preview" -> "Preview"
            "filler" -> "Filler"
            else -> category
        }
    }
}

/**
 * Return YouTube Dislike: YouTube hid dislike counts in 2021; this community
 * API estimates them from archived data and extension users' votes.
 */
class ReturnDislike(private val http: HttpClient) {
    data class Votes(val likes: Long?, val dislikes: Long?)

    private val cache = lruCache<Votes>()

    suspend fun votes(videoId: String): Votes? {
        cache[videoId]?.let { return it }
        val (_, body) = http.getLimited("https://returnyoutubedislikeapi.com/votes", MAX_BODY_BYTES) { parameter("videoId", videoId) }
        return parse(body ?: return null)?.also { cache[videoId] = it }
    }

    companion object {
        /** The answer is a handful of numbers. */
        private const val MAX_BODY_BYTES = 16 * 1024

        /** Counts from a community server: a negative one is no count at all. */
        internal fun parse(body: String): Votes? {
            val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? kotlinx.serialization.json.JsonObject ?: return null
            return Votes(root["likes"].long?.takeIf { it >= 0 }, root["dislikes"].long?.takeIf { it >= 0 })
        }
    }
}
