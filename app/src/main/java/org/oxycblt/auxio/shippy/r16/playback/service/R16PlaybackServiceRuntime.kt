/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaybackServiceRuntime.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback.service

import app.shippy.core.playback.PlaybackCommand
import app.shippy.core.playback.PlaybackCommandRejection
import app.shippy.core.playback.PlaybackCommandResult
import app.shippy.core.playback.PlaybackCommandRouter
import app.shippy.core.playback.PlaybackPhase
import app.shippy.core.playback.PlaybackSnapshot
import app.shippy.data.playback.R16PlaybackCheckpointRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.auxio.playback.service.ServiceRetentionPolicy
import org.oxycblt.auxio.shippy.r16.maintenance.R16LivePlaybackQueue
import org.oxycblt.auxio.shippy.r16.playback.BoundedPlaybackTraceRecorder
import org.oxycblt.auxio.shippy.r16.playback.PlaybackTraceEvent
import org.oxycblt.auxio.shippy.r16.playback.R16PlaybackAuthority

enum class R16PlaybackServiceLifecycle {
    DETACHED,
    ATTACHED,
    RELEASING,
    RELEASED,
}

data class R16PlaybackServiceStatus(
    val lifecycle: R16PlaybackServiceLifecycle,
    val checkpointIssue: String? = null,
)

/** The only contract future system surfaces need from the service-owned R16 authority. */
interface R16PlaybackServiceEndpoint : PlaybackCommandRouter {
    val snapshots: StateFlow<PlaybackSnapshot>
    val status: StateFlow<R16PlaybackServiceStatus>

    fun traceSnapshot(): List<PlaybackTraceEvent>

    fun exportTrace(): String
}

/**
 * Process-scoped lifecycle owner for an already composed coordinator and engine. Production R15.3
 * remains active; constructing this runtime is reserved for the explicit playback cutover path.
 */
class R16PlaybackServiceRuntime(
    parentScope: CoroutineScope,
    private val authority: R16PlaybackAuthority,
    private val checkpoints: R16PlaybackCheckpointRepository,
    private val traceRecorder: BoundedPlaybackTraceRecorder,
    private val checkpointDelayMs: Long = DEFAULT_CHECKPOINT_DELAY_MS,
    private val livePlaybackQueue: R16LivePlaybackQueue? = null,
) : R16PlaybackServiceEndpoint {
    private val runtimeJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + runtimeJob)
    private val lifecycleMutex = Mutex()
    private val mutableStatus =
        MutableStateFlow(R16PlaybackServiceStatus(R16PlaybackServiceLifecycle.DETACHED))
    override val status: StateFlow<R16PlaybackServiceStatus> = mutableStatus.asStateFlow()
    override val snapshots: StateFlow<PlaybackSnapshot> = authority.snapshots

    private var snapshotJob: Job? = null
    private var checkpointJob: Job? = null
    private var checkpointState = CheckpointState.NOT_LOADED

    init {
        require(checkpointDelayMs >= 0) { "R16 checkpoint delay cannot be negative" }
    }

    suspend fun attach(allowResume: Boolean = false) {
        lifecycleMutex.withLock {
            when (mutableStatus.value.lifecycle) {
                R16PlaybackServiceLifecycle.ATTACHED -> return
                R16PlaybackServiceLifecycle.DETACHED -> Unit
                R16PlaybackServiceLifecycle.RELEASING,
                R16PlaybackServiceLifecycle.RELEASED ->
                    error("Released R16 playback service runtime cannot be attached")
            }

            val checkpoint =
                try {
                    checkpoints.load().also {
                        checkpointState =
                            if (it == null) CheckpointState.CLEARED else CheckpointState.RESOLVED
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    checkpointState = CheckpointState.UNRESOLVED
                    recordCheckpointIssue(error)
                    null
                }
            if (checkpoint != null) {
                try {
                    when (val result = authority.restore(checkpoint, allowResume)) {
                        is PlaybackCommandResult.Accepted -> Unit
                        is PlaybackCommandResult.Rejected -> {
                            checkpointState = CheckpointState.UNRESOLVED
                            recordCheckpointIssue(
                                IllegalStateException(
                                    "R16 checkpoint restore rejected: ${result.reason}"
                                )
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    checkpointState = CheckpointState.UNRESOLVED
                    recordCheckpointIssue(error)
                }
            }
            mutableStatus.value =
                mutableStatus.value.copy(lifecycle = R16PlaybackServiceLifecycle.ATTACHED)
            livePlaybackQueue?.update(authority.snapshots.value)
            snapshotJob =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    authority.snapshots.drop(1).collect { snapshot ->
                        livePlaybackQueue?.update(snapshot)
                        scheduleCheckpoint(snapshot)
                    }
                }
        }
    }

    override suspend fun dispatch(command: PlaybackCommand): PlaybackCommandResult {
        return lifecycleMutex.withLock {
            if (mutableStatus.value.lifecycle != R16PlaybackServiceLifecycle.ATTACHED) {
                return@withLock PlaybackCommandResult.Rejected(
                    PlaybackCommandRejection.SERVICE_NOT_ATTACHED
                )
            }
            val result = authority.dispatch(command)
            if (result is PlaybackCommandResult.Accepted && command == PlaybackCommand.Clear) {
                checkpointState = CheckpointState.CLEAR_REQUESTED
                checkpointJob?.cancelAndJoin()
                if (mutableStatus.value.lifecycle == R16PlaybackServiceLifecycle.ATTACHED) {
                    persistCurrentCheckpoint()
                }
            }
            result
        }
    }

    suspend fun release() {
        lifecycleMutex.withLock {
            if (mutableStatus.value.lifecycle == R16PlaybackServiceLifecycle.RELEASED) return
            val wasAttached = mutableStatus.value.lifecycle == R16PlaybackServiceLifecycle.ATTACHED
            mutableStatus.value =
                mutableStatus.value.copy(lifecycle = R16PlaybackServiceLifecycle.RELEASING)
            snapshotJob?.cancelAndJoin()
            checkpointJob?.cancelAndJoin()
            if (wasAttached) persistCurrentCheckpoint()
            try {
                authority.release()
            } finally {
                livePlaybackQueue?.clear()
                runtimeJob.cancelAndJoin()
                mutableStatus.value =
                    mutableStatus.value.copy(lifecycle = R16PlaybackServiceLifecycle.RELEASED)
            }
        }
    }

    override fun traceSnapshot(): List<PlaybackTraceEvent> = traceRecorder.snapshot()

    override fun exportTrace(): String = traceRecorder.exportText()

    private fun scheduleCheckpoint(snapshot: PlaybackSnapshot) {
        if (mutableStatus.value.lifecycle != R16PlaybackServiceLifecycle.ATTACHED) return
        checkpointJob?.cancel()
        checkpointJob =
            scope.launch {
                if (checkpointDelayMs > 0) delay(checkpointDelayMs)
                persistSnapshot(snapshot)
            }
    }

    private suspend fun persistCurrentCheckpoint() {
        persistSnapshot(authority.snapshots.value)
    }

    private suspend fun persistSnapshot(snapshot: PlaybackSnapshot) {
        try {
            if (checkpointState == CheckpointState.CLEAR_REQUESTED) {
                checkpoints.clear()
                checkpointState = CheckpointState.CLEARED
            } else if (snapshot.queue.baseQueue.isEmpty()) {
                if (
                    checkpointState == CheckpointState.UNRESOLVED ||
                        checkpointState == CheckpointState.CLEARED
                ) {
                    return
                }
                checkpoints.clear()
                checkpointState = CheckpointState.CLEARED
            } else {
                checkpoints.save(authority.checkpoint())
                checkpointState = CheckpointState.RESOLVED
            }
            clearCheckpointIssue()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordCheckpointIssue(error)
        }
    }

    private fun recordCheckpointIssue(error: Exception) {
        mutableStatus.value =
            mutableStatus.value.copy(
                checkpointIssue = error.message ?: error::class.java.simpleName
            )
    }

    private fun clearCheckpointIssue() {
        if (mutableStatus.value.checkpointIssue != null) {
            mutableStatus.value = mutableStatus.value.copy(checkpointIssue = null)
        }
    }

    private enum class CheckpointState {
        NOT_LOADED,
        RESOLVED,
        UNRESOLVED,
        CLEAR_REQUESTED,
        CLEARED,
    }

    private companion object {
        const val DEFAULT_CHECKPOINT_DELAY_MS = 5_000L
    }
}

object R16PlaybackForegroundPolicy {
    fun hasSession(snapshot: PlaybackSnapshot): Boolean = snapshot.queue.baseQueue.isNotEmpty()

    fun shouldRetainAfterTaskRemoval(
        snapshot: PlaybackSnapshot,
        exitOnTaskRemoval: Boolean,
        hasActiveCrew: Boolean,
    ): Boolean =
        ServiceRetentionPolicy.shouldRetainAfterTaskRemoval(
            isPlaying =
                snapshot.playWhenReady &&
                    snapshot.phase !is PlaybackPhase.Idle &&
                    snapshot.phase !is PlaybackPhase.Ended,
            exitOnTaskRemoval = exitOnTaskRemoval,
            hasActiveCrew = hasActiveCrew,
        )
}
