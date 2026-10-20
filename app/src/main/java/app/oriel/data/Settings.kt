package app.oriel.data

import android.content.Context
import app.oriel.core.extras.SponsorBlock
import app.oriel.core.stream.StreamProtocol
import app.oriel.core.stream.StreamQuality
import app.oriel.util.AppJson
import app.oriel.util.VideoCaps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/** A picture height to aim for; [AUTO] follows the screen and the network (see [Settings.videoHeight]). */
@Serializable
enum class VideoQuality(val height: Int?, val label: String) {
    AUTO(null, "Auto"),
    P144(144, "144p"), P240(240, "240p"), P360(360, "360p"), P480(480, "480p"),
    P720(720, "720p"), P1080(1080, "1080p"), P1440(1440, "1440p"), P2160(2160, "2160p (4K)");

    companion object {
        val fixed = entries.filter { it != AUTO }
        fun of(height: Int?): VideoQuality = fixed.firstOrNull { it.height == height } ?: AUTO
    }
}

@Serializable
enum class AudioQuality(val label: String, val detail: String, val stream: StreamQuality) {
    LOW("Low", "≈50 kbps · saves data", StreamQuality.LOW),
    NORMAL("Normal", "≈128 kbps", StreamQuality.AUTO),
    HIGH("High", "Best available · ≈130–160 kbps, 256 kbps with Premium", StreamQuality.HIGH),
}

/** How video bytes travel; see [StreamProtocol]. */
@Serializable
enum class Transport(val label: String, val detail: String, val protocol: StreamProtocol) {
    AUTO("Automatic", "Classic URLs, or SABR when a video has none", StreamProtocol.AUTO),
    CLASSIC("Classic", "Range requests to a googlevideo URL", StreamProtocol.CLASSIC),
    SABR("SABR", "YouTube's newer server-driven streaming", StreamProtocol.SABR),
}

@Serializable
enum class ThemeMode(val label: String) { SYSTEM("System"), DARK("Dark"), LIGHT("Light") }

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
 * with their defaults instead of migrations.
 */
class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state.asStateFlow()
    val current: SettingsState get() = _state.value

    private fun load(): SettingsState =
        prefs.getString(KEY, null)?.let { runCatching { AppJson.decodeFromString<SettingsState>(it) }.getOrNull() } ?: SettingsState()

    fun update(transform: (SettingsState) -> SettingsState) {
        _state.update(transform)
        prefs.edit().putString(KEY, AppJson.encodeToString(SettingsState.serializer(), _state.value)).apply()
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

    private companion object {
        const val KEY = "state"
    }
}
