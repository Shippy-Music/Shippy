/*
 * Copyright (c) 2026 Auxio Project
 * SourceDao.kt is part of Auxio.
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
import app.shippy.data.db.entity.MetadataObservationEntity
import app.shippy.data.db.entity.SourceReferenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class SourceDao {
    @Query("SELECT * FROM source_reference WHERE source_reference_id = :sourceReferenceId")
    abstract suspend fun get(sourceReferenceId: String): SourceReferenceEntity?

    @Query(
        """
        SELECT * FROM source_reference
        WHERE provider_id = :providerId AND item_type = :itemType AND source_item_id = :sourceItemId
        LIMIT 1
        """
    )
    abstract suspend fun exact(
        providerId: String,
        itemType: String,
        sourceItemId: String,
    ): SourceReferenceEntity?

    @Query(
        """
        SELECT * FROM source_reference
        WHERE recording_id = :recordingId
        ORDER BY provider_id, item_type, source_item_id
        """
    )
    abstract fun observeForRecording(recordingId: String): Flow<List<SourceReferenceEntity>>

    @Query(
        "SELECT * FROM source_reference WHERE recording_id = :recordingId ORDER BY source_reference_id"
    )
    abstract suspend fun forRecording(recordingId: String): List<SourceReferenceEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertObservation(entity: MetadataObservationEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSource(entity: SourceReferenceEntity)

    @Query(
        """
        UPDATE source_reference SET
            original_url = :originalUrl,
            availability_state = :availabilityState,
            availability_checked_at_epoch_ms = :checkedAtEpochMs,
            availability_expires_at_epoch_ms = :expiresAtEpochMs,
            failure_kind = :failureKind,
            failure_retryable = :failureRetryable,
            raw_metadata_observation_id = :observationId,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE source_reference_id = :sourceReferenceId
        """
    )
    protected abstract suspend fun updateObservationProjection(
        sourceReferenceId: String,
        originalUrl: String?,
        availabilityState: String,
        checkedAtEpochMs: Long?,
        expiresAtEpochMs: Long?,
        failureKind: String?,
        failureRetryable: Boolean?,
        observationId: String,
        updatedAtEpochMs: Long,
    ): Int

    @Query(
        """
        UPDATE source_reference SET
            recording_id = :recordingId,
            identity_status = :identityStatus,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE source_reference_id = :sourceReferenceId
        """
    )
    abstract suspend fun reassignRecordingId(
        sourceReferenceId: String,
        recordingId: String,
        identityStatus: String = "MATCHED",
        updatedAtEpochMs: Long = System.currentTimeMillis(),
    ): Int

    @Query(
        """
        UPDATE source_reference SET
            recording_id = :recordingId,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE source_reference_id = :sourceReferenceId
        """
    )
    abstract suspend fun reassignRecordingIdPreservingIdentityStatus(
        sourceReferenceId: String,
        recordingId: String,
        updatedAtEpochMs: Long = System.currentTimeMillis(),
    ): Int

    @Query(
        """
        UPDATE source_reference SET
            recording_id = :newRecordingId,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE recording_id = :oldRecordingId
        """
    )
    abstract suspend fun reassignSourcesForRecording(
        oldRecordingId: String,
        newRecordingId: String,
        updatedAtEpochMs: Long = System.currentTimeMillis(),
    ): Int

    @Query(
        """
        UPDATE source_reference SET
            recording_id = :recordingId,
            identity_status = :identityStatus,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE source_reference_id = :sourceReferenceId
          AND (recording_id IS NULL OR recording_id = :recordingId)
        """
    )
    protected abstract suspend fun linkIfUnassignedOrSame(
        sourceReferenceId: String,
        recordingId: String,
        identityStatus: String,
        updatedAtEpochMs: Long,
    ): Int

    @Query(
        """
        UPDATE source_reference SET
            availability_state = :availabilityState,
            availability_checked_at_epoch_ms = :checkedAtEpochMs,
            availability_expires_at_epoch_ms = :expiresAtEpochMs,
            failure_kind = :failureKind,
            failure_retryable = :failureRetryable,
            updated_at_epoch_ms = :updatedAtEpochMs
        WHERE provider_id = :providerId
          AND item_type = :itemType
          AND source_item_id = :sourceItemId
        """
    )
    abstract suspend fun updateAvailability(
        providerId: String,
        itemType: String,
        sourceItemId: String,
        availabilityState: String,
        checkedAtEpochMs: Long?,
        expiresAtEpochMs: Long?,
        failureKind: String?,
        failureRetryable: Boolean?,
        updatedAtEpochMs: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM metadata_observation") abstract suspend fun observationCount(): Int

    @Transaction
    open suspend fun ingestExact(
        observation: MetadataObservationEntity,
        source: SourceReferenceEntity,
    ): SourceReferenceEntity {
        require(observation.observationId == source.rawMetadataObservationId) {
            "Source must point to the ingested metadata observation"
        }
        insertObservation(observation)
        val existing = exact(source.providerId, source.itemType, source.sourceItemId)
        if (existing == null) {
            insertSource(source)
            return source
        }
        check(existing.sourceKind == source.sourceKind) {
            "Exact source key cannot change its canonical source kind"
        }
        check(
            updateObservationProjection(
                sourceReferenceId = existing.sourceReferenceId,
                originalUrl = source.originalUrl,
                availabilityState = source.availabilityState,
                checkedAtEpochMs = source.availabilityCheckedAtEpochMs,
                expiresAtEpochMs = source.availabilityExpiresAtEpochMs,
                failureKind = source.failureKind,
                failureRetryable = source.failureRetryable,
                observationId = observation.observationId,
                updatedAtEpochMs = source.updatedAtEpochMs,
            ) == 1
        ) {
            "Exact source disappeared during ingestion"
        }
        return checkNotNull(exact(source.providerId, source.itemType, source.sourceItemId))
    }

    @Transaction
    open suspend fun link(
        sourceReferenceId: String,
        recordingId: String,
        identityStatus: String,
        updatedAtEpochMs: Long,
    ) {
        check(
            linkIfUnassignedOrSame(
                sourceReferenceId,
                recordingId,
                identityStatus,
                updatedAtEpochMs,
            ) == 1
        ) {
            "Source is missing or already linked to another recording"
        }
    }
}
