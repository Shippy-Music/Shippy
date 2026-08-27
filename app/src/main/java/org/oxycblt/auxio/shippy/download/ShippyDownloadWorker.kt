/*
 * Copyright (c) 2026 Auxio Project
 * ShippyDownloadWorker.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.shippy.domain.PlaybackPreparation
import org.oxycblt.auxio.shippy.domain.PlaybackResolutionCoordinator
import org.oxycblt.auxio.shippy.domain.QueueItemFactory
import org.oxycblt.auxio.shippy.domain.ResolutionPolicy
import org.oxycblt.auxio.shippy.domain.ResolvedQueueItem
import org.oxycblt.auxio.shippy.media.MediaObjectKey
import org.oxycblt.auxio.shippy.media.cache.PlaybackCacheManager
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobRepository
import org.oxycblt.auxio.shippy.persistence.download.PersistedDownload
import org.oxycblt.auxio.shippy.persistence.library.LibraryRelationshipRepository
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderSettings
import org.oxycblt.auxio.shippy.provider.StreamConstraints
import org.oxycblt.auxio.shippy.r16.migration.R16MigrationProcessGate

@HiltWorker
class ShippyDownloadWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val jobs: Lazy<DownloadJobRepository>,
    private val storage: SafDownloadStorage,
    private val transferStaging: DownloadTransferStaging,
    private val crewTemporaryStaging: CrewTemporaryDownloadStaging,
    private val transferEngine: DownloadTransferEngine,
    private val queueItemFactory: QueueItemFactory,
    private val resolutionCoordinator: Lazy<PlaybackResolutionCoordinator>,
    private val providerRegistry: ProviderRegistry,
    private val providerSettings: ProviderSettings,
    private val relationships: Lazy<LibraryRelationshipRepository>,
    private val publicationGate: DownloadPublicationGate,
    private val playbackCache: PlaybackCacheManager,
    private val migrationGate: R16MigrationProcessGate,
) : CoroutineWorker(appContext, workerParams) {
    private val jobRepository: DownloadJobRepository
        get() = jobs.get()

    private val relationshipRepository: LibraryRelationshipRepository
        get() = relationships.get()

    override suspend fun doWork(): Result {
        val legacyLease = migrationGate.tryAcquireLegacyAccess() ?: return Result.failure()
        val jobId =
            inputData.getString(KEY_JOB_ID)?.takeIf(String::isNotBlank)?.let(::DownloadJobId)
                ?: run {
                    legacyLease.close()
                    return Result.failure()
                }
        return try {
            try {
                runDownload(jobId)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    val stored = jobRepository.get(jobId)
                    if (
                        stored?.job?.state == DownloadState.PAUSED ||
                            stored?.job?.state == DownloadState.CANCELLED
                    ) {
                        cleanupPending(stored)
                        transferStaging.cleanup(jobId)
                        if (stored.job.state == DownloadState.CANCELLED) {
                            crewTemporaryStaging.cleanup(jobId)
                        }
                    }
                }
                throw cancelled
            }
        } finally {
            legacyLease.close()
        }
    }

    private suspend fun runDownload(jobId: DownloadJobId): Result {
        var stored = jobRepository.get(jobId) ?: return Result.failure()

        if (stored.job.state.isTerminal()) {
            if (stored.job.state == DownloadState.AVAILABLE && stored.pendingDocument != null) {
                jobRepository.setPendingDocument(jobId, null, now())
            }
            if (
                stored.job.state == DownloadState.FAILED_FINAL ||
                    stored.job.state == DownloadState.CANCELLED
            ) {
                transferStaging.cleanup(jobId)
                crewTemporaryStaging.cleanup(jobId)
            }
            return Result.success()
        }
        if (stored.job.state == DownloadState.PAUSED) return Result.success()
        if (stored.job.state == DownloadState.FAILED_RETRYABLE) {
            if (
                jobRepository.apply(jobId, DownloadEvent.Retry, now())
                    !is DownloadTransition.Applied
            ) {
                return Result.failure()
            }
            stored = jobRepository.get(jobId) ?: return Result.failure()
        }
        if (stored.job.state == DownloadState.TRANSFERRING) {
            cleanupPending(stored)
            transferStaging.cleanup(jobId)
            fail(
                jobId,
                DownloadFailure("interrupted", "The previous transfer was interrupted"),
                retryable = true,
            )
            return Result.retry()
        }
        if (
            stored.job.state == DownloadState.VERIFYING ||
                stored.job.state == DownloadState.FINALIZING
        ) {
            return finishPending(stored)
        }
        if (stored.job.state == DownloadState.REQUESTED) {
            jobRepository.apply(jobId, DownloadEvent.Resolve, now())
            stored = jobRepository.get(jobId) ?: return Result.failure()
        }

        val requestedCandidate =
            stored.track.candidates.firstOrNull { it.id == stored.job.candidateId }
                ?: return failMissingRequestedCandidate(jobId)
        val cachedObjectKey = MediaObjectKey.from(requestedCandidate)

        if (
            stored.job.state == DownloadState.RESOLVING &&
                playbackCache.hasComplete(cachedObjectKey, requestedCandidate.media?.contentLength)
        ) {
            return completeFromPlaybackCache(stored, cachedObjectKey)
        }

        val resolved =
            when (val preparation = resolve(stored)) {
                is PlaybackPreparation.Ready -> preparation.value
                is PlaybackPreparation.Failed -> {
                    fail(
                        jobId,
                        DownloadFailure(preparation.kind.name.lowercase(), preparation.message),
                        preparation.retryable,
                    )
                    return if (preparation.retryable) Result.retry() else Result.failure()
                }
            }
        if (stored.job.state == DownloadState.RESOLVING) {
            jobRepository.apply(
                jobId,
                DownloadEvent.Enqueued(resolved.playback.contentLength),
                now(),
            )
            stored = jobRepository.get(jobId) ?: return Result.failure()
        }

        if (
            jobRepository.apply(jobId, DownloadEvent.TransferStarted, now())
                !is DownloadTransition.Applied
        ) {
            cleanupPending(jobRepository.get(jobId) ?: stored)
            return Result.failure()
        }
        setForeground(createForegroundInfo(stored.track.title, 0, null))

        val output =
            try {
                transferStaging.openOutput(jobId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                fail(jobId, DownloadFailure("private_staging_unavailable"), retryable = true)
                return Result.retry()
            }
        val transferResult =
            transfer(output, resolved) { progress ->
                jobRepository.apply(
                    jobId,
                    DownloadEvent.Progress(progress.bytesTransferred, progress.expectedBytes),
                    now(),
                )
                setProgress(
                    workDataOf(
                        KEY_BYTES_TRANSFERRED to progress.bytesTransferred,
                        KEY_EXPECTED_BYTES to (progress.expectedBytes ?: -1L),
                    )
                )
                setForeground(
                    createForegroundInfo(
                        stored.track.title,
                        progress.bytesTransferred,
                        progress.expectedBytes,
                    )
                )
            }
        when (transferResult) {
            is DownloadTransferResult.Failure -> {
                cleanupPending(jobRepository.get(jobId) ?: stored)
                transferStaging.cleanup(jobId)
                fail(
                    jobId,
                    DownloadFailure(transferResult.reason.code()),
                    transferResult.reason.retryable,
                )
                return if (transferResult.reason.retryable) Result.retry() else Result.failure()
            }
            is DownloadTransferResult.Success -> {
                jobRepository.apply(jobId, DownloadEvent.TransferCompleted, now())
            }
        }
        return finishPending(jobRepository.get(jobId) ?: return Result.failure())
    }

    private suspend fun resolve(stored: PersistedDownload): PlaybackPreparation {
        val requestedCandidate =
            stored.track.candidates.firstOrNull { it.id == stored.job.candidateId }
                ?: return PlaybackPreparation.Failed(
                    trackId = stored.track.id,
                    kind = ProviderFailureKind.UNAVAILABLE,
                    message = "The requested download source is unavailable",
                    retryable = false,
                )
        val providerIds =
            providerRegistry.supporting(ProviderCapability.DOWNLOAD).map { it.descriptor.id }
        val policy =
            ResolutionPolicy(
                providerPriority = providerSettings.selection(providerIds).priority,
                pushPullEnabled = false,
            )
        return resolutionCoordinator
            .get()
            .prepare(
                queueItemFactory.fromTrack(
                    stored.track.copy(candidates = listOf(requestedCandidate)),
                    contextId = "download",
                ),
                policy,
                StreamConstraints(
                    preferredBitrateBps = providerSettings.downloadBitrateBps(),
                    forDownload = true,
                ),
            )
    }

    private suspend fun completeFromPlaybackCache(
        stored: PersistedDownload,
        key: MediaObjectKey,
    ): Result {
        val expected =
            stored.track.candidates.first { it.id == stored.job.candidateId }.media?.contentLength
        if (
            jobRepository.apply(stored.job.id, DownloadEvent.Enqueued(expected), now())
                !is DownloadTransition.Applied
        ) {
            return Result.failure()
        }
        if (
            jobRepository.apply(stored.job.id, DownloadEvent.TransferStarted, now())
                !is DownloadTransition.Applied
        ) {
            return Result.failure()
        }
        setForeground(createForegroundInfo(stored.track.title, 0, expected))
        val output =
            try {
                transferStaging.openOutput(stored.job.id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                fail(
                    stored.job.id,
                    DownloadFailure("private_staging_unavailable"),
                    retryable = true,
                )
                return Result.retry()
            }
        val copied = output.use { playbackCache.copyCompleteTo(key, expected, it) }
        if (copied == null) {
            transferStaging.cleanup(stored.job.id)
            // A concurrent eviction or corruption is not terminal; re-enter the normal resolver.
            fail(stored.job.id, DownloadFailure("playback_cache_changed"), retryable = true)
            return Result.retry()
        }
        jobRepository.apply(stored.job.id, DownloadEvent.Progress(copied, copied), now())
        jobRepository.apply(stored.job.id, DownloadEvent.TransferCompleted, now())
        return finishPending(jobRepository.get(stored.job.id) ?: return Result.failure())
    }

    private suspend fun failMissingRequestedCandidate(jobId: DownloadJobId): Result {
        fail(
            jobId,
            DownloadFailure(
                "requested_source_unavailable",
                "The requested download source is unavailable",
            ),
            retryable = false,
        )
        return Result.failure()
    }

    private suspend fun transfer(
        output: OutputStream,
        resolved: ResolvedQueueItem,
        onProgress: suspend (DownloadTransferProgress) -> Unit,
    ): DownloadTransferResult =
        output.use {
            transferEngine.transfer(
                DownloadTransferRequest(
                    uri = resolved.playback.uri,
                    headers = resolved.playback.headers,
                    expectedLength = resolved.playback.contentLength,
                ),
                it,
                onProgress,
            )
        }

    private suspend fun finishPending(stored: PersistedDownload): Result {
        val expected = stored.job.expectedBytes
        val staged = transferStaging.verifiedFile(stored.job.id, expected)
        if (staged == null) return failStagingVerification(stored)

        val pending =
            when (val result = ensurePendingDocument(stored)) {
                is PendingDocumentResult.Ready -> result.document
                is PendingDocumentResult.Finished -> return result.workResult
            }
        val destination =
            when (val opened = storage.openOutput(pending)) {
                is StorageResult.Success -> opened.value
                is StorageResult.Failure -> {
                    return failStorage(stored.copy(pendingDocument = pending), opened.reason)
                }
            }
        if (!publishStagedFile(stored, pending, staged, destination)) return Result.retry()

        val artifact =
            when (val verification = storage.verify(pending, expected, now())) {
                is StorageResult.Success -> verification.value
                is StorageResult.Failure -> return failStorage(stored, verification.reason)
            }
        return finalizePublication(stored.job.id, artifact)
    }

    private suspend fun failStagingVerification(stored: PersistedDownload): Result {
        cleanupPending(stored)
        fail(
            stored.job.id,
            DownloadFailure("private_staging_verification_failed"),
            retryable = true,
        )
        return Result.retry()
    }

    private suspend fun ensurePendingDocument(stored: PersistedDownload): PendingDocumentResult {
        val existing = stored.pendingDocument
        if (existing != null) return PendingDocumentResult.Ready(existing)

        val created =
            storage.createPendingDocument(
                stored.job.id,
                stored.track.title,
                stored.track.candidates
                    .firstOrNull { it.id == stored.job.candidateId }
                    ?.media
                    ?.mimeType,
            )
        return when (created) {
            is StorageResult.Success -> {
                jobRepository.setPendingDocument(stored.job.id, created.value, now())
                PendingDocumentResult.Ready(created.value)
            }
            is StorageResult.Failure -> {
                PendingDocumentResult.Finished(
                    failStorage(stored, created.reason, cleanupPendingDocument = false)
                )
            }
        }
    }

    private suspend fun publishStagedFile(
        stored: PersistedDownload,
        pending: PendingDownloadDocument,
        staged: File,
        destination: OutputStream,
    ): Boolean =
        try {
            transferStaging.copyTo(staged, destination)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            cleanupPending(stored.copy(pendingDocument = pending))
            fail(stored.job.id, DownloadFailure("storage_publish_failed"), retryable = true)
            false
        }

    private suspend fun failStorage(
        stored: PersistedDownload,
        reason: DownloadStorageFailure,
        cleanupPendingDocument: Boolean = true,
    ): Result {
        if (cleanupPendingDocument) cleanupPending(stored)
        fail(
            stored.job.id,
            DownloadFailure("storage_${reason.name.lowercase()}"),
            retryable = reason.isRetryable(),
        )
        return if (reason.isRetryable()) Result.retry() else Result.failure()
    }

    private suspend fun finalizePublication(
        jobId: DownloadJobId,
        artifact: DownloadArtifact,
    ): Result = publicationGate.run { finalizePublicationLocked(jobId, artifact) }

    private suspend fun finalizePublicationLocked(
        jobId: DownloadJobId,
        artifact: DownloadArtifact,
    ): Result {
        var current = jobRepository.get(jobId) ?: return Result.failure()
        if (current.job.state == DownloadState.VERIFYING) {
            if (
                jobRepository.apply(current.job.id, DownloadEvent.Verified, now())
                    !is DownloadTransition.Applied
            ) {
                return abortFinalization(jobRepository.get(jobId) ?: current)
            }
            current = jobRepository.get(jobId) ?: return Result.failure()
        }
        if (current.job.state == DownloadState.FINALIZING) {
            if (
                jobRepository.apply(current.job.id, DownloadEvent.Finalized(artifact), now())
                    !is DownloadTransition.Applied
            ) {
                return abortFinalization(jobRepository.get(jobId) ?: current)
            }
        }
        current = jobRepository.get(jobId) ?: return Result.failure()
        if (current.job.state != DownloadState.AVAILABLE || current.job.artifact == null) {
            return abortFinalization(current)
        }
        jobRepository.setPendingDocument(current.job.id, null, now())
        relationshipRepository.setDownloaded(
            current.track.id,
            jobRepository.hasAvailableForTrack(current.track.id),
        )
        transferStaging.cleanup(current.job.id)
        crewTemporaryStaging.cleanup(current.job.id)
        return Result.success(
            workDataOf(
                KEY_ARTIFACT_URI to current.job.artifact.contentUri,
                KEY_BYTES_TRANSFERRED to current.job.artifact.contentLength,
            )
        )
    }

    private sealed interface PendingDocumentResult {
        data class Ready(val document: PendingDownloadDocument) : PendingDocumentResult

        data class Finished(val workResult: Result) : PendingDocumentResult
    }

    private suspend fun abortFinalization(stored: PersistedDownload): Result {
        if (stored.job.state != DownloadState.AVAILABLE) {
            cleanupPending(stored)
            transferStaging.cleanup(stored.job.id)
        }
        return if (
            stored.job.state == DownloadState.PAUSED ||
                stored.job.state == DownloadState.CANCELLED ||
                stored.job.state == DownloadState.REMOVED
        ) {
            Result.success()
        } else {
            Result.failure()
        }
    }

    private suspend fun cleanupPending(stored: PersistedDownload) {
        stored.pendingDocument?.let { storage.delete(it.contentUri) }
        jobRepository.setPendingDocument(stored.job.id, null, now())
    }

    private suspend fun fail(jobId: DownloadJobId, failure: DownloadFailure, retryable: Boolean) {
        val transition = jobRepository.apply(jobId, DownloadEvent.Fail(failure, retryable), now())
        if (!retryable && transition is DownloadTransition.Applied) {
            crewTemporaryStaging.cleanup(jobId)
        }
    }

    private fun createForegroundInfo(title: String, bytes: Long, expected: Long?): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext
                .getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        DOWNLOAD_CHANNEL_ID,
                        applicationContext.getString(R.string.download_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
        }
        val progressMax = if (expected != null && expected > 0L) 1000 else 0
        val progress =
            if (expected != null && expected > 0L) {
                ((bytes.toDouble() / expected.toDouble()) * progressMax)
                    .toInt()
                    .coerceIn(0, progressMax)
            } else {
                0
            }
        val notification =
            NotificationCompat.Builder(applicationContext, DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_save_24)
                .setContentTitle(
                    applicationContext.getString(R.string.download_notification_title, title)
                )
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(progressMax, progress, expected == null)
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId(),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId(), notification)
        }
    }

    private fun notificationId(): Int = id.hashCode() and Int.MAX_VALUE

    private fun now() = System.currentTimeMillis()

    companion object {
        const val KEY_JOB_ID = "download_job_id"
        const val KEY_BYTES_TRANSFERRED = "download_bytes_transferred"
        const val KEY_EXPECTED_BYTES = "download_expected_bytes"
        const val KEY_ARTIFACT_URI = "download_artifact_uri"
        private const val DOWNLOAD_CHANNEL_ID = "shippy_downloads"
    }
}

private fun DownloadState.isTerminal() =
    this == DownloadState.AVAILABLE ||
        this == DownloadState.FAILED_FINAL ||
        this == DownloadState.CANCELLED ||
        this == DownloadState.REMOVED

private fun DownloadStorageFailure.isRetryable() =
    this == DownloadStorageFailure.CREATE_FAILED ||
        this == DownloadStorageFailure.OPEN_FAILED ||
        this == DownloadStorageFailure.VERIFY_FAILED

private fun DownloadTransferFailure.code(): String =
    when (this) {
        DownloadTransferFailure.InvalidRequest -> "invalid_request"
        is DownloadTransferFailure.UnsupportedScheme -> "unsupported_scheme"
        is DownloadTransferFailure.HttpStatus -> "http_$statusCode"
        DownloadTransferFailure.Timeout -> "timeout"
        DownloadTransferFailure.NetworkUnavailable -> "network_unavailable"
        DownloadTransferFailure.SourceUnavailable -> "source_unavailable"
        DownloadTransferFailure.TlsRejected -> "tls_rejected"
        is DownloadTransferFailure.RedirectRejected -> "redirect_rejected"
        DownloadTransferFailure.SourceReadFailed -> "source_read_failed"
        DownloadTransferFailure.DestinationWriteFailed -> "destination_write_failed"
        is DownloadTransferFailure.PrematureEof -> "premature_eof"
        is DownloadTransferFailure.LengthExceeded -> "length_exceeded"
    }
