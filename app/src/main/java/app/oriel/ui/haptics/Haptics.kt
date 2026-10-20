/*
 * Adapted from BitChord's Haptics.kt and Haptics.android.kt (GPL-3.0),
 * https://github.com/kushagrasinghx/BitChord. Merged into one Android file.
 */
package app.oriel.ui.haptics

import android.content.Context
import android.database.ContentObserver
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What a touch meant, not what it should feel like; the shape of the buzz is
 * this file's business. Every pattern is brief: the longest is three beats
 * under 65ms, since a haptic that outlasts the finger reads as the phone ringing.
 */
enum class Haptic { Tick, Tap, Select, ToggleOn, ToggleOff, SkipNext, SkipPrevious, Resume, Pause, Expand }

class Haptics internal constructor(context: Context) {
    private val app = context.applicationContext
    fun play(haptic: Haptic) {
        HapticDevice.of(app)?.play(haptic)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val context = LocalContext.current
    return remember(context) { Haptics(context) }
}

private class Beat(val kind: Kind, val scale: Float, val gapMs: Long) {
    enum class Kind(val pulseMs: Long, val amplitude: Int) { Tick(8, 110), LowTick(10, 95), Click(14, 210) }
}

private fun rhythmOf(haptic: Haptic): List<Beat> = when (haptic) {
    Haptic.Tick -> listOf(Beat(Beat.Kind.Tick, 0.35f, 0))
    Haptic.Tap -> listOf(Beat(Beat.Kind.Click, 0.5f, 0))
    Haptic.Select -> listOf(Beat(Beat.Kind.Tick, 0.4f, 0), Beat(Beat.Kind.Click, 0.75f, 18))
    Haptic.ToggleOn -> listOf(Beat(Beat.Kind.Tick, 0.4f, 0), Beat(Beat.Kind.Click, 0.9f, 14))
    Haptic.ToggleOff -> listOf(Beat(Beat.Kind.Click, 0.75f, 0), Beat(Beat.Kind.Tick, 0.3f, 14))
    Haptic.SkipNext -> listOf(Beat(Beat.Kind.Tick, 0.4f, 0), Beat(Beat.Kind.Tick, 0.55f, 16), Beat(Beat.Kind.Click, 0.7f, 16))
    Haptic.SkipPrevious -> listOf(Beat(Beat.Kind.Click, 0.7f, 0), Beat(Beat.Kind.Tick, 0.55f, 16), Beat(Beat.Kind.Tick, 0.4f, 16))
    Haptic.Resume -> listOf(Beat(Beat.Kind.LowTick, 0.5f, 0), Beat(Beat.Kind.Click, 0.85f, 22))
    Haptic.Pause -> listOf(Beat(Beat.Kind.Click, 0.85f, 0), Beat(Beat.Kind.LowTick, 0.4f, 22))
    Haptic.Expand -> listOf(Beat(Beat.Kind.Tick, 0.3f, 0), Beat(Beat.Kind.Tick, 0.45f, 12), Beat(Beat.Kind.Click, 0.6f, 12))
}

private class HapticDevice private constructor(
    private val vibrator: Vibrator,
    private val canCompose: Boolean,
    private val canScaleAmplitude: Boolean,
    private val systemHapticsEnabled: () -> Boolean,
) {
    private val compiled = HashMap<Haptic, VibrationEffect>()

    fun play(haptic: Haptic) {
        // The system touch-feedback switch is the user's answer to this whole feature.
        if (!systemHapticsEnabled()) return
        val effect = synchronized(compiled) { compiled.getOrPut(haptic) { compile(rhythmOf(haptic)) } }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) TouchVibration.send(vibrator, effect)
            else @Suppress("DEPRECATION") vibrator.vibrate(effect, LEGACY_ATTRIBUTES)
        }
    }

    private fun compile(beats: List<Beat>): VibrationEffect = when {
        canCompose && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Primitives.compose(beats)
        canScaleAmplitude -> waveform(beats, coarse = false)
        else -> waveform(if (beats.size > 2) listOf(beats.first(), beats.last()) else beats, coarse = true)
    }

    private fun waveform(beats: List<Beat>, coarse: Boolean): VibrationEffect {
        val timings = LongArray(beats.size * 2)
        val amplitudes = IntArray(beats.size * 2)
        beats.forEachIndexed { i, beat ->
            timings[i * 2] = if (coarse) beat.gapMs.coerceAtLeast(if (i == 0) 0 else 20) else beat.gapMs
            timings[i * 2 + 1] = if (coarse) (beat.kind.pulseMs * 2.5f * (0.6f + 0.4f * beat.scale)).toLong().coerceIn(18, 40) else beat.kind.pulseMs
            amplitudes[i * 2 + 1] = (beat.kind.amplitude * beat.scale).toInt().coerceIn(1, 255)
        }
        return if (coarse) VibrationEffect.createWaveform(timings, -1) else VibrationEffect.createWaveform(timings, amplitudes, -1)
    }

    companion object {
        private val LEGACY_ATTRIBUTES by lazy {
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        }

        @Volatile private var instance: HapticDevice? = null
        @Volatile private var probed = false

        fun of(app: Context): HapticDevice? {
            instance?.let { return it }
            synchronized(this) {
                if (probed) return instance
                probed = true
                instance = runCatching { probe(app) }.getOrNull()
                return instance
            }
        }

        private fun probe(app: Context): HapticDevice? {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) app.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else app.getSystemService(Vibrator::class.java)
            if (vibrator == null || !vibrator.hasVibrator()) return null
            // API 30 isn't enough: ERM motors support no primitives, and a composition there is silence.
            val canCompose = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Primitives.supportedBy(vibrator)
            return HapticDevice(vibrator, canCompose, vibrator.hasAmplitudeControl(), systemHapticsWatcher(app))
        }

        @Suppress("DEPRECATION")
        private fun systemHapticsWatcher(app: Context): () -> Boolean {
            val resolver = app.contentResolver
            fun read() = Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
            val enabled = AtomicBoolean(read())
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) = enabled.set(read())
            }
            runCatching { resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.HAPTIC_FEEDBACK_ENABLED), false, observer) }
            return { enabled.get() }
        }
    }
}

private object Primitives {
    @RequiresApi(Build.VERSION_CODES.R)
    fun supportedBy(vibrator: Vibrator): Boolean =
        vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK, VibrationEffect.Composition.PRIMITIVE_CLICK)

    @RequiresApi(Build.VERSION_CODES.R)
    fun compose(beats: List<Beat>): VibrationEffect {
        var composition = VibrationEffect.startComposition()
        beats.forEach { beat -> composition = composition.addPrimitive(beat.kind.primitive(), beat.scale, beat.gapMs.toInt()) }
        return composition.compose()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun Beat.Kind.primitive(): Int = when (this) {
        Beat.Kind.Tick -> VibrationEffect.Composition.PRIMITIVE_TICK
        Beat.Kind.Click -> VibrationEffect.Composition.PRIMITIVE_CLICK
        Beat.Kind.LowTick -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else VibrationEffect.Composition.PRIMITIVE_TICK
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object TouchVibration {
    private val attributes: VibrationAttributes = VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build()
    fun send(vibrator: Vibrator, effect: VibrationEffect) = vibrator.vibrate(effect, attributes)
}
