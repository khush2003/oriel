package app.oriel.util

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import kotlin.math.min

/**
 * What this device shows and decodes well, for Auto quality: there's no point
 * streaming more lines than the screen has, or more than its hardware decoders
 * can keep up with. YouTube's 1440p and 2160p come as VP9 or AV1 only.
 */
object VideoCaps {
    private val ladder = listOf(2160, 1440, 1080, 720)

    /** The tallest standard height the screen can show in landscape, at least 720. */
    fun screenHeight(context: Context): Int {
        // The panel's own mode, not the window's metrics: read in split screen or a
        // freeform window, those would hold Auto low for the rest of the process.
        val mode = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.mode
        val m = context.resources.displayMetrics
        val short = mode?.let { min(it.physicalWidth, it.physicalHeight) } ?: min(m.widthPixels, m.heightPixels)
        return ladder.firstOrNull { short >= it } ?: 720
    }

    /** The tallest height a hardware VP9 or AV1 decoder plays at 30 fps; 1080 when the device won't say. */
    fun hardwareHeight(): Int = cached ?: runCatching { probe() }
        .onFailure { Log.w("Oriel", "Couldn't query decoders", it) }
        .getOrDefault(1080).also { cached = it }

    @Volatile private var cached: Int? = null

    private fun probe(): Int {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder && it.isHardware() }
        val video = codecs.flatMap { info ->
            info.supportedTypes.filter { it.equals("video/x-vnd.on2.vp9", true) || it.equals("video/av01", true) }.map { info.getCapabilitiesForType(it).videoCapabilities }
        }
        if (video.isEmpty()) return 1080
        return ladder.firstOrNull { h -> video.any { it != null && runCatching { it.areSizeAndRateSupported(h * 16 / 9, h, 30.0) }.getOrDefault(false) } } ?: 720
    }

    /**
     * Whether a hardware VP9 decoder plays 1080p30. YouTube's ladder carries each height up to
     * 1080p twice, as H.264 and as VP9, and the VP9 one has the higher bitrate (4.7 against
     * 1.8 Mbps at 1080p, measured): where VP9 decodes in hardware, it's the better picture.
     */
    fun hardwareVp9(): Boolean = vp9 ?: runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.isHardware() && info.supportedTypes.any { it.equals("video/x-vnd.on2.vp9", true) } &&
                runCatching { info.getCapabilitiesForType("video/x-vnd.on2.vp9").videoCapabilities?.areSizeAndRateSupported(1920, 1080, 30.0) == true }.getOrDefault(false)
        }
    }.getOrDefault(false).also { vp9 = it }

    @Volatile private var vp9: Boolean? = null

    private fun MediaCodecInfo.isHardware(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isHardwareAccelerated
        else !name.startsWith("OMX.google.") && !name.startsWith("c2.android.")

    /** Auto's height on Wi-Fi: the screen's, as far as the decoders keep up. */
    fun autoHeight(context: Context): Int = min(screenHeight(context), hardwareHeight())
}
