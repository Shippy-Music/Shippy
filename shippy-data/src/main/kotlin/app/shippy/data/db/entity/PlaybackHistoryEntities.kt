/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackHistoryEntities.kt is part of Auxio.
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
package app.shippy.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "play_history",
    foreignKeys =
        [
            ForeignKey(
                entity = RecordingEntity::class,
                parentColumns = ["recording_id"],
                childColumns = ["recording_id"],
            )
        ],
    indices =
        [
            Index(
                value = ["started_at_epoch_ms"],
                orders = [Index.Order.DESC],
                name = "index_play_history_recent",
            ),
            Index(
                value = ["recording_id", "started_at_epoch_ms"],
                orders = [Index.Order.ASC, Index.Order.DESC],
                name = "index_play_history_recording",
            ),
        ],
)
data class PlayHistoryEntity(
    @PrimaryKey @ColumnInfo(name = "listening_session_id") val listeningSessionId: String,
    @ColumnInfo(name = "recording_id") val recordingId: String,
    @ColumnInfo(name = "queue_entry_id") val queueEntryId: String,
    @ColumnInfo(name = "source_reference_id") val sourceReferenceId: String?,
    @ColumnInfo(name = "started_at_epoch_ms") val startedAtEpochMs: Long,
    @ColumnInfo(name = "ended_at_epoch_ms") val endedAtEpochMs: Long?,
    @ColumnInfo(name = "active_listened_ms") val activeListenedMs: Long,
    @ColumnInfo(name = "last_position_ms") val lastPositionMs: Long,
    @ColumnInfo(name = "completion_kind") val completionKind: String,
    @ColumnInfo(name = "chosen_by_user") val chosenByUser: Boolean,
)

@Entity(tableName = "playback_checkpoint")
data class PlaybackCheckpointEntity(
    @PrimaryKey val slot: String,
    @ColumnInfo(name = "checkpoint_version") val checkpointVersion: Int,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "current_queue_entry_id") val currentQueueEntryId: String?,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "playing_intent") val playingIntent: Boolean,
    @ColumnInfo(name = "repeat_mode") val repeatMode: String,
    @ColumnInfo(name = "shuffle_enabled") val shuffleEnabled: Boolean,
    @ColumnInfo(name = "shuffle_seed") val shuffleSeed: Long?,
    @ColumnInfo(name = "base_order_json") val baseOrderJson: String,
    @ColumnInfo(name = "traversal_order_json") val traversalOrderJson: String,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
    val checksum: String,
)

@Entity(
    tableName = "playback_checkpoint_entry",
    primaryKeys = ["slot", "queue_entry_id"],
    foreignKeys =
        [
            ForeignKey(
                entity = PlaybackCheckpointEntity::class,
                parentColumns = ["slot"],
                childColumns = ["slot"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices =
        [Index(value = ["slot", "position"], name = "index_playback_checkpoint_entry_position")],
)
data class PlaybackCheckpointEntryEntity(
    val slot: String,
    @ColumnInfo(name = "queue_entry_id") val queueEntryId: String,
    val position: Int,
    @ColumnInfo(name = "recording_id") val recordingId: String,
    @ColumnInfo(name = "origin_json") val originJson: String?,
    @ColumnInfo(name = "contributor_id") val contributorId: String?,
    @ColumnInfo(name = "presentation_fallback_json") val presentationFallbackJson: String,
)
