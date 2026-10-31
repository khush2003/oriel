package app.oriel.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.oriel.ui.components.LocalSheetClose
import app.oriel.ui.components.OrielSheet
import app.oriel.ui.components.SheetTitle
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import app.oriel.core.stream.StreamRequest
import app.oriel.ui.AppState
import app.oriel.ui.components.ChoiceRow
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.RowDivider
import app.oriel.ui.components.SettingsGroup
import app.oriel.ui.components.SettingsRow
import app.oriel.ui.components.SwitchRow
import app.oriel.ui.components.TextAction
import java.util.Locale

private enum class Page { Main, Quality, Speed, Audio, Captions, Translate, Sleep }

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
private val TRANSLATE_TO = listOf("en", "es", "fr", "de", "pt", "it", "hi", "ja", "ko", "zh-Hans", "ar", "ru", "tr", "id")

fun speedLabel(speed: Float): String = if (speed == 1f) "Normal" else (if (speed % 1f == 0f) "${speed.toInt()}×" else "${speed}×")

/** Quality, speed, audio track, captions, audio only, loop and sleep, as an iOS grouped sheet with drill-down pages. */
@Composable
fun PlayerSettingsSheet(app: AppState, onDismiss: () -> Unit, onPip: () -> Unit, onChapters: () -> Unit = {}) {
    val connection = app.connection
    // The captions list may still be waiting on its deferred fetch.
    LaunchedEffect(Unit) { app.watch.wantExtras() }
    val current by connection.current.collectAsState()
    val speed by connection.speed.collectAsState()
    val audioOnly by connection.audioOnly.collectAsState()
    val override by connection.heightOverride.collectAsState()
    val repeat by connection.repeat.collectAsState()
    val sleepAt by connection.sleepAt.collectAsState()
    val sleepAtEnd by connection.sleepAtEnd.collectAsState()
    val captions by app.watch.captions.collectAsState()
    val isLive by connection.isLive.collectAsState()
    val audioTracks by connection.audioTracks.collectAsState()
    val chapterCount = app.watch.state.collectAsState().value.page?.chapters?.distinctBy { it.startMs }?.size ?: 0
    var page by remember { mutableStateOf(Page.Main) }
    val graph = app.graph

    val metered = graph.network.metered.collectAsState().value
    val autoHeight = graph.settings.videoHeight(metered)
    val stream = current?.let { v -> graph.resolver.cached(StreamRequest(v.id, graph.settings.streamQuality(metered), connection.currentHeight())) }
    val heights = stream?.availableHeights?.filter { it >= 144 }?.distinct()?.sortedDescending().orEmpty().ifEmpty { listOf(2160, 1440, 1080, 720, 480, 360, 240, 144) }
    // Auto's ladder: the height is whatever the player has moved to, so it's read off the picture.
    val adaptive = current != null && !audioOnly && connection.currentAdaptive()
    val videoSize by connection.videoSize.collectAsState()
    val shownHeight = videoSize.takeIf { it.width > 0 && it.height > 0 }?.let { minOf(it.width, it.height) }
    val playingHeight = if (adaptive) shownHeight else stream?.video?.height
    // What's actually playing, for the curious: codec and size, audio bitrate, the client and transport.
    val streamInfo = if (adaptive) {
        listOfNotNull("Adapting to your connection", shownHeight?.let { "${it}p now" }, "up to ${autoHeight}p").joinToString(" · ")
    } else stream?.takeUnless { it.isHls || audioOnly && it.video != null }?.let { st ->
        listOfNotNull(
            st.video?.let { v -> listOfNotNull(codecName(v.codecs ?: v.mimeType), v.height?.let { "${it}p" }).joinToString(" ") },
            listOfNotNull(codecName(st.codecs ?: st.mimeType), st.bitrate?.let { "${it / 1000} kbps" }).joinToString(" "),
            st.clientName.replace('_', ' ') + if (st.isSabr) " · SABR" else "",
        ).joinToString(" · ")
    }

    val autoplay = graph.settings.state.collectAsState().value.autoplay
    OrielSheet(onDismiss, scrollable = false, maxHeight = 640.dp) {
        val close = LocalSheetClose.current
        // iOS's navigation push inside a sheet: the next page slides in whole from the right as
        // this one drifts left under it, and the sheet's height springs to the new page's.
        val push = spring<IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 520f, visibilityThreshold = IntOffset.VisibilityThreshold)
        AnimatedContent(
            page,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal || targetState != Page.Main && initialState == Page.Main
                (slideInHorizontally(push) { if (forward) it else -it / 3 } + fadeIn(tween(200)))
                    .togetherWith(slideOutHorizontally(push) { if (forward) -it / 3 else it } + fadeOut(tween(160)))
                    .using(SizeTransform(clip = true) { _, _ -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 520f, visibilityThreshold = IntSize.VisibilityThreshold) })
            },
            label = "settingsPage",
        ) { p ->
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 4.dp)) {
                if (p != Page.Main) {
                    SheetTitle(
                        when (p) { Page.Translate -> "Auto-translate"; Page.Speed -> "Playback speed"; Page.Audio -> "Audio track"; Page.Sleep -> "Sleep timer"; else -> p.name },
                        onBack = { page = if (p == Page.Translate) Page.Captions else Page.Main },
                    )
                }
                when (p) {
                    Page.Main -> {
                        SettingsGroup(top = 4.dp, footer = streamInfo) {
                            SettingsRow("Quality", OrielIcons.Quality, value = when {
                                audioOnly -> "Audio only"
                                isLive -> "Auto"
                                override != null -> "${override}p"
                                else -> "Auto${playingHeight?.let { " (${it}p)" } ?: " (${autoHeight}p)"}"
                            }, chevron = true, enabled = !isLive, onClick = { page = Page.Quality })
                            RowDivider()
                            SettingsRow("Playback speed", OrielIcons.Speed, value = speedLabel(speed), chevron = true, enabled = !isLive, onClick = { page = Page.Speed })
                            // Only a dubbed video has a choice to offer.
                            if (audioTracks.size > 1) {
                                RowDivider()
                                SettingsRow("Audio track", OrielIcons.Language, value = audioTracks.firstOrNull { it.selected }?.name, chevron = true, onClick = { page = Page.Audio })
                            }
                            RowDivider()
                            SettingsRow("Captions", OrielIcons.Captions, value = captions.selected?.let { it.name + (captions.translateTo?.let { t -> " → ${Locale.forLanguageTag(t).displayLanguage}" } ?: "") } ?: if (captions.tracks.isEmpty()) "None" else "Off", chevron = true, enabled = captions.tracks.isNotEmpty(), onClick = { page = Page.Captions })
                            if (chapterCount > 0) {
                                RowDivider()
                                SettingsRow("Chapters", OrielIcons.Chapters, value = "$chapterCount", chevron = true, onClick = { close { onChapters() } })
                            }
                        }
                        SettingsGroup {
                            SwitchRow("Audio only", audioOnly, { connection.setAudioOnly(it) }, OrielIcons.AudioOnly, subtitle = "Plays the sound alone: less data, less battery")
                            RowDivider()
                            SwitchRow("Loop video", repeat == Player.REPEAT_MODE_ONE, { connection.toggleLoop() }, OrielIcons.Loop)
                            RowDivider()
                            SwitchRow("Autoplay", autoplay, { on -> graph.settings.update { it.copy(autoplay = on) } }, OrielIcons.Autoplay, subtitle = "Plays a related video when this one ends")
                            RowDivider()
                            SettingsRow("Sleep timer", OrielIcons.Sleep, value = when {
                                sleepAtEnd -> "End of video"
                                sleepAt != null -> "${((sleepAt!! - System.currentTimeMillis()) / 60_000 + 1)} min"
                                else -> "Off"
                            }, chevron = true, onClick = { page = Page.Sleep })
                            RowDivider()
                            SettingsRow("Picture-in-picture", OrielIcons.Pip, onClick = { close { onPip() } })
                        }
                    }
                    Page.Quality -> SettingsGroup(top = 12.dp, footer = "Auto moves with your connection, up to ${autoHeight}p on this network. Change the defaults in Settings › Video quality.") {
                        ChoiceRow("Auto", override == null && !audioOnly, detail = "Up to ${autoHeight}p") { connection.setAudioOnly(false); connection.setHeight(null); page = Page.Main }
                        heights.forEach { h ->
                            RowDivider(inset = false)
                            ChoiceRow(if (h >= 2160) "${h}p 4K" else if (h >= 720) "${h}p HD" else "${h}p", override == h && !audioOnly) {
                                connection.setAudioOnly(false); connection.setHeight(h); page = Page.Main
                            }
                        }
                        RowDivider(inset = false)
                        ChoiceRow("Audio only", audioOnly) { connection.setAudioOnly(true); page = Page.Main }
                    }
                    Page.Speed -> SettingsGroup(top = 12.dp) {
                        SPEEDS.forEachIndexed { i, s ->
                            if (i > 0) RowDivider(inset = false)
                            ChoiceRow(speedLabel(s), speed == s) { connection.setSpeed(s); page = Page.Main }
                        }
                    }
                    Page.Audio -> SettingsGroup(top = 12.dp, footer = "Videos after this one start in the same language when they have it. Settings › Prefer original audio decides how the others start.") {
                        audioTracks.forEachIndexed { i, t ->
                            if (i > 0) RowDivider(inset = false)
                            ChoiceRow(t.name, t.selected) { connection.selectAudioTrack(t); page = Page.Main }
                        }
                    }
                    Page.Captions -> SettingsGroup(top = 12.dp) {
                        ChoiceRow("Off", captions.selected == null) { app.watch.selectCaptions(null); graph.settings.update { it.copy(captions = false) }; page = Page.Main }
                        captions.tracks.forEach { t ->
                            RowDivider(inset = false)
                            ChoiceRow(t.name, captions.selected == t && captions.translateTo == null, detail = if (t.isAuto) "Auto-generated" else null) {
                                app.watch.selectCaptions(t)
                                graph.settings.update { it.copy(captions = true, captionLanguage = t.languageCode) }
                                page = Page.Main
                            }
                        }
                        if (captions.tracks.isNotEmpty()) {
                            RowDivider(inset = false)
                            SettingsRow("Auto-translate", value = captions.translateTo?.let { Locale.forLanguageTag(it).displayLanguage }, chevron = true, onClick = { page = Page.Translate })
                        }
                    }
                    Page.Translate -> SettingsGroup(top = 12.dp, footer = "Machine translation by YouTube.") {
                        val source = captions.selected ?: captions.tracks.firstOrNull { !it.isAuto } ?: captions.tracks.firstOrNull()
                        TRANSLATE_TO.forEachIndexed { i, lang ->
                            if (i > 0) RowDivider(inset = false)
                            ChoiceRow(Locale.forLanguageTag(lang).displayLanguage, captions.translateTo == lang) {
                                source?.let { app.watch.selectCaptions(it, lang) }
                                page = Page.Main
                            }
                        }
                    }
                    Page.Sleep -> SettingsGroup(top = 12.dp) {
                        ChoiceRow("Off", sleepAt == null && !sleepAtEnd) { connection.cancelSleepTimer(); page = Page.Main }
                        listOf(15, 30, 45, 60, 90).forEach { m ->
                            RowDivider(inset = false)
                            ChoiceRow("$m minutes", false) { connection.setSleepTimer(m); page = Page.Main }
                        }
                        RowDivider(inset = false)
                        ChoiceRow("End of video", sleepAtEnd) { connection.sleepAtEndOfVideo(); page = Page.Main }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

private fun codecName(codec: String?): String? {
    val c = codec?.lowercase() ?: return null
    return when {
        "vp09" in c || "vp9" in c -> "VP9"
        "av01" in c -> "AV1"
        "avc" in c -> "H.264"
        "opus" in c -> "Opus"
        "mp4a" in c || "aac" in c -> "AAC"
        else -> c.substringAfter('/').substringBefore(';').uppercase()
    }
}
