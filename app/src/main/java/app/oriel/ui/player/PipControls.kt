package app.oriel.ui.player

import androidx.compose.runtime.getValue
import app.oriel.ui.components.ActivityIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import app.oriel.ui.theme.motion
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.oriel.ui.AppState
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics

/**
 * The floating window's controls, as YouTube's: play/pause and close always there in the top
 * corner on small dark discs, over a picture left undimmed, and a thin line of progress along
 * the bottom. A tap anywhere else opens the watch page.
 */
@Composable
internal fun PipControls(app: AppState, onExpand: () -> Unit) {
    val connection = app.connection
    val playing by connection.playWhenReady.collectAsState()
    val buffering by connection.buffering.collectAsState()
    val duration by connection.duration.collectAsState()
    val position = rememberPosition(connection, intervalMs = 500)
    val haptics = rememberHaptics()
    Box(
        Modifier.fillMaxSize()
            .semantics { contentDescription = "Video window" }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Open the watch page") { onExpand() },
    ) {
        // 44dp targets, the play one tucked 4dp under the close one so the discs sit
        // 22dp in from the corner, 40dp apart.
        Row(Modifier.align(Alignment.TopEnd)) {
            val nudge = Modifier.offset(x = 4.dp)
            if (buffering && playing) {
                // Still a 44dp target that pauses.
                Box(
                    nudge
                        .size(44.dp)
                        .clearAndSetSemantics {
                            contentDescription = "Loading. Pause"
                            role = Role.Button
                            onClick { connection.togglePlay(); true }
                        }
                        .pressClickable(haptic = Haptic.Pause) { connection.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        ActivityIndicator(size = 14.dp, color = Color.White)
                    }
                }
            } else {
                PipButton(if (playing) OrielIcons.Pause else OrielIcons.Play, if (playing) "Pause" else "Play", 15.dp, nudge) {
                    haptics.play(if (playing) Haptic.Pause else Haptic.Resume); connection.togglePlay()
                }
            }
            PipButton(OrielIcons.Xmark, "Close", 11.dp) { haptics.play(Haptic.Tap); connection.stop() }
        }
        if (duration > 0) {
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.5.dp).background(Color.White.copy(alpha = 0.28f)).drawWithContent {
                    val f = (position.longValue.toFloat() / duration).coerceIn(0f, 1f)
                    drawRect(Color.White, size = size.copy(width = size.width * f))
                },
            )
        }
    }
}

/** A small dark disc with a white glyph, a 44dp touch target around a 30dp disc that sinks and darkens while pressed. */
@Composable
private fun PipButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, iconSize: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by androidx.compose.animation.core.animateFloatAsState(if (pressed) 1f else 0f, motion(androidx.compose.animation.core.spring(stiffness = 900f)), label = "pipPress")
    Box(modifier.size(44.dp).clickable(interaction, indication = null, onClickLabel = description, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(30.dp)
                .graphicsLayer { val k = 1f - 0.12f * press; scaleX = k; scaleY = k }
                .clip(CircleShape)
                .drawWithContent { drawRect(Color.Black.copy(alpha = 0.45f + 0.2f * press)); drawContent() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, description, tint = Color.White, modifier = Modifier.size(iconSize))
        }
    }
}
