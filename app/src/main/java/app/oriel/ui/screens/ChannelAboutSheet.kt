package app.oriel.ui.screens

import androidx.compose.animation.core.spring
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.size
import app.oriel.ui.player.linkify
import app.oriel.ui.components.ActivityIndicator
import app.oriel.ui.components.RowDivider
import app.oriel.ui.components.SettingsRow
import app.oriel.ui.components.SettingsGroup
import app.oriel.ui.components.SheetTitle
import app.oriel.ui.components.LocalSheetClose
import app.oriel.ui.components.OrielSheet
import app.oriel.core.model.Channel
import app.oriel.core.model.ChannelAbout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.oriel.ui.LocalAppState
import app.oriel.ui.components.ActionPill
import app.oriel.ui.components.PillSize
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.MessageState
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.RemoteImage

@Composable
internal fun ChannelAboutSheet(channel: Channel, about: ChannelAbout?, failed: Boolean, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val app = LocalAppState.current
    OrielSheet(onDismiss) {
        val close = LocalSheetClose.current
        SheetTitle("About")
        when {
            about == null && failed -> MessageState("Couldn't load this channel's details.", icon = OrielIcons.Offline, action = "Try again", onAction = onRetry)
            about == null -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { ActivityIndicator(size = 24.dp) }
            else -> Column(Modifier.animateContentSize(spring(stiffness = 500f))) {
                // The About panel's own description, even when empty: the header's preview of an empty
                // one is YouTube's placeholder ("More about this channel"), not words to repeat here.
                val text = about.description
                if (!text.isNullOrBlank()) {
                    Text(
                        linkify(text, MaterialTheme.colorScheme.primary, onTime = {}, onLink = { url -> close { if (!app.openLink(url)) app.openExternally(url) } }),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = PAGE_GUTTER).padding(top = 4.dp),
                    )
                }
                if (about.links.isNotEmpty()) SettingsGroup("Links") {
                    about.links.forEachIndexed { i, link ->
                        if (i > 0) RowDivider()
                        SettingsRow(
                            link.title, subtitle = link.label, chevron = true,
                            onClick = { close { if (!app.openLink(link.url)) app.openExternally(link.url) } },
                            trailing = link.favicon?.let { f -> { RemoteImage(f, Modifier.size(20.dp), RoundedCornerShape(4.dp), border = false) } },
                        )
                    }
                }
                val facts = listOfNotNull(
                    about.url?.let { OrielIcons.Link to it },
                    (about.subscribersText ?: channel.subscribersText)?.let { OrielIcons.Person to it },
                    (about.videosText ?: channel.videosText)?.let { OrielIcons.Videos to it },
                    about.viewsText?.let { OrielIcons.Chart to it },
                    about.joinedText?.let { OrielIcons.Info to it },
                    about.country?.let { OrielIcons.Globe to it },
                )
                if (facts.isNotEmpty()) SettingsGroup("Channel details") {
                    facts.forEachIndexed { i, (icon, label) ->
                        if (i > 0) RowDivider()
                        SettingsRow(label, icon = icon)
                    }
                }
                Spacer(Modifier.height(12.dp))
                ActionPill("Share channel", OrielIcons.Share, { close { app.share(channel) } }, Modifier.padding(horizontal = PAGE_GUTTER).fillMaxWidth(), size = PillSize.Medium)
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
