/*
 * Copyright (c) 2026 Auxio Project
 * DownloadWorkCoordinator.kt is part of Auxio.
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val transferStaging: DownloadTransferStaging,
    private val crewTemporaryStaging: CrewTemporaryDownloadStaging,
    private val relationships: LibraryRelationshipRepository,
    private val publicationGate: DownloadPublicationGate,
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
                        enqueue(
                            existing.job.id,
                            existing.track,
                            existing.job.candidateId,
                            ExistingWorkPolicy.REPLACE,
                        )
                    }
                    DownloadState.FAILED_RETRYABLE -> {
                        jobs.apply(existing.job.id, DownloadEvent.Retry, nowEpochMs)
                        enqueue(
                            existing.job.id,
                            existing.track,
                            existing.job.candidateId,
                            ExistingWorkPolicy.REPLACE,
                        )
                    }
                    else -> Unit
                }
                return@withLock existing.job.id
            }
            val jobId = DownloadJobId(UUID.randomUUID().toString())
            val persistedTrack = crewTemporaryStaging.stage(track, candidateId, jobId)
            var created = false
            try {
                jobs.create(jobId, persistedTrack, candidateId, nowEpochMs)
                created = true
                enqueue(jobId, persistedTrack, candidateId, ExistingWorkPolicy.REPLACE)
                jobId
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    if (created) jobs.delete(jobId)
                    crewTemporaryStaging.cleanup(jobId)
                }
                throw error
            } catch (error: Exception) {
                if (created) jobs.delete(jobId)
                crewTemporaryStaging.cleanup(jobId)
                throw error
            }
        }

    suspend fun pause(jobId: DownloadJobId, nowEpochMs: Long = System.currentTimeMillis()) {
        if (jobs.get(jobId) == null) return
        val transition = jobs.apply(jobId, DownloadEvent.Pause, nowEpochMs)
        if (transition !is DownloadTransition.Applied) return
        workManager.cancelUniqueWork(workName(jobId)).result.await()
        jobs.get(jobId)?.pendingDocument?.let { storage.delete(it.contentUri) }
        jobs.setPendingDocument(jobId, null, nowEpochMs)
    }

    suspend fun resume(jobId: DownloadJobId, nowEpochMs: Long = System.currentTimeMillis()) {
        val stored = jobs.get(jobId) ?: return
        val transition = jobs.apply(jobId, DownloadEvent.Resume, nowEpochMs)
        if (transition is DownloadTransition.Applied) {
            enqueue(jobId, stored.track, stored.job.candidateId, ExistingWorkPolicy.REPLACE)
        }
    }

    suspend fun retry(jobId: DownloadJobId, nowEpochMs: Long = System.currentTimeMillis()) {
        val stored = jobs.get(jobId) ?: return
        val transition = jobs.apply(jobId, DownloadEvent.Retry, nowEpochMs)
        if (transition is DownloadTransition.Applied) {
            enqueue(jobId, stored.track, stored.job.candidateId, ExistingWorkPolicy.REPLACE)
        }
    }

    suspend fun cancel(jobId: DownloadJobId, nowEpochMs: Long = System.currentTimeMillis()) {
        if (jobs.get(jobId) == null) return
        val transition = jobs.apply(jobId, DownloadEvent.Cancel, nowEpochMs)
        if (transition !is DownloadTransition.Applied) return
        workManager.cancelUniqueWork(workName(jobId)).result.await()
        jobs.get(jobId)?.pendingDocument?.let { storage.delete(it.contentUri) }
        jobs.setPendingDocument(jobId, null, nowEpochMs)
        transferStaging.cleanup(jobId)
        crewTemporaryStaging.cleanup(jobId)
    }

    suspend fun remove(
        jobId: DownloadJobId,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean =
        publicationGate.run {
            val stored = jobs.get(jobId) ?: return@run false
            val artifact = stored.job.artifact ?: return@run false
            val transition = jobs.apply(jobId, DownloadEvent.Remove, nowEpochMs)
            if (transition !is DownloadTransition.Applied) return@run false
            relationships.setDownloaded(stored.track.id, jobs.hasAvailableForTrack(stored.track.id))
            transferStaging.cleanup(jobId)
            crewTemporaryStaging.cleanup(jobId)
            storage.delete(artifact.contentUri)
        }

    private fun enqueue(
        jobId: DownloadJobId,
        track: Track,
        candidateId: CandidateId,
        policy: ExistingWorkPolicy,
    ) {
        val requiresNetwork = downloadRequiresNetwork(track, candidateId)
        val request =
            OneTimeWorkRequestBuilder<ShippyDownloadWorker>()
                .setInputData(workDataOf(ShippyDownloadWorker.KEY_JOB_ID to jobId.value))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED
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

internal fun downloadRequiresNetwork(track: Track, candidateId: CandidateId): Boolean {
    val candidate = track.candidates.firstOrNull { it.id == candidateId } ?: return true
    return candidate.kind == CandidateKind.PROVIDER ||
        candidate.locator?.substringBefore(':') !in setOf("content", "file")
}
