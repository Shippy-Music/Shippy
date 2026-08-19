/*
 * Copyright (c) 2026 Auxio Project
 * HistoryDao.kt is part of Auxio.
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

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.shippy.data.db.entity.PlayHistoryEntity

@Dao
internal abstract class HistoryDao {
    @Upsert protected abstract suspend fun upsert(entity: PlayHistoryEntity)

    @Query(
        """
        SELECT * FROM play_history
        ORDER BY started_at_epoch_ms DESC, listening_session_id DESC
        LIMIT :limit
        """
    )
    abstract suspend fun recent(limit: Int): List<PlayHistoryEntity>

    @Query(
        """
        SELECT * FROM play_history
        WHERE recording_id = :recordingId
        ORDER BY started_at_epoch_ms DESC, listening_session_id DESC
        LIMIT :limit
        """
    )
    abstract suspend fun forRecording(recordingId: String, limit: Int): List<PlayHistoryEntity>

    @Query(
        """
        SELECT COALESCE(SUM(active_listened_ms), 0) FROM play_history
        WHERE recording_id = :recordingId
        """
    )
    abstract suspend fun totalActiveListenedMs(recordingId: String): Long

    @Query(
        """
        SELECT * FROM play_history
        ORDER BY started_at_epoch_ms DESC, listening_session_id DESC
        """
    )
    abstract fun page(): PagingSource<Int, PlayHistoryEntity>

    open suspend fun save(entity: PlayHistoryEntity) {
        require(entity.activeListenedMs >= 0) { "Active listening time cannot be negative" }
        require(entity.lastPositionMs >= 0) { "Last position cannot be negative" }
        require(entity.endedAtEpochMs == null || entity.endedAtEpochMs >= entity.startedAtEpochMs) {
            "Listening session cannot end before it starts"
        }
        require(entity.completionKind.isNotBlank()) { "Completion kind must not be blank" }
        upsert(entity)
    }
}
