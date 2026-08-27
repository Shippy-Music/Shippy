/*
 * Copyright (c) 2026 Auxio Project
 * R16DownloadPresentationAndActionTest.kt is part of Auxio.
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
import app.shippy.data.offline.R16DownloadJobSnapshot
import app.shippy.data.offline.R16DownloadJobState
import app.shippy.data.offline.R16DownloadProgress
import app.shippy.data.offline.R16DownloadPublishingStart
import app.shippy.data.offline.R16DownloadRequest
import app.shippy.data.offline.R16DownloadRetryReset
import app.shippy.data.offline.R16ManagedDownloadRemoval
import app.shippy.data.offline.R16ManagedDownloadStorage
import app.shippy.data.offline.R16ManagedDownloadTarget
import app.shippy.data.offline.R16OfflineMutationResult
import app.shippy.data.offline.R16OfflineRepository
import app.shippy.data.offline.R16PendingCleanupEvidence
import app.shippy.data.offline.R16PhysicalRemovalResult
import app.shippy.data.offline.R16VerifiedDownloadPublication
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class R16DownloadPresentationAndActionTest {
    private val recordingId = RecordingId(UUID.randomUUID().toString())
    private val sourceId = SourceReferenceId(UUID.randomUUID().toString())
    private val publishedAssetId = MediaAssetId(UUID.randomUUID().toString())
    private val now = Instant.now()

    @Test
    fun `presentation mapping produces correct states for all lifecycle phases`() {
        // 1. No snapshot & no sources -> Unavailable
        val unavailable = R16DownloadPresentationState.fromSnapshot(recordingId, null, emptyList())
        assertTrue(unavailable is R16DownloadPresentationState.Unavailable)

        // 2. No snapshot & available sources -> ReadyToRequest
        val ready = R16DownloadPresentationState.fromSnapshot(recordingId, null, listOf(sourceId))
        assertTrue(ready is R16DownloadPresentationState.ReadyToRequest)
        assertEquals(
            listOf(sourceId),
            (ready as R16DownloadPresentationState.ReadyToRequest).availableSourceReferenceIds,
        )

        // 3. In progress transfer with fraction
        val transferringSnapshot =
            R16DownloadJobSnapshot(
                jobId = "job-1",
                recordingId = recordingId,
                requestedSourceReferenceId = sourceId,
                requestedMediaVariant = "DEFAULT",
                destinationIdentity = "PRIMARY",
                displayFallbackJson = "{}",
                pendingLocation = "content://pending/1",
                publishedAssetId = null,
                state = R16DownloadJobState.TRANSFERRING,
                bytesTransferred = 500L,
                expectedBytes = 1000L,
                failureKind = null,
                retryAfter = null,
                updatedAt = now,
            )
        val inProgress =
            R16DownloadPresentationState.fromSnapshot(recordingId, transferringSnapshot)
        assertTrue(inProgress is R16DownloadPresentationState.InProgress)
        val inProgressState = inProgress as R16DownloadPresentationState.InProgress
        assertEquals(0.5f, inProgressState.progressFraction ?: 0f, 0.001f)

        // 4. Paused state
        val pausedSnapshot = transferringSnapshot.copy(state = R16DownloadJobState.PAUSED)
        val paused = R16DownloadPresentationState.fromSnapshot(recordingId, pausedSnapshot)
        assertTrue(paused is R16DownloadPresentationState.Paused)

        // 5. Retryable regular failure
        val failedSnapshot =
            transferringSnapshot.copy(
                state = R16DownloadJobState.FAILED_RETRYABLE,
                failureKind = "NETWORK_DISCONNECTED",
            )
        val retryable = R16DownloadPresentationState.fromSnapshot(recordingId, failedSnapshot)
        assertTrue(retryable is R16DownloadPresentationState.Retryable)
        assertEquals(
            "NETWORK_DISCONNECTED",
            (retryable as R16DownloadPresentationState.Retryable).failureKind,
        )

        // 6. Awaiting storage permission failure
        val permissionSnapshot =
            transferringSnapshot.copy(
                state = R16DownloadJobState.FAILED_RETRYABLE,
                failureKind = "STORAGE_TREE_REVOKED",
            )
        val awaitingPerm =
            R16DownloadPresentationState.fromSnapshot(recordingId, permissionSnapshot)
        assertTrue(awaitingPerm is R16DownloadPresentationState.AwaitingStoragePermission)

        // 7. Available published asset
        val availableSnapshot =
            transferringSnapshot.copy(
                state = R16DownloadJobState.AVAILABLE,
                publishedAssetId = publishedAssetId,
            )
        val available = R16DownloadPresentationState.fromSnapshot(recordingId, availableSnapshot)
        assertTrue(available is R16DownloadPresentationState.Available)
        assertEquals(
            publishedAssetId,
            (available as R16DownloadPresentationState.Available).publishedAssetId,
        )

        // 8. Final failure
        val finalFailureSnapshot =
            transferringSnapshot.copy(
                state = R16DownloadJobState.FAILED_FINAL,
                failureKind = "CORRUPT_PAYLOAD",
            )
        val finalFailure =
            R16DownloadPresentationState.fromSnapshot(recordingId, finalFailureSnapshot)
        assertTrue(finalFailure is R16DownloadPresentationState.FinalFailure)
        assertEquals(
            "CORRUPT_PAYLOAD",
            (finalFailure as R16DownloadPresentationState.FinalFailure).failureKind,
        )
    }

    @Test
    fun `action coordinator coordinates lifecycle mutations idempotently`() = runBlocking {
        val fakeRepo = FakeOfflineRepository()
        val fakeScheduler = FakeDownloadScheduler()
        val fakeStorage = FakeManagedDownloadStorage()
        val coordinator = R16DownloadActionCoordinator(fakeRepo, fakeScheduler, fakeStorage)

        // Request download
        val requestResult = coordinator.requestDownload(recordingId, sourceId)
        assertTrue(requestResult.isSuccess)
        val jobId = requestResult.getOrThrow()
        assertNotNull(jobId)
        assertTrue(jobId.startsWith("job-"))
        assertEquals(jobId, fakeRepo.createdJob?.jobId)

        // Populate fake snapshot for subsequent steps
        fakeRepo.snapshot =
            R16DownloadJobSnapshot(
                jobId = jobId,
                recordingId = recordingId,
                requestedSourceReferenceId = sourceId,
                requestedMediaVariant = "DEFAULT",
                destinationIdentity = "PRIMARY_DOWNLOADS",
                displayFallbackJson = "{}",
                pendingLocation = "content://downloads/item-1",
                publishedAssetId = publishedAssetId,
                state = R16DownloadJobState.TRANSFERRING,
                bytesTransferred = 100L,
                expectedBytes = 1000L,
                failureKind = null,
                retryAfter = null,
                updatedAt = now,
            )

        // Pause download
        val pauseResult = coordinator.pauseDownload(jobId)
        assertTrue(pauseResult.isSuccess)
        assertEquals(R16DownloadJobState.PAUSED, fakeRepo.progress?.state)
        assertEquals(jobId, fakeScheduler.cancelledJobId)

        // Resume download
        val resumeResult = coordinator.resumeDownload(jobId)
        assertTrue(resumeResult.isSuccess)
        assertEquals(R16DownloadJobState.QUEUED, fakeRepo.progress?.state)
        assertEquals(jobId, fakeRepo.progress?.jobId)

        // Retry download
        fakeRepo.snapshot = fakeRepo.snapshot?.copy(state = R16DownloadJobState.FAILED_RETRYABLE)
        val retryResult = coordinator.retryDownload(jobId)
        assertTrue(retryResult.isSuccess)
        assertEquals(jobId, fakeRepo.reset?.jobId)

        // Cancel download
        val cancelResult = coordinator.cancelDownload(jobId)
        assertTrue(cancelResult.isSuccess)
        assertEquals(R16DownloadJobState.CANCELLED, fakeRepo.progress?.state)

        // Remove download
        val removeResult = coordinator.removeDownload(jobId, fakeStorage)
        assertTrue(removeResult.isSuccess)
        assertEquals(jobId, fakeRepo.removal?.jobId)
        assertEquals(publishedAssetId, fakeStorage.deletedTarget?.assetId)
    }

    @Test
    fun `resume from FAILED_RETRYABLE resets job and reschedules`() = runBlocking {
        val fakeRepo = FakeOfflineRepository()
        val fakeScheduler = FakeDownloadScheduler()
        val fakeStorage = FakeManagedDownloadStorage()
        val coordinator = R16DownloadActionCoordinator(fakeRepo, fakeScheduler, fakeStorage)
        val jobId = "job-retryable"

        fakeRepo.snapshot =
            R16DownloadJobSnapshot(
                jobId = jobId,
                recordingId = recordingId,
                requestedSourceReferenceId = sourceId,
                requestedMediaVariant = "DEFAULT",
                destinationIdentity = "PRIMARY_DOWNLOADS",
                displayFallbackJson = "{}",
                pendingLocation = null,
                publishedAssetId = null,
                state = R16DownloadJobState.FAILED_RETRYABLE,
                bytesTransferred = 100L,
                expectedBytes = 1000L,
                failureKind = "NETWORK_ERROR",
                retryAfter = null,
                updatedAt = now,
            )

        val result = coordinator.resumeDownload(jobId)
        assertTrue(result.isSuccess)
        assertEquals(jobId, fakeRepo.reset?.jobId)
        assertEquals(jobId, fakeScheduler.scheduledJobId)
    }

    @Test
    fun `resume rejects non-paused non-retryable states`() = runBlocking {
        val fakeRepo = FakeOfflineRepository()
        val fakeScheduler = FakeDownloadScheduler()
        val fakeStorage = FakeManagedDownloadStorage()
        val coordinator = R16DownloadActionCoordinator(fakeRepo, fakeScheduler, fakeStorage)
        val jobId = "job-available"

        listOf(
                R16DownloadJobState.AVAILABLE,
                R16DownloadJobState.FAILED_FINAL,
                R16DownloadJobState.CANCELLED,
                R16DownloadJobState.TRANSFERRING,
                R16DownloadJobState.REQUESTED,
            )
            .forEach { state ->
                fakeRepo.snapshot =
                    R16DownloadJobSnapshot(
                        jobId = jobId,
                        recordingId = recordingId,
                        requestedSourceReferenceId = sourceId,
                        requestedMediaVariant = "DEFAULT",
                        destinationIdentity = "PRIMARY_DOWNLOADS",
                        displayFallbackJson = "{}",
                        pendingLocation = null,
                        publishedAssetId = null,
                        state = state,
                        bytesTransferred = 100L,
                        expectedBytes = 1000L,
                        failureKind = null,
                        retryAfter = null,
                        updatedAt = now,
                    )
                fakeScheduler.scheduledJobId = null
                val result = coordinator.resumeDownload(jobId)
                assertTrue("State $state should fail resume", result.isFailure)
                assertEquals(null, fakeScheduler.scheduledJobId)
            }
    }

    @Test
    fun `retry rejects non-FAILED_RETRYABLE states`() = runBlocking {
        val fakeRepo = FakeOfflineRepository()
        val fakeScheduler = FakeDownloadScheduler()
        val fakeStorage = FakeManagedDownloadStorage()
        val coordinator = R16DownloadActionCoordinator(fakeRepo, fakeScheduler, fakeStorage)
        val jobId = "job-paused"

        listOf(
                R16DownloadJobState.AVAILABLE,
                R16DownloadJobState.PAUSED,
                R16DownloadJobState.CANCELLED,
                R16DownloadJobState.FAILED_FINAL,
                R16DownloadJobState.REQUESTED,
            )
            .forEach { state ->
                fakeRepo.snapshot =
                    R16DownloadJobSnapshot(
                        jobId = jobId,
                        recordingId = recordingId,
                        requestedSourceReferenceId = sourceId,
                        requestedMediaVariant = "DEFAULT",
                        destinationIdentity = "PRIMARY_DOWNLOADS",
                        displayFallbackJson = "{}",
                        pendingLocation = null,
                        publishedAssetId = null,
                        state = state,
                        bytesTransferred = 100L,
                        expectedBytes = 1000L,
                        failureKind = null,
                        retryAfter = null,
                        updatedAt = now,
                    )
                val result = coordinator.retryDownload(jobId)
                assertTrue("State $state should fail retry", result.isFailure)
            }
    }

    private class FakeDownloadScheduler : R16DownloadScheduler {
        var scheduledJobId: String? = null
        var cancelledJobId: String? = null

        override fun schedule(jobId: String): Boolean {
            scheduledJobId = jobId
            return true
        }

        override fun cancel(jobId: String): Boolean {
            cancelledJobId = jobId
            return true
        }
    }

    private class FakeManagedDownloadStorage : R16ManagedDownloadStorage {
        var deletedTarget: R16ManagedDownloadTarget? = null

        override suspend fun delete(target: R16ManagedDownloadTarget): R16PhysicalRemovalResult {
            deletedTarget = target
            return R16PhysicalRemovalResult.DELETED
        }
    }

    private class FakeOfflineRepository : R16OfflineRepository {
        var snapshot: R16DownloadJobSnapshot? = null
        var createdJob: R16DownloadRequest? = null
        var progress: R16DownloadProgress? = null
        var reset: R16DownloadRetryReset? = null
        var removal: R16ManagedDownloadRemoval? = null

        override suspend fun load(jobId: String): R16DownloadJobSnapshot? = snapshot

        override suspend fun pendingCleanupLocations(): List<String> = emptyList()

        override suspend fun request(command: R16DownloadRequest): R16OfflineMutationResult {
            createdJob = command
            val snap =
                R16DownloadJobSnapshot(
                    jobId = command.jobId,
                    recordingId = command.recordingId,
                    requestedSourceReferenceId = command.requestedSourceReferenceId,
                    requestedMediaVariant = command.requestedMediaVariant,
                    destinationIdentity = command.destinationIdentity,
                    displayFallbackJson = command.displayFallbackJson,
                    pendingLocation = command.pendingLocation,
                    publishedAssetId = null,
                    state = R16DownloadJobState.REQUESTED,
                    bytesTransferred = 0,
                    expectedBytes = command.expectedBytes,
                    failureKind = null,
                    retryAfter = null,
                    updatedAt = command.createdAt,
                )
            return R16OfflineMutationResult.Applied(snap, changed = true)
        }

        override suspend fun updateProgress(
            command: R16DownloadProgress
        ): R16OfflineMutationResult {
            progress = command
            val current =
                snapshot
                    ?: R16DownloadJobSnapshot(
                        jobId = command.jobId,
                        recordingId = RecordingId(UUID.randomUUID().toString()),
                        requestedSourceReferenceId = null,
                        requestedMediaVariant = "DEFAULT",
                        destinationIdentity = "PRIMARY",
                        displayFallbackJson = "{}",
                        pendingLocation = null,
                        publishedAssetId = null,
                        state = command.state,
                        bytesTransferred = command.bytesTransferred,
                        expectedBytes = command.expectedBytes,
                        failureKind = command.failureKind,
                        retryAfter = command.retryAfter,
                        updatedAt = command.updatedAt,
                    )
            val updated =
                current.copy(
                    state = command.state,
                    bytesTransferred = command.bytesTransferred,
                    expectedBytes = command.expectedBytes,
                )
            snapshot = updated
            return R16OfflineMutationResult.Applied(updated, changed = true)
        }

        override suspend fun beginPublishing(
            command: R16DownloadPublishingStart
        ): R16OfflineMutationResult =
            R16OfflineMutationResult.Applied(checkNotNull(snapshot), changed = true)

        override suspend fun resetForRetry(
            command: R16DownloadRetryReset
        ): R16OfflineMutationResult {
            reset = command
            val updated =
                snapshot?.copy(state = R16DownloadJobState.QUEUED) ?: checkNotNull(snapshot)
            snapshot = updated
            return R16OfflineMutationResult.Applied(updated, changed = true)
        }

        override suspend fun retainPendingCleanupEvidence(
            command: R16PendingCleanupEvidence
        ): R16OfflineMutationResult =
            R16OfflineMutationResult.Applied(checkNotNull(snapshot), changed = true)

        override suspend fun clearPendingCleanupEvidence(
            command: R16PendingCleanupEvidence
        ): R16OfflineMutationResult =
            R16OfflineMutationResult.Applied(checkNotNull(snapshot), changed = true)

        override suspend fun publishVerified(
            command: R16VerifiedDownloadPublication
        ): R16OfflineMutationResult =
            R16OfflineMutationResult.Applied(checkNotNull(snapshot), changed = true)

        override suspend fun markMissing(
            command: R16ManagedDownloadRemoval
        ): R16OfflineMutationResult =
            R16OfflineMutationResult.Applied(checkNotNull(snapshot), changed = true)

        override suspend fun removeManagedDownload(
            command: R16ManagedDownloadRemoval,
            storage: R16ManagedDownloadStorage,
        ): R16OfflineMutationResult {
            removal = command
            storage.delete(
                R16ManagedDownloadTarget(
                    assetId = command.assetId,
                    locationType = "SAF",
                    location = app.shippy.core.asset.AssetLocation("content://dummy"),
                    documentId = null,
                    destinationIdentity = command.destinationIdentity,
                )
            )
            return R16OfflineMutationResult.Applied(checkNotNull(snapshot), changed = true)
        }
    }
}
