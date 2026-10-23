package app.soundfire.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import app.soundfire.data.Track
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val artistId: Long,
    val durationMs: Long,
    val artworkUrl: String?,
    val genre: String,
    val tags: String,
    val plays: Long,
    val likes: Long,
    val transcodingUrl: String?,
    val trackAuthorization: String?,
    val updatedAt: Long,
)

@Entity(tableName = "play_events", indices = [Index("trackId"), Index("at")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val artistId: Long,
    val at: Long,
    val listenedMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val skipped: Boolean,
)

@Entity(tableName = "liked_seeds")
data class LikedSeedEntity(
    @PrimaryKey val trackId: Long,
    val artistId: Long,
    val likedAt: Long,
)

/** A recommendation candidate and where it came from: a seed track's related list, station, or artist. */
@Entity(tableName = "candidates", primaryKeys = ["trackId", "seedId", "kind"])
data class CandidateEntity(
    val trackId: Long,
    val seedId: Long,
    val kind: String,
    val rank: Int,
    val fetchedAt: Long,
)

@Dao
abstract class TasteDao {
    @Upsert
    abstract suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    abstract suspend fun tracks(ids: List<Long>): List<TrackEntity>

    @Insert
    abstract suspend fun insertEvent(event: PlayEventEntity)

    @Query("SELECT * FROM play_events WHERE at >= :since")
    abstract suspend fun eventsSince(since: Long): List<PlayEventEntity>

    @Upsert
    abstract suspend fun upsertLikes(likes: List<LikedSeedEntity>)

    @Query("SELECT * FROM liked_seeds")
    abstract suspend fun likes(): List<LikedSeedEntity>

    @Query("SELECT COUNT(*) FROM liked_seeds")
    abstract fun likeCount(): Flow<Int>

    @Query("SELECT * FROM candidates")
    abstract suspend fun candidates(): List<CandidateEntity>

    @Query("DELETE FROM candidates WHERE seedId = :seedId AND kind = :kind")
    abstract suspend fun clearCandidates(seedId: Long, kind: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertCandidates(candidates: List<CandidateEntity>)

    @Query("DELETE FROM candidates WHERE fetchedAt < :before")
    abstract suspend fun pruneCandidates(before: Long)

    @Transaction
    open suspend fun replaceCandidates(seedId: Long, kind: String, candidates: List<CandidateEntity>) {
        clearCandidates(seedId, kind)
        insertCandidates(candidates)
    }
}

@Database(
    entities = [TrackEntity::class, PlayEventEntity::class, LikedSeedEntity::class, CandidateEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class SoundFireDatabase : RoomDatabase() {
    abstract fun taste(): TasteDao
}

private const val TAG_SEPARATOR = "\u001F"

fun Track.toEntity(now: Long = System.currentTimeMillis()) = TrackEntity(
    id = id,
    title = title,
    artist = artist,
    artistId = artistId,
    durationMs = durationMs,
    artworkUrl = artworkUrl,
    genre = genre,
    tags = tags.joinToString(TAG_SEPARATOR),
    plays = plays,
    likes = likes,
    transcodingUrl = streamTranscodingUrl,
    trackAuthorization = trackAuthorization,
    updatedAt = now,
)

fun TrackEntity.toTrack() = Track(
    id = id,
    title = title,
    artist = artist,
    artistId = artistId,
    durationMs = durationMs,
    artworkUrl = artworkUrl,
    genre = genre,
    tags = if (tags.isEmpty()) emptyList() else tags.split(TAG_SEPARATOR),
    plays = plays,
    likes = likes,
    streamTranscodingUrl = transcodingUrl,
    trackAuthorization = trackAuthorization,
)
