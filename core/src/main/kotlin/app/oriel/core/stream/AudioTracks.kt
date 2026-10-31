package app.oriel.core.stream

import java.net.URLDecoder

/**
 * What a video's sound track is, from YouTube's `acont` tag ("audio content"): the language it
 * was made in, a dub (by people or, `dubbed-auto`, by YouTube's machine dubbing), an audio
 * description, or another track the uploader added.
 */
enum class AudioContent { ORIGINAL, DUBBED, AUTO_DUBBED, DESCRIPTIVE, SECONDARY, UNKNOWN }

/** One of a video's sound tracks, as far as YouTube describes it. */
data class AudioTrackInfo(
    /** YouTube's display name, in the app's language: "English (United States) original". */
    val name: String,
    val language: String?,
    val content: AudioContent,
    /** The one YouTube would start in; for an auto-dubbed video, often the dub in the viewer's language. */
    val isDefault: Boolean,
)

/**
 * Which sound to start a video in. [language] is a track picked in the player this session: the
 * next videos start in it too when they have it. Otherwise [preferOriginal] (the setting) plays
 * the language the video was made in, and without it YouTube's own default plays.
 */
data class AudioPreference(val preferOriginal: Boolean = true, val language: String? = null)

object AudioTracks {
    /**
     * The track to start in, or null to leave YouTube's default alone (one track, nothing known
     * about them, or no preference that applies).
     */
    fun <T> choose(tracks: List<T>, preference: AudioPreference, info: (T) -> AudioTrackInfo): T? {
        if (tracks.size < 2) return null
        val wanted = preference.language?.let(::primary)
        if (wanted != null) {
            val same = tracks.filter { t -> info(t).language?.let(::primary) == wanted }
            // An exact tag first ("es-419" over "es-ES"), then the original, then anything but a description.
            same.sortedWith(
                compareBy<T>(
                    { !info(it).language.equals(preference.language, ignoreCase = true) },
                    { info(it).content != AudioContent.ORIGINAL },
                    { info(it).content == AudioContent.DESCRIPTIVE },
                ),
            ).firstOrNull()?.let { return it }
        }
        if (preference.preferOriginal) tracks.firstOrNull { info(it).content == AudioContent.ORIGINAL }?.let { return it }
        return tracks.firstOrNull { info(it).isDefault }
    }

    /**
     * The `acont` value of YouTube's xtags in their plain form (`acont=dubbed-auto:lang=es`), or
     * URL-encoded as googlevideo URLs carry them (`acont%3Doriginal%3Alang%3Den-US`).
     */
    fun contentOf(xtags: String?): AudioContent? {
        val text = xtags?.takeIf { it.isNotBlank() }?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) } ?: return null
        val value = text.split(':', '&', ',').map { it.trim() }.firstOrNull { it.startsWith("acont=") }?.substringAfter('=') ?: return null
        return when (value.lowercase()) {
            "original" -> AudioContent.ORIGINAL
            "dubbed-auto" -> AudioContent.AUTO_DUBBED
            "dubbed" -> AudioContent.DUBBED
            "descriptive" -> AudioContent.DESCRIPTIVE
            "secondary" -> AudioContent.SECONDARY
            else -> AudioContent.UNKNOWN
        }
    }

    /**
     * A last resort when no tag says: YouTube's display names end in "original" or
     * "(auto-dubbed)" when the app's language is English. Other languages' names say it in
     * their own words, which this doesn't guess at.
     */
    fun contentOfName(name: String?): AudioContent? {
        val n = name?.lowercase() ?: return null
        return when {
            "auto-dubbed" in n || "auto dubbed" in n -> AudioContent.AUTO_DUBBED
            "dubbed" in n -> AudioContent.DUBBED
            "descriptive" in n || "audio description" in n -> AudioContent.DESCRIPTIVE
            "original" in n -> AudioContent.ORIGINAL
            else -> null
        }
    }

    private fun primary(tag: String): String = tag.substringBefore('-').substringBefore('_').lowercase()
}
