package app.oriel.data

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oriel.TestApp
import app.oriel.core.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
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
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The sign-in cookie is sealed at rest, a plain one from an earlier version is sealed in place
 * without signing anyone out, and one that can no longer be opened signs out instead of crashing.
 * Robolectric has no Android Keystore, so the box gets a software key; the cookies are invented.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [35])
class AccountStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val cookie = "SAPISID=fake1; PREF=hl=en"

    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private class Built(val manager: AccountManager, val scope: CoroutineScope)

    private fun manager(box: SecretBox): Built {
        val scope = CoroutineScope(SupervisorJob())
        // Its own scope: the library's background work isn't what [settle] waits for.
        val library = LibraryStore(File(context.cacheDir, "library-${System.nanoTime()}.json"), CoroutineScope(SupervisorJob()))
        val yt = YouTube()
        return Built(AccountManager(context, { yt }, { error("no resolver in this test") }, library, scope, box = box), scope)
    }

    private fun Built.settle() = runBlocking { scope.coroutineContext.job.children.forEach { (it as Job).join() } }

    @Before fun clean() = prefs.edit().clear().commit().let { }

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

    @Test fun aPlainCookieFromAnEarlierVersionIsSealedAndStaysSignedIn() {
        prefs.edit().putInt("schema", 1).putString("cookie", cookie).putString("datasync", "fake-sync").commit()
        val k = key()
        val built = manager(SecretBox { k })
        built.manager.restore()
        assertTrue(built.manager.signedIn.value)
        built.settle()
        val stored = prefs.getString("cookie", null)!!
        assertTrue(stored, SecretBox.isSealed(stored))
        assertFalse(stored.contains("SAPISID"))
        // And it still opens: the next launch is signed in with the same session.
        val next = manager(SecretBox { k })
        next.manager.restore()
        assertTrue(next.manager.signedIn.value)
        val client = YouTube()
        next.manager.applySession(client)
        assertEquals(cookie, client.cookie)
        assertTrue(client.isSignedIn)
    }

    @Test fun aCookieThatCantBeOpenedSignsOut() {
        // Sealed under a key the keystore no longer has (a reset, an invalidated key).
        prefs.edit().putInt("schema", 2).putString("cookie", SecretBox { key() }.seal(cookie)).putString("account", "{}").commit()
        val built = manager(SecretBox { key() })
        built.manager.restore()
        val client = YouTube()
        built.manager.applySession(client)
        assertNull(client.cookie)
        assertFalse(built.manager.signedIn.value)
        assertNull(prefs.getString("cookie", null))
        assertNull(prefs.getString("account", null))
    }

    @Test fun aKeystoreThatCantSealLeavesNothingInTheClear() {
        prefs.edit().putInt("schema", 1).putString("cookie", cookie).commit()
        val broken = SecretBox { throw java.security.KeyStoreException("no keystore") }
        val built = manager(broken)
        built.manager.restore()
        built.settle()
        // The old value stays as it was (no sign-out), and is tried again next launch.
        assertEquals(cookie, prefs.getString("cookie", null))
        assertTrue(built.manager.signedIn.value)
    }

    @Test fun aKeystoreThatIsOnlyBusyKeepsTheSession() {
        val k = key()
        prefs.edit().putInt("schema", 2).putString("cookie", SecretBox { k }.seal(cookie)).putString("account", "{}").commit()
        // Busy at launch (as some phones' keystores are just after boot): not a lost key.
        val busy = SecretBox { throw java.security.KeyStoreException("keystore busy") }
        val built = manager(busy)
        built.manager.restore()
        val client = YouTube()
        built.manager.applySession(client)
        assertNull(client.cookie)
        assertTrue("still signed in", built.manager.signedIn.value)
        assertTrue(SecretBox.isSealed(prefs.getString("cookie", null)!!))
        assertEquals("{}", prefs.getString("account", null))
        // The next launch, with the keystore back, carries on with the same session.
        val next = manager(SecretBox { k })
        next.manager.restore()
        val again = YouTube()
        next.manager.applySession(again)
        assertEquals(cookie, again.cookie)
    }
}
