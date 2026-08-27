/*
 * Copyright (c) 2026 Auxio Project
 * R16DownloadWorkScheduler.kt is part of Auxio.
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
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.auxio.shippy.r16.authority.R16AuthorityMode
import org.oxycblt.auxio.shippy.r16.authority.R16AuthoritySelector

/** Contract for scheduling background download work. */
public interface R16DownloadScheduler {
    public fun schedule(jobId: String): Boolean

    public fun cancel(jobId: String): Boolean
}

/** Inert scheduling boundary for durable R16 permanent-download work. */
@Singleton
public open class R16DownloadWorkScheduler
@Inject
constructor(
    @ApplicationContext context: Context,
    private val authoritySelector: R16AuthoritySelector,
) : R16DownloadScheduler {
    private val workManager = WorkManager.getInstance(context)

    /**
     * Schedules only when the R16 authority is explicitly active.
     *
     * No current production state can satisfy that gate, and this class intentionally has no call
     * sites yet.
     */
    public override fun schedule(jobId: String): Boolean {
        if (
            authoritySelector.select() != R16AuthorityMode.ACTIVE || !isSafeR16DownloadJobId(jobId)
        ) {
            return false
        }
        workManager.enqueueUniqueWork(
            uniqueWorkName(jobId),
            ExistingWorkPolicy.KEEP,
            buildRequest(jobId),
        )
        return true
    }

    public override fun cancel(jobId: String): Boolean {
        if (!isSafeR16DownloadJobId(jobId)) return false
        workManager.cancelUniqueWork(uniqueWorkName(jobId))
        return true
    }

    companion object {
        const val KEY_JOB_ID = "r16_download_job_id"
        const val WORK_NAME_PREFIX = "r16-download:"
        const val MAX_AUTOMATIC_ATTEMPTS = 3
        private const val INITIAL_BACKOFF_SECONDS = 15L

        internal fun uniqueWorkName(jobId: String): String = WORK_NAME_PREFIX + jobId

        internal fun buildRequest(jobId: String): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<R16DownloadWorker>()
                .setInputData(workDataOf(KEY_JOB_ID to jobId))
                .setConstraints(
                    // PUBLISHING recovery is entirely local and must run while offline. Fresh
                    // transfers classify network absence durably and use the bounded retry policy.
                    Constraints.Builder().setRequiredNetworkType(NETWORK_TYPE).build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    INITIAL_BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                )
                .addTag(uniqueWorkName(jobId))
                .build()

        internal val NETWORK_TYPE: NetworkType = NetworkType.NOT_REQUIRED
    }
}

/** Job IDs reach app-private filenames, so validate them before opening any staging path. */
internal fun isSafeR16DownloadJobId(value: String): Boolean =
    value.isNotBlank() &&
        value == value.trim() &&
        value.length <= 128 &&
        value != "." &&
        value != ".." &&
        '/' !in value &&
        '\\' !in value &&
        value.none(Char::isISOControl)
