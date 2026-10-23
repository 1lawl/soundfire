package app.soundfire.data.soundcloud

import android.util.Log
import app.soundfire.BuildConfig
import app.soundfire.data.Playlist
import app.soundfire.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** One page of a paged endpoint; [next] is SoundCloud's `next_href`, null on the last page. */
data class Page<T>(val items: List<T>, val next: String?, val total: Int? = null)

class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code for ${url.substringBefore('?')}")

/** Client for SoundCloud's private api-v2, the same API soundcloud.com uses anonymously. */
class SoundCloudApi(private val http: OkHttpClient, private val clientIds: ClientIdProvider) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    suspend fun searchTracks(query: String, limit: Int = 30, genreOrTag: String? = null): List<Track> =
        get(
            "/search/tracks",
            mapOf("q" to query, "limit" to "$limit", "filter.genre_or_tag" to genreOrTag),
            ApiPage.serializer(ApiTrack.serializer()),
        ).collection.mapNotNull { it.toTrack() }

    /** Paged track search, as on soundcloud.com/search/sounds. Pass the previous page's [Page.next]. */
    suspend fun searchTracksPage(query: String, next: String? = null): Page<Track> {
        val page = get(next ?: "/search/tracks", mapOf("q" to query, "limit" to "$PAGE_SIZE"), ApiPage.serializer(ApiTrack.serializer()))
        return Page(page.collection.mapNotNull { it.toTrack() }, page.nextHref, page.totalResults)
    }

    suspend fun autocomplete(query: String, limit: Int = 4): List<String> =
        get("/search/queries", mapOf("q" to query, "limit" to "$limit"), ApiPage.serializer(ApiQuery.serializer()))
            .collection.map { it.output }.filter { it.isNotBlank() }

    suspend fun tracks(ids: List<Long>): List<Track> =
        ids.distinct().chunked(50).flatMap { chunk ->
            get("/tracks", mapOf("ids" to chunk.joinToString(",")), ListSerializer(ApiTrack.serializer()))
                .mapNotNull { it.toTrack() }
        }

    suspend fun related(trackId: Long, limit: Int = 20): List<Track> =
        get("/tracks/$trackId/related", mapOf("limit" to "$limit"), ApiPage.serializer(ApiTrack.serializer()))
            .collection.mapNotNull { it.toTrack() }

    suspend fun station(trackId: Long, limit: Int = 30): List<Track> {
        val page = get(
            "/stations/soundcloud:track-stations:$trackId/tracks",
            mapOf("limit" to "$limit"),
            ApiPage.serializer(ApiTrack.serializer()),
        )
        return hydrate(page.collection)
    }

    suspend fun artistTracks(userId: Long, limit: Int = 12): List<Track> =
        get("/users/$userId/tracks", mapOf("limit" to "$limit"), ApiPage.serializer(ApiTrack.serializer()))
            .collection.mapNotNull { it.toTrack() }

    suspend fun trending(limit: Int = 20): List<Track> =
        get(
            "/charts",
            mapOf("kind" to "trending", "genre" to "soundcloud:genres:all-music", "limit" to "$limit"),
            ApiPage.serializer(ApiChartItem.serializer()),
        ).collection.mapNotNull { it.track?.toTrack() }

    suspend fun resolveUser(username: String): ApiUser =
        get(
            "/resolve",
            mapOf("url" to "https://soundcloud.com/${username.trim().lowercase()}"),
            ApiUser.serializer(),
        )

    /** Playlists a user made, followed by playlists they liked (albums included), up to [limit] of each. */
    suspend fun playlists(userId: Long, limit: Int = 50): List<Playlist> {
        val own = get("/users/$userId/playlists", mapOf("limit" to "$limit"), ApiPage.serializer(ApiPlaylist.serializer())).collection
        val liked = get("/users/$userId/playlist_likes", mapOf("limit" to "$limit"), ApiPage.serializer(ApiPlaylistLike.serializer()))
            .collection.mapNotNull { it.playlist }
        return (own + liked).distinctBy { it.id }.mapNotNull { p ->
            Playlist(
                id = p.id,
                title = p.title ?: return@mapNotNull null,
                owner = p.user?.username.orEmpty(),
                artworkUrl = p.artworkUrl,
                trackIds = emptyList(),
            )
        }
    }

    /** A playlist's tracks in order; SoundCloud only sends the first few in full, so the rest are hydrated. */
    suspend fun playlistTracks(playlistId: Long): List<Track> =
        hydrate(get("/playlists/$playlistId", emptyMap(), ApiPlaylist.serializer()).tracks)

    /** One page of a user's public track likes, newest first. */
    suspend fun likesPage(userId: Long, next: String? = null): Page<Pair<Track, Long>> {
        val page = get(next ?: "/users/$userId/track_likes", mapOf("limit" to "$LIKES_PAGE_SIZE"), ApiPage.serializer(ApiLike.serializer()))
        val hydrated = hydrate(page.collection.mapNotNull { it.track }).associateBy { it.id }
        val items = page.collection.mapNotNull { like ->
            val track = like.track?.let { hydrated[it.id] } ?: return@mapNotNull null
            track to (like.createdAt?.let(::parseTimestamp) ?: System.currentTimeMillis())
        }
        return Page(items, page.nextHref)
    }

    /** Every public track like a user has, reporting the running count as pages arrive. */
    suspend fun allLikes(userId: Long, onProgress: (Int) -> Unit = {}): List<Pair<Track, Long>> {
        val likes = mutableListOf<Pair<Track, Long>>()
        var next: String? = null
        // Follow next_href to the end: a page can be empty after filtering out deleted or private
        // tracks without being the last one.
        do {
            val page = likesPage(userId, next)
            likes += page.items
            onProgress(likes.size)
            next = page.next?.takeIf { it != next }
        } while (next != null)
        return likes
    }

    /**
     * Resolves a track's HLS playlist URL. Authorizations expire, so a rejected request
     * re-fetches the track once for a fresh one.
     */
    suspend fun streamUrl(track: Track): String {
        val transcoding = track.streamTranscodingUrl ?: throw IOException("${track.title} has no playable stream")
        return try {
            get(transcoding, mapOf("track_authorization" to track.trackAuthorization), ApiStream.serializer()).url
        } catch (e: HttpStatusException) {
            if (e.code !in setOf(401, 403, 404)) throw e
            val fresh = tracks(listOf(track.id)).firstOrNull() ?: throw e
            get(
                fresh.streamTranscodingUrl ?: throw e,
                mapOf("track_authorization" to fresh.trackAuthorization),
                ApiStream.serializer(),
            ).url
        }
    }

    /** Fills in stub tracks that came back without metadata. */
    private suspend fun hydrate(items: List<ApiTrack>): List<Track> {
        val full = items.mapNotNull { it.toTrack() }.associateBy { it.id }
        val missing = items.map { it.id }.filterNot { it in full }
        val fetched = if (missing.isEmpty()) emptyMap() else tracks(missing).associateBy { it.id }
        return items.mapNotNull { full[it.id] ?: fetched[it.id] }
    }

    private suspend fun <T> get(
        pathOrUrl: String,
        params: Map<String, String?>,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val clientId = clientIds.get()
        return try {
            request(pathOrUrl, params, clientId, deserializer)
        } catch (e: HttpStatusException) {
            if (e.code != 401 && e.code != 403) throw e
            request(pathOrUrl, params, clientIds.invalidate(clientId), deserializer)
        }
    }

    private suspend fun <T> request(
        pathOrUrl: String,
        params: Map<String, String?>,
        clientId: String,
        deserializer: DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        val url = buildUrl(pathOrUrl, params, clientId)
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code, url.toString())
            json.decodeFromString(deserializer, response.body.string()).also {
                if (BuildConfig.DEBUG) Log.d(TAG, "GET ${url.encodedPath} -> ${response.code}")
            }
        }
    }

    private fun buildUrl(pathOrUrl: String, params: Map<String, String?>, clientId: String): HttpUrl {
        val base = if (pathOrUrl.startsWith("http")) pathOrUrl.toHttpUrl() else "$API_HOST$pathOrUrl".toHttpUrl()
        return base.newBuilder().apply {
            // next_href links already carry paging params; only add what they lack.
            params.forEach { (key, value) ->
                if (value != null && base.queryParameter(key) == null) addQueryParameter(key, value)
            }
            setQueryParameter("client_id", clientId)
        }.build()
    }

    private fun parseTimestamp(value: String): Long? = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(value)?.time
    }.getOrNull()

    private companion object {
        const val TAG = "SoundCloudApi"
        const val API_HOST = "https://api-v2.soundcloud.com"
        const val PAGE_SIZE = 30
        const val LIKES_PAGE_SIZE = 200
    }
}
