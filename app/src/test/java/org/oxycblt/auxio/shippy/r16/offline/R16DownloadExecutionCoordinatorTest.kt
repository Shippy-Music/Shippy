/*
 * Copyright (c) 2026 Auxio Project
 * R16DownloadExecutionCoordinatorTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.offline

import app.shippy.core.identity.MediaAssetId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.source.AvailabilitySnapshot
import app.shippy.core.source.SourceKey
import app.shippy.core.source.SourceReference
import app.shippy.data.offline.R16DownloadJobSnapshot
import app.shippy.data.offline.R16DownloadJobState
import app.shippy.data.offline.R16DownloadProgress
import app.shippy.data.offline.R16DownloadPublishingStart
import app.shippy.data.offline.R16DownloadRequest
import app.shippy.data.offline.R16DownloadRetryReset
import app.shippy.data.offline.R16ManagedDownloadRemoval
import app.shippy.data.offline.R16ManagedDownloadStorage
import app.shippy.data.offline.R16OfflineMutationResult
import app.shippy.data.offline.R16OfflineRejection
import app.shippy.data.offline.R16OfflineRepository
import app.shippy.data.offline.R16PendingCleanupEvidence
import app.shippy.data.offline.R16VerifiedDownloadPublication
import app.shippy.data.source.R16SourceStateRepository
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.download.DownloadArtifact
import org.oxycblt.auxio.shippy.download.DownloadJobId
import org.oxycblt.auxio.shippy.download.DownloadStorageFailure
import org.oxycblt.auxio.shippy.download.DownloadTransferFailure
import org.oxycblt.auxio.shippy.download.DownloadTransferProgress
import org.oxycblt.auxio.shippy.download.DownloadTransferResult
import org.oxycblt.auxio.shippy.download.SafManagedDownloadDeletionResult
import org.oxycblt.auxio.shippy.download.StorageResult
import org.oxycblt.auxio.shippy.provider.ResolvedStream

class R16DownloadExecutionCoordinatorTest {
    @Test
    fun `terminal and concurrent user transitions leave no owned staging artifacts`() =
        runBlocking {
            val offline = FakeOffline(snapshot(R16DownloadJobState.AVAILABLE))
            val operations = FakeOperations()

            val result = coordinator(operations).execute(offline, NoSources, JOB_ID)

            assertEquals(R16DownloadExecutionResult.Completed, result)
            assertEquals(0, operations.resolveCalls)
            assertEquals(0, operations.copyCalls)
            assertTrue(operations.stageCleaned)

            RejectionPoint.entries.forEach { point ->
                val rejectedOffline =
                    FakeOffline(
                            when (point) {
                                RejectionPoint.AFTER_TRANSFER ->
                                    snapshot(R16DownloadJobState.REQUESTED)
                                RejectionPoint.BEGIN_PUBLISHING ->
                                    snapshot(R16DownloadJobState.VERIFYING)
                                        .copy(bytesTransferred = BYTES)
                                RejectionPoint.PUBLISH_VERIFIED ->
                                    snapshot(R16DownloadJobState.PUBLISHING)
                                        .copy(
                                            bytesTransferred = BYTES,
                                            pendingLocation = PENDING_URI,
                                        )
                            }
                        )
                        .apply { rejectionPoint = point }
                val rejectedOperations = FakeOperations()

                val rejectedResult =
                    coordinator(rejectedOperations).execute(rejectedOffline, NoSources, JOB_ID)

                assertEquals(point.name, R16DownloadExecutionResult.Completed, rejectedResult)
                assertTrue(point.name, rejectedOperations.stageCleaned)
                if (point != RejectionPoint.AFTER_TRANSFER) {
                    assertTrue(point.name, rejectedOperations.pendingCleaned)
                }
            }

            val cleanupBlockedOffline =
                FakeOffline(snapshot(R16DownloadJobState.VERIFYING).copy(bytesTransferred = BYTES))
                    .apply { rejectionPoint = RejectionPoint.BEGIN_PUBLISHING }
            val cleanupBlockedOperations =
                FakeOperations().apply {
                    pendingCleanupResult = SafManagedDownloadDeletionResult.FAILED
                }
            assertEquals(
                R16DownloadExecutionResult.AwaitingUser,
                coordinator(cleanupBlockedOperations)
                    .execute(cleanupBlockedOffline, NoSources, JOB_ID),
            )
            assertEquals(PENDING_URI, cleanupBlockedOffline.job.pendingLocation)
            assertFalse(cleanupBlockedOperations.stageCleaned)

            cleanupBlockedOperations.pendingCleanupResult = SafManagedDownloadDeletionResult.DELETED
            assertEquals(
                R16DownloadExecutionResult.Completed,
                coordinator(cleanupBlockedOperations)
                    .execute(cleanupBlockedOffline, NoSources, JOB_ID),
            )
            assertEquals(null, cleanupBlockedOffline.job.pendingLocation)
            assertTrue(cleanupBlockedOperations.stageCleaned)

            val verifyingEvidenceOffline =
                FakeOffline(snapshot(R16DownloadJobState.VERIFYING).copy(bytesTransferred = BYTES))
                    .apply { failBeginPublishing = true }
            val verifyingEvidenceOperations =
                FakeOperations().apply {
                    pendingCleanupResult = SafManagedDownloadDeletionResult.FAILED
                }
            assertEquals(
                R16DownloadExecutionResult.AwaitingUser,
                coordinator(verifyingEvidenceOperations)
                    .execute(verifyingEvidenceOffline, NoSources, JOB_ID),
            )
            assertEquals(R16DownloadJobState.VERIFYING, verifyingEvidenceOffline.job.state)
            assertEquals(PENDING_URI, verifyingEvidenceOffline.job.pendingLocation)
            assertFalse(verifyingEvidenceOperations.stageCleaned)

            verifyingEvidenceOffline.failBeginPublishing = false
            verifyingEvidenceOperations.pendingCleanupResult =
                SafManagedDownloadDeletionResult.ALREADY_MISSING
            assertEquals(
                R16DownloadExecutionResult.Completed,
                coordinator(verifyingEvidenceOperations)
                    .execute(verifyingEvidenceOffline, NoSources, JOB_ID),
            )
            assertEquals(R16DownloadJobState.AVAILABLE, verifyingEvidenceOffline.job.state)
            assertEquals(null, verifyingEvidenceOffline.job.pendingLocation)
            assertTrue(verifyingEvidenceOperations.stageCleaned)

            listOf(true, false).forEach { cancellation ->
                val interruptedOffline =
                    FakeOffline(
                            snapshot(R16DownloadJobState.VERIFYING).copy(bytesTransferred = BYTES)
                        )
                        .apply {
                            cancelBeginPublishing = cancellation
                            failBeginPublishing = !cancellation
                        }
                val interruptedOperations = FakeOperations()
                val interruptedResult =
                    try {
                        coordinator(interruptedOperations)
                            .execute(interruptedOffline, NoSources, JOB_ID)
                    } catch (_: CancellationException) {
                        R16DownloadExecutionResult.Completed
                    }
                if (!cancellation) {
                    assertEquals(R16DownloadExecutionResult.Retry, interruptedResult)
                }
                assertTrue(interruptedOperations.pendingCleaned)
                assertTrue(interruptedOperations.stageCleaned)
            }

            val uncertainCommitOffline =
                FakeOffline(snapshot(R16DownloadJobState.VERIFYING).copy(bytesTransferred = BYTES))
                    .apply { failBeginAfterCommit = true }
            val uncertainCommitOperations = FakeOperations()
            assertEquals(
                R16DownloadExecutionResult.Retry,
                coordinator(uncertainCommitOperations)
                    .execute(uncertainCommitOffline, NoSources, JOB_ID),
            )
            assertEquals(R16DownloadJobState.FAILED_RETRYABLE, uncertainCommitOffline.job.state)
            assertEquals(null, uncertainCommitOffline.job.pendingLocation)
            assertTrue(uncertainCommitOperations.pendingCleaned)
        }

    @Test
    fun `requested job follows exact source transfer verify and atomic publication`() =
        runBlocking {
            val offline = FakeOffline(snapshot(R16DownloadJobState.REQUESTED))
            val operations = FakeOperations()

            val result = coordinator(operations).execute(offline, NoSources, JOB_ID)

            assertEquals(R16DownloadExecutionResult.Completed, result)
            assertEquals(R16DownloadJobState.AVAILABLE, offline.job.state)
            assertEquals(1, operations.resolveCalls)
            assertEquals(1, operations.transferCalls)
            assertEquals(1, operations.copyCalls)
            assertEquals("audio/mp4", operations.createdMimeType)
            assertEquals("audio/mp4", operations.verifiedMimeType)
            assertNotNull(offline.publication)
            assertEquals(RECORDING_ID, offline.publication?.asset?.recordingId)
            assertEquals(SOURCE_ID, offline.publication?.asset?.sourceReferenceId)
            assertEquals(
                "content://downloads/document/job-1",
                offline.publication?.asset?.location?.opaqueHandle,
            )
        }

    @Test
    fun `promotion from playback cache completes download without network transfer`() =
        runBlocking {
            val offline = FakeOffline(snapshot(R16DownloadJobState.REQUESTED))
            val operations = FakeOperations().apply { promotedBytes = BYTES }

            val result = coordinator(operations).execute(offline, NoSources, JOB_ID)

            assertEquals(R16DownloadExecutionResult.Completed, result)
            assertEquals(R16DownloadJobState.AVAILABLE, offline.job.state)
            assertEquals(1, operations.resolveCalls)
            assertEquals(1, operations.promotedCalls)
            assertEquals("audio/mp4", operations.promotedVariant)
            assertEquals(0, operations.transferCalls) // No network transfer!
            assertEquals(1, operations.copyCalls)
            assertNotNull(offline.publication)
        }

    @Test
    fun `exact source rejection becomes final without transfer`() = runBlocking {
        val offline = FakeOffline(snapshot(R16DownloadJobState.REQUESTED))
        val operations =
            FakeOperations().apply {
                resolution =
                    R16DownloadSourceResolution.Rejected(
                        R16DownloadSourceRejection.RECORDING_MISMATCH
                    )
            }

        val result = coordinator(operations).execute(offline, NoSources, JOB_ID)

        assertEquals(R16DownloadExecutionResult.Failed, result)
        assertEquals(R16DownloadJobState.FAILED_FINAL, offline.job.state)
        assertEquals("source_recording_mismatch", offline.job.failureKind)
        assertEquals(0, operations.transferCalls)
    }

    @Test
    fun `retryable transfer failure stays durable and requests bounded retry`() = runBlocking {
        val offline = FakeOffline(snapshot(R16DownloadJobState.REQUESTED))
        val operations =
            FakeOperations().apply {
                transferResult =
                    DownloadTransferResult.Failure(DownloadTransferFailure.NetworkUnavailable)
            }

        val result = coordinator(operations).execute(offline, NoSources, JOB_ID)

        assertEquals(R16DownloadExecutionResult.Retry, result)
        assertEquals(R16DownloadJobState.FAILED_RETRYABLE, offline.job.state)
        assertEquals("transfer_network_unavailable", offline.job.failureKind)
        assertTrue(operations.stageCleaned)
        assertFalse(offline.job.state == R16DownloadJobState.CANCELLED)

        val unexpectedOffline = FakeOffline(snapshot(R16DownloadJobState.REQUESTED))
        val unexpectedOperations = FakeOperations().apply { throwDuringTransfer = true }

        val unexpectedResult =
            coordinator(unexpectedOperations).execute(unexpectedOffline, NoSources, JOB_ID)

        assertEquals(R16DownloadExecutionResult.Retry, unexpectedResult)
        assertEquals(R16DownloadJobState.FAILED_RETRYABLE, unexpectedOffline.job.state)
        assertEquals("unexpected_execution_failure", unexpectedOffline.job.failureKind)
    }

    @Test
    fun `publishing restart recovers exact pending document without provider or transfer`() =
        runBlocking {
            val offline =
                FakeOffline(
                    snapshot(R16DownloadJobState.PUBLISHING)
                        .copy(bytesTransferred = BYTES, pendingLocation = PENDING_URI)
                )
            val operations = FakeOperations()

            val result = coordinator(operations).execute(offline, NoSources, JOB_ID)

            assertEquals(R16DownloadExecutionResult.Completed, result)
            assertEquals(R16DownloadJobState.AVAILABLE, offline.job.state)
            assertEquals(1, operations.recoverCalls)
            assertEquals(0, operations.resolveCalls)
            assertEquals(0, operations.transferCalls)
            assertEquals(1, operations.copyCalls)
            assertEquals("audio/mp4", operations.verifiedMimeType)

            val alreadyMissingOffline =
                FakeOffline(
                    snapshot(R16DownloadJobState.PUBLISHING)
                        .copy(bytesTransferred = BYTES, pendingLocation = PENDING_URI)
                )
            val alreadyMissingOperations =
                FakeOperations().apply {
                    recoverFailure = DownloadStorageFailure.OPEN_FAILED
                    pendingCleanupResult = SafManagedDownloadDeletionResult.ALREADY_MISSING
                }
            assertEquals(
                R16DownloadExecutionResult.Retry,
                coordinator(alreadyMissingOperations)
                    .execute(alreadyMissingOffline, NoSources, JOB_ID),
            )
            assertEquals(R16DownloadJobState.QUEUED, alreadyMissingOffline.job.state)
            assertEquals(null, alreadyMissingOffline.job.pendingLocation)
            assertTrue(alreadyMissingOperations.stageCleaned)
        }

    @Test
    fun `unreadable Media3 asset is removed and reset before retry`() = runBlocking {
        val offline =
            FakeOffline(
                snapshot(R16DownloadJobState.PUBLISHING)
                    .copy(bytesTransferred = BYTES, pendingLocation = PENDING_URI)
            )
        val operations =
            FakeOperations().apply {
                media3Result =
                    R16Media3ReadabilityResult.Unreadable(R16Media3UnreadableReason.READ_FAILED)
            }

        val result = coordinator(operations).execute(offline, NoSources, JOB_ID)

        assertEquals(R16DownloadExecutionResult.Retry, result)
        assertEquals(R16DownloadJobState.QUEUED, offline.job.state)
        assertEquals(null, offline.job.pendingLocation)
        assertTrue(operations.pendingCleaned)
        assertTrue(operations.stageCleaned)
        assertEquals(null, offline.publication)

        val storageOffline =
            FakeOffline(
                snapshot(R16DownloadJobState.PUBLISHING)
                    .copy(bytesTransferred = BYTES, pendingLocation = PENDING_URI)
            )
        val storageOperations =
            FakeOperations().apply { copyFailure = DownloadStorageFailure.OPEN_FAILED }
        val storageResult =
            coordinator(storageOperations).execute(storageOffline, NoSources, JOB_ID)
        assertEquals(R16DownloadExecutionResult.AwaitingUser, storageResult)
        assertEquals(R16DownloadJobState.FAILED_RETRYABLE, storageOffline.job.state)
        assertEquals(null, storageOffline.job.pendingLocation)
        assertEquals("storage_open_failed", storageOffline.job.failureKind)

        val cancelledOffline =
            FakeOffline(
                snapshot(R16DownloadJobState.PUBLISHING)
                    .copy(bytesTransferred = BYTES, pendingLocation = PENDING_URI)
            )
        val cancelledOperations = FakeOperations().apply { cancelDuringCopy = true }
        var cancellationPropagated = false
        try {
            coordinator(cancelledOperations).execute(cancelledOffline, NoSources, JOB_ID)
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }

        assertTrue(cancellationPropagated)
        assertEquals(R16DownloadJobState.QUEUED, cancelledOffline.job.state)
        assertEquals(null, cancelledOffline.job.pendingLocation)
        assertTrue(cancelledOperations.pendingCleaned)
        assertTrue(cancelledOperations.stageCleaned)

        val blockedCancellationOffline =
            FakeOffline(
                    snapshot(R16DownloadJobState.PUBLISHING)
                        .copy(bytesTransferred = BYTES, pendingLocation = PENDING_URI)
                )
                .apply { cancelOnSecondLoad = true }
        val blockedCancellationOperations =
            FakeOperations().apply {
                cancelDuringCopy = true
                pendingCleanupResult = SafManagedDownloadDeletionResult.FAILED
            }
        try {
            coordinator(blockedCancellationOperations)
                .execute(blockedCancellationOffline, NoSources, JOB_ID)
        } catch (_: CancellationException) {
            // Expected: WorkManager cancellation identity remains intact.
        }
        assertEquals(R16DownloadJobState.CANCELLED, blockedCancellationOffline.job.state)
        assertEquals(PENDING_URI, blockedCancellationOffline.job.pendingLocation)
        assertTrue(blockedCancellationOperations.pendingCleaned)
        assertFalse(blockedCancellationOperations.stageCleaned)
    }

    private fun coordinator(operations: FakeOperations) =
        R16DownloadExecutionCoordinator(operations).apply { now = { Instant.ofEpochMilli(100) } }

    private fun snapshot(state: R16DownloadJobState) =
        R16DownloadJobSnapshot(
            jobId = JOB_ID,
            recordingId = RECORDING_ID,
            requestedSourceReferenceId = SOURCE_ID,
            requestedMediaVariant = "audio/default",
            destinationIdentity = "content://downloads/tree/music",
            pendingLocation = null,
            publishedAssetId = if (state == R16DownloadJobState.AVAILABLE) ASSET_ID else null,
            state = state,
            bytesTransferred = 0,
            expectedBytes = BYTES,
            failureKind = null,
            retryAfter = null,
            displayFallbackJson = "{\"title\":\"Test Track\"}",
            updatedAt = Instant.EPOCH,
        )

    private class FakeOffline(initial: R16DownloadJobSnapshot) : R16OfflineRepository {
        var job = initial
        var publication: R16VerifiedDownloadPublication? = null
        var rejectionPoint: RejectionPoint? = null
        var cancelBeginPublishing = false
        var failBeginPublishing = false
        var failBeginAfterCommit = false
        var cancelOnSecondLoad = false
        var loadCalls = 0

        override suspend fun load(jobId: String): R16DownloadJobSnapshot? {
            loadCalls += 1
            if (cancelOnSecondLoad && loadCalls == 2) {
                job = job.copy(state = R16DownloadJobState.CANCELLED)
            }
            return job.takeIf { it.jobId == jobId }
        }

        override suspend fun pendingCleanupLocations(): List<String> =
            listOfNotNull(job.pendingLocation)

        override suspend fun request(command: R16DownloadRequest): R16OfflineMutationResult =
            error("unused")

        override suspend fun updateProgress(
            command: R16DownloadProgress
        ): R16OfflineMutationResult {
            if (
                rejectionPoint == RejectionPoint.AFTER_TRANSFER &&
                    command.state == R16DownloadJobState.VERIFYING
            ) {
                job = job.copy(state = R16DownloadJobState.CANCELLED)
                return R16OfflineMutationResult.Rejected(R16OfflineRejection.INVALID_STATE)
            }
            job =
                job.copy(
                    state = command.state,
                    bytesTransferred = command.bytesTransferred,
                    expectedBytes = command.expectedBytes ?: job.expectedBytes,
                    failureKind = command.failureKind,
                    retryAfter = command.retryAfter,
                    updatedAt = command.updatedAt,
                )
            return R16OfflineMutationResult.Applied(job, changed = true)
        }

        override suspend fun beginPublishing(
            command: R16DownloadPublishingStart
        ): R16OfflineMutationResult {
            if (cancelBeginPublishing) throw CancellationException("worker stopped")
            if (failBeginPublishing) error("database unavailable")
            if (failBeginAfterCommit) {
                job =
                    job.copy(
                        state = R16DownloadJobState.PUBLISHING,
                        pendingLocation = command.pendingLocation,
                    )
                error("commit acknowledgement lost")
            }
            if (rejectionPoint == RejectionPoint.BEGIN_PUBLISHING) {
                job = job.copy(state = R16DownloadJobState.CANCELLED)
                return R16OfflineMutationResult.Rejected(R16OfflineRejection.INVALID_STATE)
            }
            job =
                job.copy(
                    state = R16DownloadJobState.PUBLISHING,
                    pendingLocation = command.pendingLocation,
                    updatedAt = command.updatedAt,
                )
            return R16OfflineMutationResult.Applied(job, changed = true)
        }

        override suspend fun resetForRetry(
            command: R16DownloadRetryReset
        ): R16OfflineMutationResult {
            job =
                job.copy(
                    state = R16DownloadJobState.QUEUED,
                    bytesTransferred = 0,
                    failureKind = null,
                    retryAfter = null,
                    pendingLocation = null,
                    updatedAt = command.updatedAt,
                )
            return R16OfflineMutationResult.Applied(job, changed = true)
        }

        override suspend fun retainPendingCleanupEvidence(
            command: R16PendingCleanupEvidence
        ): R16OfflineMutationResult {
            if (job.pendingLocation != null && job.pendingLocation != command.pendingLocation) {
                return R16OfflineMutationResult.Rejected(R16OfflineRejection.IDENTITY_MISMATCH)
            }
            val changed = job.pendingLocation == null
            job = job.copy(pendingLocation = command.pendingLocation, updatedAt = command.updatedAt)
            return R16OfflineMutationResult.Applied(job, changed)
        }

        override suspend fun clearPendingCleanupEvidence(
            command: R16PendingCleanupEvidence
        ): R16OfflineMutationResult {
            if (job.pendingLocation == null) {
                return R16OfflineMutationResult.Applied(job, changed = false)
            }
            if (job.pendingLocation != command.pendingLocation) {
                return R16OfflineMutationResult.Rejected(R16OfflineRejection.IDENTITY_MISMATCH)
            }
            job = job.copy(pendingLocation = null, updatedAt = command.updatedAt)
            return R16OfflineMutationResult.Applied(job, changed = true)
        }

        override suspend fun publishVerified(
            command: R16VerifiedDownloadPublication
        ): R16OfflineMutationResult {
            if (rejectionPoint == RejectionPoint.PUBLISH_VERIFIED) {
                job = job.copy(state = R16DownloadJobState.CANCELLED)
                return R16OfflineMutationResult.Rejected(R16OfflineRejection.INVALID_STATE)
            }
            publication = command
            job =
                job.copy(
                    state = R16DownloadJobState.AVAILABLE,
                    pendingLocation = null,
                    publishedAssetId = command.asset.id,
                    updatedAt = command.publishedAt,
                )
            return R16OfflineMutationResult.Applied(job, changed = true)
        }

        override suspend fun markMissing(
            command: R16ManagedDownloadRemoval
        ): R16OfflineMutationResult = error("unused")

        override suspend fun removeManagedDownload(
            command: R16ManagedDownloadRemoval,
            storage: R16ManagedDownloadStorage,
        ): R16OfflineMutationResult = error("unused")
    }

    private class FakeOperations : R16DownloadOperations {
        var resolution: R16DownloadSourceResolution =
            R16DownloadSourceResolution.Ready(
                R16DownloadSource(
                    recordingId = RECORDING_ID,
                    sourceReferenceId = SOURCE_ID,
                    requestedMediaVariant = "audio/default",
                    stream =
                        ResolvedStream(
                            candidateId = CandidateId("candidate"),
                            uri = "https://example.invalid/audio",
                            mimeType = "audio/mp4",
                            contentLength = BYTES,
                        ),
                )
            )
        var transferResult: DownloadTransferResult = DownloadTransferResult.Success(BYTES, BYTES)
        var media3Result: R16Media3ReadabilityResult = R16Media3ReadabilityResult.Readable
        var resolveCalls = 0
        var transferCalls = 0
        var recoverCalls = 0
        var copyCalls = 0
        var pendingCleaned = false
        var stageCleaned = false
        var throwDuringTransfer = false
        var cancelDuringCopy = false
        var copyFailure: DownloadStorageFailure? = null
        var recoverFailure: DownloadStorageFailure? = null
        var pendingCleanupResult = SafManagedDownloadDeletionResult.DELETED
        var createdMimeType: String? = null
        var verifiedMimeType: String? = null
        var promotedBytes: Long? = null
        var promotedCalls = 0
        var promotedVariant: String? = null

        override suspend fun resolve(
            sources: R16SourceStateRepository,
            request: R16DownloadSourceRequest,
        ): R16DownloadSourceResolution {
            resolveCalls += 1
            return resolution
        }

        override suspend fun transfer(
            jobId: DownloadJobId,
            stream: ResolvedStream,
            expectedBytes: Long?,
            onProgress: suspend (DownloadTransferProgress) -> Unit,
        ): DownloadTransferResult {
            transferCalls += 1
            if (throwDuringTransfer) error("unexpected transfer failure")
            return transferResult
        }

        override suspend fun hasVerifiedStage(jobId: DownloadJobId, expectedBytes: Long?): Boolean =
            true

        override suspend fun cleanupStage(jobId: DownloadJobId) {
            stageCleaned = true
        }

        override suspend fun createPending(
            destinationIdentity: String,
            jobId: DownloadJobId,
            title: String,
            mimeType: String?,
        ): StorageResult<R16PendingDownloadDocument> {
            createdMimeType = mimeType
            return StorageResult.Success(pending(jobId, mimeType ?: "application/octet-stream"))
        }

        override suspend fun recoverPending(
            destinationIdentity: String,
            jobId: DownloadJobId,
            contentUri: String,
        ): StorageResult<R16PendingDownloadDocument> {
            recoverCalls += 1
            recoverFailure?.let {
                return StorageResult.Failure(it)
            }
            return StorageResult.Success(pending(jobId))
        }

        override suspend fun copyStage(
            destinationIdentity: String,
            pending: R16PendingDownloadDocument,
            expectedBytes: Long?,
        ): StorageResult<Unit> {
            copyCalls += 1
            if (cancelDuringCopy) throw CancellationException("worker stopped")
            copyFailure?.let {
                return StorageResult.Failure(it)
            }
            return StorageResult.Success(Unit)
        }

        override suspend fun verifyDestination(
            destinationIdentity: String,
            pending: R16PendingDownloadDocument,
            expectedBytes: Long?,
            verifiedAtEpochMs: Long,
        ): StorageResult<DownloadArtifact> =
            StorageResult.Success(
                DownloadArtifact(
                    contentUri = pending.contentUri,
                    contentLength = BYTES,
                    mimeType = "audio/mp4",
                    verifiedAtEpochMs = verifiedAtEpochMs,
                )
            )

        override suspend fun verifyMedia3(
            contentUri: String,
            expectedMimeType: String,
        ): R16Media3ReadabilityResult {
            verifiedMimeType = expectedMimeType
            return media3Result
        }

        override suspend fun cleanupPending(
            destinationIdentity: String,
            pending: R16PendingDownloadDocument,
        ): SafManagedDownloadDeletionResult {
            pendingCleaned = true
            return pendingCleanupResult
        }

        override suspend fun cleanupPersistedPending(
            destinationIdentity: String,
            jobId: DownloadJobId,
            contentUri: String,
        ): SafManagedDownloadDeletionResult {
            pendingCleaned = true
            return pendingCleanupResult
        }

        override suspend fun promoteFromCache(
            jobId: DownloadJobId,
            sourceId: app.shippy.core.identity.SourceReferenceId,
            mediaVariant: String,
            expectedBytes: Long?,
        ): Long? {
            promotedCalls += 1
            promotedVariant = mediaVariant
            return promotedBytes
        }

        private fun pending(jobId: DownloadJobId, mimeType: String = "audio/mp4") =
            R16PendingDownloadDocument(
                destinationIdentity = "content://downloads/tree/music",
                jobId = jobId,
                contentUri = PENDING_URI,
                displayName = "Test Track [shippy-job-1].m4a",
                mimeType = mimeType,
            )
    }

    private object NoSources : R16SourceStateRepository {
        override fun observe(recordingId: RecordingId): Flow<List<SourceReference>> = emptyFlow()

        override suspend fun get(sourceReferenceId: SourceReferenceId): SourceReference? = null

        override suspend fun exact(key: SourceKey): SourceReference? = null

        override suspend fun updateAvailability(
            key: SourceKey,
            availability: AvailabilitySnapshot,
            updatedAt: Instant,
        ) = Unit
    }

    private companion object {
        const val JOB_ID = "job-1"
        const val BYTES = 10L
        const val PENDING_URI = "content://downloads/document/job-1"
        val RECORDING_ID = RecordingId("550e8400-e29b-41d4-a716-446655440000")
        val SOURCE_ID = SourceReferenceId("550e8400-e29b-41d4-a716-446655440001")
        val ASSET_ID = MediaAssetId("550e8400-e29b-41d4-a716-446655440002")
    }

    private enum class RejectionPoint {
        AFTER_TRANSFER,
        BEGIN_PUBLISHING,
        PUBLISH_VERIFIED,
    }
}
