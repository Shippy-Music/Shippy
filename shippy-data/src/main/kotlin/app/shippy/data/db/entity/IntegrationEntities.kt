/*
 * Copyright (c) 2026 Auxio Project
 * IntegrationEntities.kt is part of Auxio.
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
    tableName = "download_job",
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
                childColumns = ["requested_source_reference_id"],
            ),
            ForeignKey(
                entity = MediaAssetEntity::class,
                parentColumns = ["asset_id"],
                childColumns = ["published_asset_id"],
            ),
        ],
    indices =
        [
            Index(
                value = ["recording_id", "created_at_epoch_ms"],
                name = "index_download_job_recording",
            ),
            Index(value = ["state", "updated_at_epoch_ms"], name = "index_download_job_state"),
            Index(
                value = ["requested_source_reference_id"],
                name = "index_download_job_requested_source",
            ),
            Index(value = ["published_asset_id"], name = "index_download_job_published_asset"),
        ],
)
data class DownloadJobEntity(
    @PrimaryKey @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "recording_id") val recordingId: String,
    @ColumnInfo(name = "requested_source_reference_id") val requestedSourceReferenceId: String?,
    @ColumnInfo(name = "published_asset_id") val publishedAssetId: String?,
    val state: String,
    @ColumnInfo(name = "bytes_transferred") val bytesTransferred: Long,
    @ColumnInfo(name = "expected_bytes") val expectedBytes: Long?,
    @ColumnInfo(name = "failure_kind") val failureKind: String?,
    @ColumnInfo(name = "retry_after_epoch_ms") val retryAfterEpochMs: Long?,
    @ColumnInfo(name = "pending_location") val pendingLocation: String?,
    @ColumnInfo(name = "display_fallback_json") val displayFallbackJson: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
    @ColumnInfo(name = "requested_media_variant") val requestedMediaVariant: String? = null,
    @ColumnInfo(name = "destination_identity") val destinationIdentity: String? = null,
)

@Entity(
    tableName = "lastfm_scrobble_outbox",
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
            Index(value = ["queued_at_epoch_ms"], name = "index_lastfm_outbox_fifo"),
            Index(
                value = ["listening_session_id"],
                unique = true,
                name = "index_lastfm_outbox_listening_session",
            ),
            Index(value = ["recording_id"], name = "index_lastfm_outbox_recording"),
        ],
)
data class LastFmScrobbleOutboxEntity(
    @PrimaryKey @ColumnInfo(name = "outbox_id") val outboxId: String,
    @ColumnInfo(name = "account_id", defaultValue = "''") val accountId: String = "",
    @ColumnInfo(name = "listening_session_id") val listeningSessionId: String,
    @ColumnInfo(name = "recording_id") val recordingId: String,
    val artist: String,
    val track: String,
    val album: String?,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Long?,
    @ColumnInfo(name = "started_at_epoch_seconds") val startedAtEpochSeconds: Long,
    @ColumnInfo(name = "chosen_by_user") val chosenByUser: Boolean,
    @ColumnInfo(name = "queued_at_epoch_ms") val queuedAtEpochMs: Long,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "last_attempt_at_epoch_ms") val lastAttemptAtEpochMs: Long?,
)

@Entity(
    tableName = "lyrics_cache",
    primaryKeys = ["recording_id", "metadata_fingerprint", "provider_id"],
    foreignKeys =
        [
            ForeignKey(
                entity = RecordingEntity::class,
                parentColumns = ["recording_id"],
                childColumns = ["recording_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["cached_at_epoch_ms"], name = "index_lyrics_cache_age")],
)
data class LyricsCacheEntity(
    @ColumnInfo(name = "recording_id") val recordingId: String,
    @ColumnInfo(name = "metadata_fingerprint") val metadataFingerprint: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "provider_record_id") val providerRecordId: String?,
    @ColumnInfo(name = "plain_lyrics") val plainLyrics: String?,
    @ColumnInfo(name = "synchronized_lyrics") val synchronizedLyrics: String?,
    val instrumental: Boolean,
    @ColumnInfo(name = "cached_at_epoch_ms") val cachedAtEpochMs: Long,
    @ColumnInfo(name = "expires_at_epoch_ms") val expiresAtEpochMs: Long?,
)

@Entity(
    tableName = "saved_source_entity",
    primaryKeys = ["provider_id", "entity_type", "source_item_id"],
    indices =
        [
            Index(
                value = ["pinned", "saved_at_epoch_ms"],
                orders = [Index.Order.DESC, Index.Order.DESC],
                name = "index_saved_source_entity_library_order",
            )
        ],
)
data class SavedSourceEntity(
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "source_item_id") val sourceItemId: String,
    val title: String,
    val subtitle: String?,
    @ColumnInfo(name = "artwork_url") val artworkUrl: String?,
    @ColumnInfo(name = "original_url") val originalUrl: String?,
    val pinned: Boolean,
    @ColumnInfo(name = "saved_at_epoch_ms") val savedAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)
