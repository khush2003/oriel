package app.oriel.util

import app.oriel.core.model.Video

/**
 * Thumbnail URLs at the size they'll be drawn.
 *
 * Avatars on googleusercontent/ggpht take their size in the URL (`=s176-…`),
 * so a 24dp avatar never decodes a 900px original. Video frames come from
 * i.ytimg.com in a few fixed renditions; small cards take the 320×180 one,
 * big ones keep the signed 1280×720 URL YouTube handed out.
 */
object Thumbnails {
    private val SIZED = Regex("""=w\d+-h\d+(?=[^/]*$)""")
    private val SQUARE = Regex("""=s\d+(?=[^/]*$)""")
    private val VIDEO_ID = Regex("""i\.ytimg\.com/(?:vi|vi_webp)/([\w-]{11})/""")

    /**
     * [url] as it may be fetched: image URLs come from responses, and a plain-http one (or a
     * protocol-relative `//host/...`) is asked for over HTTPS instead, as cleartext is never sent.
     */
    fun secure(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.regionMatches(0, "http://", 0, 7, ignoreCase = true) -> "https://" + url.substring(7)
        else -> url
    }

    fun sized(url: String?, px: Int): String? {
        if (url == null) return null
        return when {
            "googleusercontent.com" in url || "ggpht.com" in url -> when {
                // Only the dimensions change: flags like -c (crop) and the format stay as served.
                SIZED.containsMatchIn(url) -> url.replace(SIZED, "=w$px-h$px")
                SQUARE.containsMatchIn(url) -> url.replace(SQUARE, "=s$px")
                else -> "$url=s$px"
            }
            "i.ytimg.com/vi" in url && px <= 320 -> VIDEO_ID.find(url)?.groupValues?.get(1)?.let { frame(it, px) } ?: url
            else -> url
        }
    }

    /** A 16:9-croppable frame of [videoId] that every video has. */
    fun frame(videoId: String, px: Int = 480): String =
        "https://i.ytimg.com/vi/$videoId/${if (px <= 320) "mqdefault" else "hqdefault"}.jpg"

    /** The frame to show for [video] at [px] wide. */
    fun of(video: Video, px: Int): String =
        sized(video.thumbnail, px) ?: frame(video.id, px)

    /** Vertical frame for Shorts; YouTube's own 9:16 poster when it has one. */
    fun short(video: Video): String = video.thumbnail ?: "https://i.ytimg.com/vi/${video.id}/oar2.jpg"
}
