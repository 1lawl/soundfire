package app.soundfire.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.soundfire.data.Track
import app.soundfire.data.soundcloud.SoundCloudApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchState(
    val query: String = "",
    val suggestions: List<String> = emptyList(),
    val results: List<Track> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Kept at app level so reopening the keyboard returns to the last search. */
@OptIn(FlowPreview::class)
class SearchViewModel(private val api: SoundCloudApi) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.map { it.query.trim() }
                .distinctUntilChanged()
                .debounce(350)
                .collectLatest { query ->
                    if (query.isEmpty()) {
                        _state.update { it.copy(suggestions = emptyList(), results = emptyList(), loading = false, error = null) }
                        return@collectLatest
                    }
                    _state.update { it.copy(loading = true, error = null) }
                    coroutineScope {
                        val suggestions = async { runCatching { api.autocomplete(query) }.getOrElse { emptyList() } }
                        val results = async { runCatching { api.searchTracks(query, limit = 24) } }
                        val found = results.await()
                        _state.update {
                            it.copy(
                                suggestions = suggestions.await().filterNot { s -> s.equals(query, ignoreCase = true) },
                                results = found.getOrElse { emptyList() },
                                loading = false,
                                error = found.exceptionOrNull()?.let { "Search failed. Check the connection." },
                            )
                        }
                    }
                }
        }
    }

    fun editQuery(transform: (String) -> String) = _state.update { it.copy(query = transform(it.query)) }
}
