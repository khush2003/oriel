package app.oriel.core.stream

import app.oriel.core.json.at
import app.oriel.core.json.int
import app.oriel.core.json.long
import app.oriel.core.json.string
import com.metrolist.innertubex.extraction.PlayerConfig
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.strategy.ClientFailureKind
import com.metrolist.innertubex.extraction.strategy.ClientHealthMonitor
import com.metrolist.innertubex.extraction.strategy.ClientHealthScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the watch page's player config (player version, signature timestamp,
 * client version) on disk between launches. Getting it means downloading the
 * whole watch page, up to 1.4 MB with ten seconds to do it in, before a cold
 * start's first player request.
 *
 * A kept copy under [MAX_AGE_MS] old, for the same locale and account,
 * answers the first ask of each launch. Every later ask goes to the network,
 * and that includes the one InnerTubeX makes when it finds a config doesn't
 * work, so a stale copy costs one extraction pass at most.
 */
internal class DiskCachedConfigParser(
    private val upstream: YtConfigParser,
    private val store: KeyValueStore,
    /** Locale and account the config was fetched under; a copy kept under another isn't used. */
    private val identity: (login: Boolean) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) : YtConfigParser {
    private val served = ConcurrentHashMap.newKeySet<Boolean>()

    override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean): PlayerConfig {
        if (served.add(useLoginCookies)) read(useLoginCookies)?.let { return it }
        return upstream.fetchConfig(videoId, useLoginCookies).also { write(useLoginCookies, it) }
    }

    override suspend fun fetchEmbeddedConfig(videoId: String, useLoginCookies: Boolean): PlayerConfig =
        upstream.fetchEmbeddedConfig(videoId, useLoginCookies)

    private fun key(login: Boolean) = if (login) "config.login" else "config.anon"

    private fun read(login: Boolean): PlayerConfig? = runCatching {
        val root = Json.parseToJsonElement(store.getString(key(login)) ?: return null)
        if (root.at("id").string != identity(login)) return null
        val at = root.at("at").long ?: return null
        if (clock() - at !in 0..MAX_AGE_MS) return null
        PlayerConfig(
            playerUrl = root.at("playerUrl").string ?: return null,
            signatureTimestamp = root.at("sts").int,
            visitorData = root.at("visitorData").string,
            clientVersion = root.at("clientVersion").string,
            encryptedHostFlags = root.at("flags").string,
        )
    }.getOrNull()

    private fun write(login: Boolean, config: PlayerConfig) {
        if (config.playerUrl.isBlank()) return
        val json = buildJsonObject {
            put("id", identity(login))
            put("at", clock())
            put("playerUrl", config.playerUrl)
            config.signatureTimestamp?.let { put("sts", it) }
            config.visitorData?.let { put("visitorData", it) }
            config.clientVersion?.let { put("clientVersion", it) }
            config.encryptedHostFlags?.let { put("flags", it) }
        }
        runCatching { store.putString(key(login), json.toString()) }
    }

    private companion object {
        const val MAX_AGE_MS = 12 * 60 * 60_000L
    }
}

/**
 * What playback has shown about each client lately, for InnerTubeX's client
 * order: one that keeps failing drops down the list for a while, one that
 * just played comes first. InnerTubeX reports failed player requests itself;
 * the host reports what only it sees, a stream that played or a URL the CDN
 * refused. Kept in memory: a client's standing is about the last few minutes.
 */
internal class ClientHealth(private val clock: () -> Long = System::currentTimeMillis) : ClientHealthMonitor {
    private class Standing(val score: Int, val at: Long)

    private val standings = ConcurrentHashMap<String, Standing>()

    override fun scoreAdjustment(clientId: String, scope: ClientHealthScope?): Int {
        val s = standings[clientId] ?: return 0
        return current(s).also { if (it == 0) standings.remove(clientId, s) }
    }

    /** Fades linearly to nothing over [FORGET_MS]. */
    private fun current(s: Standing): Int {
        val age = clock() - s.at
        return if (age >= FORGET_MS) 0 else (s.score * (FORGET_MS - age) / FORGET_MS).toInt()
    }

    override fun recordSuccess(clientId: String, scope: ClientHealthScope?) {
        standings[clientId] = Standing(SUCCESS, clock())
    }

    override fun recordFailure(clientId: String, kind: ClientFailureKind, scope: ClientHealthScope?) {
        val penalty = when (kind) {
            ClientFailureKind.MEDIA_FORBIDDEN, ClientFailureKind.MEDIA_FAILURE -> 30
            ClientFailureKind.PLAYABILITY, ClientFailureKind.TOKEN -> 20
            ClientFailureKind.PLAYER_REQUEST -> 15
        }
        standings.compute(clientId) { _, old ->
            val before = old?.let { current(it).coerceAtMost(0) } ?: 0
            Standing((before - penalty).coerceAtLeast(FLOOR), clock())
        }
    }

    private companion object {
        const val SUCCESS = 20
        const val FLOOR = -90
        const val FORGET_MS = 15 * 60_000L
    }
}
