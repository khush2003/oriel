package app.oriel.core

import app.oriel.core.parse.Pages
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

/** Research probe: where ads sit in the responses Oriel reads. Opt in with -Pprobe=true. */
class AdsProbeTest {
    private val AD = Regex("^(ad[A-Z].*|.*Ad[A-Z].*|.*Ads[A-Z].*|.*[pP]romo.*|.*[sS]ponsor.*|.*Pyv.*)")

    private fun keys(e: JsonElement?, out: MutableSet<String>, depth: Int = 0) {
        if (depth > 40) return
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> if (AD.matches(k)) out += k; keys(v, out, depth + 1) }
            is JsonArray -> e.forEach { keys(it, out, depth + 1) }
            else -> {}
        }
    }

    @Test fun ads() = runBlocking {
        if (System.getProperty("oriel.probe") != "true") return@runBlocking
        val yt = YouTube().apply { setLocale("US", "en") }
        var cont: String? = null
        repeat(4) { page ->
            val raw = yt.shortsRaw(cont)
            val found = mutableSetOf<String>(); keys(raw, found)
            val batch = Pages.shortsSequence(raw)
            val entries = (raw as JsonObject)["entries"] as? JsonArray
            val endpointKeys = entries?.mapNotNull { ((it as? JsonObject)?.get("command") as? JsonObject)?.keys }?.flatten()?.toSet()
            println("ADSPROBE shorts page=$page ids=${batch.ids.size} entries=${entries?.size} commandKeys=$endpointKeys adKeys=$found")
            entries?.forEach { e ->
                val f = mutableSetOf<String>(); keys(e, f)
                val id = ((e as JsonObject)["command"] as? JsonObject)?.get("reelWatchEndpoint")?.let { (it as JsonObject)["videoId"] }
                val endpoint = ((e["command"] as? JsonObject)?.get("reelWatchEndpoint") as? JsonObject)
                if ("adClientParams" in f) println("ADSPROBE   ad $id endpointKeys=${endpoint?.keys} entryKeys=${e.keys}")
            }
            cont = batch.continuation
        }
        for ((label, raw) in runCatching { listOf(
            "search" to yt.raw("search") { put("query", "iphone 17") },
            "next" to yt.raw("next") { put("videoId", "dQw4w9WgXcQ") },
            "browse" to yt.raw("browse") { put("browseId", "FEwhat_to_watch") },
        ) }.onFailure { println("ADSPROBE raw failed $it") }.getOrDefault(emptyList())) {
            val found = mutableSetOf<String>(); keys(raw, found)
            println("ADSPROBE $label adKeys=$found")
        }
    }
}
