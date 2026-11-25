/*
 * Section headers, chips, buttons, the search field, the segmented control,
 * message states and skeletons, adapted from BitChord (GPL-3.0): Common.kt,
 * SearchField.kt, Skeletons.kt, DetailScreen.kt (ActionPill, CircleIconButton)
 * and SettingsSheet.kt (SegmentedControl).
 */
package app.oriel.ui.components

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import app.oriel.ui.glass.glassEdgeColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.oriel.ui.theme.LocalIsDark
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.oriel.ui.AppState
import app.oriel.ui.theme.fade
import app.oriel.ui.theme.motion
import androidx.compose.ui.platform.LocalAccessibilityManager
import kotlinx.coroutines.delay

// ---- notice ----------------------------------------------------------------------------------

/**
 * BitChord's QueueActionNotice: a short-lived banner. Announced politely as
 * it arrives. On light, where the card is the page's own white, a soft shadow sets it off.
 */
@Composable
fun NoticeBanner(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(16.dp)
    val dark = LocalIsDark.current
    Row(
        modifier
            .padding(horizontal = BAR_GUTTER)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .shadow(if (dark) 0.dp else 12.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(0.5.dp, glassEdgeColor(), shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false).padding(vertical = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (action != null && onAction != null) {
            Spacer(Modifier.width(14.dp))
            TextButton(action, onAction, MaterialTheme.typography.titleSmall, MaterialTheme.colorScheme.primary, PaddingValues(4.dp))
        }
    }
}

/** Brief notices: a banner that drops in under the status bar and leaves on its own. */
@Composable
fun NoticeHost(app: AppState, modifier: Modifier = Modifier) {
    val notice by app.notice.collectAsState()
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(notice?.id) {
        val n = notice ?: return@LaunchedEffect
        // Longer when TalkBack or the "time to take action" setting asks for it.
        val base = if (n.action != null) 4_500L else 2_800L
        delay(accessibility?.calculateRecommendedTimeoutMillis(base, containsIcons = false, containsText = true, containsControls = n.action != null) ?: base)
        app.clearNotice(n)
    }
    var last by remember { mutableStateOf<AppState.Notice?>(null) }
    SideEffect { if (notice != null) last = notice }
    val shownNotice = notice ?: last
    AnimatedVisibility(
        notice != null,
        enter = slideInVertically(motion(spring(dampingRatio = 0.8f, stiffness = 420f, visibilityThreshold = IntOffset.VisibilityThreshold))) { -it } + fadeIn(fade(180)),
        exit = slideOutVertically(motion(spring(dampingRatio = 1f, stiffness = 520f, visibilityThreshold = IntOffset.VisibilityThreshold))) { -it } + fadeOut(fade(200)),
        modifier = modifier,
    ) {
        shownNotice?.let { n ->
            NoticeBanner(n.text, Modifier, n.action, n.onAction?.let { act -> { app.clearNotice(n); act() } })
        }
    }
}
