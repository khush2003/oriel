package app.oriel.core.extras

import app.oriel.core.json.arr
import app.oriel.core.json.get
import app.oriel.core.json.long
import app.oriel.core.json.string
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
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
        val response = http.get("$API/api/skipSegments/$prefix") {
            parameter("categories", categories.joinToString(",", "[", "]") { "\"$it\"" })
            parameter("actionTypes", "[\"skip\"]")
        }
        // 404 means nobody has submitted a segment for any video in the bucket.
        if (response.status.value == 404) return emptyList<Segment>().also { cache[key] = it }
        if (response.status.value !in 200..299) return emptyList()
        val root = Json.parseToJsonElement(response.bodyAsText())
        val segments = root.arr.firstOrNull { it["videoID"].string == videoId }?.get("segments").arr.mapNotNull { s ->
            val range = s["segment"].arr
            val start = (range.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val end = (range.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            Segment((start * 1000).toLong(), (end * 1000).toLong(), s["category"].string ?: "sponsor")
        }.sortedBy { it.startMs }
        cache[key] = segments
        return segments
    }

    companion object {
        private const val API = "https://sponsor.ajay.app"
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
        val response = http.get("https://returnyoutubedislikeapi.com/votes") { parameter("videoId", videoId) }
        if (response.status.value !in 200..299) return null
        val root = Json.parseToJsonElement(response.bodyAsText())
        return Votes(root["likes"].long, root["dislikes"].long).also { cache[videoId] = it }
    }
}
