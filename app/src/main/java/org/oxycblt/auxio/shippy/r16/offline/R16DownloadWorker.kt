/*
 * Copyright (c) 2026 Auxio Project
 * R16DownloadWorker.kt is part of Auxio.
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

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.oxycblt.auxio.shippy.r16.authority.R16ActiveDataRuntimeOwner

/** Thin WorkManager adapter for the fail-closed R16 permanent-download coordinator. */
@HiltWorker
class R16DownloadWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val activeRuntimeOwner: R16ActiveDataRuntimeOwner,
    private val coordinator: R16DownloadExecutionCoordinator,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        // Inactive R16 work is intentionally consumed without opening Room, providers, or storage.
        val runtime = activeRuntimeOwner.activeRuntimeOrNull() ?: return Result.success()
        val jobId =
            inputData
                .getString(R16DownloadWorkScheduler.KEY_JOB_ID)
                ?.takeIf(::isSafeR16DownloadJobId) ?: return Result.failure()
        return when (
            r16DownloadWorkerDecision(coordinator.execute(runtime, jobId), runAttemptCount)
        ) {
            R16DownloadWorkerDecision.SUCCESS -> Result.success()
            R16DownloadWorkerDecision.RETRY -> Result.retry()
            R16DownloadWorkerDecision.FAILURE -> Result.failure()
        }
    }
}

internal enum class R16DownloadWorkerDecision {
    SUCCESS,
    RETRY,
    FAILURE,
}

internal fun r16DownloadWorkerDecision(
    execution: R16DownloadExecutionResult,
    runAttemptCount: Int,
): R16DownloadWorkerDecision =
    when (execution) {
        R16DownloadExecutionResult.Completed -> R16DownloadWorkerDecision.SUCCESS
        R16DownloadExecutionResult.Retry ->
            if (runAttemptCount + 1 < R16DownloadWorkScheduler.MAX_AUTOMATIC_ATTEMPTS) {
                R16DownloadWorkerDecision.RETRY
            } else {
                R16DownloadWorkerDecision.FAILURE
            }
        R16DownloadExecutionResult.AwaitingUser,
        R16DownloadExecutionResult.Failed -> R16DownloadWorkerDecision.FAILURE
    }
