package app.oriel.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.oriel.ui.theme.Sp
import app.oriel.ui.theme.blobRectPath
import app.oriel.ui.theme.rubber
import app.oriel.ui.theme.smoothstep
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The liquid panel's flight, as the mock's sheet: from the tapped card's rect and radius, the
 * height spring (Sp.SheetH) leading the width's (Sp.SheetW), the sides and top swelling
 * mid-flight, the card's fill turning into the page's; closed, it shrinks back into the card on
 * Sp.SheetClose. A drag down on it shrinks it toward the card (by up to 42%) as it follows the
 * finger; a drag up rubber-bands its top. Read only in the draw phase.
 */
@androidx.compose.runtime.Stable
internal class PanelMorph {
    val pw = androidx.compose.animation.core.Animatable(0f)
    val ph = androidx.compose.animation.core.Animatable(0f)
    /** The panel's drag, px: down positive, up negative (rubber-banded when drawn). */
    val drag = androidx.compose.animation.core.Animatable(0f)
    /** The speed it was let go at when a drag closed it, px per second. */
    var closeVelocity = 0f
    var shown by mutableStateOf(false)
    var from: androidx.compose.ui.geometry.Rect? = null
    var fromCorner = 0f
    var cardCorner = 0f
    var box: androidx.compose.ui.layout.LayoutCoordinates? = null
    val cards = HashMap<String, androidx.compose.ui.layout.LayoutCoordinates>()
    val path = androidx.compose.ui.graphics.Path()
    var liftMax = 0f

    fun boundsOf(key: String): androidx.compose.ui.geometry.Rect? {
        val b = box?.takeIf { it.isAttached } ?: return null
        val c = cards[key]?.takeIf { it.isAttached } ?: return null
        return b.localBoundingBoxOf(c, clipBounds = false)
    }

    /** How far it's dragged down, which the content follows. */
    fun pull() = drag.value.coerceAtLeast(0f)

    /** How grown it is, 0 to 1, after the shrink a drag down gives it. */
    fun progress(): Float = (minOf(pw.value, ph.value) * shrink()).coerceIn(0f, 1f)

    var height = 1f
    fun shrink() = 1f - 0.42f * smoothstep(0f, height * 1.1f, pull())

    suspend fun run(open: Boolean) = kotlinx.coroutines.coroutineScope {
        if (open) {
            closeVelocity = 0f
            drag.snapTo(0f)
            pw.snapTo(0f)
            ph.snapTo(0f)
            shown = true
            launch { ph.animateTo(1f, Sp.SheetH.unit) }
            launch { pw.animateTo(1f, Sp.SheetW.unit) }
        } else if (shown) {
            // Flicked down, it carries the flick into the shrink, as the mock's close does.
            val v = -closeVelocity.coerceAtLeast(0f) / height * 1.2f
            val a = launch { ph.animateTo(0f, Sp.SheetClose.unit, v) }
            val b = launch { pw.animateTo(0f, Sp.SheetClose.unit, v) }
            a.join()
            b.join()
            shown = false
            drag.snapTo(0f)
            from = null
        }
    }
}

/** Clips to the morphing panel ([PanelMorph]) and fills it with [page], the card's [tint] fading as it grows. */
internal fun Modifier.liquidPanel(m: PanelMorph, page: Color, tint: Color): Modifier = this
    .onPlaced { m.height = it.size.height.toFloat() }
    .drawWithContent {
        val w = size.width
        val h = size.height
        m.liftMax = 140.dp.toPx()
        val raw = m.drag.value
        val pull = raw.coerceAtLeast(0f)
        val lift = if (raw < 0f) rubber(-raw, m.liftMax, 0.5f) else 0f
        val k = m.shrink()
        val pw = m.pw.value * k
        val ph = m.ph.value * k
        val p = minOf(pw, ph)
        val pc = p.coerceIn(0f, 1f)
        // Without a card to grow from it rises out of the bottom edge, swelling the same.
        val src = m.from ?: androidx.compose.ui.geometry.Rect(0f, h, w, h)
        var l = lerp(src.left, 0f, pw)
        var r = lerp(src.right, w, pw)
        var t = lerp(src.top, pull, ph) - lift
        var b = lerp(src.bottom, h + pull, ph)
        if (p < 0f) {
            // Plops back into the card with a squash.
            val q = minOf(0.35f, -p)
            val cx = (l + r) / 2f
            val cy = (t + b) / 2f
            val hw = (r - l) / 2f * (1f + q)
            val hh = (b - t) / 2f * (1f - q * 0.8f)
            l = cx - hw; r = cx + hw; t = cy - hh; b = cy + hh
        }
        val ease = smoothstep(0f, 1f, pc)
        val radTop = minOf(lerp(m.fromCorner, 22.dp.toPx(), ease), (r - l) / 2f, (b - t) / 2f).coerceAtLeast(0f)
        val radBottom = minOf(lerp(m.fromCorner, 0f, ease), (r - l) / 2f, (b - t) / 2f).coerceAtLeast(0f)
        val sw = kotlin.math.sin(pc * kotlin.math.PI.toFloat())
        val vel = kotlin.math.abs(m.ph.velocity + m.pw.velocity) * 0.5f
        val bs = sw * (8f + (vel * 2f).coerceIn(0f, 10f)) * density
        val bt = sw * 18f * density
        blobRectPath(m.path, l, t, r, b, radTop, bs, bt, radBottom)
        clipPath(m.path) {
            drawRect(page, topLeft = Offset(-bs * 2f, t - bt * 2f), size = Size(w + bs * 4f, h - t + bt * 3f + pull))
            this@drawWithContent.drawContent()
            val a = 1f - smoothstep(0.02f, 0.45f, pc)
            if (a > 0f) drawRect(tint, topLeft = Offset(l - bs * 2f, t - bt * 2f), size = Size(r - l + bs * 4f, b - t + bt * 3f), alpha = a)
        }
    }
