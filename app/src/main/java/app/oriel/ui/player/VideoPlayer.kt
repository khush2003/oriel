package app.oriel.ui.player

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.layout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import app.oriel.ui.theme.LocalReduceAnimation
import app.oriel.ui.theme.motion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedContent
import app.oriel.ui.theme.EaseOutCubic
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.oriel.ui.components.ActivityIndicator
import app.oriel.core.extras.SponsorBlock
import app.oriel.playback.PlayerConnection
import app.oriel.ui.AppState
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.glass.videoGlass
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.util.ReportKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The playing position, refreshed every [intervalMs] while composed. Read it in draw/layout lambdas where possible. */
@Composable
fun rememberPosition(connection: PlayerConnection, intervalMs: Long = 200): MutableLongState {
    val state = remember { mutableLongStateOf(connection.position()) }
    LaunchedEffect(connection, intervalMs) {
        while (isActive) {
            state.longValue = connection.position()
            delay(intervalMs)
        }
    }
    return state
}

enum class ControlsMode { Expanded, Fullscreen }

/**
 * The glass controls over the video: tap to show or hide, double-tap either
 * side to seek, transport in the middle, the scrubber with chapters and
 * SponsorBlock segments along the bottom, captions above it.
 */
@Composable
fun VideoControls(
    app: AppState,
    mode: ControlsMode,
    onCollapse: () -> Unit,
    onFullscreen: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onChapters: () -> Unit = {},
    /** A sheet the controls opened (chapters, settings) is up: they stay while it is, and hide again after. */
    sheetOpen: Boolean = false,
) {
    val connection = app.connection
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val playing by connection.playWhenReady.collectAsState()
    val buffering by connection.buffering.collectAsState()
    val ended by connection.ended.collectAsState()
    val duration by connection.duration.collectAsState()
    val hasNext by connection.hasNext.collectAsState()
    val hasPrevious by connection.hasPrevious.collectAsState()
    val isLive by connection.isLive.collectAsState()
    val error by connection.error.collectAsState()
    val segments by connection.segments.collectAsState()
    val skipped by connection.skipped.collectAsState()
    val captions by app.watch.captions.collectAsState()
    val watch by app.watch.state.collectAsState()
    val storyboard by app.watch.storyboard.collectAsState()
    val chapters = watch.page?.chapters.orEmpty()
    val canPip = remember { supportsPip(app.activity) }
    val accessibility = remember { app.activity.getSystemService(android.view.accessibility.AccessibilityManager::class.java) }

    var visible by remember { mutableStateOf(true) }
    var scrubbing by remember { mutableStateOf(false) }
    LaunchedEffect(scrubbing) { if (scrubbing) app.watch.wantExtras() }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var hideJob by remember { mutableStateOf<Job?>(null) }
    // Hidden, only captions need the position, and with none showing nothing does: polled at a walk.
    val position = rememberPosition(connection, if (visible) 200 else if (captions.selected != null) 250 else 1_000)

    fun scheduleHide() {
        hideJob?.cancel()
        // TalkBack users find the controls by touch; ones that vanish under the finger can't be found.
        if (accessibility?.isTouchExplorationEnabled == true) return
        hideJob = scope.launch {
            delay(3_200)
            if (playing && !scrubbing) visible = false
        }
    }
    LaunchedEffect(playing) { if (playing) scheduleHide() else visible = true }
    // Opening the chapters cancels the hide; closing them starts it again.
    LaunchedEffect(sheetOpen) { if (sheetOpen) hideJob?.cancel() else if (playing && visible) scheduleHide() }

    // Double-tap seeking: each tap on a side adds the step and restarts the label.
    val step = app.graph.settings.current.doubleTapSeekSec
    var seekSide by remember { mutableIntStateOf(0) }
    var seekAmount by remember { mutableIntStateOf(0) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    val seekPulse = remember { Animatable(0f) }
    // Read when a gesture runs, which outlives the composition that set it up.
    val reduceMotion by rememberUpdatedState(LocalReduceAnimation.current)
    var seekAt by remember { mutableStateOf(Offset.Zero) }

    // Press and hold plays at 2x until the finger lifts, as YouTube's player does.
    var boosting by remember { mutableStateOf(false) }
    var speedBefore by remember { mutableFloatStateOf(1f) }
    DisposableEffect(Unit) { onDispose { if (boosting) connection.setSpeed(speedBefore) } }

    Box(
        modifier
            .fillMaxSize()
            .semantics {
                onClick(label = "Show controls") { visible = true; scheduleHide(); true }
                // The double-tap seeks, for TalkBack, where a double tap is the activation.
                if (!isLive) customActions = listOf(
                    CustomAccessibilityAction("Back $step seconds") { connection.seekBy(-step * 1000L); true },
                    CustomAccessibilityAction("Forward $step seconds") { connection.seekBy(step * 1000L); true },
                )
            }
            // Not keyed on anything that changes mid-video: a restart would cancel a hold at 2x.
            .pointerInput(step) {
                fun seek(side: Int, at: Offset) {
                    haptics.play(Haptic.Tick)
                    if (seekSide != side) seekAmount = 0
                    seekSide = side
                    seekAt = at
                    seekAmount += step
                    connection.seekBy(side * step * 1000L)
                    seekJob?.cancel()
                    seekJob = scope.launch {
                        seekPulse.snapTo(0f)
                        if (reduceMotion) { delay(650); seekPulse.snapTo(1f) }
                        else seekPulse.animateTo(1f, tween(650, easing = EaseOutCubic))
                        delay(250)
                        seekSide = 0
                        seekAmount = 0
                    }
                }
                fun sideOf(x: Float) = when {
                    x < size.width * 0.38f -> -1
                    x > size.width * 0.62f -> 1
                    else -> 0
                }
                fun toggleControls() {
                    visible = !visible
                    if (visible) scheduleHide()
                }
                // Hand-rolled rather than detectTapGestures, which holds every single tap back by
                // the double-tap timeout: here a tap in the middle toggles the controls at once,
                // and while a seek streak is running each further tap on a side seeks again at once,
                // as YouTube's player does. Only a first tap on a side waits to see if a second follows.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var cancelled = false
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        awaitTapUp(down).also { if (it == null) cancelled = true }
                    }
                    if (cancelled) return@awaitEachGesture
                    if (up == null) {
                        // Held: 2x while the finger stays down. The speed goes back however the
                        // hold ends, even if the gesture itself is cancelled.
                        try {
                            if (playing && !isLive && !ended) {
                                speedBefore = connection.speed.value
                                boosting = true
                                visible = false
                                hideJob?.cancel()
                                haptics.play(Haptic.Tick)
                                connection.setSpeed(2f)
                            }
                            // The hold owns the finger until it lifts: moving it must not start the
                            // swipes into or out of fullscreen, or the sheet's drag, underneath.
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            if (boosting) {
                                boosting = false
                                connection.setSpeed(speedBefore)
                                haptics.play(Haptic.Tap)
                            }
                        }
                        return@awaitEachGesture
                    }
                    up.consume()
                    val side = sideOf(down.position.x)
                    when {
                        side != 0 && seekSide != 0 -> seek(side, down.position)
                        side == 0 -> toggleControls()
                        else -> {
                            val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
                            if (second == null) toggleControls()
                            else {
                                // A second touch that turns into a drag (down to the window, out
                                // of fullscreen) is that drag's, not a seek.
                                val secondUp = awaitTapUp(second)
                                if (secondUp != null) {
                                    secondUp.consume()
                                    seek(sideOf(second.position.x).takeIf { it != 0 } ?: side, second.position)
                                }
                            }
                        }
                    }
                }
            },
    ) {
        // Captions sit above the scrubber when the controls show, near the bottom when they don't.
        val cueBottom = if (visible) (if (mode == ControlsMode.Fullscreen) 92.dp else 54.dp) else 14.dp
        CaptionsOverlay(captions, position, Modifier.align(Alignment.BottomCenter).padding(bottom = cueBottom, start = 24.dp, end = 24.dp), large = mode == ControlsMode.Fullscreen)

        var lastSide by remember { mutableIntStateOf(1) }
        if (seekSide != 0) lastSide = seekSide
        AnimatedVisibility(
            seekSide != 0,
            enter = enterMotion(fadeIn(tween(120))),
            exit = exitMotion(fadeOut(tween(220))),
            modifier = Modifier.align(if (lastSide < 0) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.38f),
        ) {
            SeekIndicator(lastSide, seekAmount, { seekPulse.value }, { seekAt }, Modifier.fillMaxSize())
        }

        AnimatedVisibility(visible || error != null || ended, enter = enterMotion(fadeIn(spring(stiffness = 700f))), exit = exitMotion(fadeOut(spring(stiffness = 500f))), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))) {
                val insets = if (mode == ControlsMode.Fullscreen) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier
                Box(Modifier.fillMaxSize().then(insets)) {
                    // 8dp, not 10: the buttons' 44dp targets reach 2dp past their 40dp discs.
                    Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OverlayButton(OrielIcons.Collapse, if (mode == ControlsMode.Fullscreen) "Exit full screen" else "Minimise", onClick = { if (mode == ControlsMode.Fullscreen) onFullscreen() else onCollapse() })
                        if (mode == ControlsMode.Fullscreen) {
                            Spacer(Modifier.width(8.dp))
                            Text(watch.video?.title ?: "", style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        } else Spacer(Modifier.weight(1f))
                        if (canPip) {
                            OverlayButton(OrielIcons.Pip, "Picture in picture", onClick = { enterPip(app.activity, connection.videoSize.value) })
                            Spacer(Modifier.width(4.dp))
                        }
                        if (captions.tracks.isNotEmpty()) {
                            OverlayButton(if (captions.selected != null) OrielIcons.Captions else OrielIcons.CaptionsOff, "Captions", onClick = { app.watch.toggleCaptions(); scheduleHide() })
                            Spacer(Modifier.width(4.dp))
                        }
                        OverlayButton(OrielIcons.Settings, "Settings", onClick = onSettings)
                    }
                    // Centre transport, out of the way of the preview while scrubbing. Its fade is read in
                    // the layer, not here, so it doesn't recompose the overlay each frame; only whether
                    // it's there to touch is composition's. Gone under an error, which takes the centre.
                    val transport = animateFloatAsState(if (scrubbing) 0f else 1f, motion(spring(stiffness = 600f)), label = "transport")
                    val transportShown by remember { derivedStateOf { transport.value > 0.01f } }
                    val canGoBack by remember { derivedStateOf { position.longValue > 4000 } }
                    if (error == null) Row(
                        Modifier
                            .align(Alignment.Center)
                            .graphicsLayer { val t = transport.value; alpha = t; scaleX = 0.92f + 0.08f * t; scaleY = scaleX }
                            .then(if (transportShown) Modifier else Modifier.clearAndSetSemantics { }.pointerInput(Unit) {
                                awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                            }),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (mode == ControlsMode.Fullscreen) 56.dp else 36.dp),
                    ) {
                        OverlayButton(OrielIcons.Previous, "Previous", size = 46.dp, iconSize = 28.dp, enabled = hasPrevious || canGoBack, onClick = {
                            haptics.play(Haptic.SkipPrevious); connection.previous(); scheduleHide()
                        })
                        // Spinner, play, pause and replay cross-scale into each other rather than swapping.
                        val centre = when { buffering && error == null -> 0; ended -> 3; playing -> 2; else -> 1 }
                        val reduce = LocalReduceAnimation.current
                        AnimatedContent(
                            centre,
                            transitionSpec = {
                                if (reduce) EnterTransition.None togetherWith ExitTransition.None
                                else (fadeIn(tween(140)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 700f), initialScale = 0.6f))
                                    .togetherWith(fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.6f))
                                    .using(SizeTransform(clip = false))
                            },
                            contentAlignment = Alignment.Center,
                            label = "centre",
                        ) { c ->
                            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                                // Loading still answers a tap: it pauses (or plays) as the button it covers would.
                                if (c == 0) Box(
                                    Modifier
                                        .size(64.dp)
                                        .clearAndSetSemantics {
                                            contentDescription = if (playing) "Loading. Pause" else "Loading. Play"
                                            role = Role.Button
                                            onClick { connection.togglePlay(); scheduleHide(); true }
                                        }
                                        .pressClickable(haptic = if (playing) Haptic.Pause else Haptic.Resume) { connection.togglePlay(); scheduleHide() },
                                    contentAlignment = Alignment.Center,
                                ) { ActivityIndicator(size = 40.dp, color = Color.White) }
                                else OverlayButton(
                                    when (c) { 3 -> OrielIcons.Replay; 2 -> OrielIcons.Pause; else -> OrielIcons.Play },
                                    when (c) { 3 -> "Replay"; 2 -> "Pause"; else -> "Play" }, size = 64.dp, iconSize = 40.dp,
                                    onClick = {
                                        haptics.play(if (playing) Haptic.Pause else Haptic.Resume)
                                        connection.togglePlay()
                                        scheduleHide()
                                    },
                                )
                            }
                        }
                        OverlayButton(OrielIcons.Next, "Next", size = 46.dp, iconSize = 28.dp, enabled = hasNext, onClick = {
                            haptics.play(Haptic.SkipNext); connection.next(); scheduleHide()
                        })
                    }
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = if (mode == ControlsMode.Fullscreen) 10.dp else 0.dp)) {
                        if (scrubbing && !isLive) {
                            // The preview rides above the thumb, kept inside the frame at the ends. The
                            // scrub is read inside it and in layout, so a drag recomposes the preview alone.
                            val scrubMs = { (scrubValue * duration.coerceAtLeast(0)).toLong() }
                            val previewWidth = if (mode == ControlsMode.Fullscreen) 208.dp else 148.dp
                            ScrubPreview(
                                storyboard, scrubMs, { chapterAt(chapters, scrubMs())?.title }, previewWidth,
                                Modifier
                                    .fillMaxWidth()
                                    .layout { m, c ->
                                        val p = m.measure(c.copy(minWidth = 0))
                                        val x = (scrubValue * c.maxWidth - p.width / 2f).coerceIn(0f, (c.maxWidth - p.width).coerceAtLeast(0).toFloat())
                                        layout(c.maxWidth, p.height) { p.place(x.toInt(), 0) }
                                    }
                                    .padding(bottom = 8.dp),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
                            // Weighted as one group, so the full-screen button always sits at the edge.
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                if (isLive) {
                                    LiveBadge(onClick = { connection.seekToLiveEdge() })
                                } else {
                                    TimeAndChapter(position, { if (scrubbing) (scrubValue * duration.coerceAtLeast(0)).toLong() else null }, duration, chapters) {
                                        haptics.play(Haptic.Tap); hideJob?.cancel(); onChapters()
                                    }
                                }
                            }
                            OverlayButton(
                                if (mode == ControlsMode.Fullscreen) OrielIcons.FullscreenExit else OrielIcons.Fullscreen,
                                if (mode == ControlsMode.Fullscreen) "Exit full screen" else "Full screen",
                                size = 36.dp, iconSize = 22.dp, glass = false, onClick = onFullscreen,
                            )
                        }
                        if (!isLive) {
                            Scrubber(
                                connection, position, duration, buffering, chapters, segments,
                                value = { if (scrubbing) scrubValue else null },
                                onValueChange = { scrubValue = it },
                                onDragStateChange = { dragging ->
                                    scrubbing = dragging
                                    if (dragging) hideJob?.cancel() else scheduleHide()
                                },
                                onValueChangeFinished = {
                                    if (duration > 0) connection.seekTo((scrubValue * duration).toLong())
                                    haptics.play(Haptic.Select)
                                },
                            )
                        } else Spacer(Modifier.height(12.dp))
                    }
                    if (error != null) ErrorOverlay(
                        error!!, Modifier.align(Alignment.Center),
                        onRetry = { haptics.play(Haptic.Tap); connection.retry() },
                        onReport = { haptics.play(Haptic.Tap); app.reportProblem(ReportKind.PLAYBACK, watch.video?.id ?: connection.current.value?.id) },
                    )
                }
            }
        }

        AnimatedVisibility(boosting, enter = enterMotion(fadeIn(tween(120)) + scaleIn(initialScale = 0.9f)), exit = exitMotion(fadeOut(tween(160))), modifier = Modifier.align(Alignment.TopCenter).padding(top = if (mode == ControlsMode.Fullscreen) 24.dp else 14.dp)) {
            Row(Modifier.videoGlass(RoundedCornerShape(50), 0.6f).padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("2×", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.W700), color = Color.White)
                Spacer(Modifier.width(6.dp))
                Icon(OrielIcons.FastForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }

        // "Skipped sponsor · Undo", over everything, as SponsorBlock's own extension shows it.
        AnimatedVisibility(skipped != null, enter = enterMotion(fadeIn() + scaleIn(initialScale = 0.9f)), exit = exitMotion(fadeOut() + scaleOut(targetScale = 0.9f)), modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = if (visible) 57.dp else 9.dp)) {
            skipped?.let { s ->
                // A 44dp-tall target around the chip; the press shows on the chip itself.
                val interaction = remember { MutableInteractionSource() }
                Row(
                    Modifier
                        .heightIn(min = 44.dp)
                        .clickable(interaction, indication = null, onClickLabel = "Undo") { connection.undoSkip() }
                        .wrapContentHeight()
                        .videoGlass(RoundedCornerShape(50), 0.6f)
                        .indication(interaction, LocalIndication.current)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(segmentColor(s.category)))
                    Spacer(Modifier.width(8.dp))
                    Text("Skipped ${SponsorBlock.label(s.category).lowercase()}", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text("Undo", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.W700), color = Color.White)
                }
            }
        }
    }
}
