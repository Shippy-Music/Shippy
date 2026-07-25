/*
 * Copyright (c) 2026 Shippy contributors
 * DownloadWorkCoordinator.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobRepository
import org.oxycblt.auxio.shippy.persistence.library.LibraryRelationshipRepository

@Singleton
class DownloadWorkCoordinator
@Inject
constructor(
    @ApplicationContext context: Context,
    private val jobs: DownloadJobRepository,
    private val storage: SafDownloadStorage,
    private val relationships: LibraryRelationshipRepository,
) {
    private val workManager = WorkManager.getInstance(context)
    private val requestMutex = Mutex()

    suspend fun request(
        track: Track,
        candidateId: CandidateId,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): DownloadJobId =
        requestMutex.withLock {
            val existing = jobs.getLatestForTrack(track.id)
            if (
                existing != null &&
                    existing.job.state != DownloadState.REMOVED &&
                    existing.job.state != DownloadState.CANCELLED &&
                    existing.job.state != DownloadState.FAILED_FINAL
            ) {
                when (existing.job.state) {
                    DownloadState.PAUSED -> {
                        jobs.apply(existing.job.id, DownloadEvent.Resume, nowEpochMs)
                        enqueue(existing.job.id, existing.track, ExistingWorkPolicy.REPLACE)
                    }
                    DownloadState.FAILED_RETRYABLE -> {
                        jobs.apply(existing.job.id, DownloadEvent.Retry, nowEpochMs)
                        enqueue(existing.job.id, existing.track, ExistingWorkPolicy.REPLACE)
                    }
                    else -> Unit
                }
                return@withLock existing.job.id
            }
            val jobId = DownloadJobId(UUID.randomUUID().toString())
            jobs.create(jobId, track, candidateId, nowEpochMs)
            enqueue(jobId, track, ExistingWorkPolicy.REPLACE)
            jobId
        }

    suspend fun pause(
        jobId: DownloadJobId,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        if (jobs.get(jobId) == null) return
        val transition = jobs.apply(jobId, DownloadEvent.Pause, nowEpochMs)
        if (transition !is DownloadTransition.Applied) return
        workManager.cancelUniqueWork(workName(jobId)).result.await()
        jobs.get(jobId)?.pendingDocument?.let { storage.delete(it.contentUri) }
        jobs.setPendingDocument(jobId, null, nowEpochMs)
    }

    suspend fun resume(
        jobId: DownloadJobId,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        val stored = jobs.get(jobId) ?: return
        val transition = jobs.apply(jobId, DownloadEvent.Resume, nowEpochMs)
        if (transition is DownloadTransition.Applied) {
            enqueue(jobId, stored.track, ExistingWorkPolicy.REPLACE)
        }
    }

    suspend fun retry(
        jobId: DownloadJobId,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        val stored = jobs.get(jobId) ?: return
        val transition = jobs.apply(jobId, DownloadEvent.Retry, nowEpochMs)
        if (transition is DownloadTransition.Applied) {
            enqueue(jobId, stored.track, ExistingWorkPolicy.REPLACE)
        }
    }

    suspend fun cancel(
        jobId: DownloadJobId,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        if (jobs.get(jobId) == null) return
        val transition = jobs.apply(jobId, DownloadEvent.Cancel, nowEpochMs)
        if (transition !is DownloadTransition.Applied) return
        workManager.cancelUniqueWork(workName(jobId)).result.await()
        jobs.get(jobId)?.pendingDocument?.let { storage.delete(it.contentUri) }
        jobs.setPendingDocument(jobId, null, nowEpochMs)
    }

    suspend fun remove(
        jobId: DownloadJobId,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val stored = jobs.get(jobId) ?: return false
        val artifact = stored.job.artifact ?: return false
        if (!storage.delete(artifact.contentUri)) return false
        val transition = jobs.apply(jobId, DownloadEvent.Remove, nowEpochMs)
        if (transition !is DownloadTransition.Applied) return false
        relationships.setDownloaded(
            stored.track.id,
            jobs.hasAvailableForTrack(stored.track.id),
        )
        return true
    }

    private fun enqueue(
        jobId: DownloadJobId,
        track: Track,
        policy: ExistingWorkPolicy,
    ) {
        val requiresNetwork =
            track.candidates.none { candidate ->
                candidate.kind != CandidateKind.PROVIDER &&
                    candidate.locator?.substringBefore(':') in setOf("content", "file")
            }
        val request =
            OneTimeWorkRequestBuilder<ShippyDownloadWorker>()
                .setInputData(workDataOf(ShippyDownloadWorker.KEY_JOB_ID to jobId.value))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (requiresNetwork) NetworkType.CONNECTED
                            else NetworkType.NOT_REQUIRED
                        )
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(DOWNLOAD_WORK_TAG)
                .addTag(jobId.value)
                .build()
        workManager.enqueueUniqueWork(workName(jobId), policy, request)
    }

    private fun workName(jobId: DownloadJobId) = "shippy-download:${jobId.value}"

    private companion object {
        const val DOWNLOAD_WORK_TAG = "shippy-download"
    }
}
