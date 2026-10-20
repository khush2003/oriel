package app.oriel.ui.player

import app.oriel.ui.screens.notePageFailure
import androidx.compose.runtime.Stable
import app.oriel.AppGraph
import app.oriel.core.CaptionTrack
import app.oriel.core.Cue
import app.oriel.core.Storyboard
import app.oriel.core.extras.ReturnDislike
import app.oriel.core.model.Comment
import app.oriel.core.model.CommentsPage
import app.oriel.core.model.Item
import app.oriel.core.model.SortOption
import app.oriel.core.model.Video
import app.oriel.core.model.WatchPage
import app.oriel.playback.PlayerConnection
import app.oriel.ui.screens.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class WatchState(
    val video: Video? = null,
    val page: WatchPage? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val related: List<Item> = emptyList(),
    val relatedContinuation: String? = null,
    val loadingMore: Boolean = false,
    val votes: ReturnDislike.Votes? = null,
)

data class CommentsState(
    val loading: Boolean = false,
    val page: CommentsPage? = null,
    val comments: List<Comment> = emptyList(),
    val continuation: String? = null,
    val loadingMore: Boolean = false,
    val sort: List<SortOption> = emptyList(),
    val error: String? = null,
    /** Replies opened under a comment: the parent and its replies. */
    val thread: Comment? = null,
    val replies: List<Comment> = emptyList(),
    val repliesContinuation: String? = null,
    val loadingReplies: Boolean = false,
)

data class CaptionsState(
    val tracks: List<CaptionTrack> = emptyList(),
    val selected: CaptionTrack? = null,
    val translateTo: String? = null,
    val cues: List<Cue> = emptyList(),
)

/**
 * Everything the watch page shows about the video that's playing: its page
 * (title, channel, likes, description, chapters, related), comments,
 * captions and Return YouTube Dislike's estimate. It follows the player, so
 * when Autoplay moves on the page moves with it.
 */
@Stable
class WatchModel(private val graph: AppGraph, private val connection: PlayerConnection, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()
    private val _comments = MutableStateFlow(CommentsState())
    val comments: StateFlow<CommentsState> = _comments.asStateFlow()
    private val _captions = MutableStateFlow(CaptionsState())
    val captions: StateFlow<CaptionsState> = _captions.asStateFlow()
    /** Scrub previews for the video on the page; null until loaded, and for live streams. */
    private val _storyboard = MutableStateFlow<Storyboard?>(null)
    val storyboard: StateFlow<Storyboard?> = _storyboard.asStateFlow()

    private var loadJob: Job? = null
    private var commentsJob: Job? = null
    private var captionsJob: Job? = null
    private var storyboardJob: Job? = null
    // Paging and reply loads, tracked so a new video or thread cancels them: a late answer
    // would otherwise land on whatever the page shows by then.
    private var relatedMoreJob: Job? = null
    private var commentsMoreJob: Job? = null
    private var repliesJob: Job? = null
    /** Released when the current video's captions and storyboard should be fetched; see [load]. */
    private var extrasWanted: CompletableDeferred<Unit>? = null
    private var playlistId: String? = null
    private var index: Int? = null

    init {
        scope.launch {
            connection.current.filterNotNull().distinctUntilChangedBy { it.id }.collect { video ->
                if (_state.value.video?.id != video.id) load(video)
            }
        }
    }

    /** Shows [state] as if it had loaded, for screenshots. */
    @androidx.annotation.VisibleForTesting
    internal fun preview(state: WatchState, comments: CommentsState = CommentsState(), storyboard: Storyboard? = null) {
        _state.value = state
        _comments.value = comments
        _storyboard.value = storyboard
    }

    /** Opens [video]'s page; [playlistId] keeps the playlist panel when it was opened from one. */
    fun load(video: Video, playlistId: String? = this.playlistId, index: Int? = null) {
        this.playlistId = playlistId
        this.index = index
        loadJob?.cancel()
        commentsJob?.cancel()
        relatedMoreJob?.cancel()
        commentsMoreJob?.cancel()
        repliesJob?.cancel()
        _comments.value = CommentsState()
        _state.value = WatchState(video = video, loading = true)
        loadJob = scope.launch {
            val page = try {
                Result.success(withContext(Dispatchers.IO) { graph.yt.watch(video.id, playlistId, index, video) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            // Another video's page may have been opened meanwhile; this answer isn't its.
            if (_state.value.video?.id != video.id) return@launch
            page.onSuccess { p ->
                // A playlist that no longer contains this video stops being the context.
                if (p.playlist == null) this@WatchModel.playlistId = null
                _state.update { it.copy(page = p, loading = false, related = p.related, relatedContinuation = p.relatedContinuation, video = mergeVideo(video, p)) }
            }.onFailure { e ->
                graph.notePageFailure("Loading a watch page", e)
                _state.update { it.copy(loading = false, error = friendlyError(e)) }
            }
            if (graph.settings.current.returnDislike && !video.isShort) {
                val votes = withContext(Dispatchers.IO) { runCatching { graph.dislikes.votes(video.id) }.getOrNull() }
                if (_state.value.video?.id == video.id) _state.update { it.copy(votes = votes) }
            }
        }
        // Captions and the scrub storyboard come from one more player request. Unless captions
        // are on, it waits until the video has had a few seconds to start, so it doesn't compete
        // with the first bytes of the video itself; a scrub or the captions menu asks for it at once.
        val wanted = CompletableDeferred<Unit>().also { extrasWanted = it }
        if (graph.settings.current.captions) wanted.complete(Unit)
        else scope.launch { delay(EXTRAS_DELAY_MS); wanted.complete(Unit) }
        loadCaptions(video.id, wanted)
        loadStoryboard(video, wanted)
    }

    /** A scrub began or the captions menu opened: fetch this video's captions and storyboard now. */
    fun wantExtras() {
        extrasWanted?.complete(Unit)
    }

    /** Fetched with the captions (one player request serves both). */
    private fun loadStoryboard(video: Video, wanted: Deferred<Unit>) {
        storyboardJob?.cancel()
        _storyboard.value = null
        if (video.isLive || video.isShort) return
        storyboardJob = scope.launch {
            wanted.await()
            val board = withContext(Dispatchers.IO) { runCatching { graph.yt.storyboard(video.id) }.getOrNull() }
            if (_state.value.video?.id == video.id) _storyboard.value = board
        }
    }

    fun retry() = _state.value.video?.let { load(it) }

    /** The page's own copy of the video is richer (title, channel avatar); keep what the card already knew where it isn't. */
    private fun mergeVideo(video: Video, page: WatchPage): Video = page.video.copy(
        thumbnail = page.video.thumbnail ?: video.thumbnail,
        durationSec = page.video.durationSec ?: video.durationSec,
        channel = page.video.channel ?: video.channel,
    )

    fun loadMoreRelated() {
        val s = _state.value
        val token = s.relatedContinuation ?: return
        if (s.loadingMore) return
        val videoId = s.video?.id
        _state.update { it.copy(loadingMore = true) }
        relatedMoreJob = scope.launch {
            val more = runCatching { withContext(Dispatchers.IO) { graph.yt.relatedMore(token) } }.getOrNull()
            _state.update {
                if (it.video?.id != videoId) it
                else if (more == null) it.copy(loadingMore = false)
                else it.copy(related = (it.related + more.items).distinctBy { i -> i.id }, relatedContinuation = more.continuation, loadingMore = false)
            }
        }
    }

    // ---- comments --------------------------------------------------------------------------

    fun loadComments(token: String? = _state.value.page?.commentsToken) {
        token ?: run {
            _comments.value = CommentsState(error = "Comments are turned off")
            return
        }
        commentsJob?.cancel()
        // A page of the old sort order (or video) mustn't be appended to the new list.
        commentsMoreJob?.cancel()
        _comments.update { it.copy(loading = true, loadingMore = false, error = null, comments = if (it.sort.isEmpty()) emptyList() else it.comments) }
        commentsJob = scope.launch {
            runCatching { withContext(Dispatchers.IO) { graph.yt.comments(token) } }
                .onSuccess { p ->
                    _comments.value = CommentsState(
                        page = p,
                        comments = p.comments,
                        continuation = p.continuation,
                        sort = p.sort.ifEmpty { _comments.value.sort }.ifEmpty { _state.value.page?.commentSort.orEmpty() },
                        error = p.disabledReason,
                    )
                }
                .onFailure { _comments.update { it.copy(loading = false, error = "Couldn't load comments") } }
        }
    }

    fun loadMoreComments() {
        val c = _comments.value
        val token = c.continuation ?: return
        if (c.loadingMore) return
        _comments.update { it.copy(loadingMore = true) }
        commentsMoreJob = scope.launch {
            val more = runCatching { withContext(Dispatchers.IO) { graph.yt.comments(token) } }.getOrNull()
            _comments.update {
                if (it.continuation != token) it
                else if (more == null) it.copy(loadingMore = false)
                else it.copy(comments = (it.comments + more.comments).distinctBy { c -> c.id }, continuation = more.continuation, loadingMore = false)
            }
        }
    }

    fun sortComments(option: SortOption) {
        _comments.update { it.copy(sort = it.sort.map { s -> s.copy(selected = s.token == option.token) }) }
        loadComments(option.token)
    }

    fun openReplies(comment: Comment) {
        val token = comment.repliesToken ?: return
        repliesJob?.cancel()
        _comments.update { it.copy(thread = comment, replies = emptyList(), repliesContinuation = null, loadingReplies = true) }
        repliesJob = scope.launch {
            val page = runCatching { withContext(Dispatchers.IO) { graph.yt.comments(token) } }.getOrNull()
            // Another thread may have been opened meanwhile; these aren't its replies.
            _comments.update { if (it.thread?.id != comment.id) it else it.copy(replies = page?.comments.orEmpty(), repliesContinuation = page?.continuation, loadingReplies = false) }
        }
    }

    fun loadMoreReplies() {
        val c = _comments.value
        val token = c.repliesContinuation ?: return
        if (c.loadingReplies) return
        val threadId = c.thread?.id
        _comments.update { it.copy(loadingReplies = true) }
        repliesJob = scope.launch {
            val page = runCatching { withContext(Dispatchers.IO) { graph.yt.comments(token) } }.getOrNull()
            _comments.update { if (it.thread?.id != threadId) it else it.copy(replies = it.replies + page?.comments.orEmpty(), repliesContinuation = page?.continuation, loadingReplies = false) }
        }
    }

    fun closeReplies() {
        repliesJob?.cancel()
        _comments.update { it.copy(thread = null, replies = emptyList(), repliesContinuation = null, loadingReplies = false) }
    }

    // ---- captions --------------------------------------------------------------------------

    private fun loadCaptions(videoId: String, wanted: Deferred<Unit>) {
        captionsJob?.cancel()
        _captions.value = CaptionsState()
        captionsJob = scope.launch {
            wanted.await()
            val tracks = withContext(Dispatchers.IO) { runCatching { graph.yt.captionTracks(videoId) }.getOrDefault(emptyList()) }
            if (_state.value.video?.id != videoId) return@launch
            _captions.value = CaptionsState(tracks = tracks)
            if (graph.settings.current.captions) preferredTrack(tracks)?.let { selectCaptions(it) }
        }
    }

    /** The user's caption language, else the device's, preferring human captions to auto-generated ones. */
    private fun preferredTrack(tracks: List<CaptionTrack>): CaptionTrack? {
        val wanted = (graph.settings.current.captionLanguage ?: Locale.getDefault().language).lowercase()
        val matching = tracks.filter { it.languageCode.lowercase().substringBefore('-') == wanted.substringBefore('-') }
        return matching.firstOrNull { !it.isAuto } ?: matching.firstOrNull() ?: tracks.firstOrNull { !it.isAuto } ?: tracks.firstOrNull()
    }

    fun selectCaptions(track: CaptionTrack?, translateTo: String? = null) {
        _captions.update { it.copy(selected = track, translateTo = translateTo, cues = emptyList()) }
        if (track == null) return
        scope.launch {
            val cues = withContext(Dispatchers.IO) { runCatching { graph.yt.captionCues(track, translateTo) }.getOrDefault(emptyList()) }
            if (_captions.value.selected == track) _captions.update { it.copy(cues = cues) }
        }
    }

    fun toggleCaptions() {
        val c = _captions.value
        if (c.selected != null) {
            selectCaptions(null)
            graph.settings.update { it.copy(captions = false) }
        } else {
            preferredTrack(c.tracks)?.let { selectCaptions(it) }
            graph.settings.update { it.copy(captions = true) }
        }
    }

    private companion object {
        const val EXTRAS_DELAY_MS = 4_000L
    }
}
