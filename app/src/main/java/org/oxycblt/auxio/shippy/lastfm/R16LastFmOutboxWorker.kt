/*
 * Copyright (c) 2026 Auxio Project
 * R16LastFmOutboxWorker.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.lastfm

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.oxycblt.auxio.shippy.r16.authority.R16ActiveDataRuntimeOwner

/** WorkManager adapter for the inactive R16 Last.fm delivery boundary. */
@HiltWorker
class R16LastFmOutboxWorker
@AssistedInject
constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val activeRuntimeOwner: R16ActiveDataRuntimeOwner,
    private val client: LastFmClient,
    private val credentials: LastFmCredentialRepository,
    private val reauth: LastFmReauthState,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val runtime = activeRuntimeOwner.activeRuntimeOrNull() ?: return Result.success()
        val coordinator =
            R16LastFmOutboxDeliveryCoordinator(
                credentials = credentials,
                reauth = reauth,
                submit = { entries, auth -> client.scrobble(entries, auth) },
            )
        return when (coordinator.flush(runtime.lastFmOutbox)) {
            R16LastFmOutboxFlushResult.Succeeded -> Result.success()
            R16LastFmOutboxFlushResult.Retry -> Result.retry()
        }
    }
}
