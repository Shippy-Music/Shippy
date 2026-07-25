package org.oxycblt.auxio.shippy.persistence.lastfm

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.PrimaryKey

@Entity(
    tableName = "lastfm_scrobble_outbox",
    indices = [Index("queuedAtEpochMs")],
)
data class LastFmScrobbleEntity(
    @PrimaryKey val id: String,
    val artist: String,
    val track: String,
    val album: String?,
    val durationSeconds: Int?,
    val startedAtEpochSeconds: Long,
    val queuedAtEpochMs: Long,
)

@Dao
interface LastFmScrobbleDao {
    @Query("SELECT * FROM lastfm_scrobble_outbox ORDER BY queuedAtEpochMs ASC, id ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<LastFmScrobbleEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: LastFmScrobbleEntity)

    @Query("DELETE FROM lastfm_scrobble_outbox WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)
}
