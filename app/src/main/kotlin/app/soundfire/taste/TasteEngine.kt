package app.soundfire.taste

import android.util.Log
import app.soundfire.data.Prefs
import app.soundfire.data.Track
import app.soundfire.data.local.CandidateEntity
import app.soundfire.data.local.LikedSeedEntity
import app.soundfire.data.local.PlayEventEntity
import app.soundfire.data.local.TasteDao
import app.soundfire.data.local.toEntity
import app.soundfire.data.local.toTrack
import app.soundfire.data.soundcloud.SoundCloudApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

class NoPublicLikesException : Exception("User has no public likes")

/**
 * On-device taste profile. Listening behaviour (and optionally a one-time import of a user's
 * public likes) becomes weighted affinities for tracks, artists, genres and tags. SoundCloud's
 * related/station/artist endpoints supply candidates, which are ranked against that profile.
 */
class TasteEngine(
    private val api: SoundCloudApi,
    private val dao: TasteDao,
    private val prefs: Prefs,
) {
    data class Profile(
        val tracks: Map<Long, Double>,
        val artists: Map<Long, Double>,
        val genres: Map<String, Double>,
        val tags: Map<String, Double>,
        val recentlyPlayed: Set<Long>,
    ) {
        val isEmpty: Boolean get() = tracks.isEmpty()

        fun seeds(count: Int): List<Long> =
            tracks.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(count).map { it.key }

        fun topArtists(count: Int): List<Long> =
            artists.entries.filter { it.value > 0 && it.key != 0L }.sortedByDescending { it.value }.take(count).map { it.key }
    }

    private val refreshMutex = Mutex()

    val likeCount: Flow<Int> = dao.likeCount()

    suspend fun recordPlay(track: Track, listenedMs: Long, completed: Boolean) {
        if (listenedMs < MIN_RECORD_MS && !completed) return
        dao.upsertTracks(listOf(track.toEntity()))
        dao.insertEvent(
            PlayEventEntity(
                trackId = track.id,
                artistId = track.artistId,
                at = System.currentTimeMillis(),
                listenedMs = listenedMs,
                durationMs = track.durationMs,
                completed = completed,
                skipped = !completed && listenedMs < SKIP_THRESHOLD_MS,
            ),
        )
    }

    /** Level-1 account link: seed the profile from a username's public likes. Returns how many were imported. */
    suspend fun importLikes(username: String, onProgress: (Int) -> Unit = {}): Int {
        val user = api.resolveUser(username)
        val likes = api.allLikes(user.id, onProgress)
        if (likes.isEmpty()) throw NoPublicLikesException()
        dao.upsertTracks(likes.map { (track, _) -> track.toEntity() })
        dao.upsertLikes(likes.map { (track, at) -> LikedSeedEntity(track.id, track.artistId, at) })
        prefs.setUsername(user.permalink ?: username.trim())
        refreshCandidates(force = true)
        return likes.size
    }

    suspend fun profile(now: Long = System.currentTimeMillis()): Profile = withContext(Dispatchers.Default) {
        val events = dao.eventsSince(now - HISTORY_WINDOW_MS)
        val likes = dao.likes()
        val meta = dao.tracks((events.map { it.trackId } + likes.map { it.trackId }).distinct())
            .associateBy { it.id }

        val tracks = HashMap<Long, Double>()
        val artists = HashMap<Long, Double>()
        val genres = HashMap<String, Double>()
        val tags = HashMap<String, Double>()

        fun credit(trackId: Long, weight: Double) {
            tracks.merge(trackId, weight, Double::plus)
            val track = meta[trackId] ?: return
            artists.merge(track.artistId, weight * 0.6, Double::plus)
            if (track.genre.isNotBlank()) genres.merge(track.genre.lowercase(), weight * 0.4, Double::plus)
            track.tags.split('\u001F').filter { it.isNotBlank() }.forEach { tags.merge(it, weight * 0.15, Double::plus) }
        }

        for (like in likes) {
            // Old likes still say something about taste, so they decay slowly and never vanish.
            credit(like.trackId, LIKE_WEIGHT * maxOf(0.35, decay(now - like.likedAt, LIKE_HALF_LIFE_MS)))
        }
        for (event in events) {
            val fraction = if (event.durationMs > 0) event.listenedMs.toDouble() / event.durationMs else 0.0
            val signal = when {
                event.completed -> 1.0
                event.skipped -> -1.0
                fraction >= 0.5 -> 0.6
                else -> 0.15
            }
            credit(event.trackId, signal * decay(now - event.at, PLAY_HALF_LIFE_MS))
        }

        val recent = events.filter { now - it.at < RECENT_WINDOW_MS }.map { it.trackId }.toSet()
        Profile(tracks, artists, genres, tags, recent)
    }

    /** Pulls fresh candidates for the strongest seeds. Cheap no-op unless stale or forced. */
    suspend fun refreshCandidates(force: Boolean = false) = refreshMutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.lastCandidateRefresh() < REFRESH_INTERVAL_MS) return@withLock
        val profile = profile(now)
        if (profile.isEmpty) return@withLock

        val gate = Semaphore(4)
        coroutineScope {
            val seedJobs = profile.seeds(SEED_COUNT).mapIndexed { index, seed ->
                async {
                    gate.withPermit {
                        store(seed, KIND_RELATED, runCatching { api.related(seed, 20) }.getOrNull(), now)
                        if (index < STATION_SEEDS) {
                            store(seed, KIND_STATION, runCatching { api.station(seed, 30) }.getOrNull(), now)
                        }
                    }
                }
            }
            val artistJobs = profile.topArtists(ARTIST_SEEDS).map { artist ->
                async { gate.withPermit { store(artist, KIND_ARTIST, runCatching { api.artistTracks(artist, 12) }.getOrNull(), now) } }
            }
            (seedJobs + artistJobs).awaitAll()
        }
        dao.pruneCandidates(now - CANDIDATE_TTL_MS)
        prefs.setLastCandidateRefresh(now)
        Log.d(TAG, "Refreshed candidates for ${profile.seeds(SEED_COUNT).size} seeds")
    }

    /** "Your mix": the best-scoring candidates across every seed. */
    suspend fun mix(limit: Int): List<Track> {
        val profile = profile()
        if (profile.isEmpty) return emptyList()
        return rank(profile, dao.candidates(), exclude = profile.recentlyPlayed + profile.seeds(SEED_COUNT), limit = limit)
    }

    /** The strongest seed and its ranked related tracks, for a "Because you played" row. */
    suspend fun becauseYouPlayed(limit: Int): Pair<Track, List<Track>>? {
        val profile = profile()
        val candidates = dao.candidates().filter { it.kind == KIND_RELATED }
        // Near-tied seeds can reorder between refreshes; use the strongest one we have related tracks for.
        val seedId = profile.seeds(SEED_COUNT).firstOrNull { seed -> candidates.any { it.seedId == seed } } ?: return null
        val seed = dao.tracks(listOf(seedId)).firstOrNull()?.toTrack() ?: return null
        val related = candidates.filter { it.seedId == seedId }
        val ranked = rank(profile, related, exclude = setOf(seedId), limit = limit)
        return if (ranked.isEmpty()) null else seed to ranked
    }

    /** Next tracks for autoplay: SoundCloud's related list for [current], re-ranked by taste. */
    suspend fun nextFor(current: Track, exclude: Set<Long>, count: Int): List<Track> {
        val now = System.currentTimeMillis()
        val related = runCatching { api.related(current.id, 20) }.getOrNull()
        store(current.id, KIND_RELATED, related, now)
        val profile = profile(now)
        return rank(
            profile,
            dao.candidates(),
            exclude = exclude + profile.recentlyPlayed + current.id,
            limit = count,
            boostSeed = current.id,
        ).ifEmpty { related.orEmpty().filter { it.playable && it.id !in exclude }.take(count) }
    }

    private suspend fun store(seedId: Long, kind: String, tracks: List<Track>?, now: Long) {
        if (tracks.isNullOrEmpty()) return
        dao.upsertTracks(tracks.map { it.toEntity(now) })
        dao.replaceCandidates(seedId, kind, tracks.mapIndexed { i, t -> CandidateEntity(t.id, seedId, kind, i, now) })
    }

    private suspend fun rank(
        profile: Profile,
        candidates: List<CandidateEntity>,
        exclude: Set<Long>,
        limit: Int,
        boostSeed: Long? = null,
    ): List<Track> = withContext(Dispatchers.Default) {
        val byTrack = candidates.filter { it.trackId !in exclude }.groupBy { it.trackId }
        val meta = dao.tracks(byTrack.keys.toList()).associateBy { it.id }

        val scored = byTrack.mapNotNull { (trackId, sources) ->
            val track = meta[trackId]?.toTrack()?.takeIf { it.playable } ?: return@mapNotNull null
            // Agreement across seeds is the core signal: a track several favourites point to wins.
            val support = sources.sumOf { source ->
                val seedWeight = when (source.kind) {
                    KIND_ARTIST -> profile.artists[source.seedId] ?: 0.0
                    else -> profile.tracks[source.seedId] ?: 1.0
                }.coerceAtLeast(0.1)
                val kindFactor = when (source.kind) {
                    KIND_RELATED -> 1.0
                    KIND_STATION -> 0.7
                    else -> 0.8
                }
                // During autoplay, tracks related to what's playing get a head start so the session stays coherent.
                val boost = if (source.seedId == boostSeed) 3.0 else 1.0
                soft(seedWeight) * kindFactor * boost / (1 + source.rank * 0.08)
            }
            val artist = profile.artists[track.artistId] ?: 0.0
            val genre = profile.genres[track.genre.lowercase()] ?: 0.0
            val tagScore = track.tags.sumOf { profile.tags[it] ?: 0.0 }
            var score = support + 1.2 * soft(artist) + 0.6 * soft(genre) + 0.4 * soft(tagScore) +
                0.15 * log10(track.plays + 1.0) / 7
            if (artist < -0.5) score -= 2.0
            if (profile.tracks[track.id]?.let { it < 0 } == true) score -= 3.0
            track to score
        }.sortedByDescending { it.second }

        // Variety: at most two tracks per artist.
        val perArtist = HashMap<Long, Int>()
        scored.asSequence()
            .map { it.first }
            .filter { perArtist.merge(it.artistId, 1, Int::plus)!! <= 2 }
            .take(limit)
            .toList()
    }

    private fun soft(x: Double) = x / (1 + abs(x))

    private fun decay(ageMs: Long, halfLifeMs: Long) = 0.5.pow(ageMs.coerceAtLeast(0).toDouble() / halfLifeMs)

    private companion object {
        const val TAG = "TasteEngine"
        const val KIND_RELATED = "related"
        const val KIND_STATION = "station"
        const val KIND_ARTIST = "artist"
        const val SEED_COUNT = 8
        const val STATION_SEEDS = 3
        const val ARTIST_SEEDS = 3
        const val LIKE_WEIGHT = 2.0
        const val MIN_RECORD_MS = 5_000L
        const val SKIP_THRESHOLD_MS = 30_000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val PLAY_HALF_LIFE_MS = 21 * DAY_MS
        const val LIKE_HALF_LIFE_MS = 120 * DAY_MS
        const val HISTORY_WINDOW_MS = 180 * DAY_MS
        const val RECENT_WINDOW_MS = 2 * DAY_MS
        const val REFRESH_INTERVAL_MS = 4 * 60 * 60 * 1000L
        const val CANDIDATE_TTL_MS = 7 * DAY_MS
    }
}
