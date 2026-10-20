package app.oriel.data

import android.os.Build
import android.util.Log
import app.oriel.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Opt-in, anonymous usage counts, sent to Aptabase (aptabase.com: open source, no device ids,
 * no IP kept). Off until the person says yes, and absent altogether from builds made without an
 * App Key ([BuildConfig.APTABASE_KEY], from the `aptabaseKey` Gradle property or the
 * `APTABASE_KEY` environment variable): a fork or a build from source reports nowhere.
 *
 * What's sent: the event's name (the app opened, a video played, a feature used), a few coarse
 * props (signed in or not, a quality like "1080p"), and what Aptabase attaches itself: app
 * version, Android version, device model, locale, and a random session id that changes every
 * hour of quiet. Never what was watched or searched, nor anything about the account.
 *
 * Requests go on their own plain connection, never through the YouTube client, so no cookie can
 * ride along; failures are dropped silently.
 */
class Telemetry(private val settings: Settings, private val scope: CoroutineScope) {
    /** Whether this build can report at all; the setting and the first-run question hide without it. */
    val available: Boolean = endpoint(BuildConfig.APTABASE_KEY) != null

    private val enabled get() = available && settings.current.telemetry == true

    private var session = UUID.randomUUID().toString()
    private var touched = 0L

    fun track(event: String, props: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        // A new session after an hour of quiet, as Aptabase's own SDKs do.
        if (now - touched > SESSION_MS) session = UUID.randomUUID().toString()
        touched = now
        val body = buildJsonObject {
            put("timestamp", stamp.get().format(Date(now)))
            put("sessionId", session)
            put("eventName", event)
            put("systemProps", buildJsonObject {
                put("isDebug", BuildConfig.DEBUG)
                put("osName", "Android")
                put("osVersion", Build.VERSION.RELEASE)
                put("locale", Locale.getDefault().toLanguageTag())
                put("appVersion", BuildConfig.VERSION_NAME)
                put("appBuildNumber", BuildConfig.VERSION_CODE.toString())
                put("sdkVersion", "oriel@${BuildConfig.VERSION_NAME}")
                put("deviceModel", Build.MODEL)
            })
            put("props", buildJsonObject {
                props.forEach { (k, v) ->
                    when (v) {
                        null -> {}
                        is Boolean -> put(k, v)
                        is Number -> put(k, JsonPrimitive(v))
                        else -> put(k, v.toString())
                    }
                }
            })
        }.toString()
        scope.launch(Dispatchers.IO) { send(body) }
    }

    private fun send(body: String) {
        val url = endpoint(BuildConfig.APTABASE_KEY) ?: return
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.doOutput = true
                c.setRequestProperty("App-Key", BuildConfig.APTABASE_KEY)
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
                c.responseCode
            } finally {
                c.disconnect()
            }
        }.onFailure { if (BuildConfig.DEBUG) Log.d("Oriel", "telemetry not sent: ${it.message}") }
    }

    private companion object {
        const val SESSION_MS = 60 * 60_000L
        val stamp: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }

        /** Aptabase keys look like A-EU- followed by digits; the middle part is the region. */
        fun endpoint(key: String): String? {
            val parts = key.split('-')
            if (parts.size != 3) return null
            val host = when (parts[1]) {
                "EU" -> "https://eu.aptabase.com"
                "US" -> "https://us.aptabase.com"
                else -> return null
            }
            return "$host/api/v0/event"
        }
    }
}
