/*
 * Copyright (c) 2026 Auxio Project
 * SavedSourceDao.kt is part of Auxio.
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
import app.shippy.data.db.entity.SavedSourceEntity

@Dao
internal interface SavedSourceDao {
    @Query(
        """
        SELECT * FROM saved_source_entity
        WHERE provider_id = :providerId
          AND entity_type = :entityType
          AND source_item_id = :sourceItemId
        """
    )
    suspend fun exact(
        providerId: String,
        entityType: String,
        sourceItemId: String,
    ): SavedSourceEntity?

    @Query(
        """
        SELECT * FROM saved_source_entity
        ORDER BY pinned DESC, saved_at_epoch_ms DESC, provider_id, entity_type, source_item_id
        LIMIT :limit
        """
    )
    suspend fun library(limit: Int): List<SavedSourceEntity>

    @Upsert suspend fun upsert(entity: SavedSourceEntity)

    @Query(
        """
        DELETE FROM saved_source_entity
        WHERE provider_id = :providerId
          AND entity_type = :entityType
          AND source_item_id = :sourceItemId
        """
    )
    suspend fun delete(providerId: String, entityType: String, sourceItemId: String): Int
}
