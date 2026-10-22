package app.oriel.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invented releases in the shape of GitHub's API: made-up versions, sizes and hashes. */
class ReleasesTest {
    private fun asset(tag: String, name: String, size: Long = 1_000, host: String = "github.com") =
        """{"name":"$name","size":$size,"content_type":"application/vnd.android.package-archive","browser_download_url":"https://$host/khush2003/oriel/releases/download/$tag/$name"}"""

    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false, extra: List<String> = emptyList()): String {
        val v = tag.removePrefix("v")
        val apks = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "universal").map { asset(tag, "Oriel-$v-$it.apk") }
        val assets = (apks + asset(tag, "SHA256SUMS.txt", 488) + extra).joinToString(",")
        return """{"tag_name":"$tag","name":"Oriel $v","draft":$draft,"prerelease":$prerelease,"body":"## What's new\n- **Faster** start","html_url":"https://github.com/khush2003/oriel/releases/tag/$tag","assets":[$assets]}"""
    }

    private val feed = "[" + listOf(
        release("v9.3.0", draft = true),
        release("v9.2.0-rc.1", prerelease = true),
        release("nightly"),
        release("v9.1.0", extra = listOf(asset("v9.1.0", "Oriel-9.1.0-evil.apk", host = "example.com"))),
        release("v9.0.0"),
        release("v9.0.0-beta.10", prerelease = true),
    ).joinToString(",") + "]"

    private val releases = Releases.parse(feed)

    @Test fun draftsAndOddTagsAreLeftOut() {
        assertEquals(listOf("9.2.0-rc.1", "9.1.0", "9.0.0", "9.0.0-beta.10"), releases.map { it.version })
        assertTrue(releases.first { it.version == "9.2.0-rc.1" }.prerelease)
    }

    @Test fun filesOffGitHubAreDropped() {
        val r = releases.first { it.version == "9.1.0" }
        assertFalse(r.assets.any { it.name.contains("evil") })
        assertEquals(6, r.assets.size)
    }

    @Test fun stableUsersGetTheNewestStable() {
        assertEquals("9.1.0", Releases.pickUpdate(releases, SemVer.parse("9.0.0")!!, includePreReleases = false)?.version)
        assertNull(Releases.pickUpdate(releases, SemVer.parse("9.1.0")!!, includePreReleases = false))
    }

    @Test fun betaUsersGetTheNewestOfAll() {
        assertEquals("9.2.0-rc.1", Releases.pickUpdate(releases, SemVer.parse("9.0.0-beta.2")!!, includePreReleases = true)?.version)
        assertNull(Releases.pickUpdate(releases, SemVer.parse("9.2.0-rc.1")!!, includePreReleases = true))
    }

    @Test fun aBetaUserWithBetasOffStillGetsTheRelease() {
        assertEquals("9.1.0", Releases.pickUpdate(releases, SemVer.parse("9.0.0-beta.10")!!, includePreReleases = false)?.version)
    }

    @Test fun nothingOlderOrEqualIsOffered() {
        assertNull(Releases.pickUpdate(releases, SemVer.parse("10.0.0")!!, includePreReleases = true))
    }

    @Test fun apkForThePreferredAbiElseUniversal() {
        val r = releases.first { it.version == "9.1.0" }
        assertEquals("Oriel-9.1.0-arm64-v8a.apk", Releases.apkFor(r, listOf("arm64-v8a", "armeabi-v7a"))?.name)
        assertEquals("Oriel-9.1.0-x86.apk", Releases.apkFor(r, listOf("x86"))?.name)
        assertEquals("Oriel-9.1.0-universal.apk", Releases.apkFor(r, listOf("riscv64"))?.name)
        assertEquals("Oriel-9.1.0-universal.apk", Releases.apkFor(r, emptyList())?.name)
        assertNull(Releases.apkFor(r.copy(assets = r.assets.filter { it.name.endsWith(".txt") }), listOf("arm64-v8a")))
        assertEquals("SHA256SUMS.txt", Releases.checksumsOf(r)?.name)
    }

    @Test fun parsesSha256sumOutput() {
        val a = "a".repeat(64)
        val b = "0123456789ABCDEF".repeat(4)
        val sums = Releases.parseChecksums(
            "$a  Oriel-9.1.0-arm64-v8a.apk\r\n$b *Oriel-9.1.0-universal.apk\nnot a line\n${"c".repeat(63)}  short.apk\n\n",
        )
        assertEquals(mapOf("Oriel-9.1.0-arm64-v8a.apk" to a, "Oriel-9.1.0-universal.apk" to b.lowercase()), sums)
    }

    @Test fun aNameListedTwiceDifferentlyIsNotTrusted() {
        val sums = Releases.parseChecksums("${"a".repeat(64)}  x.apk\n${"b".repeat(64)}  x.apk\n${"d".repeat(64)}  y.apk\n${"d".repeat(64)}  y.apk")
        assertEquals(mapOf("y.apk" to "d".repeat(64)), sums)
    }

    @Test fun onlyGitHubOverHttps() {
        listOf(
            "https://api.github.com/repos/khush2003/oriel/releases",
            "https://github.com/khush2003/oriel/releases/download/v9.1.0/x.apk",
            "https://objects.githubusercontent.com/github-production-release-asset/1/2",
            "https://release-assets.githubusercontent.com/x",
        ).forEach { assertTrue(it, Releases.isAllowedHost(it.toHttpUrl())) }
        listOf(
            "http://github.com/x",
            "http://objects.githubusercontent.com/x",
            "https://github.com.example.com/x",
            "https://evilgithubusercontent.com/x",
            "https://githubusercontent.com.evil.net/x",
            "https://gist.github.com/x",
            "https://example.com/x",
        ).forEach { assertFalse(it, Releases.isAllowedHost(it.toHttpUrl())) }
        assertFalse(Releases.isAllowedUrl("file:///sdcard/x.apk"))
        assertFalse(Releases.isAllowedUrl("not a url"))
    }

    @Test fun notesBecomePlainText() {
        val md = "## Fixes\r\n- **Faster** start, see [the issue](https://github.com/khush2003/oriel/issues/1)\n* `Shorts` *resume*\n\n\n\nThanks"
        assertEquals("Fixes\n• Faster start, see the issue\n• Shorts resume\n\nThanks", Releases.plainNotes(md))
        val long = Releases.plainNotes("word ".repeat(1_000), max = 100)
        assertTrue(long.length <= 101)
        assertTrue(long.endsWith("…"))
    }

    @Test fun releaseSurvivesBeingStored() {
        val r = releases.first()
        val json = kotlinx.serialization.json.Json.encodeToString(Release.serializer(), r)
        assertEquals(r, kotlinx.serialization.json.Json.decodeFromString(Release.serializer(), json))
    }
}
