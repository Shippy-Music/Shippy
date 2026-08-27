/*
 * Copyright (c) 2026 Auxio Project
 * R16DownloadExecutionCoordinator.kt is part of Auxio.
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

import android.net.Uri
import android.provider.DocumentsContract
import app.shippy.core.asset.AssetLocation
import app.shippy.core.asset.AssetState
import app.shippy.core.asset.AudioTechnicalMetadata
import app.shippy.core.asset.MediaAsset
import app.shippy.core.asset.MediaAssetKind
import app.shippy.core.identity.MediaAssetId
import app.shippy.data.R16DataRuntime
import app.shippy.data.offline.R16DownloadJobSnapshot
import app.shippy.data.offline.R16DownloadJobState
import app.shippy.data.offline.R16DownloadProgress
import app.shippy.data.offline.R16DownloadPublishingStart
import app.shippy.data.offline.R16DownloadRetryReset
import app.shippy.data.offline.R16DownloadVerificationEvidence
import app.shippy.data.offline.R16OfflineMutationResult
import app.shippy.data.offline.R16OfflineRepository
import app.shippy.data.offline.R16PendingCleanupEvidence
import app.shippy.data.offline.R16VerifiedDownloadPublication
import app.shippy.data.source.R16SourceStateRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.oxycblt.auxio.shippy.download.DownloadArtifact
import org.oxycblt.auxio.shippy.download.DownloadJobId
import org.oxycblt.auxio.shippy.download.DownloadStorageFailure
import org.oxycblt.auxio.shippy.download.DownloadTransferEngine
import org.oxycblt.auxio.shippy.download.DownloadTransferFailure
import org.oxycblt.auxio.shippy.download.DownloadTransferProgress
import org.oxycblt.auxio.shippy.download.DownloadTransferRequest
import org.oxycblt.auxio.shippy.download.DownloadTransferResult
import org.oxycblt.auxio.shippy.download.DownloadTransferStaging
import org.oxycblt.auxio.shippy.download.SafManagedDownloadDeletionResult
import org.oxycblt.auxio.shippy.download.StorageResult
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ResolvedStream

/** WorkManager-facing outcome; durable failure truth is written before this is returned. */
sealed interface R16DownloadExecutionResult {
    data object Completed : R16DownloadExecutionResult

    data object Retry : R16DownloadExecutionResult

    /** Retryable only after a user-visible condition, such as a revoked SAF grant, is repaired. */
    data object AwaitingUser : R16DownloadExecutionResult

    data object Failed : R16DownloadExecutionResult
}

/** Physical/provider effects kept outside the durable download state machine. */
internal interface R16DownloadOperations {
    suspend fun resolve(
        sources: R16SourceStateRepository,
        request: R16DownloadSourceRequest,
    ): R16DownloadSourceResolution

    suspend fun transfer(
        jobId: DownloadJobId,
        stream: ResolvedStream,
        expectedBytes: Long?,
        onProgress: suspend (DownloadTransferProgress) -> Unit,
    ): DownloadTransferResult

    suspend fun hasVerifiedStage(jobId: DownloadJobId, expectedBytes: Long?): Boolean

    suspend fun cleanupStage(jobId: DownloadJobId)

    suspend fun createPending(
        destinationIdentity: String,
        jobId: DownloadJobId,
        title: String,
        mimeType: String?,
    ): StorageResult<R16PendingDownloadDocument>

    suspend fun recoverPending(
        destinationIdentity: String,
        jobId: DownloadJobId,
        contentUri: String,
    ): StorageResult<R16PendingDownloadDocument>

    suspend fun copyStage(
        destinationIdentity: String,
        pending: R16PendingDownloadDocument,
        expectedBytes: Long?,
    ): StorageResult<Unit>

    suspend fun verifyDestination(
        destinationIdentity: String,
        pending: R16PendingDownloadDocument,
        expectedBytes: Long?,
        verifiedAtEpochMs: Long,
    ): StorageResult<DownloadArtifact>

    suspend fun verifyMedia3(
        contentUri: String,
        expectedMimeType: String,
    ): R16Media3ReadabilityResult

    suspend fun cleanupPending(
        destinationIdentity: String,
        pending: R16PendingDownloadDocument,
    ): SafManagedDownloadDeletionResult

    suspend fun cleanupPersistedPending(
        destinationIdentity: String,
        jobId: DownloadJobId,
        contentUri: String,
    ): SafManagedDownloadDeletionResult

    suspend fun promoteFromCache(
        jobId: DownloadJobId,
        sourceId: app.shippy.core.identity.SourceReferenceId,
        mediaVariant: String,
        expectedBytes: Long?,
    ): Long?
}

@Singleton
internal class AndroidR16DownloadOperations
@Inject
constructor(
    private val providers: ProviderRegistry,
    private val transferEngine: DownloadTransferEngine,
    private val transferStaging: DownloadTransferStaging,
    private val destination: SafR16DownloadDestination,
    private val media3Verifier: R16Media3DownloadVerifier,
    private val cacheManager: org.oxycblt.auxio.shippy.media.cache.PlaybackCacheManager,
) : R16DownloadOperations {
    override suspend fun resolve(
        sources: R16SourceStateRepository,
        request: R16DownloadSourceRequest,
    ): R16DownloadSourceResolution = R16DownloadSourceResolver(sources, providers).resolve(request)

    override suspend fun transfer(
        jobId: DownloadJobId,
        stream: ResolvedStream,
        expectedBytes: Long?,
        onProgress: suspend (DownloadTransferProgress) -> Unit,
    ): DownloadTransferResult {
        val output = transferStaging.openOutput(jobId)
        return output.use {
            transferEngine.transfer(
                DownloadTransferRequest(
                    uri = stream.uri,
                    headers = stream.headers,
                    expectedLength = expectedBytes,
                ),
                it,
                onProgress,
            )
        }
    }

    override suspend fun hasVerifiedStage(jobId: DownloadJobId, expectedBytes: Long?): Boolean =
        transferStaging.verifiedFile(jobId, expectedBytes) != null

    override suspend fun cleanupStage(jobId: DownloadJobId) {
        transferStaging.cleanup(jobId)
    }

    override suspend fun createPending(
        destinationIdentity: String,
        jobId: DownloadJobId,
        title: String,
        mimeType: String?,
    ): StorageResult<R16PendingDownloadDocument> =
        destination.createPending(destinationIdentity, jobId, title, mimeType)

    override suspend fun recoverPending(
        destinationIdentity: String,
        jobId: DownloadJobId,
        contentUri: String,
    ): StorageResult<R16PendingDownloadDocument> =
        destination.recoverPending(destinationIdentity, jobId, contentUri)

    override suspend fun copyStage(
        destinationIdentity: String,
        pending: R16PendingDownloadDocument,
        expectedBytes: Long?,
    ): StorageResult<Unit> = destination.copyStage(destinationIdentity, pending, expectedBytes)

    override suspend fun verifyDestination(
        destinationIdentity: String,
        pending: R16PendingDownloadDocument,
        expectedBytes: Long?,
        verifiedAtEpochMs: Long,
    ): StorageResult<DownloadArtifact> =
        destination.verify(destinationIdentity, pending, expectedBytes, verifiedAtEpochMs)

    override suspend fun verifyMedia3(
        contentUri: String,
        expectedMimeType: String,
    ): R16Media3ReadabilityResult = media3Verifier.verify(contentUri, expectedMimeType)

    override suspend fun cleanupPending(
        destinationIdentity: String,
        pending: R16PendingDownloadDocument,
    ): SafManagedDownloadDeletionResult = destination.cleanup(destinationIdentity, pending)

    override suspend fun cleanupPersistedPending(
        destinationIdentity: String,
        jobId: DownloadJobId,
        contentUri: String,
    ): SafManagedDownloadDeletionResult =
        destination.cleanupPersistedPending(destinationIdentity, jobId, contentUri)

    override suspend fun promoteFromCache(
        jobId: DownloadJobId,
        sourceId: app.shippy.core.identity.SourceReferenceId,
        mediaVariant: String,
        expectedBytes: Long?,
    ): Long? {
        val candidateVariants = linkedSetOf(mediaVariant, "DEFAULT")
        for (variant in candidateVariants) {
            val key =
                org.oxycblt.auxio.shippy.media.MediaObjectKey.fromSourceReference(sourceId, variant)
            if (cacheManager.hasComplete(key, expectedBytes)) {
                val output = transferStaging.openOutput(jobId)
                val promoted =
                    try {
                        output.use { cacheManager.copyCompleteTo(key, expectedBytes, it) }
                    } catch (_: Exception) {
                        transferStaging.cleanup(jobId)
                        null
                    }
                if (promoted != null) return promoted
            }
        }
        return null
    }
}

/** Sole owner of durable R16 download transitions and publication ordering. */
@Singleton
class R16DownloadExecutionCoordinator
@Inject
internal constructor(private val operations: R16DownloadOperations) {
    internal var now: () -> Instant = { Instant.now() }

    suspend fun execute(runtime: R16DataRuntime, jobId: String): R16DownloadExecutionResult {
        return execute(runtime.offline, runtime.sources, jobId)
    }

    internal suspend fun execute(
        offline: R16OfflineRepository,
        sources: R16SourceStateRepository,
        jobId: String,
    ): R16DownloadExecutionResult {
        if (!isSafeR16DownloadJobId(jobId)) return R16DownloadExecutionResult.Failed
        val initial = offline.load(jobId) ?: return R16DownloadExecutionResult.Failed
        val id = DownloadJobId(jobId)
        return try {
            executeLoaded(sources, offline, id, initial)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                try {
                    cleanupAfterCancellation(offline, id)
                } catch (_: Exception) {
                    // Cleanup is best effort; WorkManager cancellation must retain its identity.
                }
            }
            throw cancelled
        } catch (_: R16DownloadStopped) {
            withContext(NonCancellable) { operations.cleanupStage(id) }
            R16DownloadExecutionResult.Completed
        } catch (_: Exception) {
            withContext(NonCancellable) { recordUnexpectedFailure(offline, id) }
        }
    }

    private suspend fun executeLoaded(
        sources: R16SourceStateRepository,
        offline: R16OfflineRepository,
        id: DownloadJobId,
        initial: R16DownloadJobSnapshot,
    ): R16DownloadExecutionResult {
        var job = initial
        if (job.pendingLocation != null && job.state != R16DownloadJobState.PUBLISHING) {
            val drained = cleanupRetainedPending(offline, id, job)
            if (drained != R16DownloadExecutionResult.Completed) return drained
            job = offline.load(id.value) ?: return R16DownloadExecutionResult.Failed
        }
        when (job.state) {
            R16DownloadJobState.AVAILABLE,
            R16DownloadJobState.REMOVED,
            R16DownloadJobState.CANCELLED,
            R16DownloadJobState.FAILED_FINAL -> return cleanupTerminalStage(id)
            // No range/resume behavior is invented here; PAUSED retains private stage by policy.
            R16DownloadJobState.PAUSED -> return R16DownloadExecutionResult.Completed
            else -> Unit
        }
        if (
            job.requestedSourceReferenceId == null ||
                job.requestedMediaVariant == null ||
                job.destinationIdentity == null
        ) {
            return fail(offline, job, "missing_exact_request_identity", retryable = false)
        }
        when (job.state) {
            R16DownloadJobState.FAILED_RETRYABLE -> {
                when (val reset = resetRetryable(offline, id, job)) {
                    is ResetResult.Ready -> job = reset.job
                    is ResetResult.Done -> return reset.result
                }
            }
            R16DownloadJobState.TRANSFERRING -> {
                operations.cleanupStage(id)
                return fail(offline, job, "interrupted_transfer", retryable = true)
            }
            R16DownloadJobState.VERIFYING -> Unit
            R16DownloadJobState.PUBLISHING -> return recoverAndPublish(offline, id, job)
            R16DownloadJobState.REQUESTED,
            R16DownloadJobState.RESOLVING,
            R16DownloadJobState.QUEUED -> Unit
            R16DownloadJobState.AVAILABLE,
            R16DownloadJobState.REMOVED,
            R16DownloadJobState.CANCELLED,
            R16DownloadJobState.FAILED_FINAL,
            R16DownloadJobState.PAUSED -> error("Terminal download state escaped early return")
        }

        val sourceId = job.requestedSourceReferenceId
        val mediaVariant = job.requestedMediaVariant
        val destinationIdentity = job.destinationIdentity
        check(sourceId != null && mediaVariant != null && destinationIdentity != null)

        if (job.state == R16DownloadJobState.REQUESTED) {
            job =
                advance(offline, job, R16DownloadJobState.RESOLVING)
                    ?: return concurrentOutcome(offline, id)
        }

        val resolved =
            when (
                val result =
                    operations.resolve(
                        sources,
                        R16DownloadSourceRequest(
                            recordingId = job.recordingId,
                            sourceReferenceId = sourceId,
                            requestedMediaVariant = mediaVariant,
                        ),
                    )
            ) {
                is R16DownloadSourceResolution.Ready -> result.value
                is R16DownloadSourceResolution.Failed -> {
                    if (job.state == R16DownloadJobState.VERIFYING) {
                        operations.cleanupStage(id)
                    }
                    return fail(
                        offline,
                        job,
                        "provider_${result.failure.kind.name.lowercase()}",
                        result.failure.retryable,
                    )
                }
                is R16DownloadSourceResolution.Rejected -> {
                    val awaitUser =
                        result.reason == R16DownloadSourceRejection.PROVIDER_DISABLED ||
                            result.reason == R16DownloadSourceRejection.PROVIDER_UNAVAILABLE
                    if (job.state == R16DownloadJobState.VERIFYING) {
                        operations.cleanupStage(id)
                    }
                    return fail(
                        offline,
                        job,
                        "source_${result.reason.name.lowercase()}",
                        retryable = awaitUser,
                        awaitUser = awaitUser,
                    )
                }
            }

        val expectedBytes = job.expectedBytes ?: resolved.stream.contentLength
        if (job.state == R16DownloadJobState.VERIFYING) {
            val mimeType =
                resolved.stream.mimeType?.trim()?.takeIf(String::isNotBlank)
                    ?: run {
                        operations.cleanupStage(id)
                        return fail(offline, job, "source_missing_mime_type", retryable = false)
                    }
            return publish(offline, id, job, pending = null, resolvedMimeType = mimeType)
        }
        if (job.state == R16DownloadJobState.RESOLVING) {
            job =
                advance(offline, job, R16DownloadJobState.QUEUED, expectedBytes = expectedBytes)
                    ?: return concurrentOutcome(offline, id)
        }
        job =
            advance(offline, job, R16DownloadJobState.TRANSFERRING, expectedBytes = expectedBytes)
                ?: return concurrentOutcome(offline, id)

        val resolvedVariant =
            listOfNotNull(resolved.stream.mimeType, resolved.stream.bitrateBps?.toString())
                .joinToString("-")
                .ifEmpty { "DEFAULT" }
        val promotedBytes =
            try {
                operations.promoteFromCache(id, sourceId, resolvedVariant, expectedBytes)
                    ?: if (resolvedVariant != mediaVariant) {
                        operations.promoteFromCache(id, sourceId, mediaVariant, expectedBytes)
                    } else {
                        null
                    }
            } catch (_: Exception) {
                null
            }

        val transfer =
            if (promotedBytes != null) {
                val updated =
                    offline.updateProgress(
                        R16DownloadProgress(
                            jobId = id.value,
                            state = R16DownloadJobState.TRANSFERRING,
                            bytesTransferred = promotedBytes,
                            expectedBytes = expectedBytes ?: promotedBytes,
                            failureKind = null,
                            retryAfter = null,
                            updatedAt = now(),
                        )
                    )
                if (updated is R16OfflineMutationResult.Applied) {
                    job = updated.job
                    DownloadTransferResult.Success(promotedBytes, expectedBytes)
                } else {
                    operations.cleanupStage(id)
                    return concurrentOutcome(offline, id)
                }
            } else {
                try {
                    operations.transfer(id, resolved.stream, expectedBytes) { progress ->
                        val updated =
                            offline.updateProgress(
                                R16DownloadProgress(
                                    jobId = id.value,
                                    state = R16DownloadJobState.TRANSFERRING,
                                    bytesTransferred = progress.bytesTransferred,
                                    expectedBytes = progress.expectedBytes ?: expectedBytes,
                                    failureKind = null,
                                    retryAfter = null,
                                    updatedAt = now(),
                                )
                            )
                        if (updated !is R16OfflineMutationResult.Applied) throw R16DownloadStopped()
                        job = updated.job
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (stopped: R16DownloadStopped) {
                    throw stopped
                } catch (_: Exception) {
                    operations.cleanupStage(id)
                    return fail(offline, job, "unexpected_execution_failure", retryable = true)
                }
            }
        when (transfer) {
            is DownloadTransferResult.Failure -> {
                operations.cleanupStage(id)
                return fail(
                    offline,
                    job,
                    "transfer_${transfer.reason.failureCode()}",
                    transfer.reason.retryable,
                )
            }
            is DownloadTransferResult.Success -> {
                val verifying =
                    advance(
                        offline,
                        job,
                        R16DownloadJobState.VERIFYING,
                        bytesTransferred = transfer.bytesTransferred,
                        expectedBytes = expectedBytes ?: transfer.bytesTransferred,
                    )
                if (verifying == null) {
                    operations.cleanupStage(id)
                    return concurrentOutcome(offline, id)
                }
                job = verifying
            }
        }
        val mimeType =
            resolved.stream.mimeType?.trim()?.takeIf(String::isNotBlank)
                ?: run {
                    operations.cleanupStage(id)
                    return fail(offline, job, "source_missing_mime_type", retryable = false)
                }
        return publish(offline, id, job, pending = null, resolvedMimeType = mimeType)
    }

    private suspend fun publish(
        offline: R16OfflineRepository,
        id: DownloadJobId,
        job: R16DownloadJobSnapshot,
        pending: R16PendingDownloadDocument?,
        resolvedMimeType: String? = null,
    ): R16DownloadExecutionResult {
        val destinationIdentity =
            job.destinationIdentity
                ?: return fail(offline, job, "missing_destination_identity", retryable = false)
        if (!operations.hasVerifiedStage(id, job.expectedBytes)) {
            if (pending != null) {
                return failPublishing(
                    offline,
                    id,
                    job,
                    pending,
                    "private_staging_verification_failed",
                    awaitUser = false,
                )
            }
            operations.cleanupStage(id)
            return fail(offline, job, "private_staging_verification_failed", retryable = true)
        }

        var publishing = job
        val exactPending =
            if (pending != null) {
                pending
            } else {
                val mimeType =
                    resolvedMimeType
                        ?: return fail(offline, job, "source_missing_mime_type", retryable = false)
                when (
                    val registration =
                        createAndBeginPublishing(
                            offline = offline,
                            id = id,
                            job = job,
                            destinationIdentity = destinationIdentity,
                            mimeType = mimeType,
                        )
                ) {
                    is PendingRegistration.Ready -> {
                        publishing = registration.job
                        registration.pending
                    }
                    is PendingRegistration.StorageFailure ->
                        return failStorage(offline, job, registration.reason)
                    is PendingRegistration.Finished -> return registration.result
                }
            }

        when (
            val copied = operations.copyStage(destinationIdentity, exactPending, job.expectedBytes)
        ) {
            is StorageResult.Success -> Unit
            is StorageResult.Failure -> {
                return failPublishingStorage(offline, id, publishing, exactPending, copied.reason)
            }
        }
        val artifact =
            when (
                val verified =
                    operations.verifyDestination(
                        destinationIdentity,
                        exactPending,
                        publishing.expectedBytes,
                        now().toEpochMilli(),
                    )
            ) {
                is StorageResult.Success -> verified.value
                is StorageResult.Failure ->
                    return failPublishingStorage(
                        offline,
                        id,
                        publishing,
                        exactPending,
                        verified.reason,
                    )
            }
        val verifiedMimeType =
            artifact.mimeType?.trim()?.takeIf(String::isNotBlank)
                ?: return failPublishing(
                    offline,
                    id,
                    publishing,
                    exactPending,
                    "storage_missing_mime_type",
                    awaitUser = false,
                )
        when (val media3 = operations.verifyMedia3(artifact.contentUri, verifiedMimeType)) {
            R16Media3ReadabilityResult.Readable -> Unit
            is R16Media3ReadabilityResult.Unreadable -> {
                return failPublishing(
                    offline,
                    id,
                    publishing,
                    exactPending,
                    "media3_${media3.reason.name.lowercase()}",
                    awaitUser = false,
                )
            }
        }

        val publication =
            offline.publishVerified(
                R16VerifiedDownloadPublication(
                    jobId = id.value,
                    destinationIdentity = destinationIdentity,
                    asset = publishing.toAsset(artifact),
                    locationType = "CONTENT_URI",
                    documentId = artifact.documentIdOrNull(),
                    mediaStoreId = null,
                    displayName = exactPending.displayName,
                    container = null,
                    normalizedPathToken = null,
                    lastModifiedAt = null,
                    evidence =
                        R16DownloadVerificationEvidence(
                            bytesComplete = true,
                            finalLocationExists = true,
                            accessRetained = true,
                            media3Readable = true,
                        ),
                    publishedAt = Instant.ofEpochMilli(artifact.verifiedAtEpochMs),
                )
            )
        return when (publication) {
            is R16OfflineMutationResult.Applied -> {
                operations.cleanupStage(id)
                R16DownloadExecutionResult.Completed
            }
            is R16OfflineMutationResult.Rejected -> {
                val current =
                    offline.load(id.value) ?: return R16DownloadExecutionResult.AwaitingUser
                if (current.state == R16DownloadJobState.AVAILABLE) {
                    operations.cleanupStage(id)
                    return R16DownloadExecutionResult.Completed
                }
                if (!cleanupNewPending(offline, current, destinationIdentity, exactPending, id)) {
                    return R16DownloadExecutionResult.AwaitingUser
                }
                concurrentOutcome(offline, id)
            }
        }
    }

    /**
     * Makes the empty SAF document and its durable PUBLISHING identity one cancellation-shielded
     * operation. No network bytes are copied until both steps succeed.
     */
    private suspend fun createAndBeginPublishing(
        offline: R16OfflineRepository,
        id: DownloadJobId,
        job: R16DownloadJobSnapshot,
        destinationIdentity: String,
        mimeType: String,
    ): PendingRegistration {
        var created: R16PendingDownloadDocument? = null
        return try {
            withContext(NonCancellable) {
                when (
                    val result =
                        operations.createPending(
                            destinationIdentity,
                            id,
                            title = job.displayFallbackJson.downloadTitle(),
                            mimeType = mimeType,
                        )
                ) {
                    is StorageResult.Failure -> PendingRegistration.StorageFailure(result.reason)
                    is StorageResult.Success -> {
                        created = result.value
                        when (
                            val started =
                                offline.beginPublishing(
                                    R16DownloadPublishingStart(
                                        jobId = id.value,
                                        recordingId = job.recordingId,
                                        requestedSourceReferenceId = job.requestedSourceReferenceId,
                                        requestedMediaVariant =
                                            requireNotNull(job.requestedMediaVariant),
                                        destinationIdentity = destinationIdentity,
                                        pendingLocation = result.value.contentUri,
                                        updatedAt = now(),
                                    )
                                )
                        ) {
                            is R16OfflineMutationResult.Applied ->
                                PendingRegistration.Ready(result.value, started.job)
                            is R16OfflineMutationResult.Rejected -> {
                                val cleaned =
                                    cleanupNewPending(
                                        offline,
                                        job,
                                        destinationIdentity,
                                        result.value,
                                        id,
                                    )
                                if (cleaned) {
                                    PendingRegistration.Finished(concurrentOutcome(offline, id))
                                } else {
                                    PendingRegistration.Finished(
                                        R16DownloadExecutionResult.AwaitingUser
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                created?.let { pending ->
                    if (cleanupNewPending(offline, job, destinationIdentity, pending, id)) {
                        reconcileCleanedRegistration(offline, id, pending, "work_cancelled")
                    }
                }
            }
            throw cancelled
        } catch (_: Exception) {
            val cleaned =
                withContext(NonCancellable) {
                    created?.let { cleanupNewPending(offline, job, destinationIdentity, it, id) }
                        ?: true
                }
            if (cleaned) {
                val reconciled =
                    created?.let { pending ->
                        reconcileCleanedRegistration(
                            offline,
                            id,
                            pending,
                            "unexpected_execution_failure",
                        )
                    }
                PendingRegistration.Finished(reconciled ?: recordUnexpectedFailure(offline, id))
            } else {
                PendingRegistration.Finished(R16DownloadExecutionResult.AwaitingUser)
            }
        }
    }

    /** Clears durable PUBLISHING truth if beginPublishing committed before its call failed. */
    private suspend fun reconcileCleanedRegistration(
        offline: R16OfflineRepository,
        id: DownloadJobId,
        pending: R16PendingDownloadDocument,
        failureKind: String,
    ): R16DownloadExecutionResult? {
        val current = offline.load(id.value) ?: return R16DownloadExecutionResult.Failed
        if (current.pendingLocation != pending.contentUri) return null
        val failed = fail(offline, current, failureKind, retryable = true)
        if (failed != R16DownloadExecutionResult.Retry) return failed
        return when (resetAfterPhysicalCleanup(offline, id, current)) {
            is R16OfflineMutationResult.Applied -> R16DownloadExecutionResult.Retry
            is R16OfflineMutationResult.Rejected -> concurrentOutcome(offline, id)
        }
    }

    private suspend fun cleanupNewPending(
        offline: R16OfflineRepository,
        job: R16DownloadJobSnapshot,
        destinationIdentity: String,
        pending: R16PendingDownloadDocument,
        id: DownloadJobId,
    ): Boolean {
        if (
            job.requestedSourceReferenceId == null ||
                job.requestedMediaVariant == null ||
                job.destinationIdentity != destinationIdentity
        ) {
            return false
        }
        val evidence = job.pendingCleanupEvidence(pending.contentUri, now())
        val retained =
            try {
                offline.retainPendingCleanupEvidence(evidence)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return false
            }
        if (retained !is R16OfflineMutationResult.Applied) return false
        val pendingCleaned =
            try {
                operations.cleanupPersistedPending(destinationIdentity, id, pending.contentUri) !=
                    SafManagedDownloadDeletionResult.FAILED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        if (!pendingCleaned) return false
        val cleared =
            try {
                offline.clearPendingCleanupEvidence(evidence)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return false
            }
        if (cleared !is R16OfflineMutationResult.Applied) return false
        return try {
            operations.cleanupStage(id)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun recoverAndPublish(
        offline: R16OfflineRepository,
        id: DownloadJobId,
        job: R16DownloadJobSnapshot,
    ): R16DownloadExecutionResult {
        val destinationIdentity =
            job.destinationIdentity
                ?: return fail(offline, job, "missing_destination_identity", retryable = false)
        val pendingLocation =
            job.pendingLocation
                ?: return fail(offline, job, "missing_pending_location", retryable = true)
        return when (
            val recovered = operations.recoverPending(destinationIdentity, id, pendingLocation)
        ) {
            is StorageResult.Success -> publish(offline, id, job, recovered.value)
            is StorageResult.Failure ->
                reconcileMissingPublishing(
                    offline,
                    id,
                    job,
                    destinationIdentity,
                    pendingLocation,
                    recovered.reason,
                )
        }
    }

    private suspend fun reconcileMissingPublishing(
        offline: R16OfflineRepository,
        id: DownloadJobId,
        job: R16DownloadJobSnapshot,
        destinationIdentity: String,
        pendingLocation: String,
        recoveryFailure: DownloadStorageFailure,
    ): R16DownloadExecutionResult {
        val cleaned =
            try {
                operations.cleanupPersistedPending(destinationIdentity, id, pendingLocation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SafManagedDownloadDeletionResult.FAILED
            }
        if (cleaned == SafManagedDownloadDeletionResult.FAILED) {
            return failStorage(offline, job, recoveryFailure)
        }
        val cleared =
            try {
                offline.clearPendingCleanupEvidence(
                    job.pendingCleanupEvidence(pendingLocation, now())
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return R16DownloadExecutionResult.AwaitingUser
            }
        if (cleared !is R16OfflineMutationResult.Applied) {
            return R16DownloadExecutionResult.AwaitingUser
        }
        operations.cleanupStage(id)
        val failed = fail(offline, cleared.job, "publishing_output_missing", retryable = true)
        if (failed != R16DownloadExecutionResult.Retry) return failed
        return when (resetAfterPhysicalCleanup(offline, id, cleared.job)) {
            is R16OfflineMutationResult.Applied -> R16DownloadExecutionResult.Retry
            is R16OfflineMutationResult.Rejected -> concurrentOutcome(offline, id)
        }
    }

    private suspend fun resetRetryable(
        offline: R16OfflineRepository,
        id: DownloadJobId,
        job: R16DownloadJobSnapshot,
    ): ResetResult {
        val destinationIdentity =
            job.destinationIdentity ?: return ResetResult.Done(R16DownloadExecutionResult.Failed)
        operations.cleanupStage(id)
        val sourceId =
            job.requestedSourceReferenceId
                ?: return ResetResult.Done(R16DownloadExecutionResult.Failed)
        val mediaVariant =
            job.requestedMediaVariant ?: return ResetResult.Done(R16DownloadExecutionResult.Failed)
        return when (
            val reset =
                offline.resetForRetry(
                    R16DownloadRetryReset(
                        jobId = id.value,
                        recordingId = job.recordingId,
                        requestedSourceReferenceId = sourceId,
                        requestedMediaVariant = mediaVariant,
                        destinationIdentity = destinationIdentity,
                        updatedAt = now(),
                    )
                )
        ) {
            is R16OfflineMutationResult.Applied -> ResetResult.Ready(reset.job)
            is R16OfflineMutationResult.Rejected -> ResetResult.Done(concurrentOutcome(offline, id))
        }
    }

    private suspend fun failPublishingStorage(
        offline: R16OfflineRepository,
        id: DownloadJobId,
        job: R16DownloadJobSnapshot,
        pending: R16PendingDownloadDocument,
        reason: DownloadStorageFailure,
    ): R16DownloadExecutionResult =
        failPublishing(
            offline,
            id,
            job,
            pending,
            "storage_${reason.name.lowercase()}",
            awaitUser = true,
        )

    private suspend fun failPublishing(
        offline: R16OfflineRepository,
        id: DownloadJobId,
        job: R16DownloadJobSnapshot,
        pending: R16PendingDownloadDocument,
        failureKind: String,
        awaitUser: Boolean,
    ): R16DownloadExecutionResult {
        val result = fail(offline, job, failureKind, retryable = true, awaitUser = awaitUser)
        val cleaned = operations.cleanupPending(requireNotNull(job.destinationIdentity), pending)
        if (cleaned != SafManagedDownloadDeletionResult.FAILED) {
            operations.cleanupStage(id)
            when (val reset = resetAfterPhysicalCleanup(offline, id, job)) {
                is R16OfflineMutationResult.Rejected -> return concurrentOutcome(offline, id)
                is R16OfflineMutationResult.Applied -> {
                    if (awaitUser) {
                        return fail(
                            offline,
                            reset.job,
                            failureKind,
                            retryable = true,
                            awaitUser = true,
                        )
                    }
                }
            }
        }
        return if (cleaned == SafManagedDownloadDeletionResult.FAILED) {
            R16DownloadExecutionResult.AwaitingUser
        } else {
            result
        }
    }

    private suspend fun failStorage(
        offline: R16OfflineRepository,
        job: R16DownloadJobSnapshot,
        reason: DownloadStorageFailure,
    ): R16DownloadExecutionResult =
        fail(offline, job, "storage_${reason.name.lowercase()}", retryable = true, awaitUser = true)

    private suspend fun fail(
        offline: R16OfflineRepository,
        job: R16DownloadJobSnapshot,
        failureKind: String,
        retryable: Boolean,
        awaitUser: Boolean = false,
    ): R16DownloadExecutionResult {
        val mutation =
            offline.updateProgress(
                R16DownloadProgress(
                    jobId = job.jobId,
                    state =
                        if (retryable) {
                            R16DownloadJobState.FAILED_RETRYABLE
                        } else {
                            R16DownloadJobState.FAILED_FINAL
                        },
                    bytesTransferred = job.bytesTransferred,
                    expectedBytes = job.expectedBytes,
                    failureKind = failureKind,
                    retryAfter = null,
                    updatedAt = now(),
                )
            )
        if (mutation !is R16OfflineMutationResult.Applied) {
            return concurrentOutcome(offline, DownloadJobId(job.jobId))
        }
        return when {
            !retryable -> R16DownloadExecutionResult.Failed
            awaitUser -> R16DownloadExecutionResult.AwaitingUser
            else -> R16DownloadExecutionResult.Retry
        }
    }

    private suspend fun advance(
        offline: R16OfflineRepository,
        job: R16DownloadJobSnapshot,
        state: R16DownloadJobState,
        bytesTransferred: Long = job.bytesTransferred,
        expectedBytes: Long? = job.expectedBytes,
    ): R16DownloadJobSnapshot? =
        when (
            val result =
                offline.updateProgress(
                    R16DownloadProgress(
                        jobId = job.jobId,
                        state = state,
                        bytesTransferred = bytesTransferred,
                        expectedBytes = expectedBytes,
                        failureKind = null,
                        retryAfter = null,
                        updatedAt = now(),
                    )
                )
        ) {
            is R16OfflineMutationResult.Applied -> result.job
            is R16OfflineMutationResult.Rejected -> null
        }

    private suspend fun concurrentOutcome(
        offline: R16OfflineRepository,
        id: DownloadJobId,
    ): R16DownloadExecutionResult =
        when (offline.load(id.value)?.state) {
            R16DownloadJobState.PAUSED,
            R16DownloadJobState.CANCELLED,
            R16DownloadJobState.REMOVED,
            R16DownloadJobState.AVAILABLE -> R16DownloadExecutionResult.Completed
            else -> R16DownloadExecutionResult.Failed
        }

    private suspend fun cleanupTerminalStage(id: DownloadJobId): R16DownloadExecutionResult =
        try {
            operations.cleanupStage(id)
            R16DownloadExecutionResult.Completed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            R16DownloadExecutionResult.Failed
        }

    private suspend fun cleanupTerminalPending(
        offline: R16OfflineRepository,
        id: DownloadJobId,
        job: R16DownloadJobSnapshot,
    ): R16DownloadExecutionResult {
        val result = cleanupRetainedPending(offline, id, job)
        if (result != R16DownloadExecutionResult.Completed) return result
        return cleanupTerminalStage(id)
    }

    /** Removes exact cleanup-only SAF evidence without touching a reusable verified stage. */
    private suspend fun cleanupRetainedPending(
        offline: R16OfflineRepository,
        id: DownloadJobId,
        job: R16DownloadJobSnapshot,
    ): R16DownloadExecutionResult {
        val location = job.pendingLocation ?: return R16DownloadExecutionResult.Completed
        val destinationIdentity =
            job.destinationIdentity ?: return R16DownloadExecutionResult.AwaitingUser
        if (job.requestedSourceReferenceId == null || job.requestedMediaVariant == null) {
            return R16DownloadExecutionResult.AwaitingUser
        }
        val deleted =
            try {
                operations.cleanupPersistedPending(destinationIdentity, id, location)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SafManagedDownloadDeletionResult.FAILED
            }
        if (deleted == SafManagedDownloadDeletionResult.FAILED) {
            return R16DownloadExecutionResult.AwaitingUser
        }
        val cleared =
            try {
                offline.clearPendingCleanupEvidence(job.pendingCleanupEvidence(location, now()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return R16DownloadExecutionResult.AwaitingUser
            }
        if (cleared !is R16OfflineMutationResult.Applied) {
            return R16DownloadExecutionResult.AwaitingUser
        }
        return R16DownloadExecutionResult.Completed
    }

    private suspend fun cleanupAfterCancellation(offline: R16OfflineRepository, id: DownloadJobId) {
        var job = offline.load(id.value) ?: return
        if (
            job.state == R16DownloadJobState.AVAILABLE ||
                job.state == R16DownloadJobState.FAILED_FINAL ||
                job.state == R16DownloadJobState.CANCELLED ||
                job.state == R16DownloadJobState.REMOVED
        ) {
            cleanupTerminalPending(offline, id, job)
            return
        }
        if (job.state == R16DownloadJobState.PAUSED) {
            cleanupRetainedPending(offline, id, job)
            return
        }
        if (job.pendingLocation != null) {
            if (cleanupRetainedPending(offline, id, job) != R16DownloadExecutionResult.Completed) {
                return
            }
            job = offline.load(id.value) ?: return
        }
        when (job.state) {
            R16DownloadJobState.AVAILABLE,
            R16DownloadJobState.FAILED_FINAL,
            R16DownloadJobState.CANCELLED,
            R16DownloadJobState.REMOVED -> {
                cleanupTerminalStage(id)
                return
            }
            R16DownloadJobState.PAUSED -> return
            else -> Unit
        }
        operations.cleanupStage(id)
        val failed = fail(offline, job, "work_cancelled", retryable = true)
        if (failed == R16DownloadExecutionResult.Retry) {
            resetAfterPhysicalCleanup(offline, id, job)
        }
    }

    private suspend fun resetAfterPhysicalCleanup(
        offline: R16OfflineRepository,
        id: DownloadJobId,
        job: R16DownloadJobSnapshot,
    ): R16OfflineMutationResult =
        offline.resetForRetry(
            R16DownloadRetryReset(
                jobId = id.value,
                recordingId = job.recordingId,
                requestedSourceReferenceId = job.requestedSourceReferenceId,
                requestedMediaVariant = requireNotNull(job.requestedMediaVariant),
                destinationIdentity = requireNotNull(job.destinationIdentity),
                updatedAt = now(),
            )
        )

    private suspend fun recordUnexpectedFailure(
        offline: R16OfflineRepository,
        id: DownloadJobId,
    ): R16DownloadExecutionResult {
        val current =
            try {
                offline.load(id.value)
            } catch (_: Exception) {
                null
            } ?: return R16DownloadExecutionResult.Failed
        if (
            current.state == R16DownloadJobState.AVAILABLE ||
                current.state == R16DownloadJobState.REMOVED ||
                current.state == R16DownloadJobState.CANCELLED ||
                current.state == R16DownloadJobState.FAILED_FINAL ||
                current.state == R16DownloadJobState.PAUSED
        ) {
            return R16DownloadExecutionResult.Completed
        }
        return try {
            fail(offline, current, "unexpected_execution_failure", retryable = true)
        } catch (_: Exception) {
            R16DownloadExecutionResult.Failed
        }
    }

    private sealed interface ResetResult {
        data class Ready(val job: R16DownloadJobSnapshot) : ResetResult

        data class Done(val result: R16DownloadExecutionResult) : ResetResult
    }

    private sealed interface PendingRegistration {
        data class Ready(val pending: R16PendingDownloadDocument, val job: R16DownloadJobSnapshot) :
            PendingRegistration

        data class StorageFailure(val reason: DownloadStorageFailure) : PendingRegistration

        data class Finished(val result: R16DownloadExecutionResult) : PendingRegistration
    }
}

private class R16DownloadStopped : RuntimeException()

private fun R16DownloadJobSnapshot.toAsset(artifact: DownloadArtifact): MediaAsset =
    MediaAsset(
        id =
            MediaAssetId(
                UUID.nameUUIDFromBytes("r16-download:$jobId".toByteArray(StandardCharsets.UTF_8))
                    .toString()
            ),
        recordingId = recordingId,
        sourceReferenceId = requestedSourceReferenceId,
        kind = MediaAssetKind.SHIPPY_DOWNLOAD,
        location = AssetLocation(artifact.contentUri),
        state = AssetState.AVAILABLE,
        technical =
            AudioTechnicalMetadata(
                mimeType = artifact.mimeType,
                codec = null,
                bitrateBps = null,
                sampleRateHz = null,
                channelCount = null,
                contentLength = artifact.contentLength,
            ),
        checksum = null,
        fingerprint = null,
        lastVerifiedAt = Instant.ofEpochMilli(artifact.verifiedAtEpochMs),
    )

private fun R16DownloadJobSnapshot.pendingCleanupEvidence(
    location: String,
    updatedAt: Instant,
): R16PendingCleanupEvidence =
    R16PendingCleanupEvidence(
        jobId = jobId,
        recordingId = recordingId,
        requestedSourceReferenceId = requireNotNull(requestedSourceReferenceId),
        requestedMediaVariant = requireNotNull(requestedMediaVariant),
        destinationIdentity = requireNotNull(destinationIdentity),
        pendingLocation = location,
        updatedAt = updatedAt,
    )

private fun DownloadArtifact.documentIdOrNull(): String? =
    runCatching { DocumentsContract.getDocumentId(Uri.parse(contentUri)) }.getOrNull()

private fun String.downloadTitle(): String =
    runCatching { JSONObject(this).optString("title").trim() }
        .getOrNull()
        ?.takeIf(String::isNotBlank) ?: "Track"

private fun DownloadTransferFailure.failureCode(): String =
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

@Module
@InstallIn(SingletonComponent::class)
internal abstract class R16DownloadOperationsModule {
    @Binds
    abstract fun bindR16DownloadOperations(
        implementation: AndroidR16DownloadOperations
    ): R16DownloadOperations
}
