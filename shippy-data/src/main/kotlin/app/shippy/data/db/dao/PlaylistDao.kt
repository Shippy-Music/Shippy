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

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.shippy.data.db.entity.PlaylistEntity
import app.shippy.data.db.entity.PlaylistEntryEntity
import app.shippy.data.db.util.SparseOrderKey
import kotlinx.coroutines.flow.Flow

internal data class PlaylistRecordingMembership(
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "occurrence_count") val occurrenceCount: Int,
)

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
        SELECT playlist_id, COUNT(*) AS occurrence_count
        FROM playlist_entry
        WHERE recording_id = :recordingId
        GROUP BY playlist_id
        """
    )
    abstract fun observeRecordingMemberships(
        recordingId: String
    ): Flow<List<PlaylistRecordingMembership>>

    @Query(
        """
        DELETE FROM playlist_entry
        WHERE playlist_id = :playlistId AND recording_id = :recordingId
        """
    )
    abstract suspend fun deleteEntriesForRecording(playlistId: String, recordingId: String): Int

    @Query("SELECT * FROM playlist_entry WHERE recording_id = :recordingId")
    abstract suspend fun entriesForRecording(recordingId: String): List<PlaylistEntryEntity>

    @Query("SELECT * FROM playlist_entry WHERE playlist_entry_id = :playlistEntryId")
    abstract suspend fun entry(playlistEntryId: String): PlaylistEntryEntity?

    @Query(
        "UPDATE playlist_entry SET recording_id = :newRecordingId WHERE playlist_entry_id = :playlistEntryId"
    )
    abstract suspend fun reassignEntryRecordingId(
        playlistEntryId: String,
        newRecordingId: String,
    ): Int

    @Query(
        "UPDATE playlist_entry SET recording_id = :newRecordingId WHERE recording_id = :oldRecordingId"
    )
    abstract suspend fun reassignEntriesForRecording(
        oldRecordingId: String,
        newRecordingId: String,
    ): Int

    @Query(
        """
        SELECT * FROM playlist_entry
        WHERE playlist_id = :playlistId
        ORDER BY order_key, playlist_entry_id
        """
    )
    abstract suspend fun entries(playlistId: String): List<PlaylistEntryEntity>

    /** The current append anchor without materializing the entire playlist in the common case. */
    @Query(
        """
        SELECT * FROM playlist_entry
        WHERE playlist_id = :playlistId
        ORDER BY order_key DESC, playlist_entry_id DESC
        LIMIT 1
        """
    )
    abstract suspend fun lastEntry(playlistId: String): PlaylistEntryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertPlaylist(entity: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertEntries(entities: List<PlaylistEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertEntry(entity: PlaylistEntryEntity)

    @Update protected abstract suspend fun updateEntries(entities: List<PlaylistEntryEntity>)

    /** Rewrites only order keys for the exact current playlist occurrences. */
    suspend fun rekeyEntries(entries: List<PlaylistEntryEntity>) {
        if (entries.isNotEmpty()) updateEntries(entries)
    }

    @Query(
        """
        SELECT * FROM playlist_entry
        WHERE playlist_id = :playlistId AND playlist_entry_id = :playlistEntryId
        LIMIT 1
        """
    )
    protected abstract suspend fun exactEntry(
        playlistId: String,
        playlistEntryId: String,
    ): PlaylistEntryEntity?

    @Query(
        """
        SELECT * FROM playlist_entry
        WHERE playlist_id = :playlistId
          AND (
            order_key < :orderKey
            OR (order_key = :orderKey AND playlist_entry_id < :playlistEntryId)
          )
        ORDER BY order_key DESC, playlist_entry_id DESC
        LIMIT 1
        """
    )
    protected abstract suspend fun previousEntry(
        playlistId: String,
        playlistEntryId: String,
        orderKey: Long,
    ): PlaylistEntryEntity?

    @Query(
        """
        SELECT * FROM playlist_entry
        WHERE playlist_id = :playlistId
          AND (
            order_key > :orderKey
            OR (order_key = :orderKey AND playlist_entry_id > :playlistEntryId)
          )
        ORDER BY order_key, playlist_entry_id
        LIMIT 1
        """
    )
    protected abstract suspend fun nextEntry(
        playlistId: String,
        playlistEntryId: String,
        orderKey: Long,
    ): PlaylistEntryEntity?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM playlist_entry
            WHERE playlist_id = :playlistId
              AND order_key IN (:firstOrderKey, :secondOrderKey)
              AND playlist_entry_id NOT IN (:firstEntryId, :secondEntryId)
        )
        """
    )
    protected abstract suspend fun hasThirdEntryAtEitherOrderKey(
        playlistId: String,
        firstEntryId: String,
        firstOrderKey: Long,
        secondEntryId: String,
        secondOrderKey: Long,
    ): Boolean

    @Query(
        """
        UPDATE playlist_entry
        SET order_key = CASE playlist_entry_id
            WHEN :firstEntryId THEN :secondOrderKey
            WHEN :secondEntryId THEN :firstOrderKey
            ELSE order_key
        END
        WHERE playlist_id = :playlistId
          AND playlist_entry_id IN (:firstEntryId, :secondEntryId)
        """
    )
    protected abstract suspend fun swapOrderKeys(
        playlistId: String,
        firstEntryId: String,
        firstOrderKey: Long,
        secondEntryId: String,
        secondOrderKey: Long,
    ): Int

    @Delete abstract suspend fun deleteEntries(entities: List<PlaylistEntryEntity>)

    @Query(
        """
        DELETE FROM playlist_entry
        WHERE playlist_id = :playlistId AND playlist_entry_id = :playlistEntryId
        """
    )
    abstract suspend fun deleteEntry(playlistId: String, playlistEntryId: String): Int

    @Query(
        """
        DELETE FROM playlist_entry
        WHERE playlist_id = :playlistId AND playlist_entry_id IN (:playlistEntryIds)
        """
    )
    abstract suspend fun deleteEntriesByIds(playlistId: String, playlistEntryIds: Set<String>): Int

    @Query(
        """
        UPDATE playlist
        SET name = :name, updated_at_epoch_ms = :updatedAtEpochMs
        WHERE playlist_id = :playlistId
        """
    )
    abstract suspend fun renamePlaylist(
        playlistId: String,
        name: String,
        updatedAtEpochMs: Long,
    ): Int

    @Query(
        """
        UPDATE playlist
        SET display_sort_mode = :displaySortMode,
            display_sort_direction = :displaySortDirection,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE playlist_id = :playlistId
        """
    )
    abstract suspend fun updateDisplaySort(
        playlistId: String,
        displaySortMode: String,
        displaySortDirection: String,
        updatedAtEpochMs: Long,
    ): Int

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

    @Transaction
    open suspend fun moveAdjacent(
        playlistId: String,
        playlistEntryId: String,
        direction: PlaylistAdjacentMoveDirection,
    ): PlaylistAdjacentMoveResult {
        val moving =
            exactEntry(playlistId, playlistEntryId) ?: return PlaylistAdjacentMoveResult.NOT_FOUND
        val neighbor =
            when (direction) {
                PlaylistAdjacentMoveDirection.TOWARD_START ->
                    previousEntry(playlistId, moving.playlistEntryId, moving.orderKey)
                PlaylistAdjacentMoveDirection.TOWARD_END ->
                    nextEntry(playlistId, moving.playlistEntryId, moving.orderKey)
            } ?: return PlaylistAdjacentMoveResult.AT_BOUNDARY

        val involvedKeyIsShared =
            hasThirdEntryAtEitherOrderKey(
                playlistId = playlistId,
                firstEntryId = moving.playlistEntryId,
                firstOrderKey = moving.orderKey,
                secondEntryId = neighbor.playlistEntryId,
                secondOrderKey = neighbor.orderKey,
            )
        if (moving.orderKey != neighbor.orderKey && !involvedKeyIsShared) {
            check(
                swapOrderKeys(
                    playlistId = playlistId,
                    firstEntryId = moving.playlistEntryId,
                    firstOrderKey = moving.orderKey,
                    secondEntryId = neighbor.playlistEntryId,
                    secondOrderKey = neighbor.orderKey,
                ) == 2
            ) {
                "Playlist occurrences disappeared during adjacent move"
            }
            return PlaylistAdjacentMoveResult.MOVED
        }

        // Imported/corrupt tied keys can turn a two-row swap into a multi-position jump.
        // Repair only this playlist, retaining the exact adjacent intent.
        val reordered = entries(playlistId).toMutableList()
        val movingIndex = reordered.indexOfFirst { it.playlistEntryId == moving.playlistEntryId }
        val neighborIndex =
            reordered.indexOfFirst { it.playlistEntryId == neighbor.playlistEntryId }
        check(
            movingIndex >= 0 &&
                neighborIndex >= 0 &&
                kotlin.math.abs(movingIndex - neighborIndex) == 1
        ) {
            "Adjacent playlist occurrences changed during move"
        }
        reordered[movingIndex] = neighbor
        reordered[neighborIndex] = moving
        val keys = SparseOrderKey.rebalancedKeys(reordered.size)
        updateEntries(reordered.mapIndexed { index, entry -> entry.copy(orderKey = keys[index]) })
        return PlaylistAdjacentMoveResult.MOVED
    }
}

internal enum class PlaylistAdjacentMoveDirection {
    TOWARD_START,
    TOWARD_END,
}

internal enum class PlaylistAdjacentMoveResult {
    MOVED,
    AT_BOUNDARY,
    NOT_FOUND,
}
