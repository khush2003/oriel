package app.oriel.ui.components

import app.oriel.ui.AlertAction
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.util.lerp
import app.oriel.ui.theme.LocalLiquidMotion
import app.oriel.ui.theme.LocalReduceAnimation
import app.oriel.ui.theme.Sp
import app.oriel.ui.theme.blobRectPath
import app.oriel.ui.theme.smoothstep
import kotlinx.coroutines.flow.first
import app.oriel.util.Thumbnails
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.MutableTransitionState
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.oriel.core.model.Channel
import app.oriel.core.model.Item
import app.oriel.core.model.Playlist
import app.oriel.ui.navigation.LocalPlaylistRoute
import app.oriel.core.model.Post
import app.oriel.core.model.Video
import app.oriel.ui.AlertSpec
import app.oriel.ui.AppState
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.LocalIsDark
import app.oriel.ui.theme.fade
import app.oriel.ui.theme.motion
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import androidx.compose.ui.input.pointer.PointerEventPass

/**
 * Closes the sheet it's read inside: slides it down, then runs [after]. Rows that end the
 * sheet call this rather than the dismiss callback, which would drop it with no exit.
 */
val LocalSheetClose = staticCompositionLocalOf<(after: () -> Unit) -> Unit> { { it() } }

/**
 * Where a sheet grows out of: the tapped button's bounds in the window, its corner radius in px,
 * and its fill (the sheet starts in it). Recorded as the button is pressed ([sheetOrigin]) and
 * taken by the next [OrielSheet] to open, so callers needn't thread it through.
 */
@Immutable
class SheetOrigin(val bounds: Rect, val corner: Float, val tint: Color?)

object SheetOrigins {
    private var origin: SheetOrigin? = null
    private var recordedAt = 0L

    fun record(o: SheetOrigin?) {
        origin = o
        recordedAt = System.nanoTime()
    }

    /** The origin of the press that's opening a sheet now, once. */
    fun take(): SheetOrigin? {
        val o = origin?.takeIf { System.nanoTime() - recordedAt < 600_000_000L }
        origin = null
        return o
    }
}

/** Marks a button as the place the sheet it opens grows out of: its bounds, [corner] and [tint] are recorded as it's pressed. */
@Composable
fun Modifier.sheetOrigin(corner: Dp, tint: Color? = null): Modifier {
    if (!LocalLiquidMotion.current) return this
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val radius = with(LocalDensity.current) { corner.toPx() }
    val fill by rememberUpdatedState(tint)
    return this
        .onPlaced { coordinates[0] = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val c = coordinates[0]?.takeIf { it.isAttached } ?: return@awaitEachGesture
                val r = c.boundsInWindow()
                if (r.width > 1f && r.height > 1f) SheetOrigins.record(SheetOrigin(r, minOf(radius, r.height / 2f, r.width / 2f), fill))
            }
        }
}

/**
 * The mock's sheet morph over M3's sheet: it starts as the button that opened it (its rect,
 * radius and fill), the height leading the width (Sp.SheetH against Sp.SheetW), the sides and
 * top swelling mid-flight (blobRect: 8 to 18 dp at the sides, 18 at the top); dragged down it
 * shrinks back toward the button (by up to 42%) and fades its content; closed, it shrinks back
 * into it on Sp.SheetClose. M3's own slide is held still meanwhile (counter-translated), so
 * only the morph moves it. Without an origin it rises out of the bottom edge, swelling the same.
 */
@Stable
private class SheetMorph(val origin: SheetOrigin?) {
    val pw = Animatable(0f)
    val ph = Animatable(0f)
    /** Opening (until M3's slide has settled) or closing: M3's motion is cancelled out meanwhile. */
    var opening by mutableStateOf(true)
    var closing by mutableStateOf(false)
    /** How far it had been dragged down when it began to close: it shrinks from there. */
    var heldDrag = 0f
    var placedY = 0f
    var containerH = 0f
    var sheetH = 0f
    var containerWindow = Offset.Zero
    val path = Path()
}

@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.sheetMorph(m: SheetMorph, state: androidx.compose.material3.SheetState, page: Color, scrimAlpha: Float): Modifier = this
    .onPlaced { c ->
        m.placedY = c.positionInParent().y
        m.sheetH = c.size.height.toFloat()
        c.parentLayoutCoordinates?.let { p ->
            m.containerH = p.size.height.toFloat()
            m.containerWindow = p.positionInWindow()
        }
    }
    .drawBehind {
        // The scrim, under the sheet's own layer so the morph's clip leaves it whole.
        val p = minOf(m.pw.value, m.ph.value).coerceIn(0f, 1f)
        if (p > 0f) drawRect(Color.Black.copy(alpha = scrimAlpha * p), topLeft = Offset(-size.width, -m.containerH * 2f), size = Size(size.width * 3f, m.containerH * 5f))
    }
    .graphicsLayer {
        val offset = runCatching { state.requireOffset() }.getOrDefault(0f)
        val rest = (m.containerH - size.height).coerceAtLeast(0f)
        translationY = when {
            m.closing -> rest + m.heldDrag - offset
            m.opening -> rest - offset
            else -> 0f
        }
    }
    .drawWithContent {
        val offset = runCatching { state.requireOffset() }.getOrDefault(0f)
        val rest = (m.containerH - size.height).coerceAtLeast(0f)
        val tY = when {
            m.closing -> rest + m.heldDrag - offset
            m.opening -> rest - offset
            else -> 0f
        }
        // The sheet's top in these coordinates, and how far it's dragged below its rest.
        val top = offset - m.placedY
        val drag = when {
            m.closing -> m.heldDrag
            m.opening -> 0f
            else -> (offset - rest).coerceAtLeast(0f)
        }
        val k = 1f - 0.42f * smoothstep(0f, size.height * 1.1f, drag)
        val pw = m.pw.value * k
        val ph = m.ph.value * k
        val p = minOf(pw, ph)
        val pc = p.coerceIn(0f, 1f)
        val w = size.width
        val h = size.height
        // Where the button is, in these (untranslated) coordinates.
        val o = m.origin
        val src = if (o != null) {
            o.bounds.translate(-m.containerWindow.x, -m.containerWindow.y - m.placedY - tY)
        } else {
            Rect(0f, top + h, w, top + h)
        }
        val srcR = o?.corner ?: 28.dp.toPx()
        var l = lerp(src.left, 0f, pw)
        var r = lerp(src.right, w, pw)
        var t = lerp(src.top, top, ph)
        var b = lerp(src.bottom, top + h, ph)
        if (p < 0f) {
            // Plops back into the button with a squash.
            val q = minOf(0.35f, -p)
            val cx = (l + r) / 2f
            val cy = (t + b) / 2f
            val hw = (r - l) / 2f * (1f + q)
            val hh = (b - t) / 2f * (1f - q * 0.8f)
            l = cx - hw; r = cx + hw; t = cy - hh; b = cy + hh
        }
        val ease = smoothstep(0f, 1f, pc)
        val radTop = minOf(lerp(srcR, 28.dp.toPx(), ease), (r - l) / 2f, (b - t) / 2f).coerceAtLeast(0f)
        val radBottom = minOf(lerp(srcR, 0f, ease), (r - l) / 2f, (b - t) / 2f).coerceAtLeast(0f)
        val sw = kotlin.math.sin(pc * kotlin.math.PI.toFloat())
        val vel = kotlin.math.abs(m.ph.velocity + m.pw.velocity) * 0.5f
        val bs = sw * (8f + (vel * 2f).coerceIn(0f, 10f)) * density
        val bt = sw * 18f * density
        blobRectPath(m.path, l, t, r, b, radTop, bs, bt, radBottom)
        clipPath(m.path) {
            // The sheet's colour past its top too, for the swell and the overshoot to fill.
            drawRect(page, topLeft = Offset(-bs * 2f, top - h), size = Size(w + bs * 4f, h + 2f))
            this@drawWithContent.drawContent()
            // It starts in the button's fill and turns into the sheet's.
            val tint = o?.tint
            val a = 1f - smoothstep(0.02f, 0.45f, pc)
            if (tint != null && a > 0f) drawRect(tint, topLeft = Offset(l - bs * 2f, t - bt * 2f), size = Size(r - l + bs * 4f, b - t + bt * 3f), alpha = a)
        }
    }

/**
 * BitChord's sheet: the page colour, a 28dp top radius and iOS's grabber, a 36 x 5 capsule.
 * Every sheet in the app is one of these, and each grows out of the button that opened it, as
 * the mock's do ([SheetMorph]; the button marks itself with [sheetOrigin], or a caller passes
 * [origin]). [scrollable] wraps the content in a scroll; sheets that bring their own list
 * (chapters, comments) pass false. Reduce motion keeps M3's plain slide.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrielSheet(
    onDismiss: () -> Unit,
    scrollable: Boolean = true,
    maxHeight: Dp = 680.dp,
    origin: SheetOrigin? = remember { SheetOrigins.take() },
    content: @Composable ColumnScope.() -> Unit,
) {
    // M3's plain slide, as before liquid motion, unless it's on.
    val reduce = LocalReduceAnimation.current || !LocalLiquidMotion.current
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    val morph = remember { SheetMorph(origin) }
    // Every way out (a drag down, the scrim, back) asks M3 to hide; the morph says no and closes itself instead.
    val closeRef = remember { arrayOfNulls<(() -> Unit) -> Unit>(1) }
    val reduceNow by rememberUpdatedState(reduce)
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { v ->
        if (v == SheetValue.Hidden && !reduceNow) {
            closeRef[0]?.invoke {}
            false
        } else true
    })
    val close: (() -> Unit) -> Unit = remember(state) {
        { after ->
            if (reduceNow) {
                scope.launch { state.hide() }.invokeOnCompletion { dismiss(); after() }
            } else if (!morph.closing) {
                val offset = runCatching { state.requireOffset() }.getOrDefault(0f)
                morph.heldDrag = if (morph.opening) 0f else (offset - (morph.containerH - morph.sheetH).coerceAtLeast(0f)).coerceAtLeast(0f)
                morph.closing = true
                scope.launch {
                    val a = launch { morph.ph.animateTo(0f, Sp.SheetClose.unit) }
                    morph.pw.animateTo(0f, Sp.SheetClose.unit)
                    a.join()
                    dismiss()
                    after()
                }
            }
        }
    }
    closeRef[0] = close
    LaunchedEffect(morph) {
        if (reduce) {
            morph.pw.snapTo(1f)
            morph.ph.snapTo(1f)
            morph.opening = false
            return@LaunchedEffect
        }
        launch { morph.ph.animateTo(1f, Sp.SheetH.unit) }
        launch { morph.pw.animateTo(1f, Sp.SheetW.unit) }
        snapshotFlow { state.currentValue == SheetValue.Expanded && !state.isAnimationRunning }.first { it }
        morph.opening = false
    }
    val scroll = rememberScrollState()
    val page = MaterialTheme.colorScheme.background
    val overdrag = rememberSheetOverdrag(state) { !scroll.canScrollForward }
    val scrim = 0.36f
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = (if (reduce) Modifier else Modifier.sheetMorph(morph, state, page, scrim)).then(if (scrollable) Modifier.sheetOverdrag(overdrag, page) else Modifier),
        sheetState = state,
        containerColor = page,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 0.dp,
        scrimColor = if (reduce) Color.Black.copy(alpha = scrim) else Color.Transparent,
        dragHandle = { Grabber { close {} } },
    ) {
        // A scrolling sheet stretches as a whole past its top, rather than its content inside it.
        CompositionLocalProvider(
            LocalSheetClose provides close,
            androidx.compose.foundation.LocalOverscrollFactory provides if (scrollable) null else androidx.compose.foundation.LocalOverscrollFactory.current,
        ) {
            val body = Modifier.fillMaxWidth().heightIn(max = maxHeight)
                .then(
                    if (reduce) Modifier else Modifier.graphicsLayer {
                        // The mock's body: it fades in late and settles from 94%, from its top.
                        val pc = minOf(morph.pw.value, morph.ph.value).coerceIn(0f, 1f)
                        alpha = smoothstep(0.55f, 0.95f, pc)
                        val s = lerp(0.94f, 1f, smoothstep(0.4f, 1f, pc))
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    },
                )
            Column(
                (if (scrollable) body.verticalScroll(scroll) else body).navigationBarsPadding().padding(bottom = 12.dp),
                content = content,
            )
        }
    }
}

/**
 * The sheet pulled up past its top: M3's stops dead there; iOS's gives, less and less the
 * further it's pulled, and springs back on release. The finger is watched, never consumed, so
 * M3's own drag and fling-to-dismiss run as before; this only adds the stretch above the rest.
 */
@OptIn(ExperimentalMaterial3Api::class)
private class SheetOverdrag(private val state: androidx.compose.material3.SheetState, private val scope: kotlinx.coroutines.CoroutineScope, private val canStretch: () -> Boolean) {
    val stretch = androidx.compose.animation.core.Animatable(0f)
    private var restOffset = Float.NaN

    fun noteRest() {
        if (state.currentValue == androidx.compose.material3.SheetValue.Expanded && !state.isAnimationRunning) restOffset = runCatching { state.requireOffset() }.getOrDefault(Float.NaN)
    }

    private fun atRest(): Boolean {
        val rest = restOffset
        if (rest.isNaN()) return false
        val now = runCatching { state.requireOffset() }.getOrNull() ?: return false
        return kotlin.math.abs(now - rest) < 1.5f
    }

    fun Modifier.stretched(fill: Color): Modifier = this
        .pointerInput(Unit) {
            val limit = size.height * 0.5f
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                scope.launch { stretch.stop() }
                var over = stretch.value.let { if (it > 0f) unRubber(it, limit) else 0f }
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val dy = change.position.y - change.previousPosition.y
                    over = when {
                        dy < 0f && atRest() && canStretch() -> over - dy
                        dy > 0f -> (over - dy).coerceAtLeast(0f)
                        else -> over
                    }
                    val shown = rubber(over, limit)
                    scope.launch { stretch.snapTo(shown) }
                }
                scope.launch { stretch.animateTo(0f, spring(dampingRatio = 1f, stiffness = 400f)) }
            }
        }
        .graphicsLayer { translationY = -stretch.value }
        // The sheet's foot comes up with it; this keeps the gap under it filled with the sheet.
        .drawBehind {
            val s = stretch.value
            if (s > 0f) drawRect(fill, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 1f), size = androidx.compose.ui.geometry.Size(size.width, s + 1f))
        }
}

private fun Modifier.sheetOverdrag(overdrag: SheetOverdrag, fill: Color): Modifier = with(overdrag) { this@sheetOverdrag.stretched(fill) }

/** UIScrollView's rubber band: the further past, the less it gives, never beyond [limit]. */
private fun rubber(x: Float, limit: Float): Float = if (x <= 0f || limit <= 0f) 0f else limit * (1f - 1f / (x * 0.55f / limit + 1f))
private fun unRubber(y: Float, limit: Float): Float = if (y <= 0f || y >= limit) 0f else limit / 0.55f * (1f / (1f - y / limit) - 1f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberSheetOverdrag(state: androidx.compose.material3.SheetState, canStretch: () -> Boolean): SheetOverdrag {
    val scope = rememberCoroutineScope()
    val stretchable by rememberUpdatedState(canStretch)
    val overdrag = remember(state) { SheetOverdrag(state, scope) { stretchable() } }
    LaunchedEffect(overdrag) { snapshotFlow { state.currentValue to state.isAnimationRunning }.collect { overdrag.noteRest() } }
    return overdrag
}

/** iOS's grabber. Drawn only; for TalkBack it's the sheet's close, since the drag has no other equivalent. */
@Composable
private fun Grabber(onDismiss: () -> Unit) {
    Box(
        Modifier.padding(top = 7.dp, bottom = 9.dp).size(36.dp, 5.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f))
            .semantics {
                contentDescription = "Close sheet"
                onClick("Close") { onDismiss(); true }
                dismiss { onDismiss(); true }
            },
    )
}

/**
 * A sheet's title row, as iOS's sheet navigation bar: an optional back chevron, the title
 * centred, and an optional trailing action. The sides are measured first and the title gets
 * what's left, so a wide action never runs under it.
 */
@Composable
fun SheetTitle(title: String, onBack: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val haptics = rememberHaptics()
    CenteredTitleRow(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 2.dp),
        start = onBack?.let { back ->
            {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { haptics.play(Haptic.Tap); back() },
                    contentAlignment = Alignment.Center,
                ) { Icon(OrielIcons.Back, "Back", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
            }
        },
        title = { Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
        end = trailing,
    )
}

/** A text button in a sheet's title bar (Cancel, Done, New), with a 44dp hit area. */
@Composable
fun SheetAction(text: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Box(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable { haptics.play(Haptic.Tap); onClick() }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

/** The header of an item's sheet: its picture and title, as iOS context menus preview what they act on. */
@Composable
private fun ItemHeader(item: Item) {
    Row(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when (item) {
            is Video -> RemoteImage(Thumbnails.of(item, 240), Modifier.width(96.dp).height(54.dp), RoundedCornerShape(8.dp))
            is Channel -> Avatar(item.avatar, 54.dp, name = item.title)
            is Playlist -> RemoteImage(item.thumbnail, Modifier.width(96.dp).height(54.dp), RoundedCornerShape(8.dp))
            is Post -> Avatar(item.author?.avatar, 54.dp, name = item.author?.name)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = when (item) {
                is Video -> item.channel?.name
                is Channel -> item.subscribersText ?: item.handle
                is Playlist -> listOfNotNull(item.owner?.name, item.countText).joinToString(" · ")
                is Post -> item.author?.name
            }
            sub?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

/**
 * What can be done with an item, as rows: shared by the long-press menu
 * (lifted over the blurred page) and the ⋮ sheet.
 */
@Composable
fun ColumnScope.ItemActions(app: AppState, item: Item, dismiss: () -> Unit) {
    val library by app.graph.library.state.collectAsState()
    fun run(block: () -> Unit) { dismiss(); block() }
    when (item) {
        is Video -> {
            MenuRow("Play next", OrielIcons.Queue, { run { app.connection.playNext(listOf(item)); app.notice("Playing next") } })
            MenuRow("Add to queue", OrielIcons.Playlist, { run { app.connection.addToQueue(listOf(item)); app.notice("Added to queue") } })
            MenuGroupBand()
            val later = library.watchLater.any { it.id == item.id }
            MenuRow(if (later) "Remove from Watch later" else "Save to Watch later", OrielIcons.WatchLater, { run { app.graph.actions.toggleWatchLater(item) } })
            MenuRow("Save to playlist", OrielIcons.Save, { run { app.saveTarget = item } })
            MenuRow("Listen in the background", OrielIcons.AudioOnly, {
                run {
                    app.openVideo(item, expand = false)
                    app.connection.setAudioOnly(true)
                }
            })
            MenuGroupBand()
            MenuRow("Share", OrielIcons.Share, { run { app.share(item) } })
            item.channel?.id?.let { id -> MenuRow("Go to channel", OrielIcons.Person, { run { app.openChannel(id) } }) }
            if (library.history.any { it.video.id == item.id }) {
                MenuRow("Remove from history", OrielIcons.History, { run { app.graph.library.removeHistory(item.id) } }, destructive = true)
            }
        }
        is Channel -> {
            val subscribed = app.graph.library.subscribedIds.collectAsState().value.contains(item.id)
            MenuRow(if (subscribed) "Unsubscribe" else "Subscribe", if (subscribed) OrielIcons.Bell else OrielIcons.BellOutline, { run { app.toggleSubscribe(item) } }, destructive = subscribed)
            MenuRow("Share", OrielIcons.Share, { run { app.share(item) } })
        }
        is Playlist -> if (item.id.startsWith("local:")) {
            // A playlist kept on this device: nothing on YouTube to share or save it to.
            val id = item.id.removePrefix("local:")
            MenuRow("Open playlist", OrielIcons.Playlist, { run { app.navigate(LocalPlaylistRoute(id)) } })
            MenuRow("Rename", OrielIcons.Edit, { run { app.renameTarget = item } })
            MenuRow("Delete playlist", OrielIcons.Delete, {
                run {
                    app.alert = AlertSpec("Delete “${item.title}”?", "This removes the playlist from this device. The videos stay on YouTube.", listOf(
                        AlertAction("Cancel"),
                        AlertAction("Delete", destructive = true) { app.graph.library.deleteLocalPlaylist(id) },
                    ))
                }
            }, destructive = true)
        } else {
            if (item.isMix) {
                MenuRow("Play mix", OrielIcons.Play, { run { app.openItem(item) } })
            } else {
                MenuRow("Open playlist", OrielIcons.Playlist, { run { app.openPlaylist(item.id) } })
                val saved = item.id in app.graph.library.savedPlaylistIds.collectAsState().value
                MenuRow(if (saved) "Remove from library" else "Save to library", OrielIcons.Library, { run { app.graph.actions.toggleSaved(item) } })
            }
            MenuRow("Share", OrielIcons.Share, { run { app.share(item) } })
        }
        is Post -> {
            item.video?.let { v -> MenuRow("Play video", OrielIcons.Play, { run { app.openVideo(v) } }) }
            item.author?.id?.let { id -> MenuRow("Go to channel", OrielIcons.Person, { run { app.openChannel(id) } }) }
            MenuRow("Share", OrielIcons.Share, { run { app.share(item) } })
        }
    }
}

@Composable
fun ItemActionsSheet(app: AppState, item: Item, onDismiss: () -> Unit) {
    OrielSheet(onDismiss) {
        val close = LocalSheetClose.current
        ItemHeader(item)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = PAGE_GUTTER).clip(GroupShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            ItemActions(app, item) { close {} }
        }
    }
}

/**
 * Save to…: Watch later, the playlists kept on the device, and, signed in,
 * the account's own playlists with a tick on those already holding it.
 */
@Composable
fun SaveToPlaylistSheet(app: AppState, video: Video, onDismiss: () -> Unit) {
    val library by app.graph.library.state.collectAsState()
    val signedIn by app.graph.account.signedIn.collectAsState()
    var remote by remember { mutableStateOf<List<Pair<Playlist, Boolean>>?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(video.id, signedIn) { if (signedIn) remote = app.graph.actions.accountPlaylists(video.id) }

    // The sheet's own close, so finishing a new playlist slides it down rather than cutting it.
    // (A plain holder, not state: it's written while composing.)
    val closeSheet = remember { arrayOfNulls<(() -> Unit) -> Unit>(1) }
    OrielSheet(onDismiss) {
        closeSheet[0] = LocalSheetClose.current
        SheetTitle("Save to…", trailing = { SheetAction("New") { creating = true } })
        SettingsGroup(header = "On this device", top = 8.dp) {
            val later = library.watchLater.any { it.id == video.id }
            ChoiceRow("Watch later", later, multi = true) { app.graph.actions.toggleWatchLater(video) }
            library.localPlaylists.forEach { p ->
                RowDivider(inset = false)
                val has = p.videos.any { it.id == video.id }
                ChoiceRow(p.name, has, detail = "${p.videos.size}", multi = true) {
                    if (has) app.graph.library.removeFromLocalPlaylist(p.id, video.id) else app.graph.actions.addToLocalPlaylist(p, listOf(video))
                }
            }
        }
        if (signedIn) {
            SettingsGroup(header = "Your YouTube playlists", footer = "Changes here are made on your YouTube account.") {
                val list = remote
                when {
                    list == null -> Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) { ActivityIndicator() }
                    list.isEmpty() -> Text("No playlists yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    else -> list.forEachIndexed { i, (p, has) ->
                        if (i > 0) RowDivider(inset = false)
                        ChoiceRow(p.title, has, multi = true) {
                            remote = list.map { if (it.first.id == p.id) it.first to !has else it }
                            app.graph.actions.setInAccountPlaylist(p.id, video, !has, p.title)
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        NewPlaylistDialog(signedIn, onDismiss = { creating = false }) { name, onAccount, private ->
            creating = false
            if (onAccount) app.graph.actions.createAccountPlaylist(name, private, video)
            else app.graph.actions.addToLocalPlaylist(app.graph.library.createPlaylist(name), listOf(video))
            closeSheet[0]?.invoke {} ?: onDismiss()
        }
    }
}

/** An iOS alert with one text field: a title, the field pre-filled with [initial], Cancel and [confirm]. */
@Composable
fun TextPromptDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val done = { if (text.text.isNotBlank()) onConfirm(text.text.trim()) }
    AlertCard(visible, onDismiss) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 18.dp, start = 16.dp, end = 16.dp))
        Spacer(Modifier.height(12.dp))
        BasicTextField(
            text, { text = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { done() }),
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(alertFieldFill()).padding(horizontal = 10.dp, vertical = 9.dp).focusRequester(focus),
        )
        Spacer(Modifier.height(16.dp))
        AlertButtons(listOf(AlertAction("Cancel") { onDismiss() }, AlertAction(confirm, emphasized = true) { done() }))
    }
}

/** iOS-style text prompt for a new playlist's name, with where it should live. */
@Composable
fun NewPlaylistDialog(signedIn: Boolean, onDismiss: () -> Unit, onCreate: (name: String, onAccount: Boolean, private: Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var onAccount by remember { mutableStateOf(signedIn) }
    var private by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AlertCard(visible, onDismiss) {
        Text("New playlist", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 18.dp, start = 16.dp, end = 16.dp))
        Spacer(Modifier.height(12.dp))
        BasicTextField(
            name, { name = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onCreate(name.trim(), onAccount, private) }),
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(alertFieldFill()).padding(horizontal = 10.dp, vertical = 9.dp).focusRequester(focus),
            decorationBox = { inner ->
                Box { if (name.isEmpty()) Text("Title", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant); inner() }
            },
        )
        if (signedIn) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.padding(horizontal = 16.dp)) {
                SegmentedControl(listOf(true, false), onAccount, { if (it) "YouTube" else "This device" }, { onAccount = it }, Modifier.weight(1f))
            }
            if (onAccount) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.padding(horizontal = 16.dp)) {
                    SegmentedControl(listOf(true, false), private, { if (it) "Private" else "Public" }, { private = it }, Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        AlertButtons(
            listOf(
                AlertAction("Cancel") { onDismiss() },
                AlertAction("Create", emphasized = true) { if (name.isNotBlank()) onCreate(name.trim(), onAccount, private) },
            ),
        )
    }
}

// ---- iOS alerts --------------------------------------------------------------------------

/** An alert's text field: a veil of the text colour, which shows on the card in both modes (the page colour vanished on light). */
@Composable
private fun alertFieldFill(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (LocalIsDark.current) 0.10f else 0.06f)

@Composable
internal fun AlertCard(visible: MutableTransitionState<Boolean>, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val liquid = LocalLiquidMotion.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // iOS's alert: blooms in from a touch larger with a soft spring, and on the way out
        // shrinks a touch and fades quickly. Kept composed until the exit has played. Liquid,
        // it blooms out of a drop at the centre instead ([alertBloom]).
        AnimatedVisibility(
            visible,
            enter = if (liquid) fadeIn(Sp.Fade.unit) else fadeIn(motion(spring(stiffness = 900f))) + scaleIn(motion(spring(dampingRatio = 0.78f, stiffness = 520f)), initialScale = 1.14f),
            exit = if (liquid) fadeOut(Sp.FadeOut.unit) else fadeOut(fade(140)) + scaleOut(fade(160), targetScale = 0.94f),
        ) {
            val fill = MaterialTheme.colorScheme.surfaceContainerHigh
            Column(
                Modifier.widthIn(max = 290.dp).fillMaxWidth(0.76f)
                    .then(if (liquid) Modifier.alertBloom(this, fill) else Modifier.clip(RoundedCornerShape(16.dp)).background(fill)),
                content = content,
            )
        }
    }
}

/**
 * The liquid alert's way in: a drop at the centre that swells into the card, its width on the
 * bouncy spring and its height on a slower one so it spreads, then drops open, the sides
 * bowing out mid-flight (blobRect) and the whole a touch past full size before it settles; the
 * text comes in once it's mostly open. Out, it draws back in quickly. Draw phase only.
 */
@Composable
private fun Modifier.alertBloom(scope: androidx.compose.animation.AnimatedVisibilityScope, fill: Color): Modifier {
    val shown = androidx.compose.animation.EnterExitState.Visible
    val pw = scope.transition.animateFloat({ if (targetState == shown) Sp.Bouncy.unit else Sp.MenuClose.unit }, label = "bloomW") { if (it == shown) 1f else 0f }
    val ph = scope.transition.animateFloat({ if (targetState == shown) Sp.SheetW.unit else Sp.MenuClose.unit }, label = "bloomH") { if (it == shown) 1f else 0f }
    return this
        .graphicsLayer {
            val w = pw.value
            val h = ph.value
            val p = minOf(w, h).coerceIn(0f, 1f)
            val s = lerp(0.82f, 1f, minOf(w, h))
            scaleX = s
            scaleY = s
            val drop = minOf(size.width, size.height) * 0.34f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val hw = lerp(drop / 2f, size.width / 2f, w.coerceAtLeast(0f))
            val hh = lerp(drop / 2f, size.height / 2f, h.coerceAtLeast(0f))
            val radius = lerp(drop / 2f, 16.dp.toPx(), smoothstep(0f, 1f, p))
            val swell = kotlin.math.sin(p * kotlin.math.PI.toFloat())
            val bs = swell * 10.dp.toPx()
            val bt = swell * 8.dp.toPx()
            shape = app.oriel.ui.theme.PathShape { out, _ -> blobRectPath(out, cx - hw, cy - hh, cx + hw, cy + hh, radius, bs, bt) }
            clip = true
        }
        .background(fill)
        // The text, once the drop has mostly opened.
        .graphicsLayer { alpha = smoothstep(0.55f, 0.9f, minOf(pw.value, ph.value)) }
}

@Composable
internal fun AlertButtons(actions: List<AlertAction>) {
    val divider = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(divider))
    if (actions.size == 2) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            actions.forEachIndexed { i, a ->
                if (i > 0) Box(Modifier.width(0.5.dp).fillMaxHeight().background(divider))
                AlertButton(a, Modifier.weight(1f).fillMaxHeight())
            }
        }
    } else {
        actions.forEachIndexed { i, a ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(divider))
            AlertButton(a, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AlertButton(action: AlertAction, modifier: Modifier) {
    val haptics = rememberHaptics()
    Box(
        modifier.heightIn(min = 46.dp).clickable(role = Role.Button) { haptics.play(Haptic.Tap); action.onClick() }.padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            action.label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (action.emphasized) FontWeight.W600 else FontWeight.W400),
            color = if (action.destructive) DestructiveRed else MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Shows [AppState.alert] as an iOS alert: centred card, title, message, hairline-separated buttons. */
@Composable
fun AlertHost(app: AppState) {
    val current = app.alert
    var shown by remember { mutableStateOf<AlertSpec?>(null) }
    val visible = remember { MutableTransitionState(false) }
    if (current != null && current !== shown) { shown = current; visible.targetState = true }
    if (current == null) visible.targetState = false
    val spec = shown ?: return
    // Gone once the exit has finished; until then the last alert stays up, leaving.
    if (current == null && visible.isIdle && !visible.currentState) {
        SideEffect { shown = null }
        return
    }
    AlertCard(visible, { app.alert = null }) {
        Text(spec.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 18.dp, start = 16.dp, end = 16.dp))
        spec.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 16.dp, end = 16.dp)) }
        Spacer(Modifier.height(16.dp))
        AlertButtons(spec.actions.map { a -> a.copy(onClick = { app.alert = null; a.onClick() }) })
    }
}

/** Brief notices: a glass banner that drops in under the status bar and leaves on its own. */
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

