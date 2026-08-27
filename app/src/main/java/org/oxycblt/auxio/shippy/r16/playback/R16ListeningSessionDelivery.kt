/*
 * Copyright (c) 2026 Auxio Project
 * R16ListeningSessionDelivery.kt is part of Auxio.
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

import app.shippy.core.identity.ListeningSessionId
import app.shippy.core.listening.ActiveListeningSessionCheckpoint
import app.shippy.core.listening.FinalizedListeningSession
import app.shippy.data.listening.R16ListeningSessionRecord
import app.shippy.data.listening.R16ListeningSessionRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Asynchronous app-to-data handoff for finalized listening sessions.
 *
 * [offer] and [offerCheckpoint] are deliberately synchronous. Final sessions use an unbounded
 * channel while checkpoints use a conflated channel, so a slow repository cannot block playback or
 * accumulate an unbounded stream of intermediate snapshots. Callers must invoke [closeAndDrain]
 * after the playback authority has been released and before its runtime scope is torn down.
 */
class R16ListeningSessionDelivery(
    parentScope: CoroutineScope,
    private val repository: R16ListeningSessionRepository,
    private val onScrobbleQueued: () -> Unit = {},
) : ListeningSessionSink {
    @Deprecated("isScrobbleAuthorized is now captured at session start")
    constructor(
        parentScope: CoroutineScope,
        repository: R16ListeningSessionRepository,
        isScrobbleAuthorized: suspend () -> Boolean,
        onScrobbleQueued: () -> Unit = {},
    ) : this(parentScope, repository, onScrobbleQueued)

    private val closed = AtomicBoolean(false)
    private val finalEvents = Channel<FinalizedListeningSession>(64)
    private val checkpointEvents = Channel<ActiveListeningSessionCheckpoint>(Channel.CONFLATED)
    private val deliveryScope =
        CoroutineScope(
            parentScope.coroutineContext +
                SupervisorJob(parentScope.coroutineContext[Job]) +
                Dispatchers.IO
        )
    private val failure = CompletableDeferred<Throwable?>()
    private val worker =
        deliveryScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var error: Throwable? = null
            try {
                var finalEventsOpen = true
                var checkpointEventsOpen = true
                while (finalEventsOpen || checkpointEventsOpen) {
                    select<Unit> {
                        if (finalEventsOpen) {
                            finalEvents.onReceiveCatching { result ->
                                val session = result.getOrNull()
                                if (session == null) {
                                    finalEventsOpen = false
                                } else {
                                    persist(session.toRecord())
                                }
                            }
                        }
                        if (checkpointEventsOpen) {
                            checkpointEvents.onReceiveCatching { result ->
                                val checkpoint = result.getOrNull()
                                if (checkpoint == null) {
                                    checkpointEventsOpen = false
                                } else {
                                    persist(checkpoint.toRecord())
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                error = cancelled
            } catch (thrown: Throwable) {
                error = thrown
            } finally {
                failure.complete(error)
            }
        }

    override suspend fun offer(session: FinalizedListeningSession) {
        if (!closed.get()) {
            supersedePendingCheckpoint(session.sessionId)
            finalEvents.send(session)
        }
    }

    private suspend fun persist(record: R16ListeningSessionRecord) {
        if (repository.persist(record).scrobbleQueued) {
            // The durable row is already authoritative. A transient WorkManager scheduling
            // failure must not fail playback release; active startup will schedule it again.
            runCatching(onScrobbleQueued)
        }
    }

    override fun offerCheckpoint(checkpoint: ActiveListeningSessionCheckpoint) {
        if (!closed.get()) {
            checkpointEvents.trySend(checkpoint)
        }
    }

    private fun supersedePendingCheckpoint(sessionId: ListeningSessionId) {
        val pending = checkpointEvents.tryReceive().getOrNull() ?: return
        if (pending.sessionId != sessionId) {
            checkpointEvents.trySend(pending)
        }
    }

    /** Closes intake, persists every accepted event, and reports a persistence failure. */
    suspend fun closeAndDrain() {
        if (closed.compareAndSet(false, true)) {
            finalEvents.close()
            checkpointEvents.close()
        }
        worker.join()
        val error = failure.await()
        deliveryScope.cancel()
        error?.let { throw it }
    }
}

private fun FinalizedListeningSession.toRecord() =
    R16ListeningSessionRecord(
        sessionId = sessionId,
        recordingId = recordingId,
        queueEntryId = queueEntryId,
        sourceReferenceId = sourceReferenceId,
        startedAt = startedAtWallClock,
        endedAt = endedAtWallClock,
        activeListenedMs = activeListenedMs,
        lastPositionMs = lastPositionMs,
        completionKind = completionReason.name,
        chosenByUser = chosenByUser,
        scrobbleAuthorized = scrobbleAuthorized,
        accountId = accountId,
    )

private fun ActiveListeningSessionCheckpoint.toRecord() =
    R16ListeningSessionRecord(
        sessionId = sessionId,
        recordingId = recordingId,
        queueEntryId = queueEntryId,
        sourceReferenceId = sourceReferenceId,
        startedAt = startedAtWallClock,
        endedAt = null,
        activeListenedMs = activeListenedMs,
        lastPositionMs = lastPositionMs,
        completionKind = ACTIVE_CHECKPOINT_COMPLETION_KIND,
        chosenByUser = chosenByUser,
        scrobbleAuthorized = scrobbleAuthorized,
        accountId = accountId,
    )

private const val ACTIVE_CHECKPOINT_COMPLETION_KIND = "ACTIVE_CHECKPOINT"
