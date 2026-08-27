/*
 * Copyright (c) 2026 Auxio Project
 * LastFmOutboxDao.kt is part of Auxio.
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
package app.shippy.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.shippy.data.db.entity.LastFmScrobbleOutboxEntity

@Dao
internal interface LastFmOutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(entity: LastFmScrobbleOutboxEntity): Long

    @Query(
        """
        SELECT * FROM lastfm_scrobble_outbox
        ORDER BY queued_at_epoch_ms, outbox_id
        LIMIT :limit
        """
    )
    suspend fun oldest(limit: Int): List<LastFmScrobbleOutboxEntity>

    @Query(
        """
        SELECT * FROM lastfm_scrobble_outbox
        WHERE account_id = :accountId
        ORDER BY queued_at_epoch_ms, outbox_id
        LIMIT :limit
        """
    )
    suspend fun oldest(accountId: String, limit: Int): List<LastFmScrobbleOutboxEntity>

    @Query(
        """
        UPDATE lastfm_scrobble_outbox SET
            attempt_count = attempt_count + 1,
            last_attempt_at_epoch_ms = :attemptedAtEpochMs
        WHERE outbox_id = :outboxId
        """
    )
    suspend fun recordAttempt(outboxId: String, attemptedAtEpochMs: Long): Int

    @Query("DELETE FROM lastfm_scrobble_outbox WHERE outbox_id IN (:outboxIds)")
    suspend fun deleteAccepted(outboxIds: Set<String>): Int

    @Query("SELECT COUNT(*) FROM lastfm_scrobble_outbox") suspend fun count(): Int
}
