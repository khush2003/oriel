package app.oriel.ui.components

import app.oriel.ui.glass.backdrop.effects.blur
import app.oriel.ui.glass.backdrop.drawPlainBackdrop
import app.oriel.ui.glass.isGlassSupported
import app.oriel.ui.glass.LocalAppBackdrop
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import app.oriel.ui.glass.glassSurface
import androidx.compose.ui.graphics.RectangleShape
import app.oriel.ui.theme.LocalReduceBlur
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.spring
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.LocalIsDark
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
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
 * The mock's sheet morph: grows from its button (or the bottom edge), shrinks back on drag or
 * close. M3's own slide is counter-translated so only the morph moves it.
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
private fun Modifier.sheetMorph(m: SheetMorph, state: androidx.compose.material3.SheetState, scrimAlpha: Float): Modifier = this
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
        val srcR = o?.corner ?: SHEET_CORNER.toPx()
        // It lands as a card clear of the screen's edges, not docked to them.
        val inset = SHEET_INSET.toPx()
        var l = lerp(src.left, inset, pw)
        var r = lerp(src.right, w - inset, pw)
        var t = lerp(src.top, top, ph)
        var b = lerp(src.bottom, top + h - inset, ph)
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
        val radTop = minOf(lerp(srcR, SHEET_CORNER.toPx(), ease), (r - l) / 2f, (b - t) / 2f).coerceAtLeast(0f)
        val radBottom = radTop
        val sw = kotlin.math.sin(pc * kotlin.math.PI.toFloat())
        val vel = kotlin.math.abs(m.ph.velocity + m.pw.velocity) * 0.5f
        val bs = sw * (8f + (vel * 2f).coerceIn(0f, 10f)) * density
        val bt = sw * 18f * density
        blobRectPath(m.path, l, t, r, b, radTop, bs, bt, radBottom)
        clipPath(m.path) {
            this@drawWithContent.drawContent()
            // It starts in the button's fill and turns into the sheet's.
            val tint = o?.tint
            val a = 1f - smoothstep(0.02f, 0.45f, pc)
            if (tint != null && a > 0f) drawRect(tint, topLeft = Offset(l - bs * 2f, t - bt * 2f), size = Size(r - l + bs * 4f, b - t + bt * 3f), alpha = a)
        }
    }

private val SHEET_INSET = 8.dp
private val SHEET_CORNER = 30.dp

/** How far in each open sheet is, 0 to 1: what [SheetScrimHost] dims and blurs the page by. */
private val SheetScrims = androidx.compose.runtime.mutableStateListOf<() -> Float>()

/**
 * The page under an open sheet, blurred and dimmed as it is under the account card. In the app's
 * window, over the pages and outside what the glass samples: a sheet's own window has nothing
 * behind it to blur.
 */
@Composable
fun SheetScrimHost() {
    if (SheetScrims.isEmpty()) return
    val dark = LocalIsDark.current
    val backdrop = LocalAppBackdrop.current
    val reduceBlur = LocalReduceBlur.current || !isGlassSupported()
    val scrim = when {
        reduceBlur && dark -> Color.Black.copy(alpha = 0.62f)
        reduceBlur -> Color.Black.copy(alpha = 0.36f)
        dark -> Color.Black.copy(alpha = 0.38f)
        else -> Color.Black.copy(alpha = 0.16f)
    }
    val blurPx = with(LocalDensity.current) { 24.dp.toPx() } * 0.33f
    val shown = { SheetScrims.maxOfOrNull { it() } ?: 0f }
    Spacer(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = shown() }
            .then(
                if (!reduceBlur && backdrop != null) Modifier.drawPlainBackdrop(backdrop = backdrop, shape = { RectangleShape }, effects = { blur(blurPx * shown().coerceIn(0.05f, 1f)) })
                else Modifier,
            )
            .background(scrim),
    )
}

/**
 * BitChord's sheet, floating: the account card's glass ([glassSurface]) clear of the screen's
 * edges, the page blurred and dimmed behind it, and iOS's grabber. With liquid motion it grows
 * out of the button that opened it ([SheetMorph]; the button marks itself with [sheetOrigin],
 * or a caller passes [origin]). [scrollable] wraps the content in a scroll; sheets that bring
 * their own list (chapters, comments) pass false.
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
    // M3's plain slide, unless liquid motion is on.
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
    val overdrag = rememberSheetOverdrag(state) { !scroll.canScrollForward }
    // The page behind is blurred and dimmed in the app's own window ([SheetScrimHost]), exactly
    // as it is behind the account card, so the two read as the same glass.
    DisposableEffect(morph) {
        val shown = { minOf(morph.pw.value, morph.ph.value).coerceIn(0f, 1f) }
        SheetScrims += shown
        onDispose { SheetScrims -= shown }
    }
    val card = RoundedCornerShape(SHEET_CORNER)
    // The sheet M3 draws is clear and edge to edge; the card is drawn inside it, by the morph or below.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = (if (reduce) Modifier else Modifier.sheetMorph(morph, state, 0f)).then(if (scrollable) Modifier.sheetOverdrag(overdrag) else Modifier),
        sheetState = state,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RectangleShape,
        tonalElevation = 0.dp,
        scrimColor = Color.Transparent,
        dragHandle = null,
    ) {
        // A scrolling sheet stretches as a whole past its top, rather than its content inside it.
        CompositionLocalProvider(
            LocalSheetClose provides close,
            LocalOnGlass provides true,
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
                Modifier.padding(start = SHEET_INSET, end = SHEET_INSET, bottom = SHEET_INSET).fillMaxWidth()
                    // The account card's glass, sampling the page from this window: the morph only shapes it.
                    .glassSurface(card, panel = true),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Grabber { close {} }
                Column(
                    (if (scrollable) body.verticalScroll(scroll) else body).navigationBarsPadding().padding(bottom = 12.dp),
                    content = content,
                )
            }
        }
    }
}

/**
 * The sheet pulled up past its top: M3's stops dead there; iOS's gives, less and less the
 * further it's pulled, and springs back on release. The finger is watched, never consumed, so
 * M3's own drag and fling-to-dismiss still run.
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

    fun Modifier.stretched(): Modifier = this
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
        // A floating card: it rises whole, its gap from the screen's edge with it.
        .graphicsLayer { translationY = -stretch.value }
}

private fun Modifier.sheetOverdrag(overdrag: SheetOverdrag): Modifier = with(overdrag) { this@sheetOverdrag.stretched() }

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
