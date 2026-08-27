/*
 * Copyright (c) 2026 Auxio Project
 * LibraryEntities.kt is part of Auxio.
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
    tableName = "library_recording",
    foreignKeys =
        [
            ForeignKey(
                entity = RecordingEntity::class,
                parentColumns = ["recording_id"],
                childColumns = ["recording_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices =
        [
            Index(value = ["liked"], name = "index_library_recording_liked"),
            Index(value = ["explicitly_saved"], name = "index_library_recording_saved"),
        ],
)
data class LibraryRecordingEntity(
    @PrimaryKey @ColumnInfo(name = "recording_id") val recordingId: String,
    val liked: Boolean,
    @ColumnInfo(name = "explicitly_saved") val explicitlySaved: Boolean,
    @ColumnInfo(name = "user_edited") val userEdited: Boolean,
    @ColumnInfo(name = "manually_identified") val manuallyIdentified: Boolean,
    @ColumnInfo(name = "first_added_at_epoch_ms") val firstAddedAtEpochMs: Long?,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "playlist",
    indices =
        [
            Index(
                value = ["pinned", "library_order_key"],
                orders = [Index.Order.DESC, Index.Order.ASC],
                name = "index_playlist_library_order",
            ),
            Index(
                value = ["origin_kind", "origin_key"],
                unique = true,
                name = "index_playlist_origin",
            ),
        ],
)
data class PlaylistEntity(
    @PrimaryKey @ColumnInfo(name = "playlist_id") val playlistId: String,
    val name: String,
    val pinned: Boolean,
    @ColumnInfo(name = "library_order_key") val libraryOrderKey: Long,
    @ColumnInfo(name = "artwork_override") val artworkOverride: String?,
    @ColumnInfo(name = "display_sort_mode") val displaySortMode: String,
    @ColumnInfo(name = "display_sort_direction") val displaySortDirection: String,
    @ColumnInfo(name = "origin_kind") val originKind: String,
    @ColumnInfo(name = "origin_key") val originKey: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "playlist_entry",
    foreignKeys =
        [
            ForeignKey(
                entity = PlaylistEntity::class,
                parentColumns = ["playlist_id"],
                childColumns = ["playlist_id"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = RecordingEntity::class,
                parentColumns = ["recording_id"],
                childColumns = ["recording_id"],
            ),
        ],
    indices =
        [
            Index(value = ["playlist_id", "order_key"], name = "index_playlist_entry_order"),
            Index(value = ["recording_id"], name = "index_playlist_entry_recording"),
        ],
)
data class PlaylistEntryEntity(
    @PrimaryKey @ColumnInfo(name = "playlist_entry_id") val playlistEntryId: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "recording_id") val recordingId: String,
    @ColumnInfo(name = "order_key") val orderKey: Long,
    @ColumnInfo(name = "added_at_epoch_ms") val addedAtEpochMs: Long,
)

@Entity(
    tableName = "library_layout_entry",
    primaryKeys = ["target_type", "target_id"],
    indices =
        [
            Index(
                value = ["pinned", "order_key"],
                orders = [Index.Order.DESC, Index.Order.ASC],
                name = "index_library_layout_order",
            )
        ],
)
data class LibraryLayoutEntryEntity(
    @ColumnInfo(name = "target_type") val targetType: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    val pinned: Boolean,
    @ColumnInfo(name = "order_key") val orderKey: Long,
)

@Entity(
    tableName = "user_metadata_override",
    primaryKeys = ["recording_id", "field_name"],
    foreignKeys =
        [
            ForeignKey(
                entity = RecordingEntity::class,
                parentColumns = ["recording_id"],
                childColumns = ["recording_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class UserMetadataOverrideEntity(
    @ColumnInfo(name = "recording_id") val recordingId: String,
    @ColumnInfo(name = "field_name") val fieldName: String,
    @ColumnInfo(name = "value_json") val valueJson: String,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "canonical_field_provenance",
    primaryKeys = ["recording_id", "field_name"],
    foreignKeys =
        [
            ForeignKey(
                entity = RecordingEntity::class,
                parentColumns = ["recording_id"],
                childColumns = ["recording_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class CanonicalFieldProvenanceEntity(
    @ColumnInfo(name = "recording_id") val recordingId: String,
    @ColumnInfo(name = "field_name") val fieldName: String,
    @ColumnInfo(name = "selected_source_type") val selectedSourceType: String,
    @ColumnInfo(name = "selected_source_id") val selectedSourceId: String?,
    val confidence: Double,
    @ColumnInfo(name = "selected_at_epoch_ms") val selectedAtEpochMs: Long,
)
