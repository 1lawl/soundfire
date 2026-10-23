package app.soundfire.data

enum class ArtworkSize(val suffix: String) {
    Small("t300x300"),
    Large("t500x500"),

    /** Full upload resolution, for the full-screen playing-now artwork. */
    Original("original"),
}

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val artistId: Long,
    val durationMs: Long,
    val artworkUrl: String?,
    val genre: String,
    val tags: List<String>,
    val plays: Long,
    val likes: Long,
    /** Unencrypted HLS transcoding; null when SoundCloud only offers DRM or preview streams. */
    val streamTranscodingUrl: String?,
    val trackAuthorization: String?,
) {
    val playable: Boolean get() = streamTranscodingUrl != null

    /** Stable hue used for generated artwork when a track has no cover. */
    val hue: Int get() = Math.floorMod(id * 47, 360L).toInt()

    fun artwork(size: ArtworkSize): String? = artworkUrl?.replace("-large.", "-${size.suffix}.")
}

data class Playlist(
    val id: Long,
    val title: String,
    val owner: String,
    val artworkUrl: String?,
    val trackIds: List<Long>,
)
