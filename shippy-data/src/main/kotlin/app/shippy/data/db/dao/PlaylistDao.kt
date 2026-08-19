/*
 * Copyright (c) 2026 Auxio Project
 * PlaylistDao.kt is part of Auxio.
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
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.shippy.data.db.entity.PlaylistEntity
import app.shippy.data.db.entity.PlaylistEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class PlaylistDao {
    @Query("SELECT * FROM playlist WHERE playlist_id = :playlistId")
    abstract fun observe(playlistId: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlist WHERE playlist_id = :playlistId")
    abstract suspend fun get(playlistId: String): PlaylistEntity?

    @Query(
        """
        SELECT * FROM playlist_entry
        WHERE playlist_id = :playlistId
        ORDER BY order_key, playlist_entry_id
        """
    )
    abstract fun observeEntries(playlistId: String): Flow<List<PlaylistEntryEntity>>

    @Query(
        """
        SELECT * FROM playlist_entry
        WHERE playlist_id = :playlistId
        ORDER BY order_key, playlist_entry_id
        """
    )
    abstract suspend fun entries(playlistId: String): List<PlaylistEntryEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertPlaylist(entity: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertEntries(entities: List<PlaylistEntryEntity>)

    @Update protected abstract suspend fun updateEntries(entities: List<PlaylistEntryEntity>)

    @Delete abstract suspend fun deleteEntries(entities: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist WHERE playlist_id = :playlistId")
    abstract suspend fun deletePlaylist(playlistId: String): Int

    @Transaction
    open suspend fun create(playlist: PlaylistEntity, initialEntries: List<PlaylistEntryEntity>) {
        require(initialEntries.all { it.playlistId == playlist.playlistId }) {
            "Initial entries must belong to the created playlist"
        }
        require(
            initialEntries.map(PlaylistEntryEntity::playlistEntryId).toSet().size ==
                initialEntries.size
        ) {
            "Playlist entry identities must be unique"
        }
        insertPlaylist(playlist)
        if (initialEntries.isNotEmpty()) insertEntries(initialEntries)
    }

    @Transaction
    open suspend fun replaceOrder(playlistId: String, orderedEntries: List<PlaylistEntryEntity>) {
        val existingIds = entries(playlistId).map(PlaylistEntryEntity::playlistEntryId).toSet()
        require(orderedEntries.all { it.playlistId == playlistId }) {
            "Reordered entries must belong to the playlist"
        }
        require(orderedEntries.map(PlaylistEntryEntity::playlistEntryId).toSet() == existingIds) {
            "Reorder must contain every current playlist occurrence exactly once"
        }
        updateEntries(orderedEntries)
    }
}
