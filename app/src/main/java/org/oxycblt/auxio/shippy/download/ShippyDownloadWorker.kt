/*
 * Copyright (c) 2026 Shippy contributors
 * ShippyDownloadWorker.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
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
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobRepository
import org.oxycblt.auxio.shippy.persistence.download.PersistedDownload
import org.oxycblt.auxio.shippy.persistence.library.LibraryRelationshipRepository
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderSettings
import org.oxycblt.auxio.shippy.provider.StreamConstraints

@HiltWorker
class ShippyDownloadWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val jobs: DownloadJobRepository,
    private val storage: SafDownloadStorage,
    private val crewTemporaryStaging: CrewTemporaryDownloadStaging,
    private val transferEngine: DownloadTransferEngine,
    private val queueItemFactory: QueueItemFactory,
    private val resolutionCoordinator: PlaybackResolutionCoordinator,
    private val providerRegistry: ProviderRegistry,
    private val providerSettings: ProviderSettings,
    private val relationships: LibraryRelationshipRepository,
    private val publicationGate: DownloadPublicationGate,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val jobId =
            inputData.getString(KEY_JOB_ID)?.takeIf(String::isNotBlank)?.let(::DownloadJobId)
                ?: return Result.failure()
        return try {
            runDownload(jobId)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val stored = jobs.get(jobId)
                if (
                    stored?.job?.state == DownloadState.PAUSED ||
                        stored?.job?.state == DownloadState.CANCELLED
                ) {
                    cleanupPending(stored)
                    if (stored.job.state == DownloadState.CANCELLED) {
                        crewTemporaryStaging.cleanup(jobId)
                    }
                }
            }
            throw cancelled
        }
    }

    private suspend fun runDownload(jobId: DownloadJobId): Result {
        var stored = jobs.get(jobId) ?: return Result.failure()

        if (stored.job.state.isTerminal()) {
            if (stored.job.state == DownloadState.AVAILABLE && stored.pendingDocument != null) {
                jobs.setPendingDocument(jobId, null, now())
            }
            if (
                stored.job.state == DownloadState.FAILED_FINAL ||
                    stored.job.state == DownloadState.CANCELLED
            ) {
                crewTemporaryStaging.cleanup(jobId)
            }
            return Result.success()
        }
        if (stored.job.state == DownloadState.PAUSED) return Result.success()
        if (stored.job.state == DownloadState.FAILED_RETRYABLE) {
            if (jobs.apply(jobId, DownloadEvent.Retry, now()) !is DownloadTransition.Applied) {
                return Result.failure()
            }
            stored = jobs.get(jobId) ?: return Result.failure()
        }
        if (stored.job.state == DownloadState.TRANSFERRING) {
            cleanupPending(stored)
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
            jobs.apply(jobId, DownloadEvent.Resolve, now())
            stored = jobs.get(jobId) ?: return Result.failure()
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
            jobs.apply(
                jobId,
                DownloadEvent.Enqueued(resolved.playback.contentLength),
                now(),
            )
            stored = jobs.get(jobId) ?: return Result.failure()
        }

        val pending =
            stored.pendingDocument
                ?: when (
                    val created =
                        storage.createPendingDocument(
                            jobId,
                            stored.track.title,
                            resolved.playback.mimeType,
                        )
                ) {
                    is StorageResult.Success -> {
                        jobs.setPendingDocument(jobId, created.value, now())
                        created.value
                    }
                    is StorageResult.Failure -> {
                        fail(
                            jobId,
                            DownloadFailure("storage_${created.reason.name.lowercase()}"),
                            retryable = created.reason.isRetryable(),
                        )
                        return if (created.reason.isRetryable()) Result.retry()
                        else Result.failure()
                    }
                }

        if (jobs.apply(jobId, DownloadEvent.TransferStarted, now()) !is DownloadTransition.Applied) {
            cleanupPending(jobs.get(jobId) ?: stored)
            return Result.failure()
        }
        setForeground(createForegroundInfo(stored.track.title, 0, null))

        val output =
            when (val opened = storage.openOutput(pending)) {
                is StorageResult.Success -> opened.value
                is StorageResult.Failure -> {
                    cleanupPending(jobs.get(jobId) ?: stored)
                    fail(
                        jobId,
                        DownloadFailure("storage_${opened.reason.name.lowercase()}"),
                        opened.reason.isRetryable(),
                    )
                    return if (opened.reason.isRetryable()) Result.retry() else Result.failure()
                }
            }
        val transferResult =
            transfer(output, resolved) { progress ->
                jobs.apply(
                    jobId,
                    DownloadEvent.Progress(
                        progress.bytesTransferred,
                        progress.expectedBytes,
                    ),
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
                cleanupPending(jobs.get(jobId) ?: stored)
                fail(
                    jobId,
                    DownloadFailure(transferResult.reason.code()),
                    transferResult.reason.retryable,
                )
                return if (transferResult.reason.retryable) Result.retry() else Result.failure()
            }
            is DownloadTransferResult.Success -> {
                jobs.apply(jobId, DownloadEvent.TransferCompleted, now())
            }
        }
        return finishPending(jobs.get(jobId) ?: return Result.failure())
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
            providerRegistry
                .supporting(ProviderCapability.DOWNLOAD)
                .map { it.descriptor.id }
        val policy =
            ResolutionPolicy(
                providerPriority = providerSettings.selection(providerIds).priority,
                pushPullEnabled = false,
            )
        return resolutionCoordinator.prepare(
            queueItemFactory.fromTrack(
                stored.track.copy(candidates = listOf(requestedCandidate)),
                contextId = "download",
            ),
            policy,
            StreamConstraints(forDownload = true),
        )
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
        val pending = stored.pendingDocument ?: return Result.failure()
        val expected = stored.job.expectedBytes
        val verification = storage.verify(pending, expected, now())
        if (verification is StorageResult.Failure) {
            cleanupPending(stored)
            fail(
                stored.job.id,
                DownloadFailure("storage_${verification.reason.name.lowercase()}"),
                verification.reason.isRetryable(),
            )
            return if (verification.reason.isRetryable()) Result.retry() else Result.failure()
        }
        val artifact = (verification as StorageResult.Success).value
        return publicationGate.run {
            var current = jobs.get(stored.job.id) ?: return@run Result.failure()
            if (current.job.state == DownloadState.VERIFYING) {
                if (
                    jobs.apply(current.job.id, DownloadEvent.Verified, now()) !is
                        DownloadTransition.Applied
                ) {
                    return@run abortFinalization(jobs.get(stored.job.id) ?: current)
                }
                current = jobs.get(stored.job.id) ?: return@run Result.failure()
            }
            if (current.job.state == DownloadState.FINALIZING) {
                if (
                    jobs.apply(current.job.id, DownloadEvent.Finalized(artifact), now()) !is
                        DownloadTransition.Applied
                ) {
                    return@run abortFinalization(jobs.get(stored.job.id) ?: current)
                }
            }
            current = jobs.get(stored.job.id) ?: return@run Result.failure()
            if (current.job.state != DownloadState.AVAILABLE || current.job.artifact == null) {
                return@run abortFinalization(current)
            }
            jobs.setPendingDocument(current.job.id, null, now())
            relationships.setDownloaded(
                current.track.id,
                jobs.hasAvailableForTrack(current.track.id),
            )
            crewTemporaryStaging.cleanup(current.job.id)
            Result.success(
                workDataOf(
                    KEY_ARTIFACT_URI to current.job.artifact.contentUri,
                    KEY_BYTES_TRANSFERRED to current.job.artifact.contentLength,
                )
            )
        }
    }

    private suspend fun abortFinalization(stored: PersistedDownload): Result {
        if (stored.job.state != DownloadState.AVAILABLE) {
            cleanupPending(stored)
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
        jobs.setPendingDocument(stored.job.id, null, now())
    }

    private suspend fun fail(
        jobId: DownloadJobId,
        failure: DownloadFailure,
        retryable: Boolean,
    ) {
        val transition = jobs.apply(jobId, DownloadEvent.Fail(failure, retryable), now())
        if (!retryable && transition is DownloadTransition.Applied) {
            crewTemporaryStaging.cleanup(jobId)
        }
    }

    private fun createForegroundInfo(
        title: String,
        bytes: Long,
        expected: Long?,
    ): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
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
        DownloadTransferFailure.SourceReadFailed -> "source_read_failed"
        DownloadTransferFailure.DestinationWriteFailed -> "destination_write_failed"
        is DownloadTransferFailure.PrematureEof -> "premature_eof"
        is DownloadTransferFailure.LengthExceeded -> "length_exceeded"
    }
