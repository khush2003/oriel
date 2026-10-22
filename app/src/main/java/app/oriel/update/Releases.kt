package app.oriel.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A published release of Oriel on GitHub, as much of it as updating needs. Kept in prefs between launches. */
@Serializable
data class Release(
    val tag: String,
    val version: String,
    val prerelease: Boolean,
    val notes: String,
    val assets: List<ReleaseAsset>,
) {
    val semVer: SemVer get() = SemVer.parse(version)!!
}

@Serializable
data class ReleaseAsset(val name: String, val size: Long, val url: String)

/** The pure half of updating: reading GitHub's answer, choosing a release and a file, and where bytes may come from. */
object Releases {
    const val REPO = "khush2003/oriel"
    const val API_URL = "https://api.github.com/repos/$REPO/releases?per_page=10"
    const val CHECKSUMS = "SHA256SUMS.txt"

    @Serializable
    private class GhRelease(
        @SerialName("tag_name") val tag: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val body: String? = null,
        val assets: List<GhAsset> = emptyList(),
    )

    @Serializable
    private class GhAsset(
        val name: String = "",
        val size: Long = -1,
        @SerialName("browser_download_url") val url: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    /** Published releases with a SemVer tag. Drafts, odd tags and files that aren't on GitHub are left out. */
    fun parse(body: String): List<Release> = json.decodeFromString<List<GhRelease>>(body)
        .filter { !it.draft }
        .mapNotNull { r ->
            val v = SemVer.parse(r.tag) ?: return@mapNotNull null
            Release(
                tag = r.tag,
                version = v.toString(),
                prerelease = r.prerelease || v.isPreRelease,
                notes = r.body.orEmpty(),
                assets = r.assets.filter { it.size >= 0 && isAllowedUrl(it.url) }.map { ReleaseAsset(it.name, it.size, it.url) },
            )
        }

    /** The newest release above [installed]; pre-releases only when [includePreReleases]. */
    fun pickUpdate(releases: List<Release>, installed: SemVer, includePreReleases: Boolean): Release? = releases
        .filter { includePreReleases || !it.prerelease }
        .filter { it.semVer > installed }
        .maxByOrNull { it.semVer }

    /** The APK built for this phone's preferred ABI, else the universal one. */
    fun apkFor(release: Release, abis: List<String>): ReleaseAsset? {
        fun named(suffix: String) = release.assets.firstOrNull { it.name == "Oriel-${release.version}-$suffix.apk" }
        return abis.firstOrNull()?.let(::named) ?: named("universal")
    }

    fun checksumsOf(release: Release): ReleaseAsset? = release.assets.firstOrNull { it.name == CHECKSUMS }

    /**
     * `sha256sum` output: a 64-digit hash, a space, then a space (text mode) or `*` (binary mode)
     * and the file name. Anything else is ignored; a name listed twice with different hashes
     * isn't trusted at all.
     */
    fun parseChecksums(text: String): Map<String, String> {
        val line = Regex("^([0-9a-fA-F]{64}) [ *](.+)$")
        val seen = HashMap<String, String?>()
        text.lineSequence().map { it.trimEnd('\r') }.forEach { l ->
            val m = line.matchEntire(l) ?: return@forEach
            val (hash, name) = m.destructured
            val h = hash.lowercase()
            seen[name] = if (name in seen && seen[name] != h) null else h
        }
        return seen.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
    }

    /** Release files and the API come only from GitHub over HTTPS, redirects included. */
    fun isAllowedHost(url: HttpUrl): Boolean {
        if (!url.isHttps) return false
        val host = url.host.lowercase()
        return host == "github.com" || host == "api.github.com" || host.endsWith(".githubusercontent.com")
    }

    fun isAllowedUrl(url: String): Boolean = url.toHttpUrlOrNull()?.let(::isAllowedHost) == true

    /**
     * Markdown release notes as plain text: headings and emphasis unmarked, links as their text,
     * list items as bullets, and cut short at [max] characters.
     */
    fun plainNotes(markdown: String, max: Int = 1_500): String {
        val text = markdown.replace("\r\n", "\n").lineSequence().map { raw ->
            var l = raw.trimEnd()
            l = l.replace(Regex("^#{1,6}\\s+"), "")
            l = l.replace(Regex("^(\\s*)[-*+]\\s+"), "$1• ")
            l = l.replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            l = l.replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
            l = l.replace(Regex("(?<![\\w*])[*_]([^*_\\s][^*_]*?)[*_](?![\\w*])"), "$1")
            l.replace("`", "")
        }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        if (text.length <= max) return text
        val cut = text.take(max)
        return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > max / 2 } ?: max).trimEnd() + "…"
    }
}
