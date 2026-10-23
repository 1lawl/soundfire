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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

/** A playlist the user made or liked; [trackIds] keeps its order (comma separated). */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val owner: String,
    val artworkUrl: String?,
    val trackIds: String,
    val position: Int,
    val updatedAt: Long,
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
    protected abstract suspend fun tracksChunk(ids: List<Long>): List<TrackEntity>

    /** Chunked: Android 11's SQLite allows at most 999 bound parameters per statement. */
    open suspend fun tracks(ids: List<Long>): List<TrackEntity> = ids.chunked(MAX_SQL_PARAMS).flatMap { tracksChunk(it) }

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

    @Query("SELECT trackId FROM liked_seeds")
    abstract suspend fun likedIds(): List<Long>

    @Query("DELETE FROM liked_seeds")
    protected abstract suspend fun clearLikes()

    /** Full re-sync: drops tracks that were unliked on SoundCloud since the last one. */
    @Transaction
    open suspend fun replaceLikes(likes: List<LikedSeedEntity>) {
        clearLikes()
        upsertLikes(likes)
    }

    @Query("SELECT * FROM playlists ORDER BY position")
    abstract suspend fun playlists(): List<PlaylistEntity>

    @Query("DELETE FROM playlists")
    protected abstract suspend fun clearPlaylists()

    @Upsert
    protected abstract suspend fun upsertPlaylists(playlists: List<PlaylistEntity>)

    @Transaction
    open suspend fun replacePlaylists(playlists: List<PlaylistEntity>) {
        clearPlaylists()
        upsertPlaylists(playlists)
    }

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
    entities = [TrackEntity::class, PlayEventEntity::class, LikedSeedEntity::class, CandidateEntity::class, PlaylistEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class SoundFireDatabase : RoomDatabase() {
    abstract fun taste(): TasteDao

    companion object {
        /** v2 adds playlists; listening history and imported likes are kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                        "`owner` TEXT NOT NULL, `artworkUrl` TEXT, `trackIds` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }
    }
}

private const val MAX_SQL_PARAMS = 900

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

fun PlaylistEntity.trackIdList(): List<Long> = trackIds.split(',').mapNotNull { it.toLongOrNull() }
