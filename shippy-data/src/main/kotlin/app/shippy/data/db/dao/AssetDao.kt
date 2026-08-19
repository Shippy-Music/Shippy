/*
 * Copyright (c) 2026 Auxio Project
 * AssetDao.kt is part of Auxio.
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
import app.shippy.data.db.entity.MediaAssetEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AssetDao {
    @Query("SELECT * FROM media_asset WHERE asset_id = :assetId")
    suspend fun get(assetId: String): MediaAssetEntity?

    @Query("SELECT * FROM media_asset WHERE location_type = :locationType AND location = :location")
    suspend fun exactLocation(locationType: String, location: String): MediaAssetEntity?

    @Query("SELECT * FROM media_asset WHERE recording_id = :recordingId")
    fun observeForRecording(recordingId: String): Flow<List<MediaAssetEntity>>

    @Query(
        """
        SELECT * FROM media_asset
        WHERE recording_id = :recordingId AND asset_state = 'AVAILABLE'
        ORDER BY asset_kind, asset_id
        """
    )
    suspend fun verifiedPlayable(recordingId: String): List<MediaAssetEntity>

    @Upsert suspend fun upsert(entity: MediaAssetEntity)

    @Query(
        """
        UPDATE media_asset SET
            asset_state = :state,
            updated_at_epoch_ms = :updatedAtEpochMs,
            last_verified_at_epoch_ms = :verifiedAtEpochMs
        WHERE asset_id = :assetId
        """
    )
    suspend fun updateState(
        assetId: String,
        state: String,
        updatedAtEpochMs: Long,
        verifiedAtEpochMs: Long?,
    ): Int
}
