package app.soundfire.player

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import app.soundfire.data.ArtworkSize
import app.soundfire.data.Track
import app.soundfire.data.soundcloud.SoundCloudApi
import app.soundfire.taste.TasteEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

data class PlayerState(
    val current: Track? = null,
    val upNext: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
)

/**
 * App-wide playback. Queue items use a `soundfire://track/{id}` URI that is resolved to a fresh
 * HLS playlist only when ExoPlayer loads it, so signed stream URLs never go stale in the queue.
 */
class PlayerController(
    context: Context,
    private val api: SoundCloudApi,
    private val taste: TasteEngine,
    http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val tracks = ConcurrentHashMap<Long, Track>()
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val dataSourceFactory = ResolvingDataSource.Factory(OkHttpDataSource.Factory(http)) { spec ->
        if (spec.uri.scheme != SCHEME) return@Factory spec
        val id = spec.uri.lastPathSegment!!.toLong()
        // Runs on ExoPlayer's loader thread, where blocking is expected.
        val url = runBlocking { api.streamUrl(tracks[id] ?: api.tracks(listOf(id)).first()) }
        spec.withUri(url.toUri())
    }

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(SEEK_STEP_MS)
        .setSeekForwardIncrementMs(SEEK_STEP_MS)
        .build()

    // Lets Fire OS route media keys and Alexa voice commands ("Alexa, pause") to the player.
    private val session = MediaSession.Builder(context, player).build()

    private var lastTrack: Track? = null
    private var lastPositionMs = 0L
    private var recoveryAttempts = 0
    private var extending = false

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                recordFinished(completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                lastTrack = currentTrack()
                lastPositionMs = 0
                recoveryAttempts = 0
                publish()
                extendQueueIfNeeded()
            }

            override fun onEvents(player: Player, events: Player.Events) = publish()

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    recordFinished(completed = true)
                    extendQueueIfNeeded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Playback error ${error.errorCodeName}", error)
                // Signed stream URLs can expire mid-track (the old ~40 minute cut-off). Re-preparing
                // re-resolves the playlist and resumes where we were.
                if (recoveryAttempts++ < MAX_RECOVERY_ATTEMPTS) {
                    player.prepare()
                    player.play()
                } else {
                    _state.value = _state.value.copy(error = "Couldn't play this track")
                    player.seekToNextMediaItem()
                    player.prepare()
                }
            }
        })
        scope.launch {
            while (true) {
                if (player.isPlaying) {
                    lastPositionMs = player.currentPosition
                    publish()
                }
                delay(TICK_MS)
            }
        }
    }

    /** Plays [queue] from [startIndex]; unplayable (DRM-only) tracks are skipped. */
    fun play(queue: List<Track>, startIndex: Int = 0) {
        val start = queue.getOrNull(startIndex) ?: return
        val playable = queue.drop(startIndex).filter { it.playable }
        Log.i(TAG, "play(${start.id} “${start.title}”), queue of ${playable.size}")
        if (playable.isEmpty()) {
            _state.value = _state.value.copy(error = "“${start.title}” isn't available to stream")
            return
        }
        recordFinished(completed = false)
        playable.forEach { tracks[it.id] = it }
        player.setMediaItems(playable.map(::mediaItem), 0, 0L)
        player.prepare()
        player.play()
    }

    fun togglePlayPause() {
        if (player.mediaItemCount == 0) return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun pause() = player.pause()

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun seekBy(deltaMs: Long) {
        if (player.mediaItemCount == 0) return
        val target = (player.currentPosition + deltaMs).coerceIn(0, player.duration.coerceAtLeast(0))
        player.seekTo(target)
        lastPositionMs = target
        publish()
    }

    fun stop() {
        recordFinished(completed = false)
        player.stop()
        player.clearMediaItems()
        lastTrack = null
        publish()
    }

    fun release() {
        session.release()
        player.release()
    }

    private fun currentTrack(): Track? = player.currentMediaItem?.mediaId?.toLongOrNull()?.let(tracks::get)

    private fun recordFinished(completed: Boolean) {
        val track = lastTrack ?: return
        val listened = if (completed) track.durationMs else lastPositionMs
        lastTrack = null
        scope.launch { runCatching { taste.recordPlay(track, listened, completed) } }
    }

    /** Related-track autoplay: keep at least two tracks ahead of the current one. */
    private fun extendQueueIfNeeded() {
        val current = currentTrack() ?: return
        val remaining = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (remaining >= 2 || extending) return
        extending = true
        scope.launch {
            try {
                val queued = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }
                val next = taste.nextFor(current, exclude = queued.toSet(), count = 5)
                next.forEach { tracks[it.id] = it }
                player.addMediaItems(next.map(::mediaItem))
                if (player.playbackState == Player.STATE_ENDED && next.isNotEmpty()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Autoplay lookup failed", e)
            } finally {
                extending = false
                publish()
            }
        }
    }

    private fun publish() {
        val nextIndex = player.nextMediaItemIndex
        _state.value = PlayerState(
            current = currentTrack(),
            upNext = if (nextIndex == C.INDEX_UNSET) null else player.getMediaItemAt(nextIndex).mediaId.toLongOrNull()?.let(tracks::get),
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = currentTrack()?.durationMs ?: player.duration.coerceAtLeast(0),
            error = _state.value.error,
        )
    }

    private fun mediaItem(track: Track) = MediaItem.Builder()
        .setMediaId(track.id.toString())
        .setUri("$SCHEME://track/${track.id}")
        .setMimeType(MimeTypes.APPLICATION_M3U8)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setArtworkUri(track.artwork(ArtworkSize.Large)?.toUri())
                .build(),
        )
        .build()

    private companion object {
        const val TAG = "PlayerController"
        const val SCHEME = "soundfire"
        const val SEEK_STEP_MS = 10_000L
        const val TICK_MS = 500L
        const val MAX_RECOVERY_ATTEMPTS = 3
    }
}
