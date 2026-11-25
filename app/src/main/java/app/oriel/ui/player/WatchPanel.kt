package app.oriel.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import app.oriel.ui.theme.Sp
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.requiredSize
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.ArtworkPalette

// ---- panels ------------------------------------------------------------------------------

/**
 * A panel over the page under the video, as iOS sheets are set: a grabber, the title at the
 * leading edge with its count in grey, a round grey close button, and a hairline under the
 * header only once the list has scrolled beneath it. A drag down on the header, or on the list
 * once it's at its top, closes it.
 */
@Composable
internal fun Panel(palette: ArtworkPalette, title: String, count: String? = null, scrolled: Boolean, onBack: (() -> Unit)?, onClose: () -> Unit, morph: PanelMorph? = null, content: @Composable () -> Unit) {
    // The panel follows a drag down on its header, and closes past 80dp or on a flick down; short of that it springs back.
    // Liquid, the drag is the morph's (which draws it) and goes up too, rubber-banded.
    val own = remember { androidx.compose.animation.core.Animatable(0f) }
    val drag = morph?.drag ?: own
    val liquid = morph != null
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val close by androidx.compose.runtime.rememberUpdatedState(onClose)
    val haptics = rememberHaptics()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pullToClose = remember {
        object {
            val closeAt = with(density) { 80.dp.toPx() }
            val back = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.86f, stiffness = 520f)
            var armed = false

            fun moveTo(y: Float) {
                // One tick as it passes the point where letting go closes it.
                val past = y > closeAt
                if (past && !armed) haptics.play(Haptic.Tick)
                armed = past
                scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { drag.snapTo(y) }
            }

            fun settle(velocity: Float) {
                armed = false
                if (drag.value > closeAt || (velocity > 1000f && drag.value > 0f)) {
                    morph?.closeVelocity = velocity
                    close()
                } else scope.launch { drag.animateTo(0f, if (liquid) Sp.Sheet.px else back, velocity) }
            }

            // The list's scroll and the panel's pull are one gesture, as in an iOS sheet: at the
            // list's top a pull down moves the panel, and pushing back up gives that back before
            // the list scrolls again. Flings never pull it: one that reaches the top just bounces.
            val connection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput || available.y >= 0f || drag.value <= 0f) return androidx.compose.ui.geometry.Offset.Zero
                    val used = maxOf(available.y, -drag.value)
                    moveTo(drag.value + used)
                    return androidx.compose.ui.geometry.Offset(0f, used)
                }

                override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput || available.y <= 0f) return androidx.compose.ui.geometry.Offset.Zero
                    moveTo(drag.value + available.y)
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                    if (drag.value <= 0f) return androidx.compose.ui.unit.Velocity.Zero
                    settle(available.y)
                    return available
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().graphicsLayer { if (!liquid) translationY = drag.value }.clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(palette.background).nestedScroll(pullToClose.connection)) {
        // The header moves with its drag, so the finger is followed on the screen, not in the header's own coordinates.
        val headerScreen = remember { ScreenPosition() }
        Column(Modifier.fillMaxWidth().screenPosition(headerScreen).pointerInput(Unit) {
            val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
            var total = 0f
            var lastY = 0f
            detectVerticalDragGestures(
                onDragStart = { at -> total = 0f; lastY = headerScreen.of(at).y; tracker.resetTracking() },
                onDragEnd = { pullToClose.settle(tracker.calculateVelocity().y) },
                onDragCancel = { pullToClose.armed = false; scope.launch { drag.animateTo(0f, if (liquid) Sp.Sheet.px else pullToClose.back) } },
            ) { change, _ ->
                val y = headerScreen.of(change.position).y
                val dy = y - lastY
                lastY = y
                total += dy
                tracker.addPosition(change.uptimeMillis, androidx.compose.ui.geometry.Offset(0f, total))
                // Not above where it rests: the header's drag only pulls the panel down (liquid, up
                // it rubber-bands, as the mock's sheet does).
                pullToClose.moveTo(if (liquid) drag.value + dy else (drag.value + dy).coerceAtLeast(0f))
            }
        }) {
            Box(Modifier.padding(top = 7.dp, bottom = 2.dp).align(Alignment.CenterHorizontally).size(36.dp, 5.dp).clip(CircleShape).background(palette.onBackground.copy(alpha = 0.22f)))
            PanelHeader(title, count, palette, onClose, onBack)
            val line by androidx.compose.animation.core.animateFloatAsState(if (scrolled) 1f else 0f, androidx.compose.animation.core.tween(180), label = "panelLine")
            Box(Modifier.fillMaxWidth().height(0.5.dp).graphicsLayer { alpha = line }.background(palette.divider))
        }
        content()
    }
}

/**
 * A panel's title line: the name in bold with its count in grey, a back chevron when a level
 * down (a reply thread), and the close button as iOS draws it, a small grey disc with an ×.
 */
@Composable
internal fun PanelHeader(title: String, count: String?, palette: ArtworkPalette, onClose: (() -> Unit)?, onBack: (() -> Unit)? = null) {
    // End inset 7dp, not 14: the close button's 44dp target reaches 7dp past its 30dp disc.
    Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 6.dp else PAGE_GUTTER, end = 7.dp, top = 4.dp, bottom = 8.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.animation.AnimatedVisibility(onBack != null, enter = enterMotion(androidx.compose.animation.fadeIn() + androidx.compose.animation.expandHorizontally()), exit = exitMotion(androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkHorizontally())) {
            Box(Modifier.size(44.dp).pressClickable(onClickLabel = "Back") { onBack?.invoke() }, contentAlignment = Alignment.Center) {
                Icon(OrielIcons.Back, "Back", tint = palette.accent, modifier = Modifier.size(20.dp))
            }
        }
        androidx.compose.animation.AnimatedContent(
            title to count,
            transitionSpec = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) },
            modifier = Modifier.weight(1f), label = "panelTitle",
        ) { (t, c) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t, style = MaterialTheme.typography.titleLarge, color = palette.onBackground, maxLines = 1)
                if (c != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(c, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W500), color = palette.onBackgroundVariant, maxLines = 1)
                }
            }
        }
        if (onClose != null) {
            // A 44dp target (overhanging the 40dp row) around the 30dp disc, which shows the press.
            val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(
                Modifier.requiredSize(44.dp).clickable(interaction, indication = null, onClickLabel = "Close", onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(palette.onBackground.copy(alpha = 0.10f)).indication(interaction, androidx.compose.foundation.LocalIndication.current),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(OrielIcons.Xmark, "Close", tint = palette.onBackgroundVariant, modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}
