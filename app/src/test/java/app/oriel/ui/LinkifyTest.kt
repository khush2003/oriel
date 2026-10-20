package app.oriel.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import app.oriel.ui.player.linkify
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkifyTest {
    @Test
    fun timestampsAndLinksBecomeTappable() {
        val seeks = mutableListOf<Long>()
        val opened = mutableListOf<String>()
        val text = linkify("Intro 0:00, roof 10:10, end 1:02:03. Plans: https://example.com/a?b=1 and 2024 isn't a time.", Color.Red, { seeks += it }, { opened += it })
        val links = text.getLinkAnnotations(0, text.length).map { it.item as LinkAnnotation.Clickable }
        assertEquals(listOf("0:00", "10:10", "1:02:03", "https://example.com/a?b=1"), links.map { it.tag })
        links.forEach { it.linkInteractionListener?.onClick(it) }
        assertEquals(listOf(0L, 610_000L, 3_723_000L), seeks)
        assertEquals(listOf("https://example.com/a?b=1"), opened)
        assertEquals("Intro 0:00, roof 10:10, end 1:02:03. Plans: https://example.com/a?b=1 and 2024 isn't a time.", text.text)
    }
}
