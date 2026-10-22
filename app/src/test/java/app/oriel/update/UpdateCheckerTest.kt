package app.oriel.update

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The checker's requests and downloads against an in-memory GitHub: nothing leaves the machine. */
class UpdateCheckerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val seen = mutableListOf<Request>()

    private fun checker(code: Int = 200, body: ByteArray) = UpdateChecker("Oriel/9.0.0", OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        seen += chain.request()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x").body(body.toResponseBody()).build()
    }).build())

    private val apk = ReleaseAsset("Oriel-9.1.0-arm64-v8a.apk", 5_000, "https://github.com/khush2003/oriel/releases/download/v9.1.0/Oriel-9.1.0-arm64-v8a.apk")

    @Test fun asksWithOnlyAPlainUserAgent() {
        checker(body = "[]".toByteArray()).releases()
        val r = seen.single()
        assertEquals(Releases.API_URL, r.url.toString())
        assertEquals("Oriel/9.0.0", r.header("User-Agent"))
        assertEquals("application/vnd.github+json", r.header("Accept"))
        assertTrue(r.header("Cookie") == null && r.header("Authorization") == null)
    }

    @Test fun rateLimitIsAQuietFailure() {
        try {
            checker(code = 403, body = "{}".toByteArray()).releases()
            fail()
        } catch (e: UpdateCheckException) { /* expected */ }
    }

    @Test fun downloadHashesWhatItWrites() = runBlocking {
        val bytes = ByteArray(5_000) { (it % 251).toByte() }
        val file = tmp.newFile()
        var last = 0L
        val sha = checker(body = bytes).download(apk, file) { read, _ -> last = read }
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, sha)
        assertEquals(5_000L, last)
        assertTrue(file.readBytes().contentEquals(bytes))
    }

    @Test fun moreThanTheReleaseListsIsRefusedAndDeleted() = runBlocking {
        val file = tmp.newFile()
        try {
            checker(body = ByteArray(6_000)).download(apk, file) { _, _ -> }
            fail()
        } catch (e: IOException) { /* expected */ }
        assertFalse(file.exists())
    }

    @Test fun lessThanTheReleaseListsIsRefusedAndDeleted() = runBlocking {
        val file = File(tmp.root, "update.apk")
        try {
            checker(body = ByteArray(4_000)).download(apk, file) { _, _ -> }
            fail()
        } catch (e: IOException) { /* expected */ }
        assertFalse(file.exists())
    }

    @Test fun redirectsOffGitHubAreRefused() {
        try {
            UpdateChecker.GitHubOnly.intercept(chain("https://evil.example.com/x.apk"))
            fail()
        } catch (e: IOException) { /* expected */ }
        try {
            UpdateChecker.GitHubOnly.intercept(chain("http://objects.githubusercontent.com/x"))
            fail()
        } catch (e: IOException) { /* expected */ }
        assertEquals(200, UpdateChecker.GitHubOnly.intercept(chain("https://objects.githubusercontent.com/x")).code)
    }

    /** One hop's chain, as a network interceptor sees it: only request() and proceed() are needed. */
    private fun chain(url: String): Interceptor.Chain {
        val request = Request.Builder().url(url).build()
        return java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Interceptor.Chain::class.java)) { _, method, args ->
            when (method.name) {
                "request" -> request
                "proceed" -> Response.Builder().request(args[0] as Request).protocol(Protocol.HTTP_1_1).code(200).message("ok").body(ByteArray(0).toResponseBody()).build()
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Interceptor.Chain
    }
}
