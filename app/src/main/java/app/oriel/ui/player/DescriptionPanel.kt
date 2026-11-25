package app.oriel.ui.player

import app.oriel.core.model.WatchPage
import app.oriel.ui.components.GROUP_CORNER
import app.oriel.ui.components.GroupShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import app.oriel.core.model.Video
import app.oriel.ui.AppState
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.RemoteImage
import app.oriel.ui.theme.ArtworkPalette
import app.oriel.util.Format
import app.oriel.util.Thumbnails

@Composable
internal fun DescriptionPanel(app: AppState, video: Video, page: WatchPage?, palette: ArtworkPalette, listState: androidx.compose.foundation.lazy.LazyListState) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Text(video.title, style = MaterialTheme.typography.titleLarge, color = palette.onBackground, modifier = Modifier.padding(horizontal = PAGE_GUTTER).padding(top = 8.dp, bottom = 14.dp))
        }
        // The App Store's information strip: small grey labels over the figures, hairlines between.
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                Stat(page?.likeCount ?: "–", "Likes", palette, Modifier.weight(1f))
                StatDivider(palette)
                Stat(page?.viewCount?.substringBefore(' ') ?: video.viewsText?.substringBefore(' ') ?: "–", if (video.isLive) "Watching" else "Views", palette, Modifier.weight(1f))
                StatDivider(palette)
                Stat(page?.date ?: video.publishedText ?: "–", "Published", palette, Modifier.weight(1.3f))
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.padding(horizontal = PAGE_GUTTER).fillMaxWidth().height(0.5.dp).background(palette.divider))
        }
        page?.description?.takeIf { it.isNotBlank() }?.let { text ->
            item {
                Text(
                    linkify(text, palette.accent, onTime = { app.connection.seekTo(it) }, onLink = { url -> if (!app.openLink(url)) app.openExternally(url) }),
                    style = MaterialTheme.typography.bodyLarge, color = palette.onBackground,
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER).padding(top = 14.dp).fillMaxWidth(),
                )
            }
        }
        if (!page?.chapters.isNullOrEmpty()) {
            val chapters = page.chapters.distinctBy { it.startMs }
            item { GroupLabel("Chapters", palette) }
            itemsIndexed(chapters, key = { _, ch -> "ch${ch.startMs}" }) { i, ch ->
                GroupedRow(palette, first = i == 0, last = i == chapters.lastIndex, onClick = { app.connection.seekTo(ch.startMs) }) {
                    RemoteImage(ch.thumbnail ?: Thumbnails.frame(video.id, 320), Modifier.width(88.dp).aspectRatio(16f / 9f), RoundedCornerShape(7.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ch.title, style = MaterialTheme.typography.bodyLarge, color = palette.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(Format.durationMs(ch.startMs), style = MaterialTheme.typography.bodyMedium, color = palette.accent)
                    }
                }
            }
        }
        if (video.channel != null) {
            item { GroupLabel("Channel", palette) }
            item {
                Box(Modifier.padding(horizontal = PAGE_GUTTER).clip(GroupShape).background(palette.onBackground.copy(alpha = 0.065f))) {
                    ChannelLine(app, video, page?.subscribersText, palette, inset = 14.dp)
                }
            }
        }
    }
}

/** A grouped list's header, iOS-style: small, grey, uppercase, above the card. */
@Composable
private fun GroupLabel(text: String, palette: ArtworkPalette) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = palette.onBackgroundVariant, modifier = Modifier.padding(start = PAGE_GUTTER + 4.dp, top = 26.dp, bottom = 8.dp))
}

/** One row of an inset group drawn row by row (so it can live in a lazy list): rounded at the group's ends, hairlines between. */
@Composable
private fun GroupedRow(palette: ArtworkPalette, first: Boolean, last: Boolean, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val shape = RoundedCornerShape(topStart = if (first) GROUP_CORNER else 0.dp, topEnd = if (first) GROUP_CORNER else 0.dp, bottomStart = if (last) GROUP_CORNER else 0.dp, bottomEnd = if (last) GROUP_CORNER else 0.dp)
    Column(Modifier.padding(horizontal = PAGE_GUTTER).clip(shape).background(palette.onBackground.copy(alpha = 0.065f))) {
        Row(Modifier.fillMaxWidth().pressClickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
        if (!last) Box(Modifier.padding(start = 112.dp).fillMaxWidth().height(0.5.dp).background(palette.divider))
    }
}

@Composable
private fun Stat(value: String, label: String, palette: ArtworkPalette, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = palette.onBackgroundVariant, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W700), color = palette.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StatDivider(palette: ArtworkPalette) {
    Box(Modifier.width(0.5.dp).fillMaxHeight(0.8f).background(palette.divider))
}

private val LINK = Regex("""https?://[^\s)]+|(?<![\d:])(?:\d{1,2}:)?\d{1,2}:\d{2}(?![\d:])""")

/** Descriptions with their URLs and timestamps tappable: a timestamp seeks, a link opens in-app when it can. */
fun linkify(text: String, color: Color, onTime: (Long) -> Unit, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    var last = 0
    val style = TextLinkStyles(SpanStyle(color = color, fontWeight = FontWeight.W600))
    LINK.findAll(text).forEach { m ->
        append(text.substring(last, m.range.first))
        val value = m.value
        if (value.startsWith("http")) {
            withLink(LinkAnnotation.Clickable(value, style) { onLink(value) }) { append(value) }
        } else {
            val parts = value.split(':').map { it.toLong() }
            val ms = parts.fold(0L) { acc, p -> acc * 60 + p } * 1000
            withLink(LinkAnnotation.Clickable(value, style) { onTime(ms) }) { append(value) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}
