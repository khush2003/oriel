package app.oriel.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import app.oriel.ui.components.GroupShape
import app.oriel.ui.components.TextAction
import app.oriel.ui.components.LoadingRow
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.util.Format
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A feed's end: a spinner while the next page loads, or a way to ask again when it didn't. */
fun LazyListScope.feedFooter(state: FeedState, onRetry: () -> Unit) = moreFooter(state.loadingMore, state.moreFailed, onRetry)

/**
 * Over a feed still showing an older copy because the refresh failed: that it
 * failed, how old what's shown is, and a way to try again. Without it a failed
 * refresh looks like YouTube having nothing new.
 */
fun LazyListScope.refreshNotice(state: FeedState, onRetry: () -> Unit) {
    val error = state.error ?: return
    if (state.feed == null || state.loading || state.refreshing) return
    item(key = "refresh-notice", contentType = "notice") {
        val age = state.updatedAt?.let { " Updated ${Format.ago(it).replaceFirstChar { c -> c.lowercase() }}." }.orEmpty()
        NoticeRow(error.substringBefore(". ").removeSuffix(".") + "." + age, action = "Try again", onAction = onRetry)
    }
}

/** A list's tail when it pages: a spinner while the next page loads, or a retry if it failed. The token is kept, so retry picks up where it stopped. */
fun LazyListScope.moreFooter(loading: Boolean, failed: Boolean, onRetry: () -> Unit) {
    when {
        loading -> item(key = "more", contentType = "more") { LoadingRow() }
        failed -> item(key = "more-failed", contentType = "more") { NoticeRow("Couldn't load more.", action = "Try again", onAction = onRetry) }
    }
}

@Composable
fun NoticeRow(text: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 6.dp)
            .clip(GroupShape).background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        TextAction(action, onAction)
    }
}
