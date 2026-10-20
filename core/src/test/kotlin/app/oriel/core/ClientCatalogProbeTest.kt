package app.oriel.core

import com.metrolist.innertubex.models.YouTubeClient
import kotlin.test.Test

/** Research probe: InnerTubeX's client catalogue and what each needs. Opt in with -Pprobe=true. */
class ClientCatalogProbeTest {
    @Test fun clients() {
        if (System.getProperty("oriel.probe") != "true") return
        val companion = YouTubeClient.Companion
        companion::class.java.methods.filter { it.parameterCount == 0 && it.returnType == YouTubeClient::class.java }.forEach { m ->
            val c = m.invoke(companion) as YouTubeClient
            println("CLIENT ${m.name.removePrefix("get")} ${c.clientName}/${c.clientVersion} login=${c.loginSupported} loginReq=${c.loginRequired} sabr=${c.useSabr} embedded=${c.isEmbedded}")
        }
    }
}
