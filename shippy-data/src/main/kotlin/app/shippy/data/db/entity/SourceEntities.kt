/*
 * Copyright (c) 2026 Auxio Project
 * SourceEntities.kt is part of Auxio.
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
    tableName = "source_reference",
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
                value = ["provider_id", "item_type", "source_item_id"],
                unique = true,
                name = "index_source_reference_exact_key",
            ),
            Index(value = ["recording_id"], name = "index_source_reference_recording"),
            Index(
                value = ["provider_id", "availability_state"],
                name = "index_source_reference_availability",
            ),
        ],
)
data class SourceReferenceEntity(
    @PrimaryKey @ColumnInfo(name = "source_reference_id") val sourceReferenceId: String,
    @ColumnInfo(name = "recording_id") val recordingId: String?,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "item_type") val itemType: String,
    @ColumnInfo(name = "source_item_id") val sourceItemId: String,
    @ColumnInfo(name = "original_url") val originalUrl: String?,
    @ColumnInfo(name = "availability_state") val availabilityState: String,
    @ColumnInfo(name = "availability_checked_at_epoch_ms") val availabilityCheckedAtEpochMs: Long?,
    @ColumnInfo(name = "availability_expires_at_epoch_ms") val availabilityExpiresAtEpochMs: Long?,
    @ColumnInfo(name = "failure_kind") val failureKind: String?,
    @ColumnInfo(name = "identity_status") val identityStatus: String,
    @ColumnInfo(name = "raw_metadata_observation_id") val rawMetadataObservationId: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "metadata_observation",
    indices =
        [
            Index(
                value = ["source_reference_id", "captured_at_epoch_ms"],
                name = "index_metadata_observation_source",
            ),
            Index(
                value = ["asset_id", "captured_at_epoch_ms"],
                name = "index_metadata_observation_asset",
            ),
        ],
)
data class MetadataObservationEntity(
    @PrimaryKey @ColumnInfo(name = "observation_id") val observationId: String,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source_reference_id") val sourceReferenceId: String?,
    @ColumnInfo(name = "asset_id") val assetId: String?,
    val title: String?,
    @ColumnInfo(name = "artist_credit_json") val artistCreditJson: String?,
    @ColumnInfo(name = "release_title") val releaseTitle: String?,
    @ColumnInfo(name = "release_artist") val releaseArtist: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "artwork_json") val artworkJson: String?,
    @ColumnInfo(name = "release_year") val releaseYear: Int?,
    @ColumnInfo(name = "track_number") val trackNumber: Int?,
    @ColumnInfo(name = "disc_number") val discNumber: Int?,
    @ColumnInfo(name = "genres_json") val genresJson: String?,
    @ColumnInfo(name = "version_hints_json") val versionHintsJson: String?,
    @ColumnInfo(name = "external_ids_json") val externalIdsJson: String?,
    @ColumnInfo(name = "extras_json") val extrasJson: String?,
    @ColumnInfo(name = "captured_at_epoch_ms") val capturedAtEpochMs: Long,
)

@Entity(
    tableName = "external_identifier",
    indices =
        [
            Index(
                value = ["owner_type", "owner_id", "scheme", "value"],
                unique = true,
                name = "index_external_identifier_owner_scheme_value",
            ),
            Index(value = ["scheme", "value"], name = "index_external_identifier_lookup"),
        ],
)
data class ExternalIdentifierEntity(
    @PrimaryKey @ColumnInfo(name = "external_identifier_id") val externalIdentifierId: String,
    @ColumnInfo(name = "owner_type") val ownerType: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    val scheme: String,
    val value: String,
    val verified: Boolean,
    @ColumnInfo(name = "source_observation_id") val sourceObservationId: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
)

@Entity(
    tableName = "artwork_reference",
    indices = [Index(value = ["owner_type", "owner_id", "preferred"], name = "index_artwork_owner")],
)
data class ArtworkReferenceEntity(
    @PrimaryKey @ColumnInfo(name = "artwork_id") val artworkId: String,
    @ColumnInfo(name = "owner_type") val ownerType: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "location_type") val locationType: String,
    val location: String,
    val width: Int?,
    val height: Int?,
    @ColumnInfo(name = "source_observation_id") val sourceObservationId: String?,
    val preferred: Boolean,
    @ColumnInfo(name = "content_hash") val contentHash: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "last_verified_at_epoch_ms") val lastVerifiedAtEpochMs: Long?,
)
