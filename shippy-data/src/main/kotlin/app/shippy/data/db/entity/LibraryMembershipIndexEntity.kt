/*
 * Copyright (c) 2026 Auxio Project
 * LibraryMembershipIndexEntity.kt is part of Auxio.
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

/**
 * Materialized, rebuildable membership/order index for the Library page.
 *
 * The product state remains in [LibraryRecordingEntity] and [MediaAssetEntity]. This table only
 * avoids rescanning the full recording catalogue for the bounded, title-ordered Library page.
 */
@Entity(
    tableName = "library_membership_index",
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
            Index(value = ["recording_id"], name = "index_library_membership_recording"),
            Index(
                value = ["title_sort_key", "recording_id"],
                unique = true,
                name = "index_library_membership_order",
            ),
        ],
)
data class LibraryMembershipIndexEntity(
    @PrimaryKey @ColumnInfo(name = "recording_id") val recordingId: String,
    @ColumnInfo(name = "title_sort_key") val titleSortKey: String,
)
