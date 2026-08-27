/*
 * Copyright (c) 2026 Auxio Project
 * R16DownloadActionCoordinator.kt is part of Auxio.
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

import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.data.offline.R16DownloadJobState
import app.shippy.data.offline.R16DownloadProgress
import app.shippy.data.offline.R16DownloadRequest
import app.shippy.data.offline.R16DownloadRetryReset
import app.shippy.data.offline.R16ManagedDownloadRemoval
import app.shippy.data.offline.R16ManagedDownloadStorage
import app.shippy.data.offline.R16OfflineMutationResult
import app.shippy.data.offline.R16OfflineRepository
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Coordinates user-initiated download actions across the durable offline repository and WorkManager
 * scheduler.
 */
@Singleton
public class R16DownloadActionCoordinator {
    private val offlineRepository: R16OfflineRepository
    private val workScheduler: R16DownloadScheduler
    private val storage: R16ManagedDownloadStorage

    @Inject
    public constructor(
        offlineRepository: R16OfflineRepository,
        workScheduler: R16DownloadWorkScheduler,
        storage: SafR16ManagedDownloadStorage,
    ) {
        this.offlineRepository = offlineRepository
        this.workScheduler = workScheduler
        this.storage = storage
    }

    internal constructor(
        offlineRepository: R16OfflineRepository,
        workScheduler: R16DownloadScheduler,
        storage: R16ManagedDownloadStorage,
    ) {
        this.offlineRepository = offlineRepository
        this.workScheduler = workScheduler
        this.storage = storage
    }

    public suspend fun requestDownload(
        recordingId: RecordingId,
        sourceReferenceId: SourceReferenceId,
        mediaVariant: String = "DEFAULT",
        destinationIdentity: String = "PRIMARY_DOWNLOADS",
        expectedBytes: Long? = null,
        displayFallbackJson: String = "{}",
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val jobId = "job-${UUID.randomUUID()}"
                val request =
                    R16DownloadRequest(
                        jobId = jobId,
                        recordingId = recordingId,
                        requestedSourceReferenceId = sourceReferenceId,
                        requestedMediaVariant = mediaVariant,
                        destinationIdentity = destinationIdentity,
                        expectedBytes = expectedBytes,
                        pendingLocation = null,
                        displayFallbackJson = displayFallbackJson,
                        createdAt = Instant.now(),
                    )
                val result = offlineRepository.request(request)
                check(result is R16OfflineMutationResult.Applied) {
                    "Failed to create download request: $result"
                }
                val scheduled = workScheduler.schedule(jobId)
                if (!scheduled) {
                    offlineRepository.updateProgress(
                        R16DownloadProgress(
                            jobId = jobId,
                            state = R16DownloadJobState.FAILED_RETRYABLE,
                            bytesTransferred = 0,
                            expectedBytes = expectedBytes,
                            failureKind = "SCHEDULER_FAILED",
                            retryAfter = null,
                            updatedAt = Instant.now(),
                        )
                    )
                }
                jobId
            }
        }

    public suspend fun pauseDownload(jobId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val snapshot =
                    offlineRepository.load(jobId) ?: error("Download job not found: $jobId")
                workScheduler.cancel(jobId)
                val progress =
                    R16DownloadProgress(
                        jobId = jobId,
                        state = R16DownloadJobState.PAUSED,
                        bytesTransferred = snapshot.bytesTransferred,
                        expectedBytes = snapshot.expectedBytes,
                        failureKind = null,
                        retryAfter = null,
                        updatedAt = Instant.now(),
                    )
                val result = offlineRepository.updateProgress(progress)
                check(result is R16OfflineMutationResult.Applied) {
                    "Failed to pause download: $result"
                }
                Unit
            }
        }

    public suspend fun resumeDownload(jobId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val snapshot =
                    offlineRepository.load(jobId) ?: error("Download job not found: $jobId")
                require(
                    snapshot.state == R16DownloadJobState.PAUSED ||
                        snapshot.state == R16DownloadJobState.FAILED_RETRYABLE
                ) {
                    "Cannot resume download in state ${snapshot.state}"
                }
                if (snapshot.state == R16DownloadJobState.PAUSED) {
                    val progress =
                        R16DownloadProgress(
                            jobId = jobId,
                            state = R16DownloadJobState.QUEUED,
                            bytesTransferred = snapshot.bytesTransferred,
                            expectedBytes = snapshot.expectedBytes,
                            failureKind = null,
                            retryAfter = null,
                            updatedAt = Instant.now(),
                        )
                    val result = offlineRepository.updateProgress(progress)
                    check(result is R16OfflineMutationResult.Applied) {
                        "Failed to resume download from paused state: $result"
                    }
                } else if (snapshot.state == R16DownloadJobState.FAILED_RETRYABLE) {
                    val reset =
                        R16DownloadRetryReset(
                            jobId = jobId,
                            recordingId = snapshot.recordingId,
                            requestedSourceReferenceId = snapshot.requestedSourceReferenceId,
                            requestedMediaVariant = snapshot.requestedMediaVariant ?: "DEFAULT",
                            destinationIdentity =
                                snapshot.destinationIdentity ?: "PRIMARY_DOWNLOADS",
                            updatedAt = Instant.now(),
                        )
                    val result = offlineRepository.resetForRetry(reset)
                    check(result is R16OfflineMutationResult.Applied) {
                        "Failed to resume download: $result"
                    }
                }
                val scheduled = workScheduler.schedule(jobId)
                if (!scheduled) {
                    offlineRepository.updateProgress(
                        R16DownloadProgress(
                            jobId = jobId,
                            state = R16DownloadJobState.FAILED_RETRYABLE,
                            bytesTransferred = snapshot.bytesTransferred,
                            expectedBytes = snapshot.expectedBytes,
                            failureKind = "SCHEDULER_FAILED",
                            retryAfter = null,
                            updatedAt = Instant.now(),
                        )
                    )
                }
                Unit
            }
        }

    public suspend fun retryDownload(jobId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val snapshot =
                    offlineRepository.load(jobId) ?: error("Download job not found: $jobId")
                require(snapshot.state == R16DownloadJobState.FAILED_RETRYABLE) {
                    "Cannot retry download in state ${snapshot.state}"
                }
                val reset =
                    R16DownloadRetryReset(
                        jobId = jobId,
                        recordingId = snapshot.recordingId,
                        requestedSourceReferenceId = snapshot.requestedSourceReferenceId,
                        requestedMediaVariant = snapshot.requestedMediaVariant ?: "DEFAULT",
                        destinationIdentity = snapshot.destinationIdentity ?: "PRIMARY_DOWNLOADS",
                        updatedAt = Instant.now(),
                    )
                val result = offlineRepository.resetForRetry(reset)
                check(result is R16OfflineMutationResult.Applied) {
                    "Failed to retry download: $result"
                }
                val scheduled = workScheduler.schedule(jobId)
                if (!scheduled) {
                    offlineRepository.updateProgress(
                        R16DownloadProgress(
                            jobId = jobId,
                            state = R16DownloadJobState.FAILED_RETRYABLE,
                            bytesTransferred = snapshot.bytesTransferred,
                            expectedBytes = snapshot.expectedBytes,
                            failureKind = "SCHEDULER_FAILED",
                            retryAfter = null,
                            updatedAt = Instant.now(),
                        )
                    )
                }
                Unit
            }
        }

    public suspend fun cancelDownload(jobId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val snapshot =
                    offlineRepository.load(jobId) ?: error("Download job not found: $jobId")
                workScheduler.cancel(jobId)
                val progress =
                    R16DownloadProgress(
                        jobId = jobId,
                        state = R16DownloadJobState.CANCELLED,
                        bytesTransferred = snapshot.bytesTransferred,
                        expectedBytes = snapshot.expectedBytes,
                        failureKind = null,
                        retryAfter = null,
                        updatedAt = Instant.now(),
                    )
                val result = offlineRepository.updateProgress(progress)
                check(result is R16OfflineMutationResult.Applied) {
                    "Failed to cancel download: $result"
                }
                Unit
            }
        }

    public suspend fun removeDownload(
        jobId: String,
        customStorage: R16ManagedDownloadStorage? = null,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val snapshot =
                    offlineRepository.load(jobId) ?: error("Download job not found: $jobId")
                workScheduler.cancel(jobId)
                val assetId =
                    snapshot.publishedAssetId ?: error("No published asset found for job $jobId")
                val removal =
                    R16ManagedDownloadRemoval(
                        jobId = jobId,
                        recordingId = snapshot.recordingId,
                        assetId = assetId,
                        updatedAt = Instant.now(),
                        destinationIdentity = snapshot.destinationIdentity,
                    )
                val result =
                    offlineRepository.removeManagedDownload(removal, customStorage ?: storage)
                check(result is R16OfflineMutationResult.Applied) {
                    "Failed to remove download: $result"
                }
                Unit
            }
        }
}
