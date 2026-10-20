package app.oriel.core

import app.oriel.core.json.arr
import app.oriel.core.json.at
import app.oriel.core.json.get
import app.oriel.core.json.int
import app.oriel.core.json.string
import com.metrolist.innertubex.models.YouTubeClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Research probe, not a regression test: what each player client returns for
 * a few videos (playability, storyboards, top formats, SABR). Opt in with
 * -Pprobe=true; -Pids=a,b,c picks the videos.
 */
class PlayerProbeTest {
    @Test fun probe() = runBlocking {
        if (System.getProperty("oriel.probe") != "true") return@runBlocking
        val yt = YouTube().apply { setLocale("US", "en") }
        val ids = (System.getProperty("oriel.ids") ?: "jNQXAC9IVRw,dQw4w9WgXcQ,aqz-KE-bpKQ").split(",")
        val clients = mapOf(
            "ANDROID_VR" to YouTubeClient.ANDROID_VR_1_65_10, "ANDROID_VR_NO_AUTH" to YouTubeClient.ANDROID_VR_NO_AUTH,
            "TVHTML5" to YouTubeClient.TVHTML5, "TV_SIMPLY" to YouTubeClient.TVHTML5_SIMPLY, "IOS" to YouTubeClient.IOS,
            "WEB_EMBEDDED" to YouTubeClient.WEB_EMBEDDED_PLAYER, "WEB" to YouTubeClient.WEB, "MWEB" to YouTubeClient.MWEB,
            "VISIONOS" to YouTubeClient.VISIONOS, "WEB_SAFARI" to YouTubeClient.WEB_SAFARI,
        )
        for (id in ids) for ((name, client) in clients) {
            val line = runCatching {
                val root = yt.rawPlayer(client, id)
                val status = root.at("playabilityStatus", "status").string
                val reason = root.at("playabilityStatus", "reason").string
                val sb = root.at("storyboards", "playerStoryboardSpecRenderer", "spec").string
                val sd = root["streamingData"]
                val adaptive = sd["adaptiveFormats"].arr
                val heights = adaptive.mapNotNull { it["height"].int }.distinct().sortedDescending().take(4)
                val audio = adaptive.filter { it["mimeType"].string?.startsWith("audio") == true }
                    .map { "${it["itag"].int}:${(it["bitrate"].int ?: 0) / 1000}k" }.distinct().take(6)
                val urls = adaptive.count { it["url"].string != null }
                val ciphered = adaptive.count { it["signatureCipher"].string != null }
                val ads = listOf("adPlacements", "playerAds", "adSlots").filter { root[it] != null }
                "$status${reason?.let { " ($it)" } ?: ""} sb=${sb != null} heights=$heights audio=$audio urls=$urls cipher=$ciphered " +
                    "hls=${sd["hlsManifestUrl"].string != null} sabr=${sd["serverAbrStreamingUrl"].string != null} ads=$ads"
            }.getOrElse { "ERR ${it.javaClass.simpleName}: ${it.message?.take(80)}" }
            println("PROBE $id $name: $line")
            if (System.getProperty("oriel.ids") != null && name == "ANDROID_VR") runCatching {
                val root = yt.rawPlayer(client, id)
                println("SPEC ${root.at("storyboards", "playerStoryboardSpecRenderer", "spec").string}")
                println("SPEC_LIVE ${root.at("storyboards", "playerLiveStoryboardSpecRenderer", "spec").string}")
                println("RECOMMENDED_LEVEL ${root.at("storyboards", "playerStoryboardSpecRenderer", "recommendedLevel").int}")
                println("TOP_KEYS ${(root as? kotlinx.serialization.json.JsonObject)?.keys}")
            }
        }
    }
}
