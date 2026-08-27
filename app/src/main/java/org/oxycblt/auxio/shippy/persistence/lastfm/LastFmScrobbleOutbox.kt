/*
 * Copyright (c) 2026 Auxio Project
 * LastFmScrobbleOutbox.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.oxycblt.auxio.shippy.persistence.lastfm

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "lastfm_scrobble_outbox", indices = [Index("queuedAtEpochMs")])
data class LastFmScrobbleEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val artist: String,
    val track: String,
    val album: String?,
    val durationSeconds: Int?,
    val startedAtEpochSeconds: Long,
    val queuedAtEpochMs: Long,
)

@Dao
interface LastFmScrobbleDao {
    @Query(
        "SELECT * FROM lastfm_scrobble_outbox WHERE accountId = :accountId ORDER BY queuedAtEpochMs ASC, id ASC LIMIT :limit"
    )
    suspend fun oldest(accountId: String, limit: Int): List<LastFmScrobbleEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(entry: LastFmScrobbleEntity)

    @Query("DELETE FROM lastfm_scrobble_outbox WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)
}
