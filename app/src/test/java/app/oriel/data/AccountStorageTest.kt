package app.oriel.data

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.core.YouTube
import app.oriel.core.model.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.Collections
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Sign-ins are sealed at rest, one per account; a plain one from an earlier version is sealed in
 * place without signing anyone out; one that can no longer be opened signs that account out and
 * keeps its library. Switching accounts swaps the library and the session together.
 *
 * Robolectric has no Android Keystore, so the box gets a software key. The cookies are invented,
 * and the client's every request fails offline (recording the cookie it carried).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class AccountStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val session = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val files: File = Files.createTempDirectory("oriel-profiles").toFile()
    private val cookie = "SAPISID=fake1; PREF=hl=en"
    private val other = "SAPISID=fake2; PREF=hl=en"
    private val scopes = ArrayList<CoroutineScope>()

    /** The Cookie header of every request the client tried. */
    private val sent: MutableList<String?> = Collections.synchronizedList(ArrayList())

    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private inner class Built(box: SecretBox) {
        val scope = CoroutineScope(SupervisorJob()).also { scopes += it }
        val libraryScope = CoroutineScope(SupervisorJob()).also { scopes += it }
        val yt = YouTube(OkHttpClient.Builder().addInterceptor { chain ->
            sent += chain.request().header("Cookie")
            throw java.io.IOException("offline test")
        }.build())
        val store = ProfileStore(context, files, box)
        val library = ActiveLibrary(libraryScope, store::dir, prepare = store::adoptLegacyFiles, initial = store.index.active)
        val pages = PageDiskCache(File(files, "pages"), libraryScope, profileId = store.index.active)
        val manager = AccountManager(store, { yt }, { yt }, { }, library, pages, scope, clearWebSession = {}, main = Dispatchers.Unconfined)

        fun settle() = runBlocking { scope.coroutineContext.job.children.forEach { (it as Job).join() } }
    }

    @Before fun clean() {
        prefs.edit().clear().commit()
        session.edit().clear().commit()
    }

    @After fun tearDown() {
        scopes.forEach { it.cancel() }
        files.deleteRecursively()
    }

    private fun video(id: String) = Video(id = id, title = "Invented $id")

    @Test fun boxRoundTripsAndRejectsTampering() {
        val box = SecretBox(key().let { k -> { k } })
        val sealed = box.seal(cookie)
        assertTrue(SecretBox.isSealed(sealed))
        assertFalse(sealed.contains("SAPISID"))
        assertEquals(cookie, box.open(sealed))
        // Same text, fresh IV: two seals never match.
        assertNotEquals(sealed, box.seal(cookie))
        // Flip a whole byte of the ciphertext: editing a base64 character can land on padding bits.
        val bytes = Base64.decode(sealed.removePrefix("v1:"), Base64.NO_WRAP)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        assertNull(box.open("v1:" + Base64.encodeToString(bytes, Base64.NO_WRAP)))
        assertNull(box.open("v1:not base64!"))
        assertNull(SecretBox(key().let { k -> { k } }).open(sealed))
    }

    @Test fun aPlainCookieFromAnEarlierVersionBecomesAnAccountSealedAndSignedIn() {
        prefs.edit().putInt("schema", 1).putString("cookie", cookie).putString("datasync", "fake-sync||").commit()
        session.edit().putString("visitorData", "fake-visitor").commit()
        val k = key()
        val built = Built(SecretBox { k })
        built.manager.restore()
        assertTrue(built.manager.signedIn.value)
        assertEquals(ProfileStore.MIGRATED, built.manager.active.value.id)
        assertEquals(listOf(ProfileStore.GUEST, ProfileStore.MIGRATED), built.manager.profiles.value.map { it.id })
        built.settle()
        // The old keys are gone, and the cookie is sealed under the account's own.
        assertNull(prefs.getString("cookie", null))
        val stored = prefs.getString("cookie:${ProfileStore.MIGRATED}", null)!!
        assertTrue(stored, SecretBox.isSealed(stored))
        assertFalse(stored.contains("SAPISID"))
        // And it still opens: the next launch is signed in with the same session and visitor identity.
        val next = Built(SecretBox { k })
        next.manager.restore()
        assertTrue(next.manager.signedIn.value)
        val client = YouTube()
        next.manager.applySession(client)
        assertEquals(cookie, client.cookie)
        assertEquals("fake-sync", client.dataSyncId)
        assertEquals("fake-visitor", client.innerTube.visitorData)
        assertTrue(client.isSignedIn)
    }

    /** The account prefs as an earlier release wrote them (frozen, invented: resources/stored/account.v1.json). */
    @Test fun accountPrefsVersion1Migrate() {
        val doc = app.oriel.util.AppJson.parseToJsonElement(javaClass.classLoader!!.getResource("stored/account.v1.json")!!.readText()).jsonObject
        prefs.edit().apply {
            doc.forEach { (k, v) ->
                val p = v.jsonPrimitive
                when {
                    p.isString -> putString(k, p.content)
                    p.booleanOrNull != null -> putBoolean(k, p.boolean)
                    p.content.toLongOrNull()?.let { it > Int.MAX_VALUE } == true -> putLong(k, p.long)
                    else -> putInt(k, p.int)
                }
            }
        }.commit()
        val built = Built(SecretBox { key() })
        built.manager.restore()
        val account = built.manager.active.value
        assertEquals(ProfileStore.MIGRATED, account.id)
        assertEquals("fake-fixture-sync||", account.dataSyncId)
        assertEquals("Invented Person", account.info?.name)
        assertEquals("@invented", built.manager.account.value?.handle)
        assertTrue(account.merged)
        assertEquals(1760000000000L, account.lastSync)
        assertTrue(built.manager.signedIn.value)
    }

    @Test fun signedOutBeforeProfilesTheLibraryBecomesTheGuests() = runBlocking {
        File(files, "library.json").writeText("""{"liked":[{"id":"fakeVideo01","title":"Invented"}]}""")
        File(files, "queue.json").writeText("{}")
        val built = Built(SecretBox { key() })
        built.manager.restore()
        assertFalse(built.manager.signedIn.value)
        assertEquals(ProfileStore.GUEST, built.manager.active.value.id)
        built.library.awaitLoaded()
        assertEquals(listOf("fakeVideo01"), built.library.state.value.liked.map { it.id })
        assertFalse(File(files, "library.json").exists())
        assertTrue(File(files, "profiles/guest/library.json").isFile)
        assertTrue(File(files, "profiles/guest/queue.json").isFile)
        assertNull(built.store.index.legacyOwner)
    }

    @Test fun aMoveCutShortFinishesWithoutOverwriting() {
        prefs.edit().putInt("schema", 2).commit()
        // The library moved before the process died; its resume points didn't. A stray copy waits at the destination too.
        File(files, "profiles/guest").mkdirs()
        File(files, "profiles/guest/library.json").writeText("moved")
        File(files, "library.json").writeText("stray")
        File(files, "positions.json").writeText("{}")
        val store = ProfileStore(context, files, SecretBox { key() })
        assertEquals(ProfileStore.GUEST, store.index.legacyOwner)
        store.adoptLegacyFiles()
        assertEquals("moved", File(files, "profiles/guest/library.json").readText())
        assertEquals(1, File(files, "profiles/guest").listFiles { f -> f.name.startsWith("library.json.legacy-") }!!.size)
        assertTrue(File(files, "profiles/guest/positions.json").isFile)
        assertNull(store.index.legacyOwner)
        // Read again, nothing changes.
        assertNull(ProfileStore(context, files, SecretBox { key() }).index.legacyOwner)
    }

    @Test fun aCookieThatCantBeOpenedSignsThatAccountOutAndKeepsItsLibrary() {
        // Sealed under a key the keystore no longer has (a reset, an invalidated key).
        prefs.edit().putInt("schema", 2).putString("cookie", SecretBox { key() }.seal(cookie)).putString("account", """{"name":"Invented"}""").commit()
        val built = Built(SecretBox { key() })
        built.manager.restore()
        val client = YouTube()
        built.manager.applySession(client)
        assertNull(client.cookie)
        assertFalse(built.manager.signedIn.value)
        assertNull(prefs.getString("cookie:${ProfileStore.MIGRATED}", null))
        val account = built.store.index.profile(ProfileStore.MIGRATED)!!
        assertFalse(account.signedIn)
        assertEquals("Invented", account.info?.name)
    }

    @Test fun aKeystoreThatCantSealLeavesNothingNewInTheClear() {
        prefs.edit().putInt("schema", 1).putString("cookie", cookie).commit()
        val broken = SecretBox { throw java.security.KeyStoreException("no keystore") }
        val built = Built(broken)
        built.manager.restore()
        built.settle()
        // The old value stays as it was (no sign-out), and is tried again next launch.
        assertEquals(cookie, prefs.getString("cookie:${ProfileStore.MIGRATED}", null))
        assertTrue(built.manager.signedIn.value)
        // A new sign-in that can't be sealed lasts the session, and isn't written at all.
        val result = runBlocking { built.manager.signIn(other, "fake-sync-2") }
        assertEquals(AccountManager.SignInResult.ADDED, result)
        assertTrue(built.manager.signedIn.value)
        assertFalse(prefs.all.values.any { it.toString().contains("fake2") })
    }

    @Test fun aKeystoreThatIsOnlyBusyKeepsTheSession() {
        val k = key()
        prefs.edit().putInt("schema", 2).putString("cookie", SecretBox { k }.seal(cookie)).putString("account", "{}").commit()
        // Busy at launch (as some phones' keystores are just after boot): not a lost key.
        val busy = SecretBox { throw java.security.KeyStoreException("keystore busy") }
        val built = Built(busy)
        built.manager.restore()
        val client = YouTube()
        built.manager.applySession(client)
        assertNull(client.cookie)
        assertTrue("still signed in", built.manager.signedIn.value)
        assertTrue(SecretBox.isSealed(prefs.getString("cookie:${ProfileStore.MIGRATED}", null)!!))
        // The next launch, with the keystore back, carries on with the same session.
        val next = Built(SecretBox { k })
        next.manager.restore()
        val again = YouTube()
        next.manager.applySession(again)
        assertEquals(cookie, again.cookie)
    }

    @Test fun eachAccountKeepsItsOwnLibraryAndSession() = runBlocking {
        val k = key()
        val built = Built(SecretBox { k })
        built.manager.restore()
        built.library.awaitLoaded()
        built.library.setLiked(video("fakeGuest01"), true)

        assertEquals(AccountManager.SignInResult.ADDED, built.manager.signIn(cookie, "fake-sync-a"))
        val a = built.manager.active.value
        assertFalse(a.isGuest)
        assertEquals(cookie, built.yt.cookie)
        // A new account starts with nothing of the Guest's.
        assertEquals(emptySet<String>(), built.library.likedIds.value)
        built.library.setLiked(video("fakeA01"), true)

        assertEquals(AccountManager.SignInResult.ADDED, built.manager.signIn(other, "fake-sync-b"))
        val b = built.manager.active.value
        assertNotEquals(a.id, b.id)
        assertEquals(other, built.yt.cookie)
        assertEquals("fake-sync-b", built.yt.dataSyncId)
        assertEquals(emptyList<Video>(), built.library.state.value.liked)
        built.library.setLiked(video("fakeB01"), true)
        val aFile = File(files, "profiles/${a.id}/library.json").readText()

        assertTrue(built.manager.switchTo(a.id))
        assertEquals(cookie, built.yt.cookie)
        assertEquals("fake-sync-a", built.yt.dataSyncId)
        assertEquals(listOf("fakeA01"), built.library.state.value.liked.map { it.id })
        // Leaving B wrote it out; A's file is as it was.
        assertTrue(File(files, "profiles/${b.id}/library.json").readText().contains("fakeB01"))
        assertEquals(aFile, File(files, "profiles/${a.id}/library.json").readText())

        assertTrue(built.manager.switchTo(ProfileStore.GUEST))
        assertNull(built.yt.cookie)
        assertFalse(built.manager.signedIn.value)
        assertEquals(listOf("fakeGuest01"), built.library.state.value.liked.map { it.id })

        // The next launch comes back to the Guest, with both accounts kept.
        val next = Built(SecretBox { k })
        next.manager.restore()
        assertEquals(ProfileStore.GUEST, next.manager.active.value.id)
        assertEquals(3, next.manager.profiles.value.size)
    }

    @Test fun signingInToAnAccountAlreadyHereSignsItInAgain() = runBlocking {
        val built = Built(SecretBox { key() })
        built.manager.restore()
        built.manager.signIn(cookie, "fake-sync-a")
        val a = built.manager.active.value
        built.library.setLiked(video("fakeA01"), true)
        built.manager.signOut(a.id, keepLibrary = true)
        assertEquals(ProfileStore.GUEST, built.manager.active.value.id)
        assertFalse(built.store.index.profile(a.id)!!.signedIn)

        assertEquals(AccountManager.SignInResult.SIGNED_IN_AGAIN, built.manager.signIn(other, "fake-sync-a"))
        assertEquals(a.id, built.manager.active.value.id)
        assertEquals(2, built.manager.profiles.value.size)
        assertEquals(other, built.yt.cookie)
        assertEquals(listOf("fakeA01"), built.library.state.value.liked.map { it.id })
    }

    @Test fun removingAnAccountDeletesItsFilesAndSignIn() = runBlocking {
        val built = Built(SecretBox { key() })
        built.manager.restore()
        built.manager.signIn(cookie, "fake-sync-a")
        val a = built.manager.active.value
        built.library.setLiked(video("fakeA01"), true)
        built.manager.signOut(a.id, keepLibrary = false)
        assertEquals(ProfileStore.GUEST, built.manager.active.value.id)
        assertNull(built.store.index.profile(a.id))
        assertEquals(emptyList<String>(), built.store.index.removed)
        assertFalse(File(files, "profiles/${a.id}").exists())
        assertFalse(prefs.all.keys.any { it.startsWith("cookie:") })
    }

    @Test fun anIndexThatCantBeReadDeletesNothing() {
        prefs.edit().putInt("schema", 3).putString("index", "not json").commit()
        File(files, "profiles/someone").mkdirs()
        File(files, "profiles/someone/library.json").writeText("{}")
        val built = Built(SecretBox { key() })
        built.manager.restore()
        built.settle()
        assertEquals(listOf(ProfileStore.GUEST), built.manager.profiles.value.map { it.id })
        assertTrue(File(files, "profiles/someone/library.json").isFile)
        assertEquals("not json", prefs.getString("index.unreadable", null))
    }

    @Test fun afterASwitchNothingGoesOutWithTheAccountLeft() = runBlocking {
        val built = Built(SecretBox { key() })
        built.manager.restore()
        built.manager.signIn(cookie, "fake-sync-a")
        built.settle()
        val a = built.manager.active.value
        built.manager.signIn(other, "fake-sync-b")
        built.settle()
        // Every request the client made went out with the sign-in of the account in use when it was made.
        assertTrue(sent.all { it == null || it.contains("fake1") || it.contains("fake2") || it.startsWith("PREF") })
        sent.clear()
        built.manager.switchTo(a.id)
        built.settle()
        assertTrue(sent.none { it?.contains("fake2") == true })
    }
}
