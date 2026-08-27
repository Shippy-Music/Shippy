/*
 * Copyright (c) 2026 Auxio Project
 * R16OfflineRepository.kt is part of Auxio.
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
package app.shippy.data.offline

import androidx.room.withTransaction
import app.shippy.core.asset.AssetLocation
import app.shippy.core.asset.AssetState
import app.shippy.core.asset.MediaAsset
import app.shippy.core.asset.MediaAssetKind
import app.shippy.core.identity.MediaAssetId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.DownloadJobEntity
import app.shippy.data.db.entity.MediaAssetEntity
import java.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Durable R16 download states. Runtime publication/removal flows through this facade. */
enum class R16DownloadJobState {
    REQUESTED,
    RESOLVING,
    QUEUED,
    TRANSFERRING,
    VERIFYING,
    PUBLISHING,
    AVAILABLE,
    PAUSED,
    CANCELLED,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    REMOVED;

    internal companion object {
        fun fromStored(value: String): R16DownloadJobState =
            when (value) {
                "FINALIZING" -> PUBLISHING
                else -> valueOf(value)
            }
    }
}

data class R16DownloadJobSnapshot(
    val jobId: String,
    val recordingId: RecordingId,
    val requestedSourceReferenceId: SourceReferenceId?,
    val requestedMediaVariant: String?,
    val destinationIdentity: String?,
    val displayFallbackJson: String,
    val pendingLocation: String?,
    val publishedAssetId: MediaAssetId?,
    val state: R16DownloadJobState,
    val bytesTransferred: Long,
    val expectedBytes: Long?,
    val failureKind: String?,
    val retryAfter: Instant?,
    val updatedAt: Instant,
)

data class R16DownloadRequest(
    val jobId: String,
    val recordingId: RecordingId,
    val requestedSourceReferenceId: SourceReferenceId,
    val requestedMediaVariant: String,
    val destinationIdentity: String,
    val expectedBytes: Long?,
    val pendingLocation: String?,
    val displayFallbackJson: String = "{}",
    val createdAt: Instant,
) {
    init {
        require(jobId.isNotBlank() && jobId == jobId.trim()) {
            "Download job ID must be non-blank and trimmed"
        }
        require(
            requestedMediaVariant.isNotBlank() &&
                requestedMediaVariant == requestedMediaVariant.trim()
        ) {
            "Requested media variant must be non-blank and trimmed"
        }
        require(
            destinationIdentity.isNotBlank() && destinationIdentity == destinationIdentity.trim()
        ) {
            "Destination identity must be non-blank and trimmed"
        }
        require(expectedBytes == null || expectedBytes >= 0) {
            "Expected download length cannot be negative"
        }
        require(displayFallbackJson.isNotBlank()) { "Display fallback cannot be blank" }
    }
}

data class R16DownloadPublishingStart(
    val jobId: String,
    val recordingId: RecordingId,
    val requestedSourceReferenceId: SourceReferenceId?,
    val requestedMediaVariant: String,
    val destinationIdentity: String,
    val pendingLocation: String,
    val updatedAt: Instant,
) {
    init {
        require(jobId.isNotBlank() && jobId == jobId.trim()) {
            "Download job ID must be non-blank and trimmed"
        }
        require(
            requestedMediaVariant.isNotBlank() &&
                requestedMediaVariant == requestedMediaVariant.trim()
        ) {
            "Requested media variant must be non-blank and trimmed"
        }
        require(
            destinationIdentity.isNotBlank() && destinationIdentity == destinationIdentity.trim()
        ) {
            "Destination identity must be non-blank and trimmed"
        }
        require(pendingLocation.isNotBlank() && pendingLocation == pendingLocation.trim()) {
            "Pending download location must be non-blank and trimmed"
        }
    }
}

/** Exact durable evidence for physical output that must remain cleanup-visible. */
data class R16PendingCleanupEvidence(
    val jobId: String,
    val recordingId: RecordingId,
    val requestedSourceReferenceId: SourceReferenceId,
    val requestedMediaVariant: String,
    val destinationIdentity: String,
    val pendingLocation: String,
    val updatedAt: Instant,
) {
    init {
        require(jobId.isNotBlank() && jobId == jobId.trim()) {
            "Download job ID must be non-blank and trimmed"
        }
        require(
            requestedMediaVariant.isNotBlank() &&
                requestedMediaVariant == requestedMediaVariant.trim()
        ) {
            "Requested media variant must be non-blank and trimmed"
        }
        require(
            destinationIdentity.isNotBlank() && destinationIdentity == destinationIdentity.trim()
        ) {
            "Destination identity must be non-blank and trimmed"
        }
        require(pendingLocation.isNotBlank() && pendingLocation == pendingLocation.trim()) {
            "Pending download location must be non-blank and trimmed"
        }
    }
}

/**
 * Resets a retryable transfer after its caller has removed any pending physical output.
 *
 * The requested expected length is retained: it is part of the durable request contract and remains
 * useful for bounding the next transfer. The worker may replace it with a newly observed value
 * through [R16DownloadProgress] before writing bytes.
 */
data class R16DownloadRetryReset(
    val jobId: String,
    val recordingId: RecordingId,
    val requestedSourceReferenceId: SourceReferenceId?,
    val requestedMediaVariant: String,
    val destinationIdentity: String,
    val updatedAt: Instant,
) {
    init {
        require(jobId.isNotBlank() && jobId == jobId.trim()) {
            "Download job ID must be non-blank and trimmed"
        }
        require(
            requestedMediaVariant.isNotBlank() &&
                requestedMediaVariant == requestedMediaVariant.trim()
        ) {
            "Requested media variant must be non-blank and trimmed"
        }
        require(
            destinationIdentity.isNotBlank() && destinationIdentity == destinationIdentity.trim()
        ) {
            "Destination identity must be non-blank and trimmed"
        }
    }
}

data class R16DownloadProgress(
    val jobId: String,
    val state: R16DownloadJobState,
    val bytesTransferred: Long,
    val expectedBytes: Long?,
    val failureKind: String?,
    val retryAfter: Instant?,
    val updatedAt: Instant,
) {
    init {
        require(jobId.isNotBlank() && jobId == jobId.trim()) {
            "Download job ID must be non-blank and trimmed"
        }
        require(bytesTransferred >= 0) { "Transferred bytes cannot be negative" }
        require(expectedBytes == null || expectedBytes >= bytesTransferred) {
            "Expected bytes cannot be smaller than transferred bytes"
        }
        require(state != R16DownloadJobState.AVAILABLE && state != R16DownloadJobState.REMOVED) {
            "Publication and removal must use their exact repository operations"
        }
    }
}

/** Evidence gathered by the Android storage/Media3 adapter before registry publication. */
data class R16DownloadVerificationEvidence(
    val bytesComplete: Boolean,
    val finalLocationExists: Boolean,
    val accessRetained: Boolean,
    val media3Readable: Boolean,
) {
    val complete: Boolean
        get() = bytesComplete && finalLocationExists && accessRetained && media3Readable
}

data class R16VerifiedDownloadPublication(
    val jobId: String,
    val destinationIdentity: String,
    val asset: MediaAsset,
    val locationType: String,
    val documentId: String?,
    val mediaStoreId: Long?,
    val displayName: String?,
    val container: String?,
    val normalizedPathToken: String?,
    val lastModifiedAt: Instant?,
    val evidence: R16DownloadVerificationEvidence,
    val publishedAt: Instant,
) {
    init {
        require(jobId.isNotBlank() && jobId == jobId.trim()) {
            "Download job ID must be non-blank and trimmed"
        }
        require(
            destinationIdentity.isNotBlank() && destinationIdentity == destinationIdentity.trim()
        ) {
            "Destination identity must be non-blank and trimmed"
        }
        require(locationType.isNotBlank()) { "Download location type cannot be blank" }
    }
}

data class R16ManagedDownloadRemoval(
    val jobId: String,
    val recordingId: RecordingId,
    val assetId: MediaAssetId,
    val updatedAt: Instant,
    val destinationIdentity: String? = null,
) {
    init {
        require(jobId.isNotBlank() && jobId == jobId.trim()) {
            "Download job ID must be non-blank and trimmed"
        }
        require(
            destinationIdentity == null ||
                (destinationIdentity.isNotBlank() &&
                    destinationIdentity == destinationIdentity.trim())
        ) {
            "Destination identity must be non-blank and trimmed"
        }
    }
}

data class R16ManagedDownloadTarget(
    val assetId: MediaAssetId,
    val locationType: String,
    val location: AssetLocation,
    val documentId: String?,
    val destinationIdentity: String? = null,
)

enum class R16PhysicalRemovalResult {
    DELETED,
    ALREADY_MISSING,
    FAILED,
}

/** Android/SAF deletion boundary. The Room repository never deletes physical storage itself. */
fun interface R16ManagedDownloadStorage {
    suspend fun delete(target: R16ManagedDownloadTarget): R16PhysicalRemovalResult
}

enum class R16OfflineRejection {
    RECORDING_NOT_FOUND,
    SOURCE_NOT_FOUND,
    JOB_NOT_FOUND,
    IDENTITY_MISMATCH,
    INVALID_STATE,
    INVALID_PROGRESS,
    ASSET_CONFLICT,
    VERIFICATION_INCOMPLETE,
    STORAGE_FAILURE,
}

sealed interface R16OfflineMutationResult {
    data class Applied(val job: R16DownloadJobSnapshot, val changed: Boolean) :
        R16OfflineMutationResult

    data class Rejected(val reason: R16OfflineRejection) : R16OfflineMutationResult
}

interface R16OfflineRepository {
    suspend fun load(jobId: String): R16DownloadJobSnapshot?

    /** Every durable physical-output location still requiring cleanup or scanner suppression. */
    suspend fun pendingCleanupLocations(): List<String>

    suspend fun request(command: R16DownloadRequest): R16OfflineMutationResult

    suspend fun updateProgress(command: R16DownloadProgress): R16OfflineMutationResult

    suspend fun beginPublishing(command: R16DownloadPublishingStart): R16OfflineMutationResult

    suspend fun resetForRetry(command: R16DownloadRetryReset): R16OfflineMutationResult

    suspend fun retainPendingCleanupEvidence(
        command: R16PendingCleanupEvidence
    ): R16OfflineMutationResult

    suspend fun clearPendingCleanupEvidence(
        command: R16PendingCleanupEvidence
    ): R16OfflineMutationResult

    suspend fun publishVerified(command: R16VerifiedDownloadPublication): R16OfflineMutationResult

    suspend fun markMissing(command: R16ManagedDownloadRemoval): R16OfflineMutationResult

    suspend fun removeManagedDownload(
        command: R16ManagedDownloadRemoval,
        storage: R16ManagedDownloadStorage,
    ): R16OfflineMutationResult
}

internal class RoomR16OfflineRepository(private val database: ShippyR16Database) :
    R16OfflineRepository {
    private val mutationMutex = Mutex()

    override suspend fun load(jobId: String): R16DownloadJobSnapshot? {
        if (jobId.isBlank() || jobId != jobId.trim()) return null
        return mutationMutex.withLock { database.downloadDao().get(jobId)?.toSnapshot() }
    }

    override suspend fun pendingCleanupLocations(): List<String> =
        mutationMutex.withLock { database.downloadDao().pendingCleanupLocations() }

    override suspend fun request(command: R16DownloadRequest): R16OfflineMutationResult =
        mutationMutex.withLock {
            database.withTransaction {
                if (database.recordingDao().get(command.recordingId.value) == null) {
                    return@withTransaction rejected(R16OfflineRejection.RECORDING_NOT_FOUND)
                }
                val source =
                    database.sourceDao().get(command.requestedSourceReferenceId.value)
                        ?: return@withTransaction rejected(R16OfflineRejection.SOURCE_NOT_FOUND)
                if (source.recordingId != command.recordingId.value) {
                    return@withTransaction rejected(R16OfflineRejection.IDENTITY_MISMATCH)
                }
                val existing = database.downloadDao().get(command.jobId)
                if (existing != null) {
                    if (
                        existing.recordingId != command.recordingId.value ||
                            existing.requestedSourceReferenceId !=
                                command.requestedSourceReferenceId.value ||
                            existing.requestedMediaVariant != command.requestedMediaVariant ||
                            existing.destinationIdentity != command.destinationIdentity
                    ) {
                        return@withTransaction rejected(R16OfflineRejection.IDENTITY_MISMATCH)
                    }
                    return@withTransaction applied(existing, changed = false)
                }
                val createdAt = command.createdAt.toEpochMilli()
                val entity =
                    DownloadJobEntity(
                        jobId = command.jobId,
                        recordingId = command.recordingId.value,
                        requestedSourceReferenceId = command.requestedSourceReferenceId.value,
                        requestedMediaVariant = command.requestedMediaVariant,
                        destinationIdentity = command.destinationIdentity,
                        publishedAssetId = null,
                        state = R16DownloadJobState.REQUESTED.name,
                        bytesTransferred = 0,
                        expectedBytes = command.expectedBytes,
                        failureKind = null,
                        retryAfterEpochMs = null,
                        pendingLocation = command.pendingLocation,
                        displayFallbackJson = command.displayFallbackJson,
                        createdAtEpochMs = createdAt,
                        updatedAtEpochMs = createdAt,
                    )
                database.downloadDao().save(entity)
                applied(entity, changed = true)
            }
        }

    override suspend fun updateProgress(command: R16DownloadProgress): R16OfflineMutationResult =
        mutationMutex.withLock {
            database.withTransaction {
                val existing =
                    database.downloadDao().get(command.jobId)
                        ?: return@withTransaction rejected(R16OfflineRejection.JOB_NOT_FOUND)
                if (
                    existing.state == R16DownloadJobState.AVAILABLE.name ||
                        existing.state == R16DownloadJobState.REMOVED.name
                ) {
                    return@withTransaction rejected(R16OfflineRejection.INVALID_STATE)
                }
                val currentState = R16DownloadJobState.fromStored(existing.state)
                if (
                    command.state == R16DownloadJobState.PUBLISHING ||
                        (currentState == R16DownloadJobState.FAILED_RETRYABLE &&
                            command.state != R16DownloadJobState.FAILED_RETRYABLE)
                ) {
                    return@withTransaction rejected(R16OfflineRejection.INVALID_STATE)
                }
                if (!currentState.canAdvanceTo(command.state)) {
                    return@withTransaction rejected(R16OfflineRejection.INVALID_STATE)
                }
                val expectedBytes = command.expectedBytes ?: existing.expectedBytes
                if (
                    command.bytesTransferred < existing.bytesTransferred ||
                        (expectedBytes != null && command.bytesTransferred > expectedBytes)
                ) {
                    return@withTransaction rejected(R16OfflineRejection.INVALID_PROGRESS)
                }
                database
                    .downloadDao()
                    .updateProgress(
                        jobId = command.jobId,
                        state = command.state.name,
                        bytesTransferred = command.bytesTransferred,
                        expectedBytes = expectedBytes,
                        failureKind = command.failureKind,
                        retryAfterEpochMs = command.retryAfter?.toEpochMilli(),
                        updatedAtEpochMs = command.updatedAt.toEpochMilli(),
                    )
                applied(checkNotNull(database.downloadDao().get(command.jobId)), changed = true)
            }
        }

    override suspend fun beginPublishing(
        command: R16DownloadPublishingStart
    ): R16OfflineMutationResult =
        mutationMutex.withLock {
            database.withTransaction {
                val existing =
                    database.downloadDao().get(command.jobId)
                        ?: return@withTransaction rejected(R16OfflineRejection.JOB_NOT_FOUND)
                if (!existing.matches(command)) {
                    return@withTransaction rejected(R16OfflineRejection.IDENTITY_MISMATCH)
                }
                return@withTransaction when (R16DownloadJobState.fromStored(existing.state)) {
                    R16DownloadJobState.VERIFYING -> {
                        check(
                            database
                                .downloadDao()
                                .beginPublishing(
                                    jobId = command.jobId,
                                    recordingId = command.recordingId.value,
                                    requestedSourceReferenceId =
                                        command.requestedSourceReferenceId?.value,
                                    requestedMediaVariant = command.requestedMediaVariant,
                                    destinationIdentity = command.destinationIdentity,
                                    pendingLocation = command.pendingLocation,
                                    updatedAtEpochMs = command.updatedAt.toEpochMilli(),
                                )
                        ) {
                            "Download job disappeared during publication start"
                        }
                        applied(
                            checkNotNull(database.downloadDao().get(command.jobId)),
                            changed = true,
                        )
                    }
                    R16DownloadJobState.PUBLISHING ->
                        if (existing.pendingLocation == command.pendingLocation) {
                            applied(existing, changed = false)
                        } else {
                            rejected(R16OfflineRejection.IDENTITY_MISMATCH)
                        }
                    else -> rejected(R16OfflineRejection.INVALID_STATE)
                }
            }
        }

    override suspend fun resetForRetry(command: R16DownloadRetryReset): R16OfflineMutationResult =
        mutationMutex.withLock {
            database.withTransaction {
                val existing =
                    database.downloadDao().get(command.jobId)
                        ?: return@withTransaction rejected(R16OfflineRejection.JOB_NOT_FOUND)
                if (!existing.matches(command)) {
                    return@withTransaction rejected(R16OfflineRejection.IDENTITY_MISMATCH)
                }
                if (
                    R16DownloadJobState.fromStored(existing.state) !=
                        R16DownloadJobState.FAILED_RETRYABLE
                ) {
                    return@withTransaction rejected(R16OfflineRejection.INVALID_STATE)
                }
                check(
                    database
                        .downloadDao()
                        .resetForRetry(
                            jobId = command.jobId,
                            recordingId = command.recordingId.value,
                            requestedSourceReferenceId = command.requestedSourceReferenceId?.value,
                            requestedMediaVariant = command.requestedMediaVariant,
                            destinationIdentity = command.destinationIdentity,
                            updatedAtEpochMs = command.updatedAt.toEpochMilli(),
                        )
                ) {
                    "Download job disappeared during retry reset"
                }
                applied(checkNotNull(database.downloadDao().get(command.jobId)), changed = true)
            }
        }

    override suspend fun retainPendingCleanupEvidence(
        command: R16PendingCleanupEvidence
    ): R16OfflineMutationResult =
        mutationMutex.withLock {
            database.withTransaction {
                val existing =
                    database.downloadDao().get(command.jobId)
                        ?: return@withTransaction rejected(R16OfflineRejection.JOB_NOT_FOUND)
                if (!existing.matches(command)) {
                    return@withTransaction rejected(R16OfflineRejection.IDENTITY_MISMATCH)
                }
                when (existing.pendingLocation) {
                    command.pendingLocation -> applied(existing, changed = false)
                    null -> {
                        check(
                            database
                                .downloadDao()
                                .retainPendingCleanupEvidence(
                                    jobId = command.jobId,
                                    recordingId = command.recordingId.value,
                                    requestedSourceReferenceId =
                                        command.requestedSourceReferenceId.value,
                                    requestedMediaVariant = command.requestedMediaVariant,
                                    destinationIdentity = command.destinationIdentity,
                                    pendingLocation = command.pendingLocation,
                                    updatedAtEpochMs = command.updatedAt.toEpochMilli(),
                                )
                        ) {
                            "Download job changed during pending cleanup retention"
                        }
                        applied(
                            checkNotNull(database.downloadDao().get(command.jobId)),
                            changed = true,
                        )
                    }
                    else -> rejected(R16OfflineRejection.IDENTITY_MISMATCH)
                }
            }
        }

    override suspend fun clearPendingCleanupEvidence(
        command: R16PendingCleanupEvidence
    ): R16OfflineMutationResult =
        mutationMutex.withLock {
            database.withTransaction {
                val existing =
                    database.downloadDao().get(command.jobId)
                        ?: return@withTransaction rejected(R16OfflineRejection.JOB_NOT_FOUND)
                if (!existing.matches(command)) {
                    return@withTransaction rejected(R16OfflineRejection.IDENTITY_MISMATCH)
                }
                when (existing.pendingLocation) {
                    null -> applied(existing, changed = false)
                    command.pendingLocation -> {
                        check(
                            database
                                .downloadDao()
                                .clearPendingCleanupEvidence(
                                    jobId = command.jobId,
                                    recordingId = command.recordingId.value,
                                    requestedSourceReferenceId =
                                        command.requestedSourceReferenceId.value,
                                    requestedMediaVariant = command.requestedMediaVariant,
                                    destinationIdentity = command.destinationIdentity,
                                    pendingLocation = command.pendingLocation,
                                    updatedAtEpochMs = command.updatedAt.toEpochMilli(),
                                )
                        ) {
                            "Download job changed during pending cleanup clearance"
                        }
                        applied(
                            checkNotNull(database.downloadDao().get(command.jobId)),
                            changed = true,
                        )
                    }
                    else -> rejected(R16OfflineRejection.IDENTITY_MISMATCH)
                }
            }
        }

    override suspend fun publishVerified(
        command: R16VerifiedDownloadPublication
    ): R16OfflineMutationResult =
        mutationMutex.withLock {
            if (!command.evidence.complete) {
                return@withLock rejected(R16OfflineRejection.VERIFICATION_INCOMPLETE)
            }
            database.withTransaction {
                val job =
                    database.downloadDao().get(command.jobId)
                        ?: return@withTransaction rejected(R16OfflineRejection.JOB_NOT_FOUND)
                val jobState = R16DownloadJobState.fromStored(job.state)
                if (job.destinationIdentity != command.destinationIdentity) {
                    return@withTransaction rejected(R16OfflineRejection.IDENTITY_MISMATCH)
                }
                if (jobState == R16DownloadJobState.AVAILABLE) {
                    val stored = job.publishedAssetId?.let { database.assetDao().get(it) }
                    return@withTransaction if (
                        stored != null &&
                            stored.assetId == command.asset.id.value &&
                            stored.recordingId == command.asset.recordingId.value &&
                            stored.recordingId == job.recordingId &&
                            stored.sourceReferenceId == command.asset.sourceReferenceId?.value &&
                            (job.requestedSourceReferenceId == null ||
                                stored.sourceReferenceId == job.requestedSourceReferenceId) &&
                            stored.assetKind == MediaAssetKind.SHIPPY_DOWNLOAD.name &&
                            stored.assetState == AssetState.AVAILABLE.name &&
                            stored.locationType == command.locationType &&
                            stored.location == command.asset.location.opaqueHandle &&
                            stored.lastVerifiedAtEpochMs != null &&
                            stored.contentLength == command.asset.technical.contentLength
                    ) {
                        applied(job, changed = false)
                    } else {
                        rejected(R16OfflineRejection.ASSET_CONFLICT)
                    }
                }
                if (jobState != R16DownloadJobState.PUBLISHING) {
                    return@withTransaction rejected(R16OfflineRejection.INVALID_STATE)
                }
                if (
                    job.recordingId != command.asset.recordingId.value ||
                        (job.requestedSourceReferenceId != null &&
                            job.requestedSourceReferenceId !=
                                command.asset.sourceReferenceId?.value)
                ) {
                    return@withTransaction rejected(R16OfflineRejection.IDENTITY_MISMATCH)
                }
                command.asset.sourceReferenceId?.let { sourceReferenceId ->
                    val source =
                        database.sourceDao().get(sourceReferenceId.value)
                            ?: return@withTransaction rejected(R16OfflineRejection.SOURCE_NOT_FOUND)
                    if (source.recordingId != job.recordingId) {
                        return@withTransaction rejected(R16OfflineRejection.IDENTITY_MISMATCH)
                    }
                }
                val verifiedAt = command.asset.lastVerifiedAt
                val contentLength = command.asset.technical.contentLength
                if (
                    command.asset.kind != MediaAssetKind.SHIPPY_DOWNLOAD ||
                        command.asset.state != AssetState.AVAILABLE ||
                        verifiedAt == null ||
                        contentLength == null ||
                        job.bytesTransferred != contentLength ||
                        (job.expectedBytes != null && job.expectedBytes != contentLength)
                ) {
                    return@withTransaction rejected(R16OfflineRejection.VERIFICATION_INCOMPLETE)
                }
                val existingAsset = database.assetDao().get(command.asset.id.value)
                if (
                    existingAsset != null &&
                        (existingAsset.recordingId != job.recordingId ||
                            existingAsset.assetKind != MediaAssetKind.SHIPPY_DOWNLOAD.name ||
                            existingAsset.locationType != command.locationType ||
                            existingAsset.location != command.asset.location.opaqueHandle ||
                            (existingAsset.sourceReferenceId != null &&
                                existingAsset.sourceReferenceId !=
                                    command.asset.sourceReferenceId?.value))
                ) {
                    return@withTransaction rejected(R16OfflineRejection.ASSET_CONFLICT)
                }
                val existingLocation =
                    database
                        .assetDao()
                        .exactLocation(command.locationType, command.asset.location.opaqueHandle)
                if (
                    existingLocation != null && existingLocation.assetId != command.asset.id.value
                ) {
                    return@withTransaction rejected(R16OfflineRejection.ASSET_CONFLICT)
                }
                val entity = command.toEntity(verifiedAt)
                val published =
                    database
                        .downloadDao()
                        .publishVerified(command.jobId, entity, command.publishedAt.toEpochMilli())
                database.libraryMembershipDao().refresh(command.asset.recordingId.value)
                applied(published, changed = true)
            }
        }

    override suspend fun markMissing(command: R16ManagedDownloadRemoval): R16OfflineMutationResult =
        mutationMutex.withLock {
            database.withTransaction {
                mutatePublishedAsset(
                    command = command,
                    jobState = R16DownloadJobState.FAILED_RETRYABLE,
                    failureKind = "MANAGED_ASSET_MISSING",
                )
            }
        }

    override suspend fun removeManagedDownload(
        command: R16ManagedDownloadRemoval,
        storage: R16ManagedDownloadStorage,
    ): R16OfflineMutationResult =
        mutationMutex.withLock {
            val job =
                database.downloadDao().get(command.jobId)
                    ?: return@withLock rejected(R16OfflineRejection.JOB_NOT_FOUND)
            if (job.recordingId != command.recordingId.value) {
                return@withLock rejected(R16OfflineRejection.IDENTITY_MISMATCH)
            }
            val jobState = R16DownloadJobState.fromStored(job.state)
            if (jobState == R16DownloadJobState.REMOVED && job.publishedAssetId == null) {
                return@withLock applied(job, changed = false)
            }
            val target =
                database.withTransaction { exactTarget(command) }
                    ?: return@withLock rejected(R16OfflineRejection.IDENTITY_MISMATCH)
            if (
                R16DownloadJobState.fromStored(target.job.state) !in
                    setOf(R16DownloadJobState.AVAILABLE, R16DownloadJobState.FAILED_RETRYABLE)
            ) {
                return@withLock rejected(R16OfflineRejection.INVALID_STATE)
            }
            val hasOtherReferences =
                database.withTransaction {
                    database
                        .downloadDao()
                        .countOtherPublishedAssetReferences(command.assetId.value, command.jobId) >
                        0
                }
            if (hasOtherReferences) {
                return@withLock withContext(NonCancellable) {
                    database.withTransaction {
                        val current =
                            database.downloadDao().get(command.jobId)
                                ?: return@withTransaction rejected(
                                    R16OfflineRejection.JOB_NOT_FOUND
                                )
                        if (
                            R16DownloadJobState.fromStored(current.state) ==
                                R16DownloadJobState.REMOVED && current.publishedAssetId == null
                        ) {
                            return@withTransaction applied(current, changed = false)
                        }
                        exactTarget(command)
                            ?: return@withTransaction rejected(
                                R16OfflineRejection.IDENTITY_MISMATCH
                            )
                        check(
                            database
                                .downloadDao()
                                .markRemoved(
                                    jobId = command.jobId,
                                    recordingId = command.recordingId.value,
                                    assetId = command.assetId.value,
                                    updatedAtEpochMs = command.updatedAt.toEpochMilli(),
                                ) == 1
                        ) {
                            "Managed download job disappeared during removal"
                        }
                        database.libraryMembershipDao().refresh(command.recordingId.value)
                        applied(
                            checkNotNull(database.downloadDao().get(command.jobId)),
                            changed = true,
                        )
                    }
                }
            }
            val removal = storage.delete(target.storageTarget)
            if (removal == R16PhysicalRemovalResult.FAILED) {
                return@withLock rejected(R16OfflineRejection.STORAGE_FAILURE)
            }
            withContext(NonCancellable) {
                database.withTransaction {
                    val current =
                        database.downloadDao().get(command.jobId)
                            ?: return@withTransaction rejected(R16OfflineRejection.JOB_NOT_FOUND)
                    if (
                        R16DownloadJobState.fromStored(current.state) ==
                            R16DownloadJobState.REMOVED && current.publishedAssetId == null
                    ) {
                        return@withTransaction applied(current, changed = false)
                    }
                    exactTarget(command)
                        ?: return@withTransaction rejected(R16OfflineRejection.IDENTITY_MISMATCH)
                    check(
                        database
                            .downloadDao()
                            .markRemoved(
                                jobId = command.jobId,
                                recordingId = command.recordingId.value,
                                assetId = command.assetId.value,
                                updatedAtEpochMs = command.updatedAt.toEpochMilli(),
                            ) == 1
                    ) {
                        "Managed download job disappeared during removal"
                    }
                    check(
                        database
                            .assetDao()
                            .deleteManagedDownload(
                                assetId = command.assetId.value,
                                recordingId = command.recordingId.value,
                            ) == 1
                    ) {
                        "Managed download asset disappeared during removal"
                    }
                    database.libraryMembershipDao().refresh(command.recordingId.value)
                    applied(checkNotNull(database.downloadDao().get(command.jobId)), changed = true)
                }
            }
        }

    private suspend fun mutatePublishedAsset(
        command: R16ManagedDownloadRemoval,
        jobState: R16DownloadJobState,
        failureKind: String?,
    ): R16OfflineMutationResult {
        val target = exactTarget(command) ?: return rejected(R16OfflineRejection.IDENTITY_MISMATCH)
        val jobsNeedingState =
            database
                .downloadDao()
                .countPublishedAssetReferencesNeedingState(
                    assetId = command.assetId.value,
                    state = jobState.name,
                    failureKind = failureKind,
                )
        val assetNeedsState =
            target.asset.assetState != AssetState.MISSING.name ||
                target.asset.lastVerifiedAtEpochMs != null
        if (!assetNeedsState && jobsNeedingState == 0) {
            return applied(target.job, changed = false)
        }
        if (assetNeedsState) {
            check(
                database
                    .assetDao()
                    .updateState(
                        assetId = target.asset.assetId,
                        state = AssetState.MISSING.name,
                        updatedAtEpochMs = command.updatedAt.toEpochMilli(),
                        verifiedAtEpochMs = null,
                    ) == 1
            ) {
                "Managed download asset disappeared during mutation"
            }
        }
        if (jobsNeedingState > 0) {
            database
                .downloadDao()
                .updatePublishedState(
                    assetId = command.assetId.value,
                    state = jobState.name,
                    failureKind = failureKind,
                    updatedAtEpochMs = command.updatedAt.toEpochMilli(),
                )
        }
        database.libraryMembershipDao().refresh(command.recordingId.value)
        return applied(
            checkNotNull(database.downloadDao().get(command.jobId)),
            changed = assetNeedsState || jobsNeedingState > 0,
        )
    }

    private suspend fun exactTarget(command: R16ManagedDownloadRemoval): ExactTarget? {
        val job = database.downloadDao().get(command.jobId) ?: return null
        val asset = database.assetDao().get(command.assetId.value) ?: return null
        if (
            job.recordingId != command.recordingId.value ||
                job.publishedAssetId != command.assetId.value ||
                asset.recordingId != command.recordingId.value ||
                asset.assetKind != MediaAssetKind.SHIPPY_DOWNLOAD.name ||
                ((job.destinationIdentity != null || command.destinationIdentity != null) &&
                    job.destinationIdentity != command.destinationIdentity)
        ) {
            return null
        }
        return ExactTarget(
            job = job,
            asset = asset,
            storageTarget =
                R16ManagedDownloadTarget(
                    assetId = command.assetId,
                    locationType = asset.locationType,
                    location = AssetLocation(asset.location),
                    documentId = asset.documentId,
                    destinationIdentity = job.destinationIdentity,
                ),
        )
    }

    private fun R16VerifiedDownloadPublication.toEntity(verifiedAt: Instant): MediaAssetEntity =
        MediaAssetEntity(
            assetId = asset.id.value,
            recordingId = asset.recordingId.value,
            sourceReferenceId = asset.sourceReferenceId?.value,
            assetKind = MediaAssetKind.SHIPPY_DOWNLOAD.name,
            assetState = AssetState.AVAILABLE.name,
            locationType = locationType,
            location = asset.location.opaqueHandle,
            documentId = documentId,
            mediaStoreId = mediaStoreId,
            displayName = displayName,
            mimeType = asset.technical.mimeType,
            container = container,
            codec = asset.technical.codec,
            bitrateBps = asset.technical.bitrateBps?.toLong(),
            sampleRateHz = asset.technical.sampleRateHz,
            channelCount = asset.technical.channelCount,
            contentLength = asset.technical.contentLength,
            contentChecksum = asset.checksum?.let { "${it.algorithm}:${it.value}" },
            fingerprintId = asset.fingerprint?.value,
            createdAtEpochMs = publishedAt.toEpochMilli(),
            updatedAtEpochMs = publishedAt.toEpochMilli(),
            lastVerifiedAtEpochMs = verifiedAt.toEpochMilli(),
            normalizedPathToken = normalizedPathToken,
            lastModifiedEpochMs = lastModifiedAt?.toEpochMilli(),
            downloadJobId = jobId,
        )

    private fun applied(entity: DownloadJobEntity, changed: Boolean) =
        R16OfflineMutationResult.Applied(entity.toSnapshot(), changed)

    private fun rejected(reason: R16OfflineRejection) = R16OfflineMutationResult.Rejected(reason)

    private data class ExactTarget(
        val job: DownloadJobEntity,
        val asset: MediaAssetEntity,
        val storageTarget: R16ManagedDownloadTarget,
    )
}

private fun DownloadJobEntity.matches(command: R16DownloadPublishingStart): Boolean =
    recordingId == command.recordingId.value &&
        requestedSourceReferenceId == command.requestedSourceReferenceId?.value &&
        requestedMediaVariant == command.requestedMediaVariant &&
        destinationIdentity == command.destinationIdentity

private fun DownloadJobEntity.matches(command: R16DownloadRetryReset): Boolean =
    recordingId == command.recordingId.value &&
        requestedSourceReferenceId == command.requestedSourceReferenceId?.value &&
        requestedMediaVariant == command.requestedMediaVariant &&
        destinationIdentity == command.destinationIdentity

private fun DownloadJobEntity.matches(command: R16PendingCleanupEvidence): Boolean =
    recordingId == command.recordingId.value &&
        requestedSourceReferenceId == command.requestedSourceReferenceId.value &&
        requestedMediaVariant == command.requestedMediaVariant &&
        destinationIdentity == command.destinationIdentity

private fun R16DownloadJobState.canAdvanceTo(next: R16DownloadJobState): Boolean =
    next == this ||
        next in
            when (this) {
                R16DownloadJobState.REQUESTED ->
                    setOf(
                        R16DownloadJobState.RESOLVING,
                        R16DownloadJobState.CANCELLED,
                        R16DownloadJobState.FAILED_RETRYABLE,
                        R16DownloadJobState.FAILED_FINAL,
                    )
                R16DownloadJobState.RESOLVING ->
                    setOf(
                        R16DownloadJobState.QUEUED,
                        R16DownloadJobState.CANCELLED,
                        R16DownloadJobState.FAILED_RETRYABLE,
                        R16DownloadJobState.FAILED_FINAL,
                    )
                R16DownloadJobState.QUEUED ->
                    setOf(
                        R16DownloadJobState.TRANSFERRING,
                        R16DownloadJobState.PAUSED,
                        R16DownloadJobState.CANCELLED,
                        R16DownloadJobState.FAILED_RETRYABLE,
                        R16DownloadJobState.FAILED_FINAL,
                    )
                R16DownloadJobState.TRANSFERRING ->
                    setOf(
                        R16DownloadJobState.VERIFYING,
                        R16DownloadJobState.PAUSED,
                        R16DownloadJobState.CANCELLED,
                        R16DownloadJobState.FAILED_RETRYABLE,
                        R16DownloadJobState.FAILED_FINAL,
                    )
                R16DownloadJobState.VERIFYING ->
                    setOf(
                        R16DownloadJobState.PUBLISHING,
                        R16DownloadJobState.CANCELLED,
                        R16DownloadJobState.FAILED_RETRYABLE,
                        R16DownloadJobState.FAILED_FINAL,
                    )
                R16DownloadJobState.PUBLISHING ->
                    setOf(
                        R16DownloadJobState.CANCELLED,
                        R16DownloadJobState.FAILED_RETRYABLE,
                        R16DownloadJobState.FAILED_FINAL,
                    )
                R16DownloadJobState.PAUSED ->
                    setOf(
                        R16DownloadJobState.QUEUED,
                        R16DownloadJobState.TRANSFERRING,
                        R16DownloadJobState.CANCELLED,
                        R16DownloadJobState.FAILED_RETRYABLE,
                        R16DownloadJobState.FAILED_FINAL,
                    )
                R16DownloadJobState.FAILED_RETRYABLE ->
                    setOf(
                        R16DownloadJobState.RESOLVING,
                        R16DownloadJobState.QUEUED,
                        R16DownloadJobState.CANCELLED,
                        R16DownloadJobState.FAILED_FINAL,
                    )
                R16DownloadJobState.AVAILABLE,
                R16DownloadJobState.CANCELLED,
                R16DownloadJobState.FAILED_FINAL,
                R16DownloadJobState.REMOVED -> emptySet()
            }

private fun DownloadJobEntity.toSnapshot() =
    R16DownloadJobSnapshot(
        jobId = jobId,
        recordingId = RecordingId(recordingId),
        requestedSourceReferenceId = requestedSourceReferenceId?.let(::SourceReferenceId),
        requestedMediaVariant = requestedMediaVariant,
        destinationIdentity = destinationIdentity,
        displayFallbackJson = displayFallbackJson,
        pendingLocation = pendingLocation,
        publishedAssetId = publishedAssetId?.let(::MediaAssetId),
        state = R16DownloadJobState.fromStored(state),
        bytesTransferred = bytesTransferred,
        expectedBytes = expectedBytes,
        failureKind = failureKind,
        retryAfter = retryAfterEpochMs?.let(Instant::ofEpochMilli),
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMs),
    )
