package app.oriel.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.oriel.ui.glass.glassEdgeColor
import app.oriel.ui.theme.LocalLiquidGlassEnabled
import app.oriel.ui.glass.paneGlass
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import app.oriel.ui.haptics.Haptic
import app.oriel.ui.haptics.rememberHaptics
import app.oriel.ui.theme.DestructiveRedDark
import app.oriel.ui.theme.DestructiveRedLight
import app.oriel.ui.theme.GlassSpring
import app.oriel.ui.theme.LocalIsDark
import app.oriel.ui.theme.LocalReduceBlur
import app.oriel.ui.theme.TilePress
import app.oriel.ui.theme.accent
import app.oriel.ui.theme.pressHighlight
import app.oriel.ui.glass.GLASS_EDGE_WIDTH
import app.oriel.ui.theme.motion
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp

// Pages made of glass panes: Settings is a control pane, not a list. A pane can't sample the page
// it's part of (the page is the backdrop the bars bend), so its glass is drawn: a translucent
// sheet with a lit rim over the page's wash. Under Reduce transparency it's a solid surface.

// One corner for panes and tiles, and the one that nests in it (22 less the 8 a track sits in),
// so nothing reads as a pill beside a card.
val PaneShape = RoundedCornerShape(28.dp)
val TileShape = PaneShape
private val InnerShape = RoundedCornerShape(18.dp)
private val PANE_GAP = 12.dp
private val DISC = 36.dp
private val PANE_INSET = 16.dp

/**
 * The colour behind a page of panes: the accent as a glow from the top, with its neighbour on the
 * colour wheel beside it. One family of colour, gone by half way down, so the controls sit on calm.
 */
fun DrawScope.drawWash(accent: Color, dark: Boolean, strength: Float) {
    if (strength <= 0f) return
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(accent.toArgb(), it) }
    // A grey accent has no hue to turn; it washes as a cool grey.
    val tinted = hsv[1] > 0.12f
    fun turned(by: Float) = if (tinted) Color.hsv((hsv[0] + by + 360f) % 360f, (hsv[1] * 1.05f).coerceAtMost(1f), 1f) else Color(0xFF8E9BB8)
    fun blob(color: Color, x: Float, y: Float, radius: Float, alpha: Float) = drawRect(
        Brush.radialGradient(listOf(color.copy(alpha = (alpha * strength).coerceAtMost(0.9f)), Color.Transparent), center = Offset(size.width * x, size.height * y), radius = size.width * radius),
    )
    blob(turned(-10f), 0.08f, -0.04f, 1.0f, if (dark) 0.28f else 0.34f)
    blob(turned(-52f), 1.02f, 0.06f, 0.95f, if (dark) 0.24f else 0.28f)
    // Fainter down the page: glass with nothing behind it is only grey.
    blob(turned(-34f), 1.0f, 0.58f, 0.9f, if (dark) 0.11f else 0.13f)
    blob(turned(-4f), -0.05f, 0.92f, 0.95f, if (dark) 0.10f else 0.12f)
}

/** The page under a wash: on light, a step off white, so the white glass has something to stand on. */
@Composable
fun washPageColor(): Color = if (LocalIsDark.current) MaterialTheme.colorScheme.background else Color(0xFFEDEDF2)

/** Drawn glass: a thin sheet, brighter at its top edge where light catches it. */
@Composable
fun Modifier.glassPane(shape: Shape = PaneShape): Modifier {
    val dark = LocalIsDark.current
    val solid = LocalReduceBlur.current
    val surface = MaterialTheme.colorScheme.surfaceVariant
    // The account card's material: an even veil and the hairline every floating surface has. A
    // lit rim a pixel wide around each pane drew a page of boxes; the fill alone parts them.
    val top = if (solid) surface else if (dark) Color.White.copy(alpha = 0.085f) else Color.White.copy(alpha = 0.72f)
    val bottom = if (solid) surface else if (dark) Color.White.copy(alpha = 0.065f) else Color.White.copy(alpha = 0.64f)
    // With liquid glass, the bars' rim light and shadow; frosted, a plain veil and hairline, as the bars are then.
    val lit = if (solid || shape !is CornerBasedShape) null else paneGlass(shape, if (dark) Color.White.copy(alpha = 0.065f) else Color.White.copy(alpha = 0.52f))
    return lit ?: clip(shape)
        .drawBehind { drawRect(Brush.verticalGradient(listOf(top, bottom))) }
        .then(if (solid) Modifier else Modifier.gloss(shape))
}

/** Whether what's composed sits on a glass card (a sheet, an alert): grouped rows there take the glass finish. */
val LocalOnGlass = staticCompositionLocalOf { false }

/**
 * The light on a glass edge, from the upper left: bright at that corner, nearly gone along the
 * sides, and caught again at the far one, as the tab bar's rim is. An even outline reads as a
 * box; this reads as a surface with light on it.
 */
@Composable
internal fun glossRim(): List<Color> =
    if (LocalIsDark.current) listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.20f))
    else listOf(Color.White, Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.9f))

/** The glass finish over a surface already filled and clipped to [shape]: a sheen across its top and the lit rim. */
@Composable
fun Modifier.gloss(shape: Shape): Modifier {
    // Frosted glass has no rim light anywhere in the app: the hairline alone.
    if (!LocalLiquidGlassEnabled.current) return border(GLASS_EDGE_WIDTH, glassEdgeColor(), shape)
    val rim = glossRim()
    val sheen = Color.White.copy(alpha = if (LocalIsDark.current) 0.055f else 0.30f)
    return drawWithContent {
        drawRect(Brush.verticalGradient(listOf(sheen, Color.Transparent), endY = size.height * 0.6f))
        drawContent()
        // Half the stroke falls outside the clip, so the rim that shows is half this wide.
        drawOutline(shape.createOutline(size, layoutDirection, this), Brush.linearGradient(rim, Offset.Zero, Offset(size.width, size.height)), style = Stroke(2.dp.toPx()))
    }
}

// The mockup's type, set here rather than taken from the app's scale: a tile's label is smaller
// and heavier than body text, and its state smaller again.
@Composable internal fun paneType(size: Float, weight: Int, line: Float = size * 1.25f): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(fontSize = size.sp, fontWeight = FontWeight(weight), lineHeight = line.sp, letterSpacing = (-0.1).sp)

@Composable internal fun dim(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (LocalIsDark.current) 0.58f else 0.55f)

@Composable private fun trackColor(): Color = if (LocalIsDark.current) Color.Black.copy(alpha = 0.30f) else Color.Black.copy(alpha = 0.08f)

/**
 * A pane of rows. Hairlines part them, from where their text starts, as a list's do; [dividers]
 * off is for a pane holding one control rather than rows, [glyphs] off for rows that lead with
 * their text.
 */
@Composable
fun GlassPane(modifier: Modifier = Modifier, dividers: Boolean = true, glyphs: Boolean = true, content: @Composable () -> Unit) {
    var joins by remember { mutableStateOf(emptyList<Int>()) }
    val line = MaterialTheme.colorScheme.onSurface.copy(alpha = if (LocalIsDark.current) 0.10f else 0.08f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Layout(
        content,
        modifier.padding(horizontal = PAGE_GUTTER).padding(bottom = PANE_GAP).fillMaxWidth().glassPane()
            .drawWithContent {
                drawContent()
                if (!dividers) return@drawWithContent
                val from = (if (glyphs) DIVIDER_INSET else PANE_INSET).toPx()
                val to = size.width - PANE_INSET.toPx()
                joins.forEach { y -> drawLine(line, Offset(if (rtl) size.width - from else from, y.toFloat()), Offset(if (rtl) size.width - to else to, y.toFloat()), strokeWidth = 0.75.dp.toPx()) }
            }
            .padding(vertical = 3.dp),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val pad = 3.dp.roundToPx()
        var y = 0
        val at = placeables.dropLast(1).map { y += it.height; y + pad }
        if (at != joins) joins = at
        layout(constraints.maxWidth, placeables.sumOf { it.height }) {
            var top = 0
            placeables.forEach { it.placeRelative(0, top); top += it.height }
        }
    }
}

// Where a row's title starts: its inset, its glyph and the gap after it.
private val DIVIDER_INSET = PANE_INSET + DISC + 13.dp

/** What a group of controls is, with what they do behind an info button rather than spelled out under them. */
@Composable
fun PaneHeader(text: String, info: String? = null) {
    Row(Modifier.padding(start = PAGE_GUTTER + 6.dp, end = PAGE_GUTTER, top = 10.dp).fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = paneType(13f, 600), color = dim())
        if (info != null) InfoTip(info, Alignment.TopStart)
    }
}

/** An "i" that opens what it explains as a small glass bubble: pages stay controls, not paragraphs. */
@Composable
fun InfoTip(text: String, side: Alignment = Alignment.TopEnd) {
    var open by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val drop = with(LocalDensity.current) { 30.dp.roundToPx() }
    val ink = MaterialTheme.colorScheme.onSurface
    val soft = dim()
    // "Name: what it does" on a line of its own reads as a list of the controls, not a paragraph.
    val body = remember(text, ink, soft) {
        buildAnnotatedString {
            text.split('\n').forEachIndexed { i, para ->
                if (i > 0) withStyle(SpanStyle(fontSize = 7.sp)) { append("\n \n") }
                val colon = para.indexOf(": ")
                if (colon in 1..28) {
                    withStyle(SpanStyle(color = ink, fontWeight = FontWeight(600))) { append(para.substring(0, colon)) }
                    append("\n")
                    withStyle(SpanStyle(color = soft)) { append(para.substring(colon + 2).replaceFirstChar { it.uppercase() }) }
                } else withStyle(SpanStyle(color = ink)) { append(para) }
            }
        }
    }
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(50)).clickable(onClickLabel = "What this does", role = Role.Button) { haptics.play(Haptic.Tap); open = true },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Glyphs.LineInfo, "More information", tint = dim().copy(alpha = 0.42f), modifier = Modifier.size(16.dp))
        if (open) Popup(remember(side, drop) { BelowAnchor(side == Alignment.TopStart, drop) }, onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            Bubble(Modifier.width(284.dp), origin = if (side == Alignment.TopStart) 0f else 1f) {
                Text(body, style = paneType(13.5f, 400, 19f).copy(lineBreak = LineBreak.Paragraph), modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
            }
        }
    }
}

/** Under what opened it, from its start or its end, and never past the screen's edge. */
private class BelowAnchor(private val fromStart: Boolean, private val drop: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val left = fromStart == (layoutDirection == LayoutDirection.Ltr)
        val x = if (left) anchorBounds.left else anchorBounds.right - popupContentSize.width
        return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)), anchorBounds.top + drop)
    }
}

/** What a menu or a tip is drawn on. A popup is its own window with no page behind it to bend, so it's solid. */
@Composable
private fun Bubble(modifier: Modifier = Modifier, radius: Dp = 22.dp, origin: Float = 1f, content: @Composable ColumnScope.() -> Unit) {
    val dark = LocalIsDark.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val grow by animateFloatAsState(if (shown) 1f else 0f, motion(GlassSpring), label = "bubble")
    val shape = RoundedCornerShape(radius)
    val fill = if (dark) Brush.verticalGradient(listOf(Color(0xFF45414D), Color(0xFF29272F))) else Brush.verticalGradient(listOf(Color.White, Color(0xFFF3F3F7)))
    Column(
        Modifier.padding(8.dp)
            .graphicsLayer { val s = 0.7f + 0.3f * grow; scaleX = s; scaleY = s; alpha = (grow * 2f).coerceIn(0f, 1f); transformOrigin = TransformOrigin(origin, 0f) }
            .shadow(16.dp, shape).then(modifier).clip(shape).drawBehind { drawRect(fill) }.gloss(shape),
        content = content,
    )
}

/** A row in a pane: a line glyph, a title over an optional detail, and what's at its end. */
@Composable
fun GlassRow(
    title: String,
    icon: ImageVector? = null,
    subtitle: String? = null,
    value: String? = null,
    chevron: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    /** With [onChange], the row is a switch: in a list, that's where a switch goes. */
    checked: Boolean = false,
    onChange: ((Boolean) -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    val ink = if (destructive) (if (LocalIsDark.current) DestructiveRedDark else DestructiveRedLight) else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .then(
                when {
                    onChange != null -> Modifier.toggleable(checked, enabled = enabled, role = Role.Switch) { haptics.play(if (it) Haptic.ToggleOn else Haptic.ToggleOff); onChange(it) }
                    onClick != null -> Modifier.clickable(enabled = enabled) { haptics.play(Haptic.Tap); onClick() }
                    else -> Modifier
                },
            )
            .padding(horizontal = PANE_INSET, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(13.dp)) } else if (icon != null) { GlyphDisc(icon, DISC, ink); Spacer(Modifier.width(13.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = paneType(16f, 500), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = paneType(13f, 400), color = dim(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) { Spacer(Modifier.width(10.dp)); Text(value, style = paneType(14f, 400), color = dim(), maxLines = 1) }
        if (trailing != null) { Spacer(Modifier.width(10.dp)); trailing() }
        if (onChange != null) { Spacer(Modifier.width(10.dp)); TileSwitch(checked) }
        if (chevron) { Spacer(Modifier.width(6.dp)); Icon(OrielIcons.Chevron, null, tint = dim(), modifier = Modifier.size(16.dp)) }
    }
}

/** A glyph in a soft disc, as the account card's actions wear theirs: what every row and tile leads with. */
@Composable
fun GlyphDisc(icon: ImageVector, size: Dp = 40.dp, tint: Color = MaterialTheme.colorScheme.onSurface) {
    val disc = MaterialTheme.colorScheme.onSurface.copy(alpha = if (LocalIsDark.current) 0.09f else 0.06f)
    Box(Modifier.size(size).clip(RoundedCornerShape(50)).drawBehind { drawRect(disc) }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.54f))
    }
}

/** Tiles side by side, each given `Modifier.weight(1f)` by its caller; one alone runs the full width. */
@Composable
fun TileRow(content: @Composable RowScope.() -> Unit) {
    // As tall as its tallest tile, so a title that wraps doesn't leave its neighbour shorter.
    Row(Modifier.padding(horizontal = PAGE_GUTTER).padding(bottom = PANE_GAP).fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(PANE_GAP), verticalAlignment = Alignment.Top, content = content)
}

/** A way into a page of settings: its glyph, its name and what it's set to. */
@Composable
fun CategoryTile(title: String, detail: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val source = remember { MutableInteractionSource() }
    Column(
        modifier.heightIn(min = 112.dp).fillMaxHeight().pressHighlight(source, TilePress).glassPane(TileShape)
            .clickable(source, indication = null, role = Role.Button) { haptics.play(Haptic.Tap); onClick() }.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            GlyphDisc(icon, DISC)
            Spacer(Modifier.weight(1f))
            Icon(OrielIcons.Chevron, null, tint = dim().copy(alpha = 0.4f), modifier = Modifier.padding(top = 4.dp, end = 2.dp).size(14.dp))
        }
        Column(Modifier.padding(top = 12.dp)) {
            Text(title, style = paneType(16f, 500), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = paneType(12.5f, 400), color = dim(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A setting that's on or off, as a tile built like [CategoryTile]: its glyph and a switch above,
 * its name below. The switch alone says the state, so a page of them stays one calm surface.
 * [wide] is the same control alone on a line, laid out as a row.
 */
@Composable
fun ToggleTile(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
    wide: Boolean = false,
) {
    val haptics = rememberHaptics()
    val source = remember { MutableInteractionSource() }
    val ink = MaterialTheme.colorScheme.onSurface
    val frame = modifier.graphicsLayer { alpha = if (enabled) 1f else 0.45f }.pressHighlight(source, TilePress).glassPane(TileShape)
        .toggleable(checked, source, indication = null, enabled = enabled, role = Role.Switch) { haptics.play(if (it) Haptic.ToggleOn else Haptic.ToggleOff); onChange(it) }
    if (wide) {
        Row(frame.heightIn(min = 64.dp).padding(start = PANE_INSET, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphDisc(icon, DISC)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = paneType(16f, 500), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) Text(detail, style = paneType(13f, 400), color = dim(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            TileSwitch(checked)
        }
    } else {
        Column(frame.heightIn(min = 112.dp).fillMaxHeight().padding(start = 16.dp, end = 14.dp, top = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlyphDisc(icon, DISC)
                Spacer(Modifier.weight(1f))
                TileSwitch(checked)
            }
            Column(Modifier.padding(top = 12.dp)) {
                Text(title, style = paneType(16f, 500, 19f), color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail != null) Text(detail, style = paneType(12.5f, 400), color = dim(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The app's switch at a tile's scale. Drawing only: the tile takes the tap. */
@Composable
private fun TileSwitch(checked: Boolean) {
    Box(Modifier.size(44.dp, 27.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.requiredSize(51.dp, 31.dp).graphicsLayer { scaleX = 0.86f; scaleY = 0.86f }) { IosSwitch(checked) }
    }
}

/** A short choice made in place: a titled pane, its options a track with a glass thumb that slides to the one chosen. */
@Composable
fun <T> SegmentTile(title: String, options: List<T>, selected: T, label: (T) -> String, info: String? = null, onSelect: (T) -> Unit) {
    val haptics = rememberHaptics()
    val dark = LocalIsDark.current
    Column(Modifier.padding(horizontal = PAGE_GUTTER).padding(bottom = PANE_GAP).fillMaxWidth().glassPane().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
        Row(Modifier.heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = paneType(16f, 500), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (info != null) InfoTip(info)
        }
        Spacer(Modifier.height(2.dp))
        val track = trackColor()
        val thumb = if (dark) Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.15f))) else Brush.verticalGradient(listOf(Color.White, Color.White))
        val thumbRim = if (dark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.04f)
        val pos by animateFloatAsState(options.indexOf(selected).coerceAtLeast(0).toFloat(), motion(GlassSpring), label = "segment")
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Row(
            Modifier.fillMaxWidth().clip(InnerShape).drawBehind {
                drawRect(track)
                val pad = 3.dp.toPx()
                val w = (size.width - pad * 2) / options.size
                val at = Offset(pad + w * (if (rtl) options.size - 1 - pos else pos), pad)
                val box = Size(w, size.height - pad * 2)
                val r = CornerRadius(15.dp.toPx())
                drawRoundRect(thumb, at, box, r)
                drawRoundRect(thumbRim, at, box, r, style = Stroke(1.dp.toPx()))
            }.padding(3.dp),
        ) {
            options.forEach { option ->
                val on = option == selected
                val ink by animateFloatAsState(if (on) 1f else 0.62f, motion(GlassSpring), label = "segmentInk")
                Box(
                    Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(15.dp)).selectable(on, role = Role.RadioButton) { haptics.play(Haptic.Select); onSelect(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label(option), style = paneType(13.5f, 600), color = MaterialTheme.colorScheme.onSurface.copy(alpha = ink.coerceIn(0f, 1f)), maxLines = 1)
                }
            }
        }
    }
}

/** What a row is set to: the value and the pair of chevrons that say it opens a choice. */
@Composable
fun ValuePill(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = paneType(15.5f, 400), color = dim(), maxLines = 1)
        Spacer(Modifier.width(5.dp))
        Icon(Glyphs.LineUpDown, null, tint = dim().copy(alpha = 0.42f), modifier = Modifier.size(16.dp))
    }
}

/**
 * A row whose choices open as a glass menu grown from its pill, the current one ticked: a
 * handful of options don't need a sheet. [onLong] takes over for lists too long for a menu.
 */
@Composable
fun <T> PickerRow(
    title: String,
    icon: ImageVector,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    detail: (T) -> String? = { null },
    onLong: (() -> Unit)? = null,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val gap = with(LocalDensity.current) { 30.dp.roundToPx() }
    GlassRow(title, icon, onClick = { if (onLong != null) onLong() else open = true }) {
        Box {
            ValuePill(label(selected))
            if (open) Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, gap), onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                val haptics = rememberHaptics()
                Bubble(Modifier.width(224.dp).heightIn(max = 420.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(6.dp)) {
                        options.forEach { option ->
                            val on = option == selected
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(16.dp))
                                    .then(if (on) Modifier.drawBehind { drawRect(Color.Gray.copy(alpha = 0.22f)) } else Modifier)
                                    .selectable(on, role = Role.RadioButton) { haptics.play(Haptic.Select); open = false; onSelect(option) }
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(label(option), style = paneType(16f, 500), color = MaterialTheme.colorScheme.onSurface)
                                    detail(option)?.let { Text(it, style = paneType(12.5f, 400), color = dim()) }
                                }
                                if (on) Icon(OrielIcons.Check, null, tint = accent(), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A choice in a sheet over a glass page: the row, its detail, and a tick in the accent when it's the one. */
@Composable
fun GlassChoiceRow(label: String, selected: Boolean, detail: String? = null, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(InnerShape).selectable(selected, role = Role.RadioButton) { haptics.play(Haptic.Select); onClick() }.padding(horizontal = PANE_INSET, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = paneType(16f, 500), color = MaterialTheme.colorScheme.onSurface)
            if (detail != null) Text(detail, style = paneType(13f, 400), color = dim())
        }
        if (selected) Icon(OrielIcons.Check, null, tint = accent(), modifier = Modifier.size(18.dp))
    }
}
