package app.soundfire.data.soundcloud

import app.soundfire.data.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiPage<T>(
    val collection: List<T> = emptyList(),
    @SerialName("next_href") val nextHref: String? = null,
    @SerialName("total_results") val totalResults: Int? = null,
)

@Serializable
data class ApiUser(
    val id: Long,
    val username: String? = null,
    val permalink: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("likes_count") val likesCount: Int? = null,
)

@Serializable
data class ApiFormat(
    val protocol: String = "",
    @SerialName("mime_type") val mimeType: String = "",
)

@Serializable
data class ApiTranscoding(
    val url: String = "",
    val format: ApiFormat = ApiFormat(),
    val snipped: Boolean = false,
    val quality: String? = null,
)

@Serializable
data class ApiMedia(val transcodings: List<ApiTranscoding> = emptyList())

@Serializable
data class ApiPublisher(val artist: String? = null)

@Serializable
data class ApiTrack(
    val id: Long,
    val title: String? = null,
    val duration: Long = 0,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    val genre: String? = null,
    @SerialName("tag_list") val tagList: String? = null,
    @SerialName("playback_count") val playbackCount: Long? = null,
    @SerialName("likes_count") val likesCount: Long? = null,
    @SerialName("user_id") val userId: Long? = null,
    val user: ApiUser? = null,
    val media: ApiMedia? = null,
    @SerialName("track_authorization") val trackAuthorization: String? = null,
    @SerialName("publisher_metadata") val publisher: ApiPublisher? = null,
)

@Serializable
data class ApiLike(
    val track: ApiTrack? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class ApiChartItem(val track: ApiTrack? = null)

@Serializable
data class ApiQuery(val output: String = "")

@Serializable
data class ApiStream(val url: String)

private val TAG_PATTERN = Regex("\"([^\"]+)\"|(\\S+)")

/** Returns null for stub objects (station and like payloads sometimes omit metadata). */
fun ApiTrack.toTrack(): Track? {
    val title = title ?: return null
    val hls = media?.transcodings.orEmpty().filter { !it.snipped && it.format.protocol == "hls" }
    // "hq" needs a Go+ session; "audio/mpegurl" (MP3 HLS) is being retired and often 404s.
    val stream = hls.firstOrNull { it.format.mimeType.startsWith("audio/mp4") && it.quality != "hq" }
        ?: hls.firstOrNull { it.format.mimeType.startsWith("audio/mp4") }
        ?: hls.firstOrNull()
    val (artist, cleanTitle) = splitArtist(title, user?.username.orEmpty(), publisher?.artist)
    return Track(
        id = id,
        title = cleanTitle,
        artist = artist,
        artistId = user?.id ?: userId ?: 0L,
        durationMs = duration,
        artworkUrl = artworkUrl ?: user?.avatarUrl,
        genre = genre.orEmpty().trim(),
        tags = TAG_PATTERN.findAll(tagList.orEmpty())
            .map { (it.groups[1] ?: it.groups[2])!!.value.lowercase() }
            .toList(),
        plays = playbackCount ?: 0L,
        likes = likesCount ?: 0L,
        streamTranscodingUrl = stream?.url,
        trackAuthorization = trackAuthorization,
    )
}

/**
 * Uploads are often titled "Artist - Song". When the prefix names the uploader or the label's
 * credited artist, show it as the artist instead of repeating it in the title.
 */
private fun splitArtist(title: String, uploader: String, credited: String?): Pair<String, String> {
    val dash = title.indexOf(" - ")
    if (dash <= 0) return (credited?.takeIf { it.isNotBlank() } ?: uploader) to title
    val prefix = title.substring(0, dash).trim()
    val rest = title.substring(dash + 3).trim()
    val matches = listOfNotNull(uploader, credited).any { it.isNotBlank() && it.equals(prefix, ignoreCase = true) }
    return if (matches && rest.isNotEmpty()) prefix to rest else (credited?.takeIf { it.isNotBlank() } ?: uploader) to title
}
