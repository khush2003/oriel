package app.oriel.ui.screens

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableIntStateOf
import app.oriel.ui.player.pressClickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.oriel.ui.components.LocalSheetClose
import app.oriel.ui.components.OrielSheet
import app.oriel.ui.player.CommentSortRow
import app.oriel.core.model.Comment
import app.oriel.core.model.CommentsPage
import app.oriel.core.model.ShortInfo
import app.oriel.ui.AppState
import app.oriel.ui.components.LoadingRow
import app.oriel.ui.components.OrielIcons
import app.oriel.ui.components.MessageState
import app.oriel.ui.components.PAGE_GUTTER
import app.oriel.ui.player.CommentRow
import app.oriel.ui.player.CommentSkeleton
import app.oriel.ui.player.PanelHeader
import app.oriel.ui.theme.themePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ShortCommentsSheet(app: AppState, info: ShortInfo, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<CommentsPage?>(null) }
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var loadingMore by remember { mutableStateOf(false) }
    var moreFailed by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var thread by remember { mutableStateOf<Comment?>(null) }
    var replies by remember { mutableStateOf<List<Comment>?>(null) }
    var repliesFailed by remember { mutableStateOf(false) }
    var repliesMore by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var repliesAttempt by remember { mutableIntStateOf(0) }
    var sortToken by remember { mutableStateOf<String?>(null) }
    var moreJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    LaunchedEffect(info.id, attempt, sortToken) {
        // A next page of the old order (or the old try) never lands in the new one.
        moreJob?.cancel(); loadingMore = false
        val token = sortToken ?: info.commentsToken ?: run { loading = false; return@LaunchedEffect }
        failed = false; loading = true
        val p = withContext(Dispatchers.IO) { runCatching { app.graph.yt.comments(token) }.getOrNull() }
        loading = false
        if (p == null) failed = true
        else {
            // The sort chips come with the first page only; a re-sorted page keeps them, with the new one ticked.
            val sort = p.sort.ifEmpty { page?.sort.orEmpty().map { it.copy(selected = it.token == sortToken) } }
            page = p.copy(sort = sort); comments = p.comments; moreFailed = false
        }
    }
    LaunchedEffect(thread, repliesAttempt) {
        replies = null; repliesFailed = false; repliesMore = null
        val token = thread?.repliesToken ?: return@LaunchedEffect
        val r = withContext(Dispatchers.IO) { runCatching { app.graph.yt.comments(token) }.getOrNull() }
        if (r == null) repliesFailed = true else { replies = r.comments; repliesMore = r.continuation }
    }
    fun loadMore() {
        val cont = page?.continuation ?: return
        if (loadingMore) return
        loadingMore = true; moreFailed = false
        moreJob = scope.launch {
            val next = withContext(Dispatchers.IO) { runCatching { app.graph.yt.comments(cont) }.getOrNull() }
            if (page?.continuation != cont) return@launch
            if (next != null) {
                val seen = comments.mapTo(HashSet()) { it.id }
                comments = comments + next.comments.filterNot { it.id in seen }
                page = next.copy(sort = page?.sort.orEmpty())
            } else moreFailed = true
            loadingMore = false
        }
    }
    val palette = themePalette()
    OrielSheet(onDismiss, scrollable = false, maxHeight = 720.dp) {
        val close = LocalSheetClose.current
        // Back from a reply thread returns to the comments; from the comments it closes the sheet.
        BackHandler(enabled = thread != null) { thread = null }
        PanelHeader(if (thread != null) "Replies" else "Comments", if (thread != null) null else info.commentsCount, palette, onClose = { close {} })
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(palette.divider))
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.85f), contentPadding = PaddingValues(bottom = 32.dp)) {
            val open = thread
            if (open != null) {
                item(key = "back") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).pressClickable(onClickLabel = "Back to all comments") { thread = null }.padding(horizontal = PAGE_GUTTER, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(OrielIcons.Back, null, tint = palette.onBackground, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("All comments", style = MaterialTheme.typography.labelLarge, color = palette.onBackground)
                    }
                }
                item(key = "parent") { CommentRow(app, open, palette, onReplies = null) }
                val r = replies
                when {
                    repliesFailed -> item(key = "replies-failed") { MessageState("Replies couldn't be loaded.", action = "Try again", onAction = { repliesAttempt++ }) }
                    r == null -> item(key = "replies-loading") { LoadingRow() }
                    else -> items(r.distinctBy { it.id }, key = { "r" + it.id }) { CommentRow(app, it, palette, onReplies = null, indent = true) }
                }
                val more = repliesMore
                if (r != null && more != null) item(key = "replies-more") {
                    LaunchedEffect(more) {
                        val next = withContext(Dispatchers.IO) { runCatching { app.graph.yt.comments(more) }.getOrNull() }
                        replies = replies.orEmpty() + next?.comments.orEmpty()
                        repliesMore = next?.continuation
                    }
                    LoadingRow()
                }
                return@LazyColumn
            }
            val sort = page?.sort.orEmpty()
            if (sort.size > 1) item(key = "sort") { CommentSortRow(sort, palette) { option -> sortToken = option.token; page = page?.copy(sort = sort.map { it.copy(selected = it.token == option.token) }) } }
            when {
                failed -> item(key = "failed") { MessageState("Comments couldn't be loaded.", action = "Try again", onAction = { attempt++ }) }
                loading -> items(5, key = { "sk$it" }) { CommentSkeleton() }
                comments.isEmpty() -> item(key = "empty") { MessageState(page?.disabledReason ?: "No comments yet.") }
                else -> items(comments.distinctBy { it.id }, key = { it.id }) { CommentRow(app, it, palette, onReplies = { c -> thread = c }) }
            }
            val cont = page?.continuation
            if (cont != null && !failed && !loading) {
                if (moreFailed) item(key = "more-failed") { NoticeRow("Couldn't load more.", action = "Try again", onAction = ::loadMore) }
                else item(key = "more") {
                    LaunchedEffect(cont) { loadMore() }
                    LoadingRow()
                }
            }
        }
    }
}
