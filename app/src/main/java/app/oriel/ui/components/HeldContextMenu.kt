/*
 * The iOS "peek" long-press menu, adapted from BitChord's HeldContextMenu.kt
 * and LongPressOrigin.kt (GPL-3.0). Haze is replaced by a blur of the page's
 * own backdrop layer.
 */
package app.oriel.ui.components

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.oriel.ui.glass.LocalAppBackdrop
import app.oriel.ui.glass.backdrop.drawPlainBackdrop
import app.oriel.ui.glass.backdrop.effects.blur
import app.oriel.ui.glass.isGlassSupported
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.LocalIsDark
import app.oriel.ui.theme.LocalReduceAnimation
import app.oriel.ui.theme.LocalReduceBlur
import app.oriel.ui.theme.PressHighlight
import app.oriel.ui.theme.Sp
import app.oriel.ui.theme.pressHighlight
import app.oriel.ui.theme.goo
import app.oriel.ui.theme.gooEffect
import app.oriel.ui.theme.smoothstep
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Where the goo layer draws the menu's fill, set at placement and read when drawing. */
private class MenuGooGeometry {
    var padX = 0f; var padY = 0f; var menuW = 0f; var menuH = 0f
    var originX = 0f; var originY = 0f; var below = true
    fun set(padX: Float, padY: Float, menuW: Float, menuH: Float, originX: Float, originY: Float, below: Boolean) {
        this.padX = padX; this.padY = padY; this.menuW = menuW; this.menuH = menuH
        this.originX = originX; this.originY = originY; this.below = below
    }
}

/** Where a long-pressed item was, and (a frame later) a picture of it. */
@Stable
class HeldItem internal constructor(val bounds: Rect) {
    var image: ImageBitmap? by mutableStateOf(null)
        internal set
}

/** The last hold, handed from the pressed row to whoever opens the menu, valid for half a second. */
object LongPressOrigin {
    private var held: HeldItem? = null
    private var recordedAt = 0L

    internal fun record(item: HeldItem) {
        held = item
        recordedAt = System.nanoTime()
    }

    fun consume(): HeldItem? {
        val item = held?.takeIf { System.nanoTime() - recordedAt < 500_000_000L }
        held = null
        return item
    }
}

private class HoldCapture {
    var coordinates: LayoutCoordinates? = null
    var pending: HeldItem? = null
    var requests by mutableIntStateOf(0)
    var handled = 0
}

/**
 * A click that, on long press, records where the item is and captures what
 * it draws, so [HeldContextMenu] can lift that very picture off the page.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.longPressMenuClickable(onClick: () -> Unit, onLongClick: (() -> Unit)?, enabled: Boolean = true, onPress: (() -> Unit)? = null): Modifier {
    val capture = remember { HoldCapture() }
    // A press, not a touch: clickable reports one only once the finger has stayed put past the
    // tap timeout, so the start of a scroll doesn't count.
    val interactions = remember { MutableInteractionSource() }
    val pressed by rememberUpdatedState(onPress)
    LaunchedEffect(interactions) { interactions.interactions.collect { if (it is PressInteraction.Press) pressed?.invoke() } }
    val graphicsContext = LocalGraphicsContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val longClick = onLongClick?.let { held ->
        {
            capture.coordinates?.takeIf { it.isAttached }?.let { coordinates ->
                val item = HeldItem(coordinates.boundsInWindow())
                LongPressOrigin.record(item)
                capture.pending = item
                capture.requests++
            }
            haptics.play(Haptic.Expand)
            held()
        }
    }
    val rowActions = LocalRowActions.current
    // The squish as a modifier of its own, so it has the finger from its down (see pressHighlight).
    val indication = LocalIndication.current
    val squish = indication as? PressHighlight
    return this
        .onPlaced { capture.coordinates = it }
        .then(if (squish != null && enabled) Modifier.pressHighlight(interactions, squish) else Modifier)
        .combinedClickable(interactionSource = interactions, indication = if (squish != null) null else indication, enabled = enabled, onClick = onClick, onLongClick = longClick)
        .then(if (rowActions.isEmpty()) Modifier else Modifier.semantics { customActions = rowActions })
        .drawWithContent {
            drawContent()
            val request = capture.requests
            if (request == capture.handled) return@drawWithContent
            capture.handled = request
            val item = capture.pending ?: return@drawWithContent
            capture.pending = null
            val layer = graphicsContext.createGraphicsLayer()
            layer.record { this@drawWithContent.drawContent() }
            scope.launch {
                try {
                    // A zero-size or not-yet-laid-out card can fail to capture; the menu then opens without the lifted copy.
                    item.image = try {
                        layer.toImageBitmap()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                } finally {
                    graphicsContext.releaseGraphicsLayer(layer)
                }
            }
        }
}

/**
 * The held item lifts off a blurred, dimmed page onto a plate, scaled to fit, on the mock's lift
 * spring (kicked at 3, so it pops past and settles); its menu oozes out of the plate's edge: its
 * width and height on their own springs (Sp.MenuX, Sp.MenuY), joined to the plate for a moment by
 * a goo bridge that swells and pinches off, its rows fading in after. Closes quickly, overdamped.
 */
@Composable
fun <T : Any> HeldContextMenu(
    item: T?,
    held: HeldItem?,
    onDismiss: () -> Unit,
    onPreviewClick: ((T) -> Unit)?,
    actions: @Composable ColumnScope.(T) -> Unit,
) {
    val requested = if (item != null && held != null) item to held else null
    var last by remember { mutableStateOf<Pair<T, HeldItem>?>(null) }
    if (requested != null && requested != last) SideEffect { last = requested }
    val shown = requested ?: last ?: return
    val (menuItem, heldItem) = shown
    val open = requested != null

    val context = LocalContext.current
    val systemAnimationsOff = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val reduceMotion = LocalReduceAnimation.current || systemAnimationsOff
    val reduceBlur = LocalReduceBlur.current || !isGlassSupported()

    // The mock's three: the plate's lift, and the menu's width and height.
    val progress = remember { Animatable(0f) }
    val mx = remember { Animatable(0f) }
    val my = remember { Animatable(0f) }
    LaunchedEffect(open) {
        if (open) {
            if (reduceMotion) {
                launch { mx.snapTo(1f) }
                launch { my.snapTo(1f) }
                progress.animateTo(1f, tween(140, easing = LinearEasing))
            } else {
                launch { mx.animateTo(1f, Sp.MenuX.unit) }
                launch { my.animateTo(1f, Sp.MenuY.unit) }
                progress.animateTo(1f, Sp.Lift.unit, initialVelocity = if (progress.value == 0f) 3f else progress.velocity)
            }
        } else {
            // Springs, not tweens: a menu reopened mid-close carries on from where it is.
            if (reduceMotion) {
                launch { mx.snapTo(0f) }
                launch { my.snapTo(0f) }
                progress.animateTo(0f, tween(110, easing = FastOutSlowInEasing))
            } else {
                launch { mx.animateTo(0f, Sp.MenuClose.unit) }
                launch { my.animateTo(0f, Sp.MenuClose.unit) }
                progress.animateTo(0f, Sp.LiftClose.unit)
            }
            last = null
        }
    }
    val gooDensity = LocalDensity.current
    val gooFx = remember(gooDensity) { if (reduceMotion) null else gooEffect(with(gooDensity) { 6.dp.toPx() }) }
    val gooGeometry = remember { MenuGooGeometry() }
    BackHandler(enabled = open, onBack = onDismiss)

    val dark = LocalIsDark.current
    val plate = (if (dark) Color.Black else Color.White).copy(alpha = if (reduceBlur) 0.85f else 0.5f)
    val menuFill = if (dark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (reduceBlur) 0.98f else 0.86f) else Color.White.copy(alpha = if (reduceBlur) 0.98f else 0.86f)
    val hairline = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
    val scrimColor = when {
        reduceBlur && dark -> Color.Black.copy(alpha = 0.62f)
        reduceBlur -> Color.Black.copy(alpha = 0.36f)
        dark -> Color.Black.copy(alpha = 0.30f)
        else -> Color.Black.copy(alpha = 0.10f)
    }
    val cardShape = RoundedCornerShape(20.dp)
    val menuShape = RoundedCornerShape(16.dp)
    val platePadding = 8.dp
    val insets = WindowInsets.safeDrawing
    var overlayOffset by remember { mutableStateOf(Offset.Zero) }
    val backdrop = LocalAppBackdrop.current
    val blurPx = with(LocalDensity.current) { 24.dp.toPx() } * 0.33f

    Layout(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOffset = it.positionInWindow() }
            .semantics { paneTitle = "Actions"; isTraversalGroup = true }
            .then(if (open) Modifier else Modifier.pointerInput(Unit) { swallowAll() }),
        content = {
            Spacer(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                    .then(
                        if (!reduceBlur && backdrop != null) {
                            Modifier.drawPlainBackdrop(backdrop = backdrop, shape = { RectangleShape }, effects = { blur(blurPx * progress.value.coerceIn(0.05f, 1f)) })
                        } else Modifier,
                    )
                    .background(scrimColor)
                    .clickable(remember { MutableInteractionSource() }, indication = null, enabled = open, onClickLabel = "Dismiss", onClick = onDismiss),
            )
            Box(
                Modifier
                    .then(if (reduceMotion) Modifier else Modifier.shadow(20.dp, cardShape, clip = false))
                    .pointerInput(Unit) {}
                    .clip(cardShape)
                    .background(plate)
                    .border(0.5.dp, hairline, cardShape)
                    .then(if (onPreviewClick != null) Modifier.clickable(enabled = open) { onPreviewClick(menuItem) } else Modifier)
                    .padding(platePadding),
            ) {
                heldItem.image?.let { Image(it, null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
            }
            Column(
                Modifier
                    .pointerInput(Unit) {}
                    .clip(menuShape)
                    // With the goo the menu's fill is the goo layer's, so it can ooze; without, its own.
                    .then(if (gooFx == null) Modifier.background(menuFill) else Modifier)
                    .border(0.5.dp, hairline, menuShape)
                    .verticalScroll(rememberScrollState())
                    .graphicsLayer {
                        // The rows come in once the menu has mostly grown, dropping the last 8 dp.
                        val a = smoothstep(0.45f, 0.85f, minOf(mx.value, my.value))
                        if (!reduceMotion) {
                            alpha = a
                            translationY = (1f - a) * -8.dp.toPx()
                        }
                    },
            ) { actions(menuItem) }
            // The menu's fill and the bridge to the plate, fused (Android 12 and up).
            Spacer(
                Modifier
                    .graphicsLayer { alpha = menuFill.alpha }
                    .goo(gooFx)
                    .drawBehind {
                        val g = gooGeometry
                        val fill = menuFill.copy(alpha = 1f)
                        val m = minOf(mx.value, my.value)
                        val sx = mx.value.coerceAtLeast(0f)
                        val sy = my.value.coerceAtLeast(0f)
                        // The fill grows out of the point 36 dp in at the plate's edge.
                        val left = g.padX + g.originX * (1f - sx)
                        val top = g.padY + g.originY * (1f - sy)
                        val w = g.menuW * sx
                        val h = g.menuH * sy
                        if (w > 0f && h > 0f) {
                            val rx = 16.dp.toPx() * sx
                            val ry = 16.dp.toPx() * sy
                            drawRoundRect(fill, Offset(left, top), Size(w, h), CornerRadius(rx, ry))
                        }
                        // The bridge swells while the menu starts and pinches off as it fills out.
                        val rb = smoothstep(0f, 0.18f, m) * (1f - smoothstep(0.5f, 0.85f, m))
                        if (rb > 0f) {
                            val bx = g.padX + g.originX
                            val by = g.padY + if (g.below) -20.dp.toPx() else g.menuH + 20.dp.toPx()
                            val r = 16.dp.toPx()
                            drawOval(fill, Offset(bx - r * rb, by - r * rb * 1.3f), Size(2 * r * rb, 2 * r * rb * 1.3f))
                        }
                    },
            )
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val margin = 12.dp.roundToPx()
        val gap = 10.dp.roundToPx()
        val pad = platePadding.roundToPx()
        val top = insets.getTop(this) + 8.dp.roundToPx()
        val bottom = height - insets.getBottom(this) - 8.dp.roundToPx()
        val backdropPlaceable = measurables[0].measure(Constraints.fixed(width, height))
        val itemBounds = heldItem.bounds.translate(-overlayOffset)
        val plateW = (itemBounds.width.roundToInt() + pad * 2).coerceAtLeast(1)
        val plateH = (itemBounds.height.roundToInt() + pad * 2).coerceAtLeast(1)
        val card = measurables[1].measure(Constraints.fixed(plateW, plateH))
        // A tall card (a big video) shrinks too, so the menu always has room.
        val liftScale = minOf((width - margin * 2f) / plateW, (bottom - top) * 0.45f / plateH, 1f)
        val visualW = plateW * liftScale
        val visualH = plateH * liftScale
        val menuWidth = (width - margin * 2).coerceAtMost(280.dp.roundToPx()).coerceAtLeast(0)
        val menuMaxHeight = (bottom - top - visualH.roundToInt() - gap).coerceAtLeast(0)
        val menu = measurables[2].measure(Constraints(minWidth = menuWidth, maxWidth = menuWidth, maxHeight = menuMaxHeight))
        val gooPadX = 30.dp.roundToPx()
        val gooPadY = 46.dp.roundToPx()
        val gooLayer = measurables[3].measure(Constraints.fixed(menu.width + gooPadX * 2, menu.height + gooPadY * 2))
        
        val visualLeft = (itemBounds.center.x - visualW / 2f).coerceIn(margin.toFloat(), (width - margin - visualW).coerceAtLeast(margin.toFloat()))
        var visualTop = (itemBounds.center.y - visualH / 2f).coerceIn(top.toFloat(), (bottom - visualH).coerceAtLeast(top.toFloat()))
        val menuBelow: Boolean
        when {
            visualTop + visualH + gap + menu.height <= bottom -> menuBelow = true
            visualTop - gap - menu.height >= top -> menuBelow = false
            else -> {
                menuBelow = true
                visualTop = (bottom - menu.height - gap - visualH).coerceAtLeast(top.toFloat())
            }
        }
        val visualCenterX = visualLeft + visualW / 2f
        val visualCenterY = visualTop + visualH / 2f
        val alignEnd = visualCenterX > width / 2f && visualW < width * 0.8f
        val menuX = (if (alignEnd) visualLeft + visualW - menuWidth else visualLeft).roundToInt().coerceIn(margin, (width - margin - menuWidth).coerceAtLeast(margin))
        val menuY = if (menuBelow) (visualTop + visualH + gap).roundToInt() else (visualTop - gap - menu.height).roundToInt()
        val cardX = (visualCenterX - plateW / 2f).roundToInt()
        val cardY = (visualCenterY - plateH / 2f).roundToInt()
        val fromDx = itemBounds.center.x - visualCenterX
        val fromDy = itemBounds.center.y - visualCenterY
        layout(width, height) {
            backdropPlaceable.place(0, 0)
            card.placeWithLayer(cardX, cardY) {
                val p = progress.value
                if (reduceMotion) {
                    scaleX = liftScale; scaleY = liftScale; alpha = p.coerceIn(0f, 1f)
                } else {
                    // Lifts 3.5% past its plate size, from its bottom edge, with the spring's pop.
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    val scale = lerp(1f, liftScale, p.coerceIn(0f, 1f)) * (1f + 0.035f * p)
                    scaleX = scale; scaleY = scale
                    translationX = lerp(fromDx, 0f, p)
                    translationY = lerp(fromDy, 0f, p)
                    alpha = (p * 3f).coerceIn(0f, 1f)
                }
            }
            // The mock's menu grows from 36 dp in from its near corner, at the plate's edge.
            val originX = 36.dp.toPx()
            val ox = if (alignEnd) menu.width - originX else originX
            val oy = if (menuBelow) 0f else menu.height.toFloat()
            gooGeometry.set(gooPadX.toFloat(), gooPadY.toFloat(), menu.width.toFloat(), menu.height.toFloat(), ox, oy, menuBelow)
            // Under the menu's rows: its fill.
            if (gooFx != null) gooLayer.placeWithLayer(menuX - gooPadX, menuY - gooPadY) {
                translationX = lerp(fromDx, 0f, progress.value)
                translationY = lerp(fromDy, 0f, progress.value)
            }
            menu.placeWithLayer(menuX, menuY) {
                val p = progress.value
                if (reduceMotion) {
                    alpha = p.coerceIn(0f, 1f)
                } else {
                    if (gooFx == null) {
                        // No goo to grow the fill: the menu itself scales out of its corner.
                        transformOrigin = TransformOrigin(ox / menu.width.coerceAtLeast(1), oy / menu.height.coerceAtLeast(1))
                        scaleX = mx.value.coerceAtLeast(0f)
                        scaleY = my.value.coerceAtLeast(0f)
                    }
                    translationX = lerp(fromDx, 0f, p)
                    translationY = lerp(fromDy, 0f, p)
                }
            }
        }
    }
}

private suspend fun PointerInputScope.swallowAll() {
    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
}

/** A menu row: label first, icon trailing, as iOS draws them. */
@Composable
fun MenuRow(label: String, icon: ImageVector, onClick: () -> Unit, destructive: Boolean = false, enabled: Boolean = true) {
    val haptics = rememberHaptics()
    val color = if (destructive) DestructiveRed else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clickable(enabled = enabled) {
                haptics.play(Haptic.Tap)
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 11.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
    }
}

/** The band between groups of menu rows. */
@Composable
fun MenuGroupBand() {
    Spacer(Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
}
