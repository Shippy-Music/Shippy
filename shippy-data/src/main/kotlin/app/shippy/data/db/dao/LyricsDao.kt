/*
 * Copyright (c) 2026 Auxio Project
 * LyricsDao.kt is part of Auxio.
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
import androidx.room.Query
import androidx.room.Upsert
import app.shippy.data.db.entity.LyricsCacheEntity

@Dao
internal interface LyricsDao {
    @Query(
        """
        SELECT * FROM lyrics_cache
        WHERE recording_id = :recordingId
          AND metadata_fingerprint = :metadataFingerprint
          AND provider_id = :providerId
        """
    )
    suspend fun exact(
        recordingId: String,
        metadataFingerprint: String,
        providerId: String,
    ): LyricsCacheEntity?

    @Query(
        """
        SELECT * FROM lyrics_cache
        WHERE recording_id = :recordingId AND metadata_fingerprint = :metadataFingerprint
        ORDER BY cached_at_epoch_ms DESC, provider_id
        """
    )
    suspend fun forIdentity(
        recordingId: String,
        metadataFingerprint: String,
    ): List<LyricsCacheEntity>

    @Upsert suspend fun upsert(entity: LyricsCacheEntity)

    @Query(
        """
        DELETE FROM lyrics_cache
        WHERE expires_at_epoch_ms IS NOT NULL AND expires_at_epoch_ms < :nowEpochMs
        """
    )
    suspend fun deleteExpired(nowEpochMs: Long): Int
}
