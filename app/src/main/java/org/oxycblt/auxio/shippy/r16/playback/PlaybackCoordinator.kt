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
import app.shippy.core.listening.ListeningSessionTracker
import app.shippy.core.listening.ListeningTrackerEvent
import app.shippy.core.listening.ListeningTrackerState
import app.shippy.core.playback.EngineWindowPlanner
import app.shippy.core.playback.PlaybackCheckpoint
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
import app.shippy.core.playback.PlaybackWindowItem
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface R16PlaybackAuthority : PlaybackCommandRouter {
    val snapshots: StateFlow<PlaybackSnapshot>

    fun checkpoint(): PlaybackCheckpoint

    suspend fun restore(
        checkpoint: PlaybackCheckpoint,
        allowResume: Boolean = false,
    ): PlaybackCommandResult

    suspend fun release()
}

/** Inactive R16 playback authority. It is not wired to the production Media3 service yet. */
class PlaybackCoordinator(
    parentScope: CoroutineScope,
    private val engine: PlayerEngine,
    private val sourcePreparer: PlaybackSourcePreparer,
    private val playbackReducer: PlaybackReducer = PlaybackReducer(),
    private val queueReducer: QueueReducer = QueueReducer(),
    private val engineWindowPlanner: EngineWindowPlanner = EngineWindowPlanner(),
    private val traceSink: PlaybackTraceSink = NoOpPlaybackTraceSink,
    private val listeningSessionTracker: ListeningSessionTracker = ListeningSessionTracker(),
    private val listeningSessionSink: ListeningSessionSink = NoOpListeningSessionSink,
    private val playbackClock: PlaybackClock = SystemPlaybackClock,
    private val listeningSessionIdFactory: ListeningSessionIdFactory =
        RandomListeningSessionIdFactory,
    private val listeningTickIntervalMs: Long = DEFAULT_LISTENING_TICK_INTERVAL_MS,
) : R16PlaybackAuthority {
    private val coordinatorJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + coordinatorJob)
    private val events = Channel<CoordinatorEvent>(Channel.UNLIMITED)
    private val mutableSnapshot = MutableStateFlow(PlaybackSnapshot.Empty)
    override val snapshots: StateFlow<PlaybackSnapshot> = mutableSnapshot.asStateFlow()

    private val lifecycleLock = Any()
    private var preparationJob: Job? = null
    private var windowPreparationJob: Job? = null
    private var engineTransactionJob: Job? = null
    private var engineRecoveryTag: PlaybackRequestTag? = null
    private var listeningTickJob: Job? = null
    private var listeningState = ListeningTrackerState()
    private val released = AtomicBoolean(false)
    private val eventLoopJob: Job
    private val observationJob: Job
    private var traceSequence = 0L

    init {
        require(listeningTickIntervalMs > 0) { "Listening tick interval must be positive" }
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

    override suspend fun release() {
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
        listeningTickJob?.cancelAndJoin()
        preparationJob?.cancelAndJoin()
        windowPreparationJob?.cancelAndJoin()
        engineTransactionJob?.cancelAndJoin()
        observationJob.cancelAndJoin()
        eventLoopJob.join()
        finishListeningSession()
        engine.release()
        coordinatorJob.cancelAndJoin()
    }

    private suspend fun eventLoop() {
        for (event in events) {
            when (event) {
                is CoordinatorEvent.Command -> handleCommand(event)
                is CoordinatorEvent.Core -> apply(event.event)
                is CoordinatorEvent.EngineObservation -> handleEngineObservation(event.observation)
                is CoordinatorEvent.Restore -> handleRestore(event)
                is CoordinatorEvent.ListeningTick -> handleListeningTick(event.elapsedRealtimeMs)
            }
        }
    }

    override fun checkpoint(): PlaybackCheckpoint =
        PlaybackCheckpoint.capture(mutableSnapshot.value)

    override suspend fun restore(
        checkpoint: PlaybackCheckpoint,
        allowResume: Boolean,
    ): PlaybackCommandResult {
        val reply = CompletableDeferred<PlaybackCommandResult>()
        val accepted =
            synchronized(lifecycleLock) {
                !released.get() &&
                    events
                        .trySend(CoordinatorEvent.Restore(checkpoint, allowResume, reply))
                        .isSuccess
            }
        if (!accepted) {
            return PlaybackCommandResult.Rejected(PlaybackCommandRejection.COORDINATOR_RELEASED)
        }
        return reply.await()
    }

    private fun handleCommand(event: CoordinatorEvent.Command) {
        val reduction =
            try {
                reduceCommand(mutableSnapshot.value, event.command)
            } catch (_: IllegalArgumentException) {
                CommandReduction.Rejected(PlaybackCommandRejection.INVALID_QUEUE_MUTATION)
            }
        when (reduction) {
            is CommandReduction.Rejected -> {
                recordTrace(
                    PlaybackTraceKind.COMMAND,
                    "${event.command.traceName()}:REJECTED:${reduction.reason}",
                    event.command.traceEntryId(),
                )
                event.reply.complete(PlaybackCommandResult.Rejected(reduction.reason))
            }
            is CommandReduction.Accepted -> {
                reduction.coreEvent?.let(::apply)
                reduction.engineAction?.invoke()
                val current = mutableSnapshot.value
                recordTrace(
                    PlaybackTraceKind.COMMAND,
                    "${event.command.traceName()}:ACCEPTED",
                    event.command.traceEntryId(),
                )
                event.reply.complete(
                    PlaybackCommandResult.Accepted(current.generation, current.queueRevision)
                )
            }
        }
    }

    private fun handleRestore(event: CoordinatorEvent.Restore) {
        apply(PlaybackCoreEvent.RestoreContext(event.checkpoint, event.allowResume))
        val current = mutableSnapshot.value
        recordTrace(
            PlaybackTraceKind.RESTORE,
            if (event.allowResume) "RESTORE_AND_RESUME" else "RESTORE_PAUSED",
            current.queue.currentQueueEntryId,
        )
        event.reply.complete(
            PlaybackCommandResult.Accepted(current.generation, current.queueRevision)
        )
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
        val before = mutableSnapshot.value
        val reduction = playbackReducer.reduce(before, event)
        mutableSnapshot.value = reduction.snapshot
        engineRecoveryTag =
            engineRecoveryTag?.takeIf { recovery ->
                recovery.generation == reduction.snapshot.generation &&
                    recovery.queueRevision == reduction.snapshot.queueRevision &&
                    recovery.queueEntryId == reduction.snapshot.queue.currentQueueEntryId
            }
        recordTrace(
            PlaybackTraceKind.CORE_EVENT,
            event.traceName(),
            event.traceEntryId(),
            ignored = reduction.snapshot === before,
        )
        reduction.effects.forEach(::runEffect)
    }

    private fun runEffect(effect: PlaybackEffect) {
        recordTrace(PlaybackTraceKind.EFFECT, effect.traceName(), effect.traceEntryId())
        when (effect) {
            is PlaybackEffect.PrepareSource -> prepareSource(effect.tag)
            is PlaybackEffect.PrepareEngineWindow -> prepareEngineWindow(effect.tag)
            is PlaybackEffect.ApplyEngineWindow -> applyEngineWindow(effect)
            is PlaybackEffect.SetEnginePlayWhenReady ->
                scope.launch { engine.setPlayWhenReady(effect.value) }
            is PlaybackEffect.SetEngineRepeat -> scope.launch { engine.setRepeat(effect.mode) }
        }
    }

    private fun prepareSource(
        tag: PlaybackRequestTag,
        excludedStableKeys: Set<String> = emptySet(),
    ) {
        preparationJob?.cancel()
        windowPreparationJob?.cancel()
        val entry =
            mutableSnapshot.value.queue.baseQueue.singleOrNull { it.id == tag.queueEntryId }
                ?: return
        preparationJob =
            scope.launch {
                var result: PlaybackPreparationResult =
                    PlaybackPreparationResult.Unavailable(
                        PlaybackError("SOURCE_PREPARATION_FAILED", retryable = true)
                    )
                for (attempt in 1..MAX_CURRENT_SOURCE_ATTEMPTS) {
                    result =
                        try {
                            sourcePreparer.prepare(
                                PlaybackPreparationRequest(
                                    tag,
                                    entry.recordingId,
                                    attempt,
                                    excludedStableKeys,
                                )
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            PlaybackPreparationResult.Unavailable(
                                PlaybackError("SOURCE_PREPARATION_FAILED", retryable = true)
                            )
                        }
                    if (result is PlaybackPreparationResult.Ready) break
                    val unavailable = result as PlaybackPreparationResult.Unavailable
                    if (!unavailable.error.retryable || attempt == MAX_CURRENT_SOURCE_ATTEMPTS)
                        break
                    events.send(
                        CoordinatorEvent.Core(
                            PlaybackCoreEvent.SourceRecoveryStarted(
                                tag,
                                attempt = attempt + 1,
                                error = unavailable.error,
                            )
                        )
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

    private fun prepareEngineWindow(tag: PlaybackRequestTag) {
        windowPreparationJob?.cancel()
        val snapshot = mutableSnapshot.value
        if (
            snapshot.generation != tag.generation ||
                snapshot.queueRevision != tag.queueRevision ||
                snapshot.committedQueueEntryId != tag.queueEntryId
        ) {
            return
        }
        val currentSource = snapshot.resolvedSources[tag.queueEntryId] ?: return
        val plan = engineWindowPlanner.plan(snapshot.queue, tag.queueEntryId, snapshot.repeatMode)
        val entries = snapshot.queue.baseQueue
        windowPreparationJob =
            scope.launch {
                val prepared = mutableMapOf(tag.queueEntryId to currentSource)
                for (entryId in plan.nextEntryIds) {
                    val entry = entries.firstOrNull { it.id == entryId } ?: break
                    when (
                        val result =
                            prepareWindowSource(
                                PlaybackRequestTag(tag.generation, tag.queueRevision, entryId),
                                entry.recordingId,
                            )
                    ) {
                        is PlaybackPreparationResult.Ready -> prepared[entryId] = result.source
                        is PlaybackPreparationResult.Unavailable -> break
                    }
                }
                for (entryId in plan.previousEntryIds) {
                    val entry = entries.firstOrNull { it.id == entryId } ?: continue
                    val result =
                        prepareWindowSource(
                            PlaybackRequestTag(tag.generation, tag.queueRevision, entryId),
                            entry.recordingId,
                        )
                    if (result is PlaybackPreparationResult.Ready) {
                        prepared[entryId] = result.source
                    }
                }
                val items =
                    plan.orderedEntryIds.mapNotNull { entryId ->
                        prepared[entryId]?.let { PlaybackWindowItem(entryId, it) }
                    }
                events.send(CoordinatorEvent.Core(PlaybackCoreEvent.WindowPrepared(tag, items)))
            }
    }

    private suspend fun prepareWindowSource(
        tag: PlaybackRequestTag,
        recordingId: app.shippy.core.identity.RecordingId,
    ): PlaybackPreparationResult =
        try {
            sourcePreparer.prepare(PlaybackPreparationRequest(tag, recordingId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PlaybackPreparationResult.Unavailable(
                PlaybackError("SOURCE_PREPARATION_FAILED", retryable = true)
            )
        }

    private fun applyEngineWindow(effect: PlaybackEffect.ApplyEngineWindow) {
        engineTransactionJob?.cancel()
        val transaction =
            PlayerTransaction(
                tag = effect.tag,
                expectedCurrentEntryId = effect.tag.queueEntryId,
                window = effect.items.map { PreparedEngineItem(it.queueEntryId, it.source) },
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
        val before = mutableSnapshot.value
        if (observation is PlayerObservation.Failed && recoverEngineSource(observation, before)) {
            updateListeningSession(observation, before, mutableSnapshot.value)
            return
        }
        val coreEvent =
            when (observation) {
                is PlayerObservation.CurrentItemCommitted ->
                    if (observation.automaticTransition) {
                        PlaybackCoreEvent.EngineAdvanced(observation.tag)
                    } else {
                        PlaybackCoreEvent.EngineCommitted(observation.tag)
                    }
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
        updateListeningSession(observation, before, mutableSnapshot.value)
    }

    private fun recoverEngineSource(
        observation: PlayerObservation.Failed,
        snapshot: PlaybackSnapshot,
    ): Boolean {
        if (!observation.error.retryable || snapshot.generation != observation.generation) {
            return false
        }
        val pending =
            snapshot.expectedEngineCommit?.takeIf { it.queueEntryId == observation.queueEntryId }
        val tag =
            pending
                ?: if (snapshot.committedQueueEntryId == observation.queueEntryId) {
                    PlaybackRequestTag(
                        snapshot.generation,
                        snapshot.queueRevision,
                        observation.queueEntryId,
                    )
                } else {
                    return false
                }
        if (engineRecoveryTag == tag) return false
        apply(PlaybackCoreEvent.SourceRecoveryStarted(tag, attempt = 1, observation.error))
        if (mutableSnapshot.value.phase !is app.shippy.core.playback.PlaybackPhase.Recovering) {
            return false
        }
        engineRecoveryTag = tag
        val failedSource = snapshot.resolvedSources[observation.queueEntryId]
        prepareSource(
            tag,
            excludedStableKeys =
                if (failedSource?.mediaAssetId != null) setOf(failedSource.stableKey)
                else emptySet(),
        )
        return true
    }

    private fun updateListeningSession(
        observation: PlayerObservation,
        before: PlaybackSnapshot,
        after: PlaybackSnapshot,
    ) {
        when (observation) {
            is PlayerObservation.CurrentItemCommitted -> {
                if (
                    after.committedQueueEntryId != observation.tag.queueEntryId ||
                        (before.expectedEngineCommit != observation.tag &&
                            before.committedQueueEntryId == observation.tag.queueEntryId)
                ) {
                    return
                }
                val entry =
                    after.queue.baseQueue.singleOrNull { it.id == observation.tag.queueEntryId }
                        ?: return
                advanceListening(
                    ListeningTrackerEvent.Commit(
                        sessionId = listeningSessionIdFactory.create(),
                        queueEntryId = entry.id,
                        recordingId = entry.recordingId,
                        sourceReferenceId = after.resolvedSources[entry.id]?.sourceReferenceId,
                        startedAtWallClock = playbackClock.wallClock(),
                        elapsedRealtimeMs = playbackClock.elapsedRealtimeMs(),
                        chosenByUser = true,
                    )
                )
            }
            is PlayerObservation.PhaseChanged -> {
                if (
                    after.generation != observation.generation ||
                        after.committedQueueEntryId != observation.queueEntryId
                ) {
                    return
                }
                if (observation.phase == app.shippy.core.playback.CommittedEnginePhase.ENDED) {
                    finishListeningSession(observation.queueEntryId)
                } else {
                    setListeningAudible(
                        observation.queueEntryId,
                        observation.phase == app.shippy.core.playback.CommittedEnginePhase.PLAYING,
                    )
                }
            }
            is PlayerObservation.PositionChanged -> {
                if (
                    observation.discontinuity &&
                        after.generation == observation.generation &&
                        after.committedQueueEntryId == observation.queueEntryId
                ) {
                    advanceListening(
                        ListeningTrackerEvent.Seeked(
                            observation.queueEntryId,
                            playbackClock.elapsedRealtimeMs(),
                            observation.position.playbackSpeed,
                        )
                    )
                }
            }
            is PlayerObservation.Failed -> {
                if (
                    after.generation == observation.generation &&
                        after.committedQueueEntryId == observation.queueEntryId
                ) {
                    setListeningAudible(observation.queueEntryId, audible = false)
                }
            }
        }
    }

    private fun setListeningAudible(queueEntryId: QueueEntryId, audible: Boolean) {
        advanceListening(
            ListeningTrackerEvent.AudibleChanged(
                queueEntryId,
                audible,
                playbackClock.elapsedRealtimeMs(),
                mutableSnapshot.value.position.playbackSpeed,
            )
        )
        if (audible) startListeningTicks() else stopListeningTicks()
    }

    private fun startListeningTicks() {
        if (listeningTickJob?.isActive == true) return
        listeningTickJob =
            scope.launch {
                while (true) {
                    delay(listeningTickIntervalMs)
                    events.send(CoordinatorEvent.ListeningTick(playbackClock.elapsedRealtimeMs()))
                }
            }
    }

    private fun stopListeningTicks() {
        listeningTickJob?.cancel()
        listeningTickJob = null
    }

    private fun handleListeningTick(elapsedRealtimeMs: Long) {
        if (mutableSnapshot.value.phase !is app.shippy.core.playback.PlaybackPhase.Playing) return
        advanceListening(ListeningTrackerEvent.Tick(elapsedRealtimeMs))
    }

    private fun finishListeningSession(
        queueEntryId: QueueEntryId? = listeningState.active?.queueEntryId
    ) {
        stopListeningTicks()
        queueEntryId ?: return
        advanceListening(
            ListeningTrackerEvent.Finish(queueEntryId, playbackClock.elapsedRealtimeMs())
        )
    }

    private fun advanceListening(event: ListeningTrackerEvent) {
        val transition = listeningSessionTracker.reduce(listeningState, event)
        listeningState = transition.state
        transition.finalized?.let(listeningSessionSink::offer)
    }

    private fun recordTrace(
        kind: PlaybackTraceKind,
        detail: String,
        queueEntryId: QueueEntryId?,
        ignored: Boolean = false,
    ) {
        val snapshot = mutableSnapshot.value
        traceSink.record(
            PlaybackTraceEvent(
                sequence = traceSequence++,
                generation = snapshot.generation,
                queueRevision = snapshot.queueRevision,
                kind = kind,
                detail = detail,
                queueEntryId = queueEntryId,
                ignored = ignored,
            )
        )
    }
}

private fun PlaybackCommand.traceName(): String =
    when (this) {
        is PlaybackCommand.PlayContext -> "PLAY_CONTEXT"
        PlaybackCommand.Play -> "PLAY"
        PlaybackCommand.Pause -> "PAUSE"
        PlaybackCommand.Next -> "NEXT"
        PlaybackCommand.Previous -> "PREVIOUS"
        is PlaybackCommand.GoTo -> "GO_TO"
        is PlaybackCommand.SeekTo -> "SEEK_TO"
        is PlaybackCommand.SetShuffle -> "SET_SHUFFLE"
        is PlaybackCommand.SetRepeat -> "SET_REPEAT"
        is PlaybackCommand.AddNext -> "ADD_NEXT"
        is PlaybackCommand.AddToEnd -> "ADD_TO_END"
        is PlaybackCommand.Move -> "MOVE"
        is PlaybackCommand.Remove -> "REMOVE"
        PlaybackCommand.Clear -> "CLEAR"
    }

private fun PlaybackCommand.traceEntryId(): QueueEntryId? =
    when (this) {
        is PlaybackCommand.PlayContext -> selectedEntryId
        is PlaybackCommand.GoTo -> queueEntryId
        is PlaybackCommand.Move -> entryId
        else -> null
    }

private fun PlaybackCoreEvent.traceName(): String =
    when (this) {
        is PlaybackCoreEvent.ReplaceContext -> "REPLACE_CONTEXT"
        is PlaybackCoreEvent.QueueChanged -> "QUEUE_CHANGED"
        is PlaybackCoreEvent.SelectEntry -> "SELECT_ENTRY"
        is PlaybackCoreEvent.RestoreContext -> "RESTORE_CONTEXT"
        is PlaybackCoreEvent.SourceReady -> "SOURCE_READY"
        is PlaybackCoreEvent.SourceUnavailable -> "SOURCE_UNAVAILABLE"
        is PlaybackCoreEvent.SourceRecoveryStarted -> "SOURCE_RECOVERY_STARTED"
        is PlaybackCoreEvent.WindowPrepared -> "WINDOW_PREPARED"
        is PlaybackCoreEvent.EngineCommitted -> "ENGINE_COMMITTED"
        is PlaybackCoreEvent.EngineAdvanced -> "ENGINE_ADVANCED"
        is PlaybackCoreEvent.EnginePhaseChanged -> "ENGINE_PHASE_CHANGED"
        is PlaybackCoreEvent.EnginePosition -> "ENGINE_POSITION"
        is PlaybackCoreEvent.EngineFailed -> "ENGINE_FAILED"
        is PlaybackCoreEvent.SetPlayWhenReady -> "SET_PLAY_WHEN_READY"
        is PlaybackCoreEvent.SetRepeat -> "SET_REPEAT"
    }

private fun PlaybackCoreEvent.traceEntryId(): QueueEntryId? =
    when (this) {
        is PlaybackCoreEvent.ReplaceContext -> selectedEntryId
        is PlaybackCoreEvent.QueueChanged -> queue.currentQueueEntryId
        is PlaybackCoreEvent.SelectEntry -> queueEntryId
        is PlaybackCoreEvent.RestoreContext -> checkpoint.queue.currentQueueEntryId
        is PlaybackCoreEvent.SourceReady -> tag.queueEntryId
        is PlaybackCoreEvent.SourceUnavailable -> tag.queueEntryId
        is PlaybackCoreEvent.SourceRecoveryStarted -> tag.queueEntryId
        is PlaybackCoreEvent.WindowPrepared -> tag.queueEntryId
        is PlaybackCoreEvent.EngineCommitted -> tag.queueEntryId
        is PlaybackCoreEvent.EngineAdvanced -> tag.queueEntryId
        is PlaybackCoreEvent.EnginePhaseChanged -> queueEntryId
        is PlaybackCoreEvent.EnginePosition -> queueEntryId
        is PlaybackCoreEvent.EngineFailed -> queueEntryId
        is PlaybackCoreEvent.SetPlayWhenReady,
        is PlaybackCoreEvent.SetRepeat -> null
    }

private fun PlaybackEffect.traceName(): String =
    when (this) {
        is PlaybackEffect.PrepareSource -> "PREPARE_SOURCE"
        is PlaybackEffect.PrepareEngineWindow -> "PREPARE_ENGINE_WINDOW"
        is PlaybackEffect.ApplyEngineWindow -> "APPLY_ENGINE_WINDOW"
        is PlaybackEffect.SetEnginePlayWhenReady -> "SET_ENGINE_PLAY_WHEN_READY"
        is PlaybackEffect.SetEngineRepeat -> "SET_ENGINE_REPEAT"
    }

private fun PlaybackEffect.traceEntryId(): QueueEntryId? =
    when (this) {
        is PlaybackEffect.PrepareSource -> tag.queueEntryId
        is PlaybackEffect.PrepareEngineWindow -> tag.queueEntryId
        is PlaybackEffect.ApplyEngineWindow -> tag.queueEntryId
        is PlaybackEffect.SetEnginePlayWhenReady,
        is PlaybackEffect.SetEngineRepeat -> null
    }

private sealed interface CoordinatorEvent {
    data class Command(
        val command: PlaybackCommand,
        val reply: CompletableDeferred<PlaybackCommandResult>,
    ) : CoordinatorEvent

    data class Core(val event: PlaybackCoreEvent) : CoordinatorEvent

    data class EngineObservation(val observation: PlayerObservation) : CoordinatorEvent

    data class ListeningTick(val elapsedRealtimeMs: Long) : CoordinatorEvent

    data class Restore(
        val checkpoint: PlaybackCheckpoint,
        val allowResume: Boolean,
        val reply: CompletableDeferred<PlaybackCommandResult>,
    ) : CoordinatorEvent
}

private sealed interface CommandReduction {
    data class Accepted(val coreEvent: PlaybackCoreEvent?, val engineAction: (() -> Unit)? = null) :
        CommandReduction

    data class Rejected(val reason: PlaybackCommandRejection) : CommandReduction
}

private const val MAX_CURRENT_SOURCE_ATTEMPTS = 2
private const val DEFAULT_LISTENING_TICK_INTERVAL_MS = 1_000L
