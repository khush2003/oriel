package app.oriel.ui.screens

import androidx.compose.runtime.mutableFloatStateOf
import app.oriel.util.ReportKind
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import app.oriel.ui.player.enterMotion
import app.oriel.ui.player.exitMotion
import app.oriel.ui.player.pressClickable
import app.oriel.ui.theme.LocalReduceAnimation
import app.oriel.ui.theme.motion
import app.oriel.ui.theme.Sp
import app.oriel.ui.theme.goo
import app.oriel.ui.theme.gooEffect
import app.oriel.ui.theme.smoothstep
import app.oriel.core.model.ShortSound
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import app.oriel.core.model.Channel
import app.oriel.core.model.LikeStatus
import app.oriel.core.model.ShortInfo
import app.oriel.core.model.Video
import app.oriel.ui.AppState
import app.oriel.ui.components.Avatar
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.MessageState
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.RemoteImage
import app.oriel.ui.glass.videoGlass
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.HeartRed
import app.oriel.util.Thumbnails
import app.oriel.util.Format
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI
import androidx.compose.ui.platform.LocalDensity

internal val SHORT_CORNER = 18.dp

@OptIn(UnstableApi::class)
@Composable
internal fun ShortPage(app: AppState, id: String, info: ShortInfo?, playback: ShortsPlayback, active: Boolean, fit: Boolean, onComments: (ShortInfo) -> Unit, onSound: (ShortSound) -> Unit) {
    val graph = app.graph
    val player = playback.player
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var paused by remember { mutableStateOf(false) }
    val failure by playback.failed.collectAsState()
    val reduceMotion by rememberUpdatedState(LocalReduceAnimation.current)
    var fast by remember { mutableStateOf(false) }
    val heart = remember { Animatable(0f) }
    var heartAt by remember { mutableStateOf(Offset.Zero) }
    val liked by graph.library.likedIds.collectAsState()
    val disliked by graph.library.dislikedIds.collectAsState()
    val subscribed by graph.library.subscribedIds.collectAsState()
    val video = remember(id, info) { Video(id, info?.title.orEmpty(), channel = info?.channel, isShort = true) }
    val status = when { id in liked -> LikeStatus.LIKE; id in disliked -> LikeStatus.DISLIKE; else -> LikeStatus.INDIFFERENT }
    // The tap detector lives across recompositions, so it reads these fresh rather than the values it started with.
    val currentStatus by rememberUpdatedState(status)
    val currentVideo by rememberUpdatedState(video)
    // Scrubbing: the bar along the bottom follows a finger, the picture seeking as it goes.
    var scrubbing by remember { mutableStateOf(false) }
    var scrubAt by remember { mutableFloatStateOf(0f) }
    val overlay by animateFloatAsState(if (scrubbing) 0f else 1f, motion(tween(160)), label = "shortOverlay")
    // Polled ten times a second, glided between: the line moves on every frame, not in steps.
    // (A loop back to the start jumps rather than running backwards across the bar.) Read
    // outside composition, so the page doesn't recompose with each poll.
    val shownProgress = remember { Animatable(0f) }
    LaunchedEffect(active) {
        paused = playback.held && active
        shownProgress.snapTo(0f)
        while (active && isActive) {
            val d = player.duration
            val p = if (d > 0) (player.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
            if (p < shownProgress.value - 0.05f || scrubbing || reduceMotion) shownProgress.snapTo(p)
            else launch { shownProgress.animateTo(p, tween(110, easing = LinearEasing)) }
            delay(100)
        }
    }
    fun togglePlay() {
        val playing = playback.togglePlay()
        paused = !playing
        haptics.play(if (playing) Haptic.Resume else Haptic.Pause)
    }
    fun seekTo(f: Float) {
        val d = player.duration
        if (d <= 0) return
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
        player.seekTo((f * d).toLong())
        scope.launch { shownProgress.snapTo(f) }
    }

    Box(
        Modifier.fillMaxSize()
            // TalkBack: a double tap plays or pauses, as a tap does; liking is the Like button's.
            .semantics { if (active) onClick(label = if (paused) "Play" else "Pause") { togglePlay(); true } }
            .pointerInput(active) {
            detectTapGestures(
                onTap = {
                    if (!active) return@detectTapGestures
                    togglePlay()
                },
                onDoubleTap = { at ->
                    heartAt = at
                    if (currentStatus != LikeStatus.LIKE) graph.actions.toggleLike(currentVideo)
                    haptics.play(Haptic.ToggleOn)
                    scope.launch {
                        heart.snapTo(0f)
                        // Reduced, the heart just shows at its full size for a moment.
                        if (reduceMotion) { heart.snapTo(0.5f); delay(700); heart.snapTo(1f) }
                        else heart.animateTo(1f, tween(900, easing = LinearEasing))
                    }
                },
                onLongPress = { if (active) { fast = true; player.setPlaybackSpeed(2f); haptics.play(Haptic.Tick) } },
                onPress = {
                    tryAwaitRelease()
                    if (fast) { fast = false; player.setPlaybackSpeed(1f) }
                },
            )
        },
    ) {
        val poster = @Composable { RemoteImage(Thumbnails.short(Video(id, "")), Modifier.fillMaxSize(), RoundedCornerShape(0.dp), border = false) }
        if (active) {
            // The poster is the shutter: it holds until the first frame, so a swipe never flashes black.
            ContentFrame(
                player = player, modifier = Modifier.fillMaxSize(), surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                contentScale = if (fit) ContentScale.Fit else ContentScale.Crop, keepContentOnReset = false,
                shutter = poster,
            )
        } else poster()
        // Legibility: a soft shade at the bottom under the text and buttons.
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.45f).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))

        AnimatedVisibility(paused, Modifier.align(Alignment.Center), enter = enterMotion(fadeIn() + scaleIn(initialScale = 1.3f)), exit = exitMotion(fadeOut() + scaleOut(targetScale = 0.8f))) {
            Box(Modifier.size(72.dp).videoGlass(CircleShape, 0.45f), contentAlignment = Alignment.Center) {
                Icon(OrielIcons.Play, "Play", tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }
        val failed = failure?.takeIf { it.id == id }
        if (active && failed != null) {
            MessageState(
                failed.message, Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                action = "Try again", onAction = { playback.retry(); paused = false },
                secondary = "Report", onSecondary = { app.reportProblem(ReportKind.PLAYBACK, id) },
            )
        }
        // Fades in and out as the watch page's 2x pill does, rather than popping.
        AnimatedVisibility(fast, Modifier.align(Alignment.TopCenter).padding(top = 96.dp), enter = enterMotion(fadeIn(tween(120)) + scaleIn(initialScale = 0.9f)), exit = exitMotion(fadeOut(tween(160)))) {
            Row(Modifier.videoGlass(RoundedCornerShape(50), 0.5f).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("2× ▸▸", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
        // Read only in the layer, so the pop doesn't recompose the page each frame: it springs
        // up past full size, settles, then floats up and fades.
        Icon(
            OrielIcons.Heart, null, tint = HeartRed,
            modifier = Modifier.size(96.dp).graphicsLayer {
                val t = heart.value
                val pop = if (t < 0.25f) { val k = t / 0.25f; 0.4f + 0.85f * (1f - (1f - k) * (1f - k)) } else 1.25f - 0.15f * ((t - 0.25f) / 0.2f).coerceAtMost(1f)
                translationX = heartAt.x - 48.dp.toPx(); translationY = heartAt.y - 48.dp.toPx() - 60.dp.toPx() * ((t - 0.6f) / 0.4f).coerceIn(0f, 1f)
                rotationZ = -12f * (1f - t)
                scaleX = pop; scaleY = pop
                alpha = if (t <= 0f || t >= 1f) 0f else 1f - ((t - 0.6f) / 0.4f).coerceIn(0f, 1f)
            },
        )

        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 26.dp).graphicsLayer { alpha = overlay }, verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f).padding(start = PAGE_GUTTER, end = 12.dp)) {
                info?.channel?.let { ch ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 44.dp).pressClickable(enabled = ch.id != null, onClickLabel = "Open the channel") { app.openChannel(ch.id!!) }) {
                        Avatar(ch.avatar, 32.dp, name = ch.name)
                        Spacer(Modifier.width(8.dp))
                        Text(ch.name, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        ch.id?.let { cid ->
                            val sub = cid in subscribed
                            Spacer(Modifier.width(10.dp))
                            // A 44dp-tall target around the pill.
                            Box(
                                Modifier.heightIn(min = 44.dp).pressClickable(haptic = if (sub) Haptic.ToggleOff else Haptic.ToggleOn) {
                                    app.toggleSubscribe(Channel(cid, ch.name, ch.avatar, verified = ch.verified))
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (sub) "Subscribed" else "Subscribe",
                                    style = MaterialTheme.typography.labelLarge, color = if (sub) Color.White else Color.Black,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sub) Color.White.copy(alpha = 0.22f) else Color.White).padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                info?.title?.let { title ->
                    // Hashtags are links, as on YouTube: each opens a search for it.
                    val text = remember(title) {
                        buildAnnotatedString {
                            var last = 0
                            val style = TextLinkStyles(SpanStyle(fontWeight = FontWeight.W700))
                            HASHTAG.findAll(title).forEach { m ->
                                append(title.substring(last, m.range.first))
                                withLink(LinkAnnotation.Clickable(m.value, style) { haptics.play(Haptic.Tap); app.search(m.value) }) { append(m.value) }
                                last = m.range.last + 1
                            }
                            append(title.substring(last))
                        }
                    }
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                info?.sound?.let { sound ->
                    // A 44dp-tall target around the chip; it stands in for the space above it.
                    Box(Modifier.heightIn(min = 44.dp).pressClickable(onClickLabel = "Shorts with this sound") { onSound(sound) }, contentAlignment = Alignment.CenterStart) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f))
                            .padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(OrielIcons.Sound, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            sound.title, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1,
                            modifier = Modifier.widthIn(max = 210.dp).basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_200),
                        )
                    }
                    }
                }
            }
            Column(Modifier.padding(end = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ShortAction(if (status == LikeStatus.LIKE) OrielIcons.Liked else OrielIcons.Like, info?.likeCount ?: "Like", description = if (status == LikeStatus.LIKE) "Liked" else "Like", splash = status != LikeStatus.LIKE) {
                    haptics.play(Haptic.ToggleOn); graph.actions.toggleLike(video)
                }
                ShortAction(if (status == LikeStatus.DISLIKE) OrielIcons.Disliked else OrielIcons.Dislike, "Dislike") {
                    haptics.play(Haptic.ToggleOn); graph.actions.toggleDislike(video)
                }
                ShortAction(OrielIcons.Comments, info?.commentsCount ?: "Comments", description = "Comments", enabled = info?.commentsToken != null) { info?.let(onComments) }
                ShortAction(OrielIcons.Share, "Share") { app.share(video) }
                ShortAction(OrielIcons.More, "More") { app.sheetItem = video }
                // The sound's artwork, as YouTube ends the column with it: a tap opens the sound.
                info?.sound?.let { sound ->
                    Box(
                        Modifier.padding(top = 2.dp).size(40.dp).clip(RoundedCornerShape(10.dp)).border(2.dp, Color.White, RoundedCornerShape(10.dp))
                            .clickable(onClickLabel = "Shorts with this sound") { haptics.play(Haptic.Tap); onSound(sound) },
                    ) {
                        RemoteImage(sound.image, Modifier.fillMaxSize(), RoundedCornerShape(10.dp), border = false)
                    }
                }
            }
        }
        // The progress bar along the bottom, and a scrubber: drag along it (or tap it) to seek.
        // It swells while held, the overlay steps aside, and the time shows above it.
        val thick by animateFloatAsState(if (scrubbing) 1f else 0f, motion(spring(dampingRatio = 0.8f, stiffness = 700f)), label = "scrubThick")
        if (scrubbing) ScrubTime({ scrubAt }, player.duration.coerceAtLeast(0), Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp)
                .pointerInput(active) {
                    if (!active) return@pointerInput
                    var lastSeek = -1f
                    // The bar is drawn inset by the frame's corners; touches map onto it, not the full width.
                    val inset = SHORT_CORNER.toPx()
                    val track = (size.width - inset * 2).coerceAtLeast(1f)
                    fun seek(f: Float, exact: Boolean) {
                        val d = player.duration
                        if (d <= 0) return
                        player.setSeekParameters(if (exact) androidx.media3.exoplayer.SeekParameters.EXACT else androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
                        player.seekTo((f * d).toLong())
                        lastSeek = f
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { o -> scrubbing = true; scrubAt = ((o.x - inset) / track).coerceIn(0f, 1f); haptics.play(Haptic.Tick); seek(scrubAt, false) },
                        onDragEnd = { seek(scrubAt, true); scope.launch { shownProgress.snapTo(scrubAt) }; scrubbing = false },
                        onDragCancel = { scrubbing = false },
                    ) { change, dx ->
                        change.consume()
                        scrubAt = (scrubAt + dx / track).coerceIn(0f, 1f)
                        // Keyframe seeks while moving, a few per second's worth of travel; exact on release.
                        if (kotlin.math.abs(scrubAt - lastSeek) > 0.012f) seek(scrubAt, false)
                    }
                }
                .pointerInput(active) {
                    if (!active) return@pointerInput
                    val inset = SHORT_CORNER.toPx()
                    detectTapGestures { o ->
                        val f = ((o.x - inset) / (size.width - inset * 2).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        if (player.duration > 0) { seekTo(f); haptics.play(Haptic.Tick) }
                    }
                }
                .semantics {
                    contentDescription = "Seek"
                    progressBarRangeInfo = ProgressBarRangeInfo(shownProgress.value, 0f..1f)
                    if (active) setProgress { f -> seekTo(f.coerceIn(0f, 1f)); true }
                }
                .drawBehind {
                    val f = if (scrubbing) scrubAt else shownProgress.value
                    val h = (2.dp.toPx() + 4.dp.toPx() * thick)
                    val inset = SHORT_CORNER.toPx()
                    val w = size.width - inset * 2
                    val y = size.height - h
                    val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
                    drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(inset, y), Size(w, h), r)
                    drawRoundRect(Color.White, Offset(inset, y), Size(w * f, h), r)
                    if (thick > 0f) drawCircle(Color.White, radius = 7.dp.toPx() * thick, center = Offset(inset + w * f, y + h / 2))
                },
        )
    }
}

/** The time over the scrubber, in its own scope: each move of the finger recomposes this, not the page. */
@Composable
private fun ScrubTime(at: () -> Float, durationMs: Long, modifier: Modifier) {
    Text(
        "${Format.durationMs((at() * durationMs).toLong())} / ${Format.durationMs(durationMs)}",
        style = MaterialTheme.typography.headlineSmall.copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 12f)),
        color = Color.White, modifier = modifier,
    )
}

@Composable
private fun ShortAction(icon: ImageVector, label: String, description: String = label, enabled: Boolean = true, splash: Boolean = false, onClick: () -> Unit) {
    val reduce = LocalReduceAnimation.current
    val scope = rememberCoroutineScope()
    val burst = remember { Animatable(0f) }
    var bursting by remember { mutableStateOf(false) }
    val tap = {
        if (splash && !reduce) scope.launch {
            // The mock's like: droplets burst out, detach through the goo, then fall back into the button.
            bursting = true
            burst.snapTo(0f)
            // The droplets fall back once the burst is at rest by the mock's measure (within 0.002,
            // moving under 0.04 a second), which comes well before a Compose spring ends.
            val out = launch { burst.animateTo(1f, Sp.Splash.unit, initialVelocity = 6f) }
            androidx.compose.runtime.snapshotFlow { kotlin.math.abs(burst.value - 1f) < 0.002f && kotlin.math.abs(burst.velocity) < 0.04f || !out.isActive }.first { it }
            out.cancel()
            burst.animateTo(0f, Sp.SplashBack.unit)
            // A newer tap cancels this one before here, and keeps the layer.
            bursting = false
        }
        onClick()
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.5f }.semantics(mergeDescendants = true) { contentDescription = if (description == label) label else "$description, $label" },
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().videoGlass(CircleShape, 0.32f).clip(CircleShape).clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = tap))
            if (bursting) LikeSplash(burst)
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.W600), color = Color.White, maxLines = 1)
    }
}

/** Eight droplets on [burst], fused through the small goo, in a 120 dp square centred on the button. */
@Composable
private fun LikeSplash(burst: Animatable<Float, *>) {
    val density = LocalDensity.current
    val fx = remember(density) { gooEffect(with(density) { 4.dp.toPx() }, gain = 20f, cut = 8f) }
    Spacer(
        Modifier
            .requiredSize(120.dp)
            .goo(fx)
            .drawBehind {
                val x = burst.value
                if (x <= 0.005f) return@drawBehind
                val unit = 1.dp.toPx()
                val d = 34f * unit * x.coerceAtLeast(0f)
                val r = (7f * (1f - x * 0.45f)).coerceAtLeast(0f) * smoothstep(0f, 0.15f, x + 0.1f) * unit
                if (r <= 0f) return@drawBehind
                for (i in 0 until 8) {
                    val a = i / 8f * 2f * PI.toFloat() + 0.3f
                    drawCircle(Color.White, r, Offset(center.x + cos(a) * d, center.y + sin(a) * d))
                }
            },
    )
}

private val HASHTAG = Regex("""#[\p{L}\p{N}_]+""")
