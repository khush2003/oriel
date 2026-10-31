package app.oriel.data

import android.content.Context
import androidx.core.content.edit
import app.oriel.core.extras.SponsorBlock
import app.oriel.core.stream.StreamProtocol
import app.oriel.core.stream.StreamQuality
import app.oriel.util.AppJson
import app.oriel.util.CrashLog
import app.oriel.util.Schema
import app.oriel.util.VideoCaps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A picture height to aim for; [AUTO] follows the screen and the network (see [Settings.videoHeight]). */
@Serializable
enum class VideoQuality(val height: Int?, val label: String) {
    @SerialName("AUTO") AUTO(null, "Auto"),
    @SerialName("P144") P144(144, "144p"), @SerialName("P240") P240(240, "240p"), @SerialName("P360") P360(360, "360p"),
    @SerialName("P480") P480(480, "480p"), @SerialName("P720") P720(720, "720p"), @SerialName("P1080") P1080(1080, "1080p"),
    @SerialName("P1440") P1440(1440, "1440p"), @SerialName("P2160") P2160(2160, "2160p (4K)");

    companion object {
        val fixed = entries.filter { it != AUTO }
        fun of(height: Int?): VideoQuality = fixed.firstOrNull { it.height == height } ?: AUTO
    }
}

@Serializable
enum class AudioQuality(val label: String, val detail: String, val stream: StreamQuality) {
    @SerialName("LOW") LOW("Low", "≈50 kbps · saves data", StreamQuality.LOW),
    @SerialName("NORMAL") NORMAL("Normal", "≈128 kbps", StreamQuality.AUTO),
    @SerialName("HIGH") HIGH("High", "Best available · ≈130–160 kbps, 256 kbps with Premium", StreamQuality.HIGH),
}

/** How video bytes travel; see [StreamProtocol]. */
@Serializable
enum class Transport(val label: String, val detail: String, val protocol: StreamProtocol) {
    @SerialName("AUTO") AUTO("Automatic", "Classic URLs, or SABR when a video has none", StreamProtocol.AUTO),
    @SerialName("CLASSIC") CLASSIC("Classic", "Range requests to a googlevideo URL", StreamProtocol.CLASSIC),
    @SerialName("SABR") SABR("SABR", "YouTube's newer server-driven streaming", StreamProtocol.SABR),
}

@Serializable
enum class ThemeMode(val label: String) { @SerialName("SYSTEM") SYSTEM("System"), @SerialName("DARK") DARK("Dark"), @SerialName("LIGHT") LIGHT("Light") }

@Serializable
data class SettingsState(
    // Quality
    val videoWifi: VideoQuality = VideoQuality.AUTO,
    val videoCellular: VideoQuality = VideoQuality.AUTO,
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    val dataSaver: Boolean = false,
    val transport: Transport = Transport.AUTO,
    val premium: Boolean = false,
    // Playback
    val backgroundPlay: Boolean = true,
    val autoPip: Boolean = true,
    val autoplay: Boolean = true,
    val defaultSpeed: Float = 1f,
    val captions: Boolean = false,
    val captionLanguage: String? = null,
    val resumePlayback: Boolean = true,
    val doubleTapSeekSec: Int = 10,
    // Start videos in the language they were made in, not YouTube's dub (often a machine one) in the app's language.
    val preferOriginalAudio: Boolean = true,
    // Community extras
    val sponsorBlock: Boolean = true,
    val sponsorCategories: Set<String> = SponsorBlock.DEFAULT_CATEGORIES,
    val returnDislike: Boolean = true,
    // Library
    val pauseHistory: Boolean = false,
    val pauseSearchHistory: Boolean = false,
    val persistQueue: Boolean = true,
    val streamCacheMb: Int = 1024,
    // Appearance
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val liquidGlass: Boolean = true,
    // Interactions that stretch and morph like liquid: off until the person turns it on.
    val liquidMotion: Boolean = false,
    // Whether the first-run question about liquid motion has been answered, either way.
    val liquidMotionAsked: Boolean = false,
    val reduceAnimation: Boolean = false,
    val reduceBlur: Boolean = false,
    val highRefreshRate: Boolean = true,
    // Region & network
    val country: String? = null,
    val language: String? = null,
    val prefetchNext: Boolean = true,
    // Hints
    val signInBannerDismissed: Boolean = false,
    // Privacy: anonymous usage counts; null until asked, off unless the person says yes.
    val telemetry: Boolean? = null,
)

/**
 * App settings, one JSON document in SharedPreferences.
 *
 * Read synchronously ([current]) from playback threads that can't suspend, and
 * observed as a flow from the UI. A single document means new fields arrive
 * with their defaults; the document carries a [Schema] version for changes
 * that need more than that. Every value is written, defaults included, so a
 * choice the person made stays theirs when a later release changes a default.
 */
class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state.asStateFlow()
    val current: SettingsState get() = _state.value

    private fun load(): SettingsState {
        val text = prefs.getString(KEY, null) ?: return SettingsState()
        val doc = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrElse { e ->
            CrashLog.nonFatal(context, "Settings parse", e)
            // Nothing could be kept. The usage-counts question may have been answered: off, rather than asking again.
            return SettingsState(telemetry = false).also { store(it, now = true) }
        }
        val from = Schema.SETTINGS.version(doc)
        val (state, partial) = decode(Schema.SETTINGS.read(doc)) { CrashLog.nonFatal(context, "Settings decode", it) }
        // Brought up to date and written back at once, so the next launch reads the current shape.
        if (from < Schema.SETTINGS.current || partial) store(state, now = true)
        return state
    }

    fun update(transform: (SettingsState) -> SettingsState) = synchronized(this) {
        val next = transform(_state.value)
        _state.value = next
        store(next)
    }

    private fun store(state: SettingsState, now: Boolean = false) {
        synchronized(this) { prefs.edit(commit = now) { putString(KEY, encode(state)) } }
    }

    /**
     * The height to play at on this network: the user's choice, or what Auto
     * means for it. Auto is the screen's own resolution on Wi-Fi, as far as
     * the hardware decoders keep up (1440p on a QHD phone, 1080p on most), on
     * any network, and 480p with the data saver. Mobile data isn't capped by
     * resolution: the adaptive picker already stays within what the link carries.
     */
    fun videoHeight(metered: Boolean): Int {
        val s = current
        val choice = if (metered) s.videoCellular else s.videoWifi
        return choice.height ?: when {
            s.dataSaver -> 480
            else -> autoHeight
        }
    }

    /** Whether this network's quality setting is Auto rather than a fixed height. */
    fun isAuto(metered: Boolean): Boolean = (if (metered) current.videoCellular else current.videoWifi).height == null

    private val autoHeight: Int by lazy { VideoCaps.autoHeight(context) }

    /** Whether HLS should play its low-bitrate audio: Sound quality on Low, or the data saver on mobile data. */
    fun lowAudio(metered: Boolean): Boolean = streamQuality(metered) == StreamQuality.LOW

    fun streamQuality(metered: Boolean): StreamQuality = when {
        current.dataSaver && metered -> StreamQuality.LOW
        else -> current.audioQuality.stream
    }

    companion object {
        private const val KEY = "state"

        /** Writes defaults too: see the class comment. */
        private val StoreJson = Json(AppJson) { encodeDefaults = true }

        internal fun encode(state: SettingsState): String =
            Schema.SETTINGS.write(StoreJson.encodeToJsonElement(SettingsState.serializer(), state).jsonObject).toString()

        /**
         * [doc] as settings. A value that can't be read costs only itself: the rest, the
         * answer to the usage-counts question above all, is kept. The second value says
         * whether anything was dropped.
         */
        internal fun decode(doc: JsonObject, onError: (Throwable) -> Unit = {}): Pair<SettingsState, Boolean> {
            runCatching { return AppJson.decodeFromJsonElement(SettingsState.serializer(), doc) to false }.onFailure(onError)
            val good = doc.filter { (k, v) -> runCatching { AppJson.decodeFromJsonElement(SettingsState.serializer(), JsonObject(mapOf(k to v))) }.isSuccess }
            val state = runCatching { AppJson.decodeFromJsonElement(SettingsState.serializer(), JsonObject(good)) }.getOrDefault(SettingsState())
            // An answer that was there but can't be read counts as no, rather than asking again.
            return (if (state.telemetry == null && "telemetry" in doc) state.copy(telemetry = false) else state) to true
        }
    }
}
