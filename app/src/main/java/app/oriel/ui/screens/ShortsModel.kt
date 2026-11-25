package app.oriel.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.oriel.AppGraph
import app.oriel.core.model.ShortInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The endless Shorts sequence, with each short's overlay info fetched as it comes up. */
class ShortsModel(private val graph: AppGraph, startId: String?, queue: List<String> = emptyList()) : ViewModel() {
    private val _ids = MutableStateFlow(seed(startId, queue))
    val ids: StateFlow<List<String>> = _ids.asStateFlow()
    private val _info = MutableStateFlow<Map<String, ShortInfo>>(emptyMap())
    val info: StateFlow<Map<String, ShortInfo>> = _info.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private var continuation: String? = null
    private var loading = false
    private var exhausted = false

    init {
        _info.value = previewInfo
        // Opened from a list, its shorts play first: the general sequence waits until the pager nears the end of them.
        if (queue.isEmpty()) loadMore()
    }

    /**
     * The next batch, minus shorts watched here. An all-listed batch asks again; a failure with
     * shorts showing waits for the network. After [SEEN_BATCHES] all-watched batches, seen shorts
     * are allowed.
     */
    fun loadMore() {
        if (loading || exhausted) return
        loading = true
        viewModelScope.launch {
            // The history says what's been seen; asked before it's read, it says nothing has.
            graph.library.awaitLoaded()
            var tries = 0
            var passed = 0
            while (true) {
                val batch = withContext(Dispatchers.IO) { runCatching { graph.yt.shorts(continuation) } }
                val b = batch.getOrNull()
                if (b != null) {
                    continuation = b.continuation
                    exhausted = b.continuation == null
                    val before = _ids.value.size
                    val seen = graph.library.watchedIds()
                    val allowSeen = passed >= SEEN_BATCHES || exhausted
                    _ids.update { old -> old + unseen(old, b.ids, seen, allowSeen) }
                    _error.value = null
                    if (_ids.value.size > before || exhausted) break
                    // Watched, every one: the next batch, at once, and not counted as a failure.
                    if (b.ids.any { it !in _ids.value } && passed++ < SEEN_BATCHES) continue
                } else if (_ids.value.isEmpty()) {
                    _error.value = friendlyError(batch.exceptionOrNull()!!)
                    break
                }
                if (++tries >= 4) break
                if (b == null) { graph.network.online.first { it }; delay(1_500L * tries) }
            }
            loading = false
        }
    }

    fun retry() { exhausted = false; loadMore() }

    fun loadInfo(id: String) {
        if (id in _info.value) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { graph.yt.shortInfo(id) }.getOrNull() }?.let { i -> _info.update { it + (id to i) } }
        }
    }

    companion object {
        /** The shorts to start with: the tapped one, then the rest of the list it was opened from. */
        internal fun seed(startId: String?, queue: List<String>): List<String> = (listOfNotNull(startId) + queue).distinct()

        /**
         * What [batch] adds to the shorts [listed]: the ones neither listed nor [seen] before.
         * With [allowSeen], a batch with nothing unseen gives what it has rather than nothing.
         */
        internal fun unseen(listed: List<String>, batch: List<String>, seen: Set<String>, allowSeen: Boolean): List<String> {
            val new = batch.filterNot { it in listed }.distinct()
            return new.filterNot { it in seen }.ifEmpty { if (allowSeen) new else emptyList() }
        }

        /** How many batches of nothing but watched shorts are passed over before one is shown anyway. */
        private const val SEEN_BATCHES = 6

        /** Screenshot tests: overlay info to start with, in place of the network. */
        @androidx.annotation.VisibleForTesting
        internal var previewInfo: Map<String, ShortInfo> = emptyMap()
    }
}
