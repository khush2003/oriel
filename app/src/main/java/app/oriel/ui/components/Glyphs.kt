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
