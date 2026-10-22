package app.oriel.ui.screens

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.oriel.ui.AlertAction
import app.oriel.ui.components.AlertButtons
import app.oriel.ui.components.AlertCard
import app.oriel.ui.theme.LiquidPill
import app.oriel.ui.theme.LocalIsDark
import app.oriel.ui.theme.LocalReduceAnimation
import kotlinx.coroutines.delay

/**
 * The first-run question: whether to try liquid motion. An iOS alert with a small live preview
 * (a drop flowing between two pills, as the tab bar's and the chips' selections do with it on),
 * what it costs, and Turn On / Not Now. [onAnswer] gets the choice once the alert has gone;
 * going back counts as Not Now.
 */
@Composable
fun LiquidMotionPrompt(onAnswer: (on: Boolean) -> Unit) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    var choice by remember { mutableStateOf<Boolean?>(null) }
    val answer = { on: Boolean ->
        if (choice == null) {
            choice = on
            visible.targetState = false
        }
    }
    val done by rememberUpdatedState(onAnswer)
    // Read here, as AlertHost does: once the exit has played.
    val chosen = choice
    if (chosen != null && visible.isIdle && !visible.currentState) LaunchedEffect(Unit) { done(chosen) }
    AlertCard(visible, { answer(false) }) {
        Text(
            "Try liquid motion?", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp, start = 16.dp, end = 16.dp),
        )
        Spacer(Modifier.height(12.dp))
        LiquidPreview(Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(12.dp))
        Text(
            "Presses squish, selections flow from tab to tab, and sheets and menus grow out of what you tapped.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "It can use more battery and may feel slower on older phones. You can change this in Settings.",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(16.dp))
        AlertButtons(listOf(AlertAction("Not Now") { answer(false) }, AlertAction("Turn On", emphasized = true) { answer(true) }))
    }
}

/** How many times the preview's drop goes back and forth before it rests: enough to be seen, not forever. */
private const val PREVIEW_TRIPS = 12

/**
 * Two pills in a track and a drop that flows from one to the other on the liquid pill's two
 * edge springs, stretching and necking on the way. Drawn only (it's decoration: no semantics),
 * read in the draw phase. Under Reduce motion it stays put.
 */
@Composable
private fun LiquidPreview(modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val reduce = LocalReduceAnimation.current
    val dark = LocalIsDark.current
    val scope = rememberCoroutineScope()
    // In dp: a 168 x 36 track, its two pills 78 wide at 4 and 86.
    val pill = remember { LiquidPill(4f * density, 82f * density, 28f * density) }
    val path = remember { Path() }
    LaunchedEffect(reduce) {
        if (reduce) return@LaunchedEffect
        repeat(PREVIEW_TRIPS) { i ->
            delay(1_100)
            val right = i % 2 == 0
            if (right) pill.moveTo(scope, 86f * density, 164f * density) else pill.moveTo(scope, 4f * density, 82f * density)
        }
    }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.12f else 0.07f)
    val slot = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dark) 0.10f else 0.06f)
    val blob = MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .size(168.dp, 36.dp)
            .clearAndSetSemantics {}
            .drawBehind {
                val r = size.height / 2f
                drawRoundRect(track, cornerRadius = CornerRadius(r))
                val inset = 4.dp.toPx()
                val w = (size.width - inset * 3f) / 2f
                val h = size.height - inset * 2f
                drawRoundRect(slot, Offset(inset, inset), Size(w, h), CornerRadius(h / 2f))
                drawRoundRect(slot, Offset(inset * 2f + w, inset), Size(w, h), CornerRadius(h / 2f))
                pill.path(path, size.height / 2f)
                drawPath(path, blob)
            },
    )
}
