/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackCheckpointDao.kt is part of Auxio.
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
import androidx.room.Transaction
import androidx.room.Upsert
import app.shippy.data.db.entity.PlaybackCheckpointEntity
import app.shippy.data.db.entity.PlaybackCheckpointEntryEntity

internal data class StoredPlaybackCheckpoint(
    val checkpoint: PlaybackCheckpointEntity,
    val entries: List<PlaybackCheckpointEntryEntity>,
)

@Dao
internal abstract class PlaybackCheckpointDao {
    @Query("SELECT * FROM playback_checkpoint WHERE slot = :slot")
    protected abstract suspend fun checkpoint(slot: String): PlaybackCheckpointEntity?

    @Query(
        """
        SELECT * FROM playback_checkpoint_entry
        WHERE slot = :slot
        ORDER BY position, queue_entry_id
        """
    )
    protected abstract suspend fun entries(slot: String): List<PlaybackCheckpointEntryEntity>

    @Upsert protected abstract suspend fun upsertCheckpoint(entity: PlaybackCheckpointEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEntries(entities: List<PlaybackCheckpointEntryEntity>)

    @Query("DELETE FROM playback_checkpoint_entry WHERE slot = :slot")
    protected abstract suspend fun deleteEntries(slot: String): Int

    @Query("DELETE FROM playback_checkpoint WHERE slot = :slot")
    protected abstract suspend fun deleteCheckpoint(slot: String): Int

    @Transaction
    open suspend fun load(slot: String): StoredPlaybackCheckpoint? {
        val stored = checkpoint(slot) ?: return null
        return StoredPlaybackCheckpoint(stored, entries(slot))
    }

    @Transaction
    open suspend fun replace(
        checkpoint: PlaybackCheckpointEntity,
        entries: List<PlaybackCheckpointEntryEntity>,
    ) {
        require(checkpoint.slot.isNotBlank()) { "Checkpoint slot must not be blank" }
        require(checkpoint.checkpointVersion > 0) { "Checkpoint version must be positive" }
        require(checkpoint.positionMs >= 0) { "Checkpoint position cannot be negative" }
        require(checkpoint.checksum.isNotBlank()) { "Checkpoint checksum must not be blank" }
        require(entries.all { it.slot == checkpoint.slot }) {
            "Checkpoint entries must use the checkpoint slot"
        }
        require(
            entries.map(PlaybackCheckpointEntryEntity::queueEntryId).toSet().size == entries.size
        ) {
            "Checkpoint queue occurrence IDs must be unique"
        }
        val orderedEntries = entries.sortedBy(PlaybackCheckpointEntryEntity::position)
        require(
            orderedEntries.map(PlaybackCheckpointEntryEntity::position) ==
                orderedEntries.indices.toList()
        ) {
            "Checkpoint positions must be contiguous from zero"
        }
        require(
            checkpoint.currentQueueEntryId == null ||
                orderedEntries.any { it.queueEntryId == checkpoint.currentQueueEntryId }
        ) {
            "Current queue occurrence must exist in the checkpoint"
        }

        deleteEntries(checkpoint.slot)
        upsertCheckpoint(checkpoint)
        if (orderedEntries.isNotEmpty()) insertEntries(orderedEntries)
    }

    @Transaction open suspend fun clear(slot: String): Boolean = deleteCheckpoint(slot) == 1
}
