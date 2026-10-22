package app.oriel.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.oriel.AppGraph
import app.oriel.core.model.InboxNotification
import app.oriel.core.model.InboxPage
import app.oriel.core.model.Video
import app.oriel.ui.LocalAppState
import app.oriel.ui.components.Avatar
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.MessageState
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.components.RemoteImage
import app.oriel.ui.components.SkeletonLine
import app.oriel.ui.components.feedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InboxState(
    val items: List<InboxNotification> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
    val error: String? = null,
    val continuation: String? = null,
)

class InboxModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(InboxState())
    val state = _state.asStateFlow()
    /** Per account, so a different sign-in never opens on someone else's last inbox. */
    private val cacheKey = "inbox-" + (graph.account.account.value?.name?.hashCode() ?: 0)

    init {
        // The last inbox shows at once, as every feed's last copy does, while the new one loads.
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { graph.pages.read(cacheKey, InboxPage.serializer()) }
            if (cached != null && _state.value.items.isEmpty()) _state.update { it.copy(items = cached.items, continuation = cached.continuation, loading = false) }
            // Quietly, as feeds refresh their last copy: no spinner over what's already shown.
            load()
        }
    }

    private var job: Job? = null
    private var moreJob: Job? = null

    fun load(refresh: Boolean = false) {
        // A reload replaces the list: a load or next page still on its way belongs to the old one.
        job?.cancel()
        moreJob?.cancel()
        _state.update { it.copy(loading = it.items.isEmpty(), refreshing = refresh, error = null, loadingMore = false, moreFailed = false) }
        val generation = graph.pages.generation
        job = viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { graph.yt.inbox() } }
            r.onSuccess { p -> withContext(Dispatchers.IO) { graph.pages.write(cacheKey, InboxPage.serializer(), p, generation) } }
            _state.update { s ->
                r.fold(
                    { p -> s.copy(items = p.items, continuation = p.continuation, loading = false, refreshing = false, moreFailed = false) },
                    { e -> s.copy(loading = false, refreshing = false, error = friendlyError(e)) },
                )
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val token = s.continuation ?: return
        if (s.loadingMore || s.moreFailed) return
        _state.update { it.copy(loadingMore = true) }
        moreJob = viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { graph.yt.inbox(token) } }
            _state.update { st ->
                // The list was reloaded meanwhile: this page followed the old one.
                if (st.continuation != token) return@update st
                r.fold(
                    { p ->
                        val seen = st.items.mapTo(HashSet()) { it.id }
                        st.copy(items = st.items + p.items.filter { it.id !in seen }, continuation = p.continuation, loadingMore = false)
                    },
                    { st.copy(loadingMore = false, moreFailed = true) },
                )
            }
        }
    }

    fun retryMore() { _state.update { it.copy(moreFailed = false) }; loadMore() }
}

/** The signed-in bell: uploads from subscriptions, replies, live streams starting. */
@Composable
fun NotificationsScreen() {
    val app = LocalAppState.current
    val model: InboxModel = viewModel { InboxModel(app.graph) }
    val state by model.state.collectAsState()
    PageScaffold(
        title = "Notifications",
        onBack = app::back,
        refreshing = state.refreshing,
        onRefresh = { model.load(refresh = true) },
        onNearEnd = model::loadMore,
    ) {
        when {
            state.loading -> items(6, contentType = { "skeleton" }) { InboxSkeleton() }
            state.error != null && state.items.isEmpty() -> item(key = "error") {
                MessageState(state.error!!, icon = OrielIcons.Offline, action = "Try again", onAction = { model.load() })
            }
            state.items.isEmpty() -> item(key = "empty") { MessageState("You're all caught up. New uploads and replies show up here.", icon = OrielIcons.Bell) }
            else -> {
                state.error?.let { e -> item(key = "notice") { NoticeRow(e, action = "Try again", onAction = { model.load(refresh = true) }) } }
                items(state.items, key = { it.id }, contentType = { "notification" }) { n ->
                    InboxRow(n, Modifier.then(feedItem())) {
                        when {
                            n.videoId != null -> app.openVideo(Video(n.videoId!!, ""))
                            n.url != null -> if (!app.openLink("https://www.youtube.com" + n.url)) app.notice("Couldn't open this notification")
                        }
                    }
                }
            }
        }
        moreFooter(state.loadingMore, state.moreFailed, model::retryMore)
    }
}

@Composable
private fun InboxRow(n: InboxNotification, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = if (n.read) "Read" else "Unread" }
            .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The unread dot, as Mail's: a blue point in the gutter, gone once seen.
        Box(Modifier.padding(top = 18.dp).size(8.dp).graphicsLayer { alpha = if (n.read) 0f else 1f }.clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(8.dp))
        Avatar(n.avatar, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                n.message, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = if (n.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            n.sentText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp)) }
        }
        n.thumbnail?.let {
            Spacer(Modifier.width(12.dp))
            RemoteImage(it, Modifier.width(112.dp).aspectRatio(16f / 9f), RoundedCornerShape(8.dp))
        }
    }
}

@Composable
private fun InboxSkeleton() {
    Row(Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(16.dp))
        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { SkeletonLine(0.9f); Spacer(Modifier.padding(top = 6.dp)); SkeletonLine(0.4f) }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
