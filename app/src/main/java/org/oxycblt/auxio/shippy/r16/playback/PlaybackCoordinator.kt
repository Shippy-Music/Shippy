/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackCoordinator.kt is part of Auxio.
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

import app.shippy.core.identity.QueueEntryId
import app.shippy.core.playback.PlaybackCommand
import app.shippy.core.playback.PlaybackCommandRejection
import app.shippy.core.playback.PlaybackCommandResult
import app.shippy.core.playback.PlaybackCommandRouter
import app.shippy.core.playback.PlaybackCoreEvent
import app.shippy.core.playback.PlaybackEffect
import app.shippy.core.playback.PlaybackError
import app.shippy.core.playback.PlaybackReducer
import app.shippy.core.playback.PlaybackRequestTag
import app.shippy.core.playback.PlaybackSnapshot
import app.shippy.core.queue.QueueMutationResult
import app.shippy.core.queue.QueueReducer
import app.shippy.core.queue.QueueState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Inactive R16 playback authority. It is not wired to the production Media3 service yet. */
class PlaybackCoordinator(
    parentScope: CoroutineScope,
    private val engine: PlayerEngine,
    private val sourcePreparer: PlaybackSourcePreparer,
    private val playbackReducer: PlaybackReducer = PlaybackReducer(),
    private val queueReducer: QueueReducer = QueueReducer(),
) : PlaybackCommandRouter {
    private val coordinatorJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + coordinatorJob)
    private val events = Channel<CoordinatorEvent>(Channel.UNLIMITED)
    private val mutableSnapshot = MutableStateFlow(PlaybackSnapshot.Empty)
    val snapshots: StateFlow<PlaybackSnapshot> = mutableSnapshot.asStateFlow()

    private val lifecycleLock = Any()
    private var preparationJob: Job? = null
    private var engineTransactionJob: Job? = null
    private val released = AtomicBoolean(false)
    private val eventLoopJob: Job
    private val observationJob: Job

    init {
        eventLoopJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { eventLoop() }
        observationJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                engine.observations.collect { observation ->
                    events.send(CoordinatorEvent.EngineObservation(observation))
                }
            }
    }

    override suspend fun dispatch(command: PlaybackCommand): PlaybackCommandResult {
        val reply = CompletableDeferred<PlaybackCommandResult>()
        val accepted =
            synchronized(lifecycleLock) {
                !released.get() &&
                    events.trySend(CoordinatorEvent.Command(command, reply)).isSuccess
            }
        if (!accepted) {
            return PlaybackCommandResult.Rejected(PlaybackCommandRejection.COORDINATOR_RELEASED)
        }
        return reply.await()
    }

    suspend fun release() {
        val firstRelease =
            synchronized(lifecycleLock) {
                if (!released.compareAndSet(false, true)) {
                    false
                } else {
                    events.close()
                    true
                }
            }
        if (!firstRelease) return
        eventLoopJob.join()
        preparationJob?.cancelAndJoin()
        engineTransactionJob?.cancelAndJoin()
        engine.release()
        observationJob.cancelAndJoin()
        coordinatorJob.cancelAndJoin()
    }

    private suspend fun eventLoop() {
        for (event in events) {
            when (event) {
                is CoordinatorEvent.Command -> handleCommand(event)
                is CoordinatorEvent.Core -> apply(event.event)
                is CoordinatorEvent.EngineObservation -> handleEngineObservation(event.observation)
            }
        }
    }

    private fun handleCommand(event: CoordinatorEvent.Command) {
        val reduction =
            try {
                reduceCommand(mutableSnapshot.value, event.command)
            } catch (_: IllegalArgumentException) {
                CommandReduction.Rejected(PlaybackCommandRejection.INVALID_QUEUE_MUTATION)
            }
        when (reduction) {
            is CommandReduction.Rejected ->
                event.reply.complete(PlaybackCommandResult.Rejected(reduction.reason))
            is CommandReduction.Accepted -> {
                reduction.coreEvent?.let(::apply)
                reduction.engineAction?.invoke()
                val current = mutableSnapshot.value
                event.reply.complete(
                    PlaybackCommandResult.Accepted(current.generation, current.queueRevision)
                )
            }
        }
    }

    private fun reduceCommand(
        snapshot: PlaybackSnapshot,
        command: PlaybackCommand,
    ): CommandReduction =
        when (command) {
            is PlaybackCommand.PlayContext -> {
                if (command.entries.none { it.id == command.selectedEntryId }) {
                    CommandReduction.Rejected(PlaybackCommandRejection.ENTRY_NOT_FOUND)
                } else {
                    val queue =
                        queueReducer.replace(
                            entries = command.entries,
                            currentQueueEntryId = command.selectedEntryId,
                            shuffleSeed = command.shuffleSeed,
                        )
                    CommandReduction.Accepted(
                        PlaybackCoreEvent.ReplaceContext(queue, command.selectedEntryId, true)
                    )
                }
            }
            PlaybackCommand.Play ->
                if (snapshot.queue.baseQueue.isEmpty()) {
                    CommandReduction.Rejected(PlaybackCommandRejection.EMPTY_QUEUE)
                } else {
                    CommandReduction.Accepted(PlaybackCoreEvent.SetPlayWhenReady(true))
                }
            PlaybackCommand.Pause ->
                CommandReduction.Accepted(PlaybackCoreEvent.SetPlayWhenReady(false))
            is PlaybackCommand.GoTo -> select(snapshot, command.queueEntryId)
            is PlaybackCommand.SeekTo -> {
                val current = snapshot.committedQueueEntryId
                if (current == null) {
                    CommandReduction.Rejected(PlaybackCommandRejection.EMPTY_QUEUE)
                } else {
                    CommandReduction.Accepted(
                        coreEvent = null,
                        engineAction = { scope.launch { engine.seek(current, command.positionMs) } },
                    )
                }
            }
            is PlaybackCommand.SetRepeat ->
                CommandReduction.Accepted(PlaybackCoreEvent.SetRepeat(command.mode))
            is PlaybackCommand.SetShuffle ->
                queueMutation(
                    queueReducer.setShuffle(snapshot.queue, command.enabled, command.seed)
                )
            is PlaybackCommand.AddNext ->
                queueMutation(queueReducer.addNext(snapshot.queue, command.entries))
            is PlaybackCommand.AddToEnd ->
                queueMutation(queueReducer.addToEnd(snapshot.queue, command.entries))
            is PlaybackCommand.Move ->
                when (
                    val moved = queueReducer.move(snapshot.queue, command.entryId, command.anchor)
                ) {
                    is QueueMutationResult.Applied -> queueMutation(moved.state)
                    is QueueMutationResult.Rejected ->
                        CommandReduction.Rejected(PlaybackCommandRejection.INVALID_QUEUE_MUTATION)
                }
            is PlaybackCommand.Remove ->
                queueMutation(queueReducer.remove(snapshot.queue, command.entryIds))
            PlaybackCommand.Clear ->
                CommandReduction.Accepted(
                    PlaybackCoreEvent.ReplaceContext(QueueState.Empty, null, false)
                )
            PlaybackCommand.Next -> adjacent(snapshot, forward = true)
            PlaybackCommand.Previous -> adjacent(snapshot, forward = false)
        }

    private fun queueMutation(queue: QueueState): CommandReduction =
        CommandReduction.Accepted(PlaybackCoreEvent.QueueChanged(queue))

    private fun select(snapshot: PlaybackSnapshot, entryId: QueueEntryId): CommandReduction {
        if (snapshot.queue.baseQueue.none { it.id == entryId }) {
            return CommandReduction.Rejected(PlaybackCommandRejection.ENTRY_NOT_FOUND)
        }
        return CommandReduction.Accepted(PlaybackCoreEvent.SelectEntry(entryId))
    }

    private fun adjacent(snapshot: PlaybackSnapshot, forward: Boolean): CommandReduction {
        val order = snapshot.queue.traversalOrder
        val current =
            snapshot.queue.currentQueueEntryId
                ?: return CommandReduction.Rejected(PlaybackCommandRejection.EMPTY_QUEUE)
        val currentIndex = order.indexOf(current)
        val targetIndex = if (forward) currentIndex + 1 else currentIndex - 1
        val target =
            order.getOrNull(targetIndex)
                ?: if (snapshot.repeatMode == app.shippy.core.playback.RepeatMode.ALL) {
                    if (forward) order.firstOrNull() else order.lastOrNull()
                } else {
                    null
                }
        return when {
            snapshot.repeatMode == app.shippy.core.playback.RepeatMode.ONE ->
                CommandReduction.Accepted(
                    coreEvent = null,
                    engineAction = { scope.launch { engine.seek(current, 0) } },
                )
            target != null -> select(snapshot, target)
            else -> CommandReduction.Accepted(PlaybackCoreEvent.SetPlayWhenReady(false))
        }
    }

    private fun apply(event: PlaybackCoreEvent) {
        val reduction = playbackReducer.reduce(mutableSnapshot.value, event)
        mutableSnapshot.value = reduction.snapshot
        reduction.effects.forEach(::runEffect)
    }

    private fun runEffect(effect: PlaybackEffect) {
        when (effect) {
            is PlaybackEffect.PrepareSource -> prepareSource(effect.tag)
            is PlaybackEffect.ApplyEngineWindow -> applyEngineWindow(effect)
            is PlaybackEffect.SetEnginePlayWhenReady ->
                scope.launch { engine.setPlayWhenReady(effect.value) }
            is PlaybackEffect.SetEngineRepeat -> scope.launch { engine.setRepeat(effect.mode) }
        }
    }

    private fun prepareSource(tag: PlaybackRequestTag) {
        preparationJob?.cancel()
        val entry =
            mutableSnapshot.value.queue.baseQueue.singleOrNull { it.id == tag.queueEntryId }
                ?: return
        preparationJob =
            scope.launch {
                val result =
                    try {
                        sourcePreparer.prepare(PlaybackPreparationRequest(tag, entry.recordingId))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        PlaybackPreparationResult.Unavailable(
                            PlaybackError("SOURCE_PREPARATION_FAILED", retryable = true)
                        )
                    }
                val coreEvent =
                    when (result) {
                        is PlaybackPreparationResult.Ready ->
                            PlaybackCoreEvent.SourceReady(tag, result.source)
                        is PlaybackPreparationResult.Unavailable ->
                            PlaybackCoreEvent.SourceUnavailable(tag, result.error)
                    }
                events.send(CoordinatorEvent.Core(coreEvent))
            }
    }

    private fun applyEngineWindow(effect: PlaybackEffect.ApplyEngineWindow) {
        engineTransactionJob?.cancel()
        val transaction =
            PlayerTransaction(
                tag = effect.tag,
                expectedCurrentEntryId = effect.tag.queueEntryId,
                window = listOf(PreparedEngineItem(effect.tag.queueEntryId, effect.source)),
                startPositionMs = mutableSnapshot.value.position.positionMs,
                playWhenReady = mutableSnapshot.value.playWhenReady,
            )
        engineTransactionJob =
            scope.launch {
                try {
                    engine.apply(transaction)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    events.send(
                        CoordinatorEvent.Core(
                            PlaybackCoreEvent.EngineFailed(
                                effect.tag.generation,
                                effect.tag.queueEntryId,
                                PlaybackError("ENGINE_TRANSACTION_FAILED", retryable = true),
                            )
                        )
                    )
                }
            }
    }

    private fun handleEngineObservation(observation: PlayerObservation) {
        val coreEvent =
            when (observation) {
                is PlayerObservation.CurrentItemCommitted ->
                    PlaybackCoreEvent.EngineCommitted(observation.tag)
                is PlayerObservation.PhaseChanged ->
                    PlaybackCoreEvent.EnginePhaseChanged(
                        observation.generation,
                        observation.queueEntryId,
                        observation.phase,
                    )
                is PlayerObservation.PositionChanged ->
                    PlaybackCoreEvent.EnginePosition(
                        observation.generation,
                        observation.queueEntryId,
                        observation.position,
                    )
                is PlayerObservation.Failed ->
                    PlaybackCoreEvent.EngineFailed(
                        observation.generation,
                        observation.queueEntryId,
                        observation.error,
                    )
            }
        apply(coreEvent)
    }
}

private sealed interface CoordinatorEvent {
    data class Command(
        val command: PlaybackCommand,
        val reply: CompletableDeferred<PlaybackCommandResult>,
    ) : CoordinatorEvent

    data class Core(val event: PlaybackCoreEvent) : CoordinatorEvent

    data class EngineObservation(val observation: PlayerObservation) : CoordinatorEvent
}

private sealed interface CommandReduction {
    data class Accepted(val coreEvent: PlaybackCoreEvent?, val engineAction: (() -> Unit)? = null) :
        CommandReduction

    data class Rejected(val reason: PlaybackCommandRejection) : CommandReduction
}
