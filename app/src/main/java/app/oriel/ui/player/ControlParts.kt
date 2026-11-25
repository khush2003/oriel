package app.oriel.ui.player

import app.oriel.ui.components.sheetOrigin
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.oriel.ui.glass.videoGlass
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.LiveRed
import app.oriel.util.Format

/**
 * The finger's lift, as a tap's, or null once it isn't one: another gesture took it, it strayed
 * past the touch slop, or a second finger joined it.
 */
internal suspend fun AwaitPointerEventScope.awaitTapUp(down: PointerInputChange): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: return null
        if (event.changes.any { it.id != down.id && it.pressed }) return null
        if (change.changedToUp()) return change
        if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) return null
        // The picture's drag, around this, takes a move in the same pass, after this handler has seen it.
        if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) return null
    }
}

/** The time and the chapter's button: its own scope, so the position's ticks recompose this alone. */
@Composable
internal fun RowScope.TimeAndChapter(position: MutableLongState, scrubMs: () -> Long?, duration: Long, chapters: List<app.oriel.core.model.Chapter>, onChapters: () -> Unit) {
    val shown = scrubMs() ?: position.longValue
    Text("${Format.durationMs(shown)} / ${if (duration > 0) Format.durationMs(duration) else "--:--"}", style = MaterialTheme.typography.labelMedium, color = Color.White)
    // The chapter is a button: it opens the list of them.
    chapterAt(chapters, shown)?.let { ch ->
        Spacer(Modifier.width(6.dp))
        // A 44dp-tall target around the pill; the press shows on the pill itself.
        val interaction = remember { MutableInteractionSource() }
        Row(
            Modifier
                .weight(1f, fill = false)
                .heightIn(min = 44.dp)
                .sheetOrigin(16.dp)
                .clickable(interaction, indication = null, onClick = onChapters)
                .wrapContentHeight()
                .clip(RoundedCornerShape(50))
                .indication(interaction, LocalIndication.current)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("·  ${ch.title}", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(" ›", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.W700), color = Color.White.copy(alpha = 0.85f))
        }
    }
}

/** The scrubber, in its own scope for the same reason. A chapter crossed while scrubbing ticks. */
@Composable
internal fun Scrubber(
    connection: app.oriel.playback.PlayerConnection,
    position: MutableLongState,
    duration: Long,
    buffering: Boolean,
    chapters: List<app.oriel.core.model.Chapter>,
    segments: List<app.oriel.core.extras.Segment>,
    value: () -> Float?,
    onValueChange: (Float) -> Unit,
    onDragStateChange: (Boolean) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val haptics = rememberHaptics()
    val starts = remember(chapters) { chapters.map { it.startMs } }
    // Only whether it's loading is composition's; the position itself is read as the bar draws.
    val loading by remember(buffering) { derivedStateOf { buffering && position.longValue == 0L } }
    var lastChapter by remember { mutableIntStateOf(-1) }
    ThinSlider(
        value = { value() ?: if (duration > 0) position.longValue.toFloat() / duration else 0f },
        onValueChange = { v ->
            if (duration > 0 && starts.size > 1) {
                val ms = (v * duration).toLong()
                val i = starts.indexOfLast { it <= ms }
                if (lastChapter != -1 && i != lastChapter) haptics.play(Haptic.Tick)
                lastChapter = i
            }
            onValueChange(v)
        },
        onDragStateChange = { dragging -> if (!dragging) lastChapter = -1; onDragStateChange(dragging) },
        onValueChangeFinished = onValueChangeFinished,
        buffered = { if (duration > 0) connection.bufferedPosition().toFloat() / duration else 0f },
        loading = loading,
        durationMs = duration.coerceAtLeast(0),
        chapterStartsMs = starts,
        segments = segments,
        idleHeight = 4.dp,
        activeHeight = 10.dp,
    )
}

internal fun chapterAt(chapters: List<app.oriel.core.model.Chapter>, positionMs: Long) = chapters.lastOrNull { it.startMs <= positionMs }

@Composable
internal fun LiveBadge(onClick: () -> Unit) {
    // A 44dp-tall target around the badge, which goes to the live edge.
    Row(Modifier.heightIn(min = 44.dp).pressClickable(onClickLabel = "Go to the live edge", onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(LiveRed))
        Spacer(Modifier.width(6.dp))
        Text("LIVE", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.W700), color = Color.White)
    }
}

@Composable
internal fun SeekIndicator(side: Int, seconds: Int, pulse: () -> Float, at: () -> Offset, modifier: Modifier) {
    val shape = if (side < 0) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50) else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
    Box(
        modifier
            .clip(shape)
            .drawBehind {
                // A wash over the side, and a ring spreading from the finger: drawn, not recomposed, each frame.
                val p = pulse()
                drawRect(Color.White.copy(alpha = 0.10f))
                // The tap was in the player's coordinates; this box is pinned to its side.
                val tap = at()
                val local = if (side < 0) tap else Offset(tap.x - (size.width / 0.38f - size.width), tap.y)
                drawCircle(Color.White.copy(alpha = 0.16f * (1f - p)), radius = size.maxDimension * (0.15f + 0.9f * p), center = local)
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (side < 0) "◀◀" else "▶▶", color = Color.White, fontSize = 14.sp, modifier = Modifier.graphicsLayer { alpha = 0.6f + 0.4f * (1f - pulse()) })
            Spacer(Modifier.height(4.dp))
            Text("$seconds seconds", style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
    }
}

@Composable
internal fun ErrorOverlay(message: String, modifier: Modifier, onRetry: () -> Unit, onReport: () -> Unit) {
    Column(modifier.widthIn(max = 320.dp).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Color.White, textAlign = TextAlign.Center, maxLines = 3)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ErrorButton("Retry", onRetry)
            ErrorButton("Report", onReport)
        }
    }
}

/** A glass pill on the picture; 44dp tall to touch, as every target is. */
@Composable
private fun ErrorButton(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(Modifier.heightIn(min = 44.dp).graphicsLayer { alpha = if (pressed) 0.6f else 1f }.clickable(interaction, indication = null, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.videoGlass(RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/**
 * A dark translucent disc over the video, the way iOS draws controls on a playing picture.
 * The touch target is at least 44dp however small the disc; the press shows on the disc.
 */
@Composable
fun OverlayButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, size: Dp = 40.dp, iconSize: Dp = 22.dp, enabled: Boolean = true, glass: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .size(maxOf(size, 44.dp))
            // A sheet it opens (settings) grows out of it.
            .sheetOrigin(size / 2)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .then(if (glass) Modifier.videoGlass(CircleShape) else Modifier.clip(CircleShape))
                .indication(interaction, LocalIndication.current),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(iconSize)) }
    }
}

/** The cue under the position, on YouTube's own black caption plate. */
@Composable
fun CaptionsOverlay(captions: CaptionsState, position: MutableLongState, modifier: Modifier = Modifier, large: Boolean = false) {
    if (captions.selected == null || captions.cues.isEmpty()) return
    val now = position.longValue
    val cue = captions.cues.lastOrNull { it.startMs <= now && now < it.endMs } ?: return
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.66f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(cue.text, color = Color.White, fontSize = if (large) 19.sp else 14.sp, lineHeight = if (large) 25.sp else 19.sp, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
}
