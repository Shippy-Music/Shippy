/*
 * Copyright (c) 2026 Auxio Project
 * AssetEntities.kt is part of Auxio.
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
    tableName = "media_asset",
    foreignKeys =
        [
            ForeignKey(
                entity = RecordingEntity::class,
                parentColumns = ["recording_id"],
                childColumns = ["recording_id"],
            ),
            ForeignKey(
                entity = SourceReferenceEntity::class,
                parentColumns = ["source_reference_id"],
                childColumns = ["source_reference_id"],
            ),
        ],
    indices =
        [
            Index(
                value = ["location_type", "location"],
                unique = true,
                name = "index_media_asset_location",
            ),
            Index(
                value = ["recording_id", "asset_kind", "asset_state"],
                name = "index_media_asset_recording_kind_state",
            ),
            Index(value = ["content_checksum"], name = "index_media_asset_checksum"),
            Index(value = ["source_reference_id"], name = "index_media_asset_source"),
        ],
)
data class MediaAssetEntity(
    @PrimaryKey @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "recording_id") val recordingId: String,
    @ColumnInfo(name = "source_reference_id") val sourceReferenceId: String?,
    @ColumnInfo(name = "asset_kind") val assetKind: String,
    @ColumnInfo(name = "asset_state") val assetState: String,
    @ColumnInfo(name = "location_type") val locationType: String,
    val location: String,
    @ColumnInfo(name = "document_id") val documentId: String?,
    @ColumnInfo(name = "media_store_id") val mediaStoreId: Long?,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    val container: String?,
    val codec: String?,
    @ColumnInfo(name = "bitrate_bps") val bitrateBps: Long?,
    @ColumnInfo(name = "sample_rate_hz") val sampleRateHz: Int?,
    @ColumnInfo(name = "channel_count") val channelCount: Int?,
    @ColumnInfo(name = "content_length") val contentLength: Long?,
    @ColumnInfo(name = "content_checksum") val contentChecksum: String?,
    @ColumnInfo(name = "fingerprint_id") val fingerprintId: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
    @ColumnInfo(name = "last_verified_at_epoch_ms") val lastVerifiedAtEpochMs: Long?,
)

@Entity(
    tableName = "audio_fingerprint",
    foreignKeys =
        [
            ForeignKey(
                entity = MediaAssetEntity::class,
                parentColumns = ["asset_id"],
                childColumns = ["asset_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices =
        [
            Index(
                value = ["asset_id", "algorithm", "algorithm_version"],
                unique = true,
                name = "index_audio_fingerprint_asset_algorithm",
            )
        ],
)
data class AudioFingerprintEntity(
    @PrimaryKey @ColumnInfo(name = "fingerprint_id") val fingerprintId: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    val algorithm: String,
    @ColumnInfo(name = "algorithm_version") val algorithmVersion: String,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Long,
    @ColumnInfo(name = "compressed_fingerprint") val compressedFingerprint: ByteArray,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
)
