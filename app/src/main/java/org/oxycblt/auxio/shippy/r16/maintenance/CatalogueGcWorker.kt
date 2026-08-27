/*
 * Copyright (c) 2026 Auxio Project
 * CatalogueGcWorker.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.maintenance

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.shippy.data.maintenance.R16CatalogueGcProtections
import app.shippy.data.maintenance.R16CatalogueGcRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.oxycblt.auxio.shippy.media.cache.PlaybackCacheManager
import org.oxycblt.auxio.shippy.r16.authority.R16ActiveDataRuntimeOwner

/** WorkManager periodic worker running daily transient catalogue GC. */
@HiltWorker
class CatalogueGcWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val activeRuntimeOwner: R16ActiveDataRuntimeOwner,
    private val playbackCache: PlaybackCacheManager,
    private val livePlaybackQueue: R16LivePlaybackQueue,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            playbackCache.performConfiguredMaintenance()
            val runtime = activeRuntimeOwner.activeRuntimeOrNull() ?: return Result.success()
            val activeQueue =
                livePlaybackQueue.activeRecordingIdsOrNull()
                    ?: runtime.playbackCheckpoints
                        .load()
                        ?.queue
                        ?.baseQueue
                        ?.mapTo(hashSetOf()) { it.recordingId }
                        .orEmpty()
            val protections = R16CatalogueGcProtections(activeQueue = activeQueue)
            val request = R16CatalogueGcRequest(now = Instant.now(), protections = protections)
            runtime.catalogueMaintenance.collectExpiredTransient(request)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "r16_catalogue_gc"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
            val request =
                PeriodicWorkRequestBuilder<CatalogueGcWorker>(1, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
