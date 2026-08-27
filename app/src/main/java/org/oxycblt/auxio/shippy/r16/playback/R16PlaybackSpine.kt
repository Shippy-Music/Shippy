/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaybackSpine.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback

import app.shippy.core.playback.PlaybackCommand
import app.shippy.core.playback.PlaybackCommandResult
import app.shippy.core.playback.PlaybackSnapshot
import app.shippy.data.playback.R16PlaybackCheckpointRepository
import app.shippy.data.playback.R16PlaybackPresentationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.auxio.shippy.r16.maintenance.R16LivePlaybackQueue
import org.oxycblt.auxio.shippy.r16.playback.service.R16PlaybackServiceEndpoint
import org.oxycblt.auxio.shippy.r16.playback.service.R16PlaybackServiceRuntime
import org.oxycblt.auxio.shippy.r16.playback.system.R16SystemPlaybackBridge

/**
 * The inactive R16 process-local playback composition. It creates exactly one coordinator for one
 * supplied engine; service and system surfaces only observe and route through that authority.
 */
class R16PlaybackSpine(
    parentScope: CoroutineScope,
    engine: PlayerEngine,
    sourcePreparer: PlaybackSourcePreparer,
    checkpoints: R16PlaybackCheckpointRepository,
    presentations: R16PlaybackPresentationRepository,
    traceRecorder: BoundedPlaybackTraceRecorder = BoundedPlaybackTraceRecorder(),
    checkpointDelayMs: Long = DEFAULT_CHECKPOINT_DELAY_MS,
    private val listeningSessionDelivery: R16ListeningSessionDelivery? = null,
    listeningSessionIdentityProvider: ListeningSessionIdentityProvider =
        NoOpListeningSessionIdentityProvider,
    livePlaybackQueue: R16LivePlaybackQueue? = null,
) : R16PlaybackServiceEndpoint {
    private val coordinator =
        PlaybackCoordinator(
            parentScope,
            engine,
            sourcePreparer,
            traceSink = traceRecorder,
            listeningSessionSink = listeningSessionDelivery ?: NoOpListeningSessionSink,
            listeningSessionIdentityProvider = listeningSessionIdentityProvider,
        )
    private val service =
        R16PlaybackServiceRuntime(
            parentScope,
            coordinator,
            checkpoints,
            traceRecorder,
            checkpointDelayMs,
            livePlaybackQueue,
        )

    /** System-facing projection of [snapshots]; it is not another playback authority. */
    val system = R16SystemPlaybackBridge(parentScope, service, presentations)

    override val snapshots: StateFlow<PlaybackSnapshot>
        get() = service.snapshots

    override val status
        get() = service.status

    suspend fun attach(allowResume: Boolean = false) = service.attach(allowResume)

    override suspend fun dispatch(command: PlaybackCommand): PlaybackCommandResult =
        service.dispatch(command)

    override fun traceSnapshot(): List<PlaybackTraceEvent> = service.traceSnapshot()

    override fun exportTrace(): String = service.exportTrace()

    suspend fun release() {
        try {
            system.release()
        } finally {
            try {
                service.release()
            } finally {
                listeningSessionDelivery?.closeAndDrain()
            }
        }
    }

    private companion object {
        const val DEFAULT_CHECKPOINT_DELAY_MS = 5_000L
    }
}
