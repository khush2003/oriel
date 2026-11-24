package app.oriel.data

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.core.model.Feed
import app.oriel.core.model.Video
import app.oriel.util.AppJson
import app.oriel.util.Schema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** What the library and the page cache do with what they find on disk. All data is invented. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class LibraryStoreTest {
    private val dir: File = Files.createTempDirectory("oriel-store").toFile()
    private val scope = CoroutineScope(SupervisorJob())
    private val reported = mutableListOf<String>()

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun store(file: File = File(dir, "library.json")) = LibraryStore(file, scope) { where, _ -> synchronized(reported) { reported += where } }

    private fun video(id: String) = Video(id = id, title = "Invented $id")

    @Test
    fun aVersion0LibraryIsBackedUpAndWrittenAgainVersioned() = runBlocking {
        val file = File(dir, "library.json")
        val original = javaClass.classLoader!!.getResource("stored/library.v0.json")!!.readText()
        file.writeText(original)
        val s = store(file)
        s.awaitLoaded()
        assertEquals(2, s.state.value.liked.size)
        assertTrue(s.isLiked("fakeVideo01"))
        assertEquals(original, File(dir, "library.v0.bak").readText())
        s.flush()
        assertEquals(Schema.LIBRARY.current, AppJson.parseToJsonElement(file.readText()).jsonObject[Schema.KEY]!!.jsonPrimitive.int)
        // Resume points moved to their own file.
        assertEquals(120000L, AppJson.parseToJsonElement(File(dir, "positions.json").readText()).jsonObject["fakeVideo04"]!!.jsonPrimitive.int.toLong())
    }

    @Test
    fun aCorruptLibraryIsKeptAsideAndNotLost() = runBlocking {
        val file = File(dir, "library.json")
        file.writeText("{ this is not json")
        val s = store(file)
        s.awaitLoaded()
        assertFalse(s.readOnly)
        val kept = dir.listFiles { f -> f.name.startsWith("library.json.unreadable-") }!!
        assertEquals(1, kept.size)
        assertEquals("{ this is not json", kept[0].readText())
        assertTrue(s.problems.first().isNotBlank())
        assertTrue(reported.isNotEmpty())
    }

    @Test
    fun onlyTheLastThreeUnreadableCopiesAreKept() = runBlocking {
        listOf(1L, 2L, 3L).forEach { File(dir, "library.json.unreadable-$it").writeText("old $it") }
        File(dir, "library.json").writeText("[]")
        store().awaitLoaded()
        val kept = dir.listFiles { f -> f.name.startsWith("library.json.unreadable") }!!.map { it.name }.toSet()
        assertEquals(3, kept.size)
        assertFalse("library.json.unreadable-1" in kept)
    }

    @Test
    fun aLibraryThatCantBeReadIsNeverOverwritten() = runBlocking {
        // A directory where the file should be: reading fails with an IOException, not a decode error.
        val file = File(dir, "library.json").apply { mkdirs() }
        File(file, "inside").writeText("keep")
        val s = store(file)
        s.awaitLoaded()
        assertTrue(s.readOnly)
        s.setLiked(video("newVideo001"), true)
        s.flush()
        assertTrue(file.isDirectory)
        assertEquals("keep", File(file, "inside").readText())
        assertTrue(s.problems.first().isNotBlank())
    }

    @Test
    fun changesBeforeLoadingLandOnTopOfWhatWasRead() = runBlocking {
        val file = File(dir, "library.json")
        file.writeText("""{"schema":1,"liked":[{"id":"diskVideo01","title":"On disk"}]}""")
        val s = store(file)
        s.setLiked(video("earlyVideo1"), true)
        s.awaitLoaded()
        assertEquals(listOf("earlyVideo1", "diskVideo01"), s.state.value.liked.map { it.id })
        assertTrue(s.isLiked("earlyVideo1"))
    }

    @Test
    fun aPartialLikesSyncKeepsYouTubeOrderAheadOfTheUnseenCachedTail() = runBlocking {
        val s = store()
        s.awaitLoaded()
        s.mergeRemoteLikes(listOf(video("cachedOne"), video("cachedTwo"), video("olderUpload")), complete = true)
        s.beginSync()

        val remote = listOf(
            video("olderUpload").copy(publishedText = "6 years ago"),
            video("newerUpload").copy(publishedText = "1 day ago"),
            video("olderUpload"),
        )
        s.mergeRemoteLikes(remote, complete = false)
        assertEquals(listOf("olderUpload", "newerUpload", "cachedOne", "cachedTwo"), s.state.value.liked.map { it.id })
        assertEquals("6 years ago", s.state.value.liked.first().publishedText)

        s.beginSync()
        s.mergeRemoteLikes(remote, complete = true)
        assertEquals(listOf("olderUpload", "newerUpload"), s.state.value.liked.map { it.id })
    }

    @Test
    fun aFirstLikesSyncKeepsLocalOnlyLikesWithoutInterruptingYouTubeOrder() = runBlocking {
        val s = store()
        s.awaitLoaded()
        s.setLiked(video("localOlder"), true)
        s.setLiked(video("localNewer"), true)
        s.beginSync()

        s.mergeRemoteLikes(listOf(video("remoteNewer"), video("remoteOlder")), complete = true, keepLocal = true)
        assertEquals(listOf("remoteNewer", "remoteOlder", "localNewer", "localOlder"), s.state.value.liked.map { it.id })
    }

    @Test
    fun likesChangedDuringSyncKeepTheirNewLocalOrderAndRemovals() = runBlocking {
        val s = store()
        s.awaitLoaded()
        val reliked = video("reliked")
        val removed = video("removed")
        val unchanged = video("unchanged")
        val remote = listOf(removed, unchanged, reliked)
        s.mergeRemoteLikes(remote, complete = true)
        s.beginSync()

        s.setLiked(reliked, false)
        s.setLiked(reliked, true)
        s.setLiked(video("brandNew"), true)
        s.setLiked(removed, false)
        s.mergeRemoteLikes(remote, complete = true)

        assertEquals(listOf("brandNew", "reliked", "unchanged"), s.state.value.liked.map { it.id })
    }

    @Test
    fun addingToAPlaylistKeepsOrderAndSkipsWhatsThere() = runBlocking {
        val s = store()
        s.awaitLoaded()
        val p = s.createPlaylist("Invented", listOf(video("fakeVideo01"), video("fakeVideo02")))
        s.addToLocalPlaylist(p.id, listOf(video("fakeVideo03"), video("fakeVideo02"), video("fakeVideo04"), video("fakeVideo03")))
        assertEquals(listOf("fakeVideo01", "fakeVideo02", "fakeVideo03", "fakeVideo04"), s.state.value.localPlaylists.single().videos.map { it.id })
    }

    @Test
    fun pagesFromBeforeAClearAreDropped() {
        val pages = PageDiskCache(File(dir, "pages"), scope, profileId = "guest")
        val before = pages.generation
        pages.write("home", Feed.serializer(), Feed(message = "one"))
        assertEquals("one", pages.read("home", Feed.serializer())?.message)
        pages.clear()
        assertNull(pages.read("home", Feed.serializer()))
        // A load that started before the clear finishes after it.
        pages.write("home", Feed.serializer(), Feed(message = "stale"), before)
        assertNull(pages.read("home", Feed.serializer()))
        pages.write("home", Feed.serializer(), Feed(message = "fresh"))
        assertEquals("fresh", pages.read("home", Feed.serializer())?.message)
        assertTrue(File(dir, "pages/v${PageDiskCache.VERSION}/guest/home.json").isFile)
    }

    @Test
    fun eachProfileHasItsOwnPages() {
        val pages = PageDiskCache(File(dir, "pages"), scope, profileId = "guest")
        pages.write("home", Feed.serializer(), Feed(message = "guest's"))
        val before = pages.generation
        pages.use("a0")
        assertNull(pages.read("home", Feed.serializer()))
        // A load begun for the Guest finishing now doesn't land in the account's pages.
        pages.write("home", Feed.serializer(), Feed(message = "late"), before)
        assertNull(pages.read("home", Feed.serializer()))
        pages.use("guest")
        assertEquals("guest's", pages.read("home", Feed.serializer())?.message)
        pages.delete("guest")
        assertNull(pages.read("home", Feed.serializer()))
    }
}
