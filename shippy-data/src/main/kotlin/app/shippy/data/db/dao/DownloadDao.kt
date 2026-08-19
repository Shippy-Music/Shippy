/*
 * Copyright (c) 2026 Auxio Project
 * DownloadDao.kt is part of Auxio.
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
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.shippy.data.db.entity.DownloadJobEntity
import app.shippy.data.db.entity.MediaAssetEntity

@Dao
internal abstract class DownloadDao {
    @Query("SELECT * FROM download_job WHERE job_id = :jobId")
    abstract suspend fun get(jobId: String): DownloadJobEntity?

    @Query(
        """
        SELECT * FROM download_job
        WHERE recording_id = :recordingId
        ORDER BY created_at_epoch_ms DESC, job_id DESC
        LIMIT 1
        """
    )
    abstract suspend fun latestForRecording(recordingId: String): DownloadJobEntity?

    @Query(
        """
        SELECT COUNT(*) FROM download_job
        WHERE state = 'AVAILABLE' AND published_asset_id IS NOT NULL
        """
    )
    abstract suspend fun availablePublishedCount(): Long

    @Query(
        """
        SELECT * FROM download_job
        WHERE state IN (:states)
        ORDER BY updated_at_epoch_ms, job_id
        LIMIT :limit
        """
    )
    abstract suspend fun pending(states: Set<String>, limit: Int): List<DownloadJobEntity>

    @Upsert protected abstract suspend fun upsertJob(entity: DownloadJobEntity)

    @Upsert protected abstract suspend fun upsertPublishedAsset(entity: MediaAssetEntity)

    @Query(
        """
        UPDATE download_job SET
            state = :state,
            bytes_transferred = :bytesTransferred,
            expected_bytes = :expectedBytes,
            failure_kind = :failureKind,
            retry_after_epoch_ms = :retryAfterEpochMs,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE job_id = :jobId
        """
    )
    protected abstract suspend fun updateProgressInternal(
        jobId: String,
        state: String,
        bytesTransferred: Long,
        expectedBytes: Long?,
        failureKind: String?,
        retryAfterEpochMs: Long?,
        updatedAtEpochMs: Long,
    ): Int

    @Query(
        """
        UPDATE download_job SET
            state = 'AVAILABLE',
            published_asset_id = :assetId,
            bytes_transferred = COALESCE(:publishedBytes, expected_bytes, bytes_transferred),
            expected_bytes = COALESCE(expected_bytes, :publishedBytes),
            failure_kind = NULL,
            retry_after_epoch_ms = NULL,
            pending_location = NULL,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE job_id = :jobId
        """
    )
    protected abstract suspend fun markPublished(
        jobId: String,
        assetId: String,
        publishedBytes: Long?,
        updatedAtEpochMs: Long,
    ): Int

    open suspend fun save(entity: DownloadJobEntity) {
        validateProgress(entity.bytesTransferred, entity.expectedBytes)
        require(entity.state.isNotBlank()) { "Download state must not be blank" }
        upsertJob(entity)
    }

    open suspend fun updateProgress(
        jobId: String,
        state: String,
        bytesTransferred: Long,
        expectedBytes: Long?,
        failureKind: String?,
        retryAfterEpochMs: Long?,
        updatedAtEpochMs: Long,
    ) {
        validateProgress(bytesTransferred, expectedBytes)
        require(state.isNotBlank()) { "Download state must not be blank" }
        check(
            updateProgressInternal(
                jobId,
                state,
                bytesTransferred,
                expectedBytes,
                failureKind,
                retryAfterEpochMs,
                updatedAtEpochMs,
            ) == 1
        ) {
            "Download job is missing"
        }
    }

    @Transaction
    open suspend fun publishVerified(
        jobId: String,
        asset: MediaAssetEntity,
        updatedAtEpochMs: Long,
    ): DownloadJobEntity {
        val job = get(jobId) ?: error("Download job is missing")
        require(asset.recordingId == job.recordingId) {
            "Published asset must belong to the requested recording"
        }
        require(
            job.requestedSourceReferenceId == null ||
                asset.sourceReferenceId == job.requestedSourceReferenceId
        ) {
            "Published asset must retain the requested source identity"
        }
        require(asset.assetKind == "SHIPPY_DOWNLOAD") {
            "Only a Shippy download asset can complete a download job"
        }
        require(asset.assetState == "AVAILABLE" && asset.lastVerifiedAtEpochMs != null) {
            "Published download asset must already be verified and available"
        }
        require(asset.location.isNotBlank()) { "Published download location must not be blank" }
        require(
            job.expectedBytes == null ||
                asset.contentLength == null ||
                job.expectedBytes == asset.contentLength
        ) {
            "Published asset length does not match the verified download"
        }

        upsertPublishedAsset(asset)
        check(markPublished(jobId, asset.assetId, asset.contentLength, updatedAtEpochMs) == 1) {
            "Download job disappeared during publication"
        }
        return checkNotNull(get(jobId))
    }

    private fun validateProgress(bytesTransferred: Long, expectedBytes: Long?) {
        require(bytesTransferred >= 0) { "Transferred bytes cannot be negative" }
        require(expectedBytes == null || expectedBytes >= 0) { "Expected bytes cannot be negative" }
        require(expectedBytes == null || bytesTransferred <= expectedBytes) {
            "Transferred bytes cannot exceed expected bytes"
        }
    }
}
