/*
 * Copyright (c) 2026 Auxio Project
 * RecordingEntities.kt is part of Auxio.
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
    tableName = "recording",
    foreignKeys =
        [
            ForeignKey(
                entity = ReleaseEntity::class,
                parentColumns = ["release_id"],
                childColumns = ["preferred_release_id"],
            )
        ],
    indices =
        [
            Index(value = ["canonical_title"], name = "index_recording_title"),
            Index(
                value = ["retention_kind", "retained_until_epoch_ms"],
                name = "index_recording_retention",
            ),
            Index(value = ["preferred_release_id"], name = "index_recording_preferred_release"),
        ],
)
data class RecordingEntity(
    @PrimaryKey @ColumnInfo(name = "recording_id") val recordingId: String,
    @ColumnInfo(name = "canonical_title") val canonicalTitle: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "version_kind") val versionKind: String,
    @ColumnInfo(name = "version_label") val versionLabel: String?,
    val explicitness: String,
    @ColumnInfo(name = "preferred_release_id") val preferredReleaseId: String?,
    @ColumnInfo(name = "preferred_artwork_id") val preferredArtworkId: String?,
    @ColumnInfo(name = "retention_kind") val retentionKind: String,
    @ColumnInfo(name = "retained_until_epoch_ms") val retainedUntilEpochMs: Long?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "artist",
    indices = [Index(value = ["canonical_name"], name = "index_artist_name")],
)
data class ArtistEntity(
    @PrimaryKey @ColumnInfo(name = "artist_id") val artistId: String,
    @ColumnInfo(name = "canonical_name") val canonicalName: String,
    @ColumnInfo(name = "sort_name") val sortName: String?,
    val disambiguation: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "release",
    indices = [Index(value = ["canonical_title"], name = "index_release_title")],
)
data class ReleaseEntity(
    @PrimaryKey @ColumnInfo(name = "release_id") val releaseId: String,
    @ColumnInfo(name = "canonical_title") val canonicalTitle: String,
    @ColumnInfo(name = "release_type") val releaseType: String,
    @ColumnInfo(name = "release_year") val releaseYear: Int?,
    @ColumnInfo(name = "artwork_id") val artworkId: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "recording_artist_credit",
    primaryKeys = ["recording_id", "position"],
    foreignKeys =
        [
            ForeignKey(
                entity = RecordingEntity::class,
                parentColumns = ["recording_id"],
                childColumns = ["recording_id"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = ArtistEntity::class,
                parentColumns = ["artist_id"],
                childColumns = ["artist_id"],
            ),
        ],
    indices = [Index(value = ["artist_id"], name = "index_recording_artist_credit_artist")],
)
data class RecordingArtistCreditEntity(
    @ColumnInfo(name = "recording_id") val recordingId: String,
    val position: Int,
    @ColumnInfo(name = "artist_id") val artistId: String,
    @ColumnInfo(name = "credited_name") val creditedName: String,
    @ColumnInfo(name = "join_phrase") val joinPhrase: String,
)

@Entity(
    tableName = "release_track",
    foreignKeys =
        [
            ForeignKey(
                entity = ReleaseEntity::class,
                parentColumns = ["release_id"],
                childColumns = ["release_id"],
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
            Index(value = ["release_id", "order_key"], name = "index_release_track_release_order"),
            Index(value = ["recording_id"], name = "index_release_track_recording"),
        ],
)
data class ReleaseTrackEntity(
    @PrimaryKey @ColumnInfo(name = "release_track_id") val releaseTrackId: String,
    @ColumnInfo(name = "release_id") val releaseId: String,
    @ColumnInfo(name = "recording_id") val recordingId: String,
    @ColumnInfo(name = "disc_number") val discNumber: Int?,
    @ColumnInfo(name = "track_number") val trackNumber: Int?,
    @ColumnInfo(name = "display_title") val displayTitle: String?,
    @ColumnInfo(name = "order_key") val orderKey: Long,
)
