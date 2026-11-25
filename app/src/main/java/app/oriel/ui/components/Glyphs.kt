package app.oriel.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/**
 * Line glyphs drawn the way SF Symbols are: a light, even stroke with round caps and joins
 * on a 24-unit grid, filled variants for the "on" state. Material's set reads as Android
 * (the three-dot share, the bar-and-arrow download); these are the iOS shapes, drawn here
 * since SF Symbols can't be shipped.
 */
internal object Glyphs {
    private const val STROKE = 1.7f

    /** [autoMirror] flips the glyph in right-to-left layouts: for shapes that point along the reading direction. */
    private fun glyph(name: String, vararg paths: String, filled: Boolean = false, stroke: Float = STROKE, rotate: Float = 0f, autoMirror: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror).apply {
            group(rotate = rotate, pivotX = 12f, pivotY = 12f) {
                paths.forEach { d ->
                    addPath(
                        PathParser().parsePathString(d).toNodes(),
                        fill = if (filled) SolidColor(Color.Black) else null,
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = stroke,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
        }.build()

    private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}H${x + w - r}A$r $r 0 0 1 ${x + w} ${y + r}V${y + h - r}A$r $r 0 0 1 ${x + w - r} ${y + h}H${x + r}A$r $r 0 0 1 $x ${y + h - r}V${y + r}A$r $r 0 0 1 ${x + r} ${y}Z"
    private fun circ(cx: Float, cy: Float, r: Float) = "M${cx + r} ${cy}A$r $r 0 1 1 ${cx - r} ${cy}A$r $r 0 1 1 ${cx + r} ${cy}Z"

    // The control pane's glyphs: one weight, one grid, so a page of tiles reads as one set.
    val LineVideo = glyph("video", rr(3f, 6f, 18f, 12f, 3f), "M10 9.5V14.5L14.5 12Z")
    val LinePlay = glyph("play", "M7.5 5.2V18.8L18.8 12Z")
    val LinePeople = glyph("people", circ(9f, 9f, 3.2f), "M3 19C3.6 15.6 6 14 9 14S14.4 15.6 15 19", "M16 6.2A3 3 0 0 1 16 11.8", "M18 14.5C19.7 15.2 20.7 16.7 21 19")
    val LineDrop = glyph("drop", "M12 3C16 7.5 18 10.5 18 13.5A6 6 0 0 1 6 13.5C6 10.5 8 7.5 12 3Z")
    val LineLock = glyph("lock", rr(5f, 11f, 14f, 9f, 2.5f), "M8 11V8A4 4 0 0 1 16 8V11")
    val LineGlobe = glyph("globe", circ(12f, 12f, 9f), "M3 12H21", "M12 3C15 6.2 15 17.8 12 21", "M12 3C9 6.2 9 17.8 12 21")
    val LineUpDown = glyph("upDown", "M8 9.5L12 5.5L16 9.5", "M8 14.5L12 18.5L16 14.5")
    val LineInfo = glyph("info", circ(12f, 12f, 9f), "M12 11V16.5", "M12 7.6V7.7")
    val LineHeadphones = glyph("headphones", "M4 15V12A8 8 0 0 1 20 12V15", rr(3f, 14f, 4.5f, 6f, 1.5f), rr(16.5f, 14f, 4.5f, 6f, 1.5f))
    val LinePip = glyph("pip", rr(3f, 5f, 18f, 14f, 3f), rr(12f, 11.5f, 6.5f, 5f, 1f))
    val LineNext = glyph("next", "M6 5.5V18.5L15.5 12Z", "M18.5 5.5V18.5")
    val LineClock = glyph("clock", circ(12f, 12f, 9f), "M12 7V12.5L15.5 14.5")
    val LineCaptions = glyph("captions", rr(3f, 5.5f, 18f, 13f, 3f), "M10.5 10.5A2 2 0 1 0 10.5 13.5", "M16.5 10.5A2 2 0 1 0 16.5 13.5")
    val LineGauge = glyph("gauge", "M4 16A8 8 0 1 1 20 16", "M12 16L16 11")
    val LineShield = glyph("shield", "M12 3L19.5 6V11.5C19.5 16 16.5 19.2 12 21C7.5 19.2 4.500 16 4.500 11.5V6Z")
    val LineSearch = glyph("search", circ(11f, 11f, 6.5f), "M16 16L20.5 20.5")
    val LineMoon = glyph("moon", "M20 14.5A8.5 8.5 0 0 1 9.5 4A8.5 8.5 0 1 0 20 14.5Z")
    val LineWifi = glyph("wifi", "M3 9.5A13 13 0 0 1 21 9.5", "M6 13A8.5 8.5 0 0 1 18 13", "M9 16.500A4.200 4.200 0 0 1 15 16.500", "M12 19.6V19.7")
    val LineTrash = glyph("trash", "M5 7H19", "M10 7V4.500H14V7", "M7 7L8 19.500H16L17 7")
    val LineSync = glyph("sync", "M19 8A8 8 0 0 0 4.500 10", "M5 16A8 8 0 0 0 19.500 14", "M19 4V8H15", "M5 20V16H9")
    val LineSignOut = glyph("signOut", "M14 5H7A2 2 0 0 0 5 7V17A2 2 0 0 0 7 19H14", "M11 12H20", "M17 8.500L20.500 12L17 15.500", autoMirror = true)
    val LineSignIn = glyph("signIn", "M10 5H17A2 2 0 0 1 19 7V17A2 2 0 0 1 17 19H10", "M4 12H13", "M10 8.500L13.500 12L10 15.500", autoMirror = true)
    val LineBug = glyph("bug", rr(8f, 8f, 8f, 11f, 4f), "M9.500 5L11 8", "M14.500 5L13 8", "M4 12H8", "M16 12H20", "M5 18L8 16.500", "M19 18L16 16.500")
    val LineBox = glyph("box", rr(4f, 5f, 16f, 14f, 3f), "M4 10H20")
    val LineSliders = glyph("sliders", "M4 7H11", "M15 7H20", "M4 17H8", "M12 17H20", circ(13f, 7f, 2f), circ(10f, 17f, 2f))
    val LineQueue = glyph("queue", "M4 7H16", "M4 12H16", "M4 17H11", "M15.500 14.500V19.500L19.500 17Z")
    val LineDataSaver = glyph("dataSaver", "M12 4V15", "M7.500 11L12 15.500L16.500 11", "M5 20H19")
    val LineBadge = glyph("badge", "M12 3L14.200 5.200H17.300L17.300 8.300L19.500 10.500L17.300 12.700V15.800H14.200L12 18L9.800 15.800H6.700V12.700L4.500 10.500L6.700 8.300V5.200H9.800Z", "M9.300 10.600L11.200 12.500L14.800 8.900")
    val LineMotion = glyph("motion", "M3 12C5.500 7 8 7 10.500 12S15.500 17 18 12S20.500 9 21 9")
    val LineLayers = glyph("layers", "M12 4L20 8.500L12 13L4 8.500Z", "M4 13L12 17.500L20 13")
    val LineBell = glyph("bell", "M6 16.5V11A6 6 0 0 1 18 11V16.5L19.5 18.5H4.5Z", "M10 20.7A2.2 2.2 0 0 0 14 20.7")
    val LineHistory = glyph("history", "M4.5 12A7.5 7.5 0 1 0 6.9 6.5", "M4.5 4.5V8H8", "M12 8V12.2L14.8 14")
    val LineThumb = glyph("thumb", rr(4f, 10.5f, 4f, 9f, 1f), "M8 10.5L11.4 4.6A1.9 1.9 0 0 1 14.9 5.9L14.3 9.3H18.2A1.8 1.8 0 0 1 19.95 11.5L18.65 17.9A1.9 1.9 0 0 1 16.8 19.5H8")
    val LineDoc = glyph("doc", rr(6f, 3.5f, 12f, 17f, 2.5f), "M9.5 9H14.5", "M9.5 12.5H14.5", "M9.5 16H12.5")
    val LineRefresh = glyph("refresh", "M13 3L5 13.500H11L10 21L18 10.500H12Z")

    // square.and.arrow.up
    val Share = glyph(
        "share",
        "M8.6 9.4H7.2C6 9.4 5 10.4 5 11.6V18.8C5 20 6 21 7.2 21H16.8C18 21 19 20 19 18.8V11.6C19 10.4 18 9.4 16.8 9.4H15.4",
        "M12 14.6V3.2",
        "M8.7 6.4L12 3.1L15.3 6.4",
    )

    private const val CIRCLE = "M21 12A9 9 0 1 1 3 12A9 9 0 1 1 21 12Z"

    // arrow.down.circle
    val Download = glyph("download", CIRCLE, "M12 7.4V16.2", "M8.4 12.7L12 16.3L15.6 12.7")

    private const val BOOKMARK = "M7.2 3.5H16.8C17.7 3.5 18.4 4.2 18.4 5.1V20.4L12 16.2L5.6 20.4V5.1C5.6 4.2 6.3 3.5 7.2 3.5Z"

    // bookmark
    val Save = glyph("save", BOOKMARK)

    // bubble.left
    val Comment = glyph(
        "comment",
        "M12 4C17 4 21 7.3 21 11.4C21 15.5 17 18.8 12 18.8C11 18.8 10 18.7 9.1 18.4L5 20.3L6.1 16.7C4.2 15.4 3 13.5 3 11.4C3 7.3 7 4 12 4Z",
    )

    private const val CUFF = "M4.6 10.2H6.8V20.4H4.6C4 20.4 3.5 19.9 3.5 19.3V11.3C3.5 10.7 4 10.2 4.6 10.2Z"
    private const val HAND = "M9.2 10.2L12.4 4.2C12.8 3.5 13.6 3.2 14.3 3.5C15.2 3.8 15.6 4.8 15.4 5.7L14.6 9.1H18.7C19.9 9.1 20.8 10.2 20.6 11.4L19.1 18.6C18.9 19.6 18.1 20.4 17 20.4H9.2Z"

    // hand.thumbsup, and the same turned over for the thumbs down
    val Like = glyph("like", CUFF, HAND)
    val Liked = glyph("liked", CUFF, HAND, filled = true)
    val Dislike = glyph("dislike", CUFF, HAND, rotate = 180f)
    val Disliked = glyph("disliked", CUFF, HAND, filled = true, rotate = 180f)

    // chevron.right, xmark: a touch heavier, as iOS draws them small
    val Chevron = glyph("chevron", "M9.5 5.5L16 12L9.5 18.5", stroke = 2.1f, autoMirror = true)
    val Xmark = glyph("xmark", "M6.8 6.8L17.2 17.2", "M17.2 6.8L6.8 17.2", stroke = 2.6f)

    // ---- tab bar: line glyphs, and filled ones for the chosen tab ----

    /**
     * A glyph of mixed parts, for the filled tab icons: [solid] parts are filled and stroked,
     * [lines] only stroked, and [cutouts] filled even-odd without a stroke, so the holes in
     * them (a door, a play triangle, a person) show the bar through.
     */
    private fun parts(name: String, solid: List<String> = emptyList(), lines: List<String> = emptyList(), cutouts: List<String> = emptyList()): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            fun add(d: String, fill: Boolean, stroke: Boolean, evenOdd: Boolean = false) = addPath(
                PathParser().parsePathString(d).toNodes(),
                pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                fill = if (fill) SolidColor(Color.Black) else null,
                stroke = if (stroke) SolidColor(Color.Black) else null,
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            solid.forEach { add(it, fill = true, stroke = true) }
            lines.forEach { add(it, fill = false, stroke = true) }
            cutouts.forEach { add(it, fill = true, stroke = false, evenOdd = true) }
        }.build()

    private const val ROOF = "M3 11.2L12 3.6L21 11.2"

    // house, house.fill: the door a notch in the filled one
    val Home = glyph(
        "house", ROOF,
        "M5.4 9.6V19.2C5.4 20 6 20.6 6.8 20.6H17.2C18 20.6 18.6 20 18.6 19.2V9.6",
        "M9.8 20.6V15.6C9.8 15 10.2 14.6 10.8 14.6H13.2C13.8 14.6 14.2 15 14.2 15.6V20.6",
    )
    val HomeFilled = parts(
        "house.fill",
        solid = listOf("M12 4.6L18.6 10.2V19.2C18.6 20 18 20.6 17.2 20.6H14.6V15.8C14.6 15 14 14.4 13.2 14.4H10.8C10 14.4 9.4 15 9.4 15.8V20.6H6.8C6 20.6 5.4 20 5.4 19.2V10.2Z"),
        lines = listOf(ROOF),
    )

    private const val BOLT = "M13.4 2.8L5.4 13.4H11.6L10.6 21.2L18.6 10.6H12.4Z"

    // bolt, bolt.fill
    val Bolt = glyph("bolt", BOLT)
    val BoltFilled = glyph("bolt.fill", BOLT, filled = true)

    private const val STACK_NEAR = "M5.6 6.2H18.4"
    private const val STACK_FAR = "M7.6 3.4H16.4"

    // play.rectangle.stack, and its fill with the play knocked out
    val Subscriptions = parts(
        "play.rectangle.stack",
        solid = listOf("M10.6 12.6V17.4L14.6 15Z"),
        lines = listOf(
            "M5.6 9H18.4C19.5 9 20.4 9.9 20.4 11V19C20.4 20.1 19.5 21 18.4 21H5.6C4.5 21 3.6 20.1 3.6 19V11C3.6 9.9 4.5 9 5.6 9Z",
            STACK_NEAR, STACK_FAR,
        ),
    )
    val SubscriptionsFilled = parts(
        "play.rectangle.stack.fill",
        lines = listOf(STACK_NEAR, STACK_FAR),
        cutouts = listOf(
            "M5.6 8.15H18.4A2.85 2.85 0 0 1 21.25 11V19A2.85 2.85 0 0 1 18.4 21.85H5.6A2.85 2.85 0 0 1 2.75 19V11A2.85 2.85 0 0 1 5.6 8.15Z " +
                "M10.2 12V18L15.2 15Z",
        ),
    )

    // person.crop.circle, and its fill with the person knocked out
    val Person = glyph(
        "person.crop.circle", CIRCLE,
        "M15.3 9.8A3.3 3.3 0 1 1 8.7 9.8A3.3 3.3 0 1 1 15.3 9.8Z",
        "M5.9 18.3C7.3 16.3 9.5 15.2 12 15.2C14.5 15.2 16.7 16.3 18.1 18.3",
    )
    val PersonFilled = parts(
        "person.crop.circle.fill",
        cutouts = listOf(
            "M21.85 12A9.85 9.85 0 1 1 2.15 12A9.85 9.85 0 1 1 21.85 12Z " +
                "M15.4 9.8A3.4 3.4 0 1 1 8.6 9.8A3.4 3.4 0 1 1 15.4 9.8Z " +
                "M6.94 17.4C8.1 15.8 9.9 14.9 12 14.9C14.1 14.9 15.9 15.8 17.06 17.4A7.4 7.4 0 0 1 6.94 17.4Z",
        ),
    )
}
