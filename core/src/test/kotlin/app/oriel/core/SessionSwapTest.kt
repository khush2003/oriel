package app.oriel.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * With several accounts, the session changes under requests in flight. Each request carries one
 * session whole (cookie, channel, visitor id together), and an answer to the old session doesn't
 * give its visitor id to the new one. Invented sessions; nothing leaves the test process.
 */
class SessionSwapTest {
    private data class Seen(val cookie: String?, val channel: String?, val visitor: String?)

    private class Session(val cookie: String, val channel: String, val visitor: String)

    private val a = Session("SAPISID=fakeA; PREF=hl=en", "fake-channel-a", "fake-visitor-a")
    private val b = Session("SAPISID=fakeB; PREF=hl=en", "fake-channel-b", "fake-visitor-b")

    private fun client(seen: MutableList<Seen>, reply: () -> String = { "{}" }, hold: CountDownLatch? = null) = OkHttpClient.Builder().addInterceptor { chain ->
        val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
        val context = Json.parseToJsonElement(body).jsonObject["context"]!!.jsonObject
        seen += Seen(
            chain.request().header("Cookie")?.takeIf { "SAPISID" in it },
            context["user"]?.jsonObject?.get("onBehalfOfUser")?.jsonPrimitive?.content,
            context["client"]?.jsonObject?.get("visitorData")?.jsonPrimitive?.content,
        )
        hold?.await(5, TimeUnit.SECONDS)
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(reply().toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test fun everyRequestCarriesOneWholeSession() = runBlocking {
        val seen = Collections.synchronizedList(ArrayList<Seen>())
        val yt = YouTube(client(seen))
        yt.setSession(a.cookie, a.channel, a.visitor)
        val flipping = launch(Dispatchers.Default) {
            repeat(400) { i -> (if (i % 2 == 0) b else a).let { yt.setSession(it.cookie, it.channel, it.visitor) } }
        }
        (0 until 200).map { async(Dispatchers.IO) { runCatching { yt.browse("FEfake", fresh = true) } } }.awaitAll()
        flipping.join()
        assertTrue(seen.isNotEmpty())
        for (s in seen) assertTrue(s == Seen(a.cookie, a.channel, a.visitor) || s == Seen(b.cookie, b.channel, b.visitor), "mixed session: $s")
    }

    @Test fun anAnswerToTheLastSessionDoesntGiveItsVisitorToTheNext() = runBlocking {
        val seen = Collections.synchronizedList(ArrayList<Seen>())
        val hold = CountDownLatch(1)
        val yt = YouTube(client(seen, reply = { """{"responseContext":{"visitorData":"fake-visitor-of-a"}}""" }, hold = hold))
        // Signed in as A with no visitor id yet: the answer would bring one.
        yt.setSession(a.cookie, a.channel, null)
        val call = async(Dispatchers.IO) { runCatching { yt.browse("FEfake", fresh = true) } }
        while (seen.isEmpty()) Thread.sleep(5)
        yt.setSession(b.cookie, b.channel, null)
        hold.countDown()
        call.await()
        assertNull(yt.innerTube.visitorData)
        assertEquals(b.cookie, yt.cookie)
    }
}
