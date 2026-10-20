package app.oriel.ui.components

import androidx.compose.ui.graphics.Color
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

    private fun glyph(name: String, vararg paths: String, filled: Boolean = false, stroke: Float = STROKE, rotate: Float = 0f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
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

    // bookmark, bookmark.fill
    val Save = glyph("save", BOOKMARK)
    val Saved = glyph("saved", BOOKMARK, filled = true)

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
    val Chevron = glyph("chevron", "M9.5 5.5L16 12L9.5 18.5", stroke = 2.1f)
    val Xmark = glyph("xmark", "M6.8 6.8L17.2 17.2", "M17.2 6.8L6.8 17.2", stroke = 2.6f)

    // pip.exit-ish: a window with the arrow out to full size
    val Expand = glyph("expand", "M14 4H20V10", "M20 4L13.5 10.5", "M10 20H4V14", "M4 20L10.5 13.5")
}
