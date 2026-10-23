package app.soundfire.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.soundfire.data.Prefs
import app.soundfire.data.Track
import app.soundfire.data.soundcloud.SoundCloudApi
import app.soundfire.taste.TasteEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val loading: Boolean = true,
    val featured: List<Track> = emptyList(),
    val featuredIsPersonal: Boolean = false,
    val because: Pair<Track, List<Track>>? = null,
    val trending: List<Track> = emptyList(),
    val moods: List<String> = MOODS,
    val selectedMood: String = MOODS[3],
    val moodTracks: List<Track> = emptyList(),
    val username: String? = null,
    val likeCount: Int = 0,
    val error: String? = null,
)

/** Up to six columns fit on wide screens; the screen shows as many as fit. */
private const val FEATURED_MAX = 6

private val MOODS = listOf("Lo-fi", "Hip-Hop", "Electronic", "Chill", "R&B", "Jazz")

class HomeViewModel(
    private val api: SoundCloudApi,
    private val taste: TasteEngine,
    prefs: Prefs,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()
    private var moodJob: Job? = null

    init {
        viewModelScope.launch {
            combine(prefs.username, taste.likeCount) { name, likes -> name to likes }
                .collect { (name, likes) -> _state.update { it.copy(username = name, likeCount = likes) } }
        }
        reload()
        selectMood(_state.value.selectedMood)
    }

    fun reload() {
        viewModelScope.launch {
            val trending = async { runCatching { api.trending(24) }.onFailure { Log.w(TAG, "Trending failed", it) } }
            loadPersonalRows(trending.await().getOrElse { emptyList() }, trending.await().exceptionOrNull())
            // Fetching fresh candidates takes a few seconds; show what's cached first, then update.
            runCatching { taste.refreshCandidates() }
                .onSuccess { loadPersonalRows(_state.value.trending + _state.value.featured, null) }
                .onFailure { Log.w(TAG, "Candidate refresh failed", it) }
        }
    }

    fun selectMood(mood: String) {
        _state.update { it.copy(selectedMood = mood) }
        moodJob?.cancel()
        moodJob = viewModelScope.launch {
            // SoundCloud's genre filter is too strict combined with a query (it returns nothing), so moods are plain searches.
            val tracks = runCatching { api.searchTracks(mood, limit = 20) }
                .onFailure { Log.w(TAG, "Mood search failed", it) }
                .getOrElse { emptyList() }
            _state.update { it.copy(moodTracks = tracks.filter { t -> t.playable }) }
        }
    }

    private suspend fun loadPersonalRows(trendingPool: List<Track>, error: Throwable?) {
        val mix = runCatching { taste.mix(FEATURED_MAX) }.onFailure { Log.w(TAG, "Mix failed", it) }.getOrElse { emptyList() }
        val because = runCatching { taste.becauseYouPlayed(10) }.onFailure { Log.w(TAG, "Because row failed", it) }.getOrNull()
        val personal = mix.size >= 4
        val pool = trendingPool.filter { it.playable }.distinctBy { it.id }
        val featured = if (personal) mix else pool.take(FEATURED_MAX)
        _state.update {
            it.copy(
                loading = false,
                featured = featured,
                featuredIsPersonal = personal,
                because = because,
                trending = pool.filterNot { t -> featured.any { f -> f.id == t.id } }.take(12),
                error = if (featured.isEmpty()) error?.let { "Couldn't reach SoundCloud. Check the TV's connection." } else null,
            )
        }
    }

    private companion object {
        const val TAG = "HomeViewModel"
    }
}
