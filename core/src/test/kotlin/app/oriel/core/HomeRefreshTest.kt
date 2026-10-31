package app.oriel.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Invented responses served by an interceptor: no requests leave the test process. */
class HomeRefreshTest {
    private fun home(token: String) = """
        {"contents":{"richGridRenderer":{"header":{"chipCloudRenderer":{"chips":[
          {"chipCloudChipRenderer":{"text":{"simpleText":"Music"},"navigationEndpoint":{"continuationCommand":{"token":"$token"}}}}
        ]}},"contents":[]}}}
    """.trimIndent()

    private fun filtered() = """
        {"onResponseReceivedActions":[{"reloadContinuationItemsCommand":{"continuationItems":[
          {"videoRenderer":{"videoId":"AAAAAAAAAAA","title":{"simpleText":"Fixture music video"}}}
        ]}}]}
    """.trimIndent()

    private suspend fun withClient(reply: (String?) -> String, test: suspend (YouTube) -> Unit) {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val token = Json.parseToJsonElement(body).jsonObject["continuation"]?.jsonPrimitive?.content
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(reply(token).toResponseBody("application/json".toMediaType())).build()
        }.build()
        val yt = YouTube(client)
        try {
            withTimeout(5_000) { test(yt) }
        } finally {
            yt.http.close()
            yt.streamingHttp.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun refreshUsesTheNewHomeTokenRatherThanTheSelectedChipsOldToken() = runBlocking {
        val requests = ArrayList<String?>()
        var homeLoads = 0
        withClient(reply = { token ->
            requests += token
            if (token == null) home(if (homeLoads++ == 0) "old-token" else "new-token") else filtered()
        }) { yt ->
            val original = yt.browse(YouTube.HOME).chips.single()
            val refreshed = yt.homeFilter(original.title, original.token!!, fresh = true)
            assertEquals(listOf(null, null, "new-token"), requests)
            assertEquals("AAAAAAAAAAA", refreshed.items.single().id)
            assertEquals("new-token", refreshed.chips.single().token)
            assertTrue(refreshed.chips.single().selected)
        }
    }

    @Test fun selectingAChipDoesNotReloadHome() = runBlocking {
        val requests = ArrayList<String?>()
        withClient(reply = { token -> requests += token; filtered() }) { yt ->
            yt.homeFilter("Music", "selected-token", fresh = false)
            assertEquals(listOf<String?>("selected-token"), requests)
        }
    }

    @Test fun aDisappearedChipNeverReplaysItsStaleToken() = runBlocking {
        val requests = ArrayList<String?>()
        withClient(reply = { token -> requests += token; "{}" }) { yt ->
            assertFailsWith<YTException> { yt.homeFilter("Music", "stale-token", fresh = true) }
            assertEquals(listOf<String?>(null), requests)
        }
    }
}
