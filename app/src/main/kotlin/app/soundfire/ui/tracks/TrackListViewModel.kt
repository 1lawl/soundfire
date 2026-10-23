package app.soundfire.ui.tracks

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.soundfire.data.Track
import app.soundfire.data.soundcloud.Page
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrackListState(
    val tracks: List<Track> = emptyList(),
    val total: Int? = null,
    val loading: Boolean = true,
    val endReached: Boolean = false,
    val error: String? = null,
)

/** Infinite list over any paged SoundCloud source (search results, a user's likes). */
class TrackListViewModel(
    private val source: suspend (next: String?) -> Page<Track>,
) : ViewModel() {
    private val _state = MutableStateFlow(TrackListState())
    val state: StateFlow<TrackListState> = _state.asStateFlow()
    private var next: String? = null
    private var inFlight = false

    init {
        loadMore()
    }

    fun loadMore() {
        if (inFlight || _state.value.endReached) return
        inFlight = true
        _state.update { it.copy(loading = true, error = null) }
        val before = _state.value.tracks.size
        viewModelScope.launch {
            runCatching { source(next) }
                .onSuccess { page ->
                    next = page.next
                    _state.update { s ->
                        val known = s.tracks.mapTo(HashSet()) { it.id }
                        s.copy(
                            tracks = s.tracks + page.items.filterNot { it.id in known },
                            total = page.total ?: s.total,
                            loading = false,
                            endReached = page.next == null,
                        )
                    }
                }
                .onFailure { e ->
                    Log.w(TAG, "Page load failed", e)
                    _state.update { it.copy(loading = false, error = "Couldn't load more. Press OK to retry.") }
                }
            inFlight = false
            // A page can come back empty after filtering (deleted or private tracks) while more
            // pages remain; keep going so the list doesn't stall with nothing to scroll toward.
            if (_state.value.error == null && !_state.value.endReached && _state.value.tracks.size == before) loadMore()
        }
    }

    private companion object {
        const val TAG = "TrackListViewModel"
    }
}
