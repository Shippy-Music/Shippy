/*
 * Copyright (c) 2026 Auxio Project
 * R16SystemPlaybackBridge.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback.system

import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.playback.PlaybackCommand
import app.shippy.core.playback.PlaybackCommandRejection
import app.shippy.core.playback.PlaybackCommandResult
import app.shippy.core.playback.PlaybackCommandRouter
import app.shippy.core.playback.PlaybackPhase
import app.shippy.core.playback.PlaybackSnapshot
import app.shippy.core.playback.RepeatMode
import app.shippy.core.queue.QueueEntry
import app.shippy.core.queue.QueueState
import app.shippy.core.queue.ShuffleState
import app.shippy.data.playback.R16PlaybackPresentationRepository
import app.shippy.data.playback.R16RecordingPresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.oxycblt.auxio.shippy.r16.playback.service.R16PlaybackServiceEndpoint
import org.oxycblt.auxio.shippy.r16.playback.service.R16QueuePageEndpoint

/** One queue occurrence plus its canonical, source-neutral presentation. */
data class R16SystemQueueItem(
    val queueEntryId: QueueEntryId,
    val recordingId: RecordingId,
    val presentation: R16RecordingPresentation?,
)

/**
 * Shared derived state for MediaSession, notification, widgets, Android Auto, and system controls.
 * [traversal] is a bounded current/adjacent projection; the full logical queue remains
 * authoritative only in [playback]. It is never a second queue authority.
 */
data class R16SystemPlaybackState(
    val playback: PlaybackSnapshot,
    val traversal: List<R16SystemQueueItem>,
) {
    val selectedQueueEntryId: QueueEntryId?
        get() = playback.queue.currentQueueEntryId

    val committedQueueEntryId: QueueEntryId?
        get() = playback.committedQueueEntryId

    val displayQueueEntryId: QueueEntryId?
        get() = committedQueueEntryId ?: selectedQueueEntryId

    val displayItem: R16SystemQueueItem?
        get() = traversal.firstOrNull { it.queueEntryId == displayQueueEntryId }

    /** Index within the bounded system projection, not the full logical queue. */
    val activeQueueIndex: Int
        get() = traversal.indexOfFirst { it.queueEntryId == displayQueueEntryId }

    val hasQueue: Boolean
        get() = traversal.isNotEmpty()

    val isPlaying: Boolean
        get() = playback.phase is PlaybackPhase.Playing

    val isShuffled: Boolean
        get() = playback.queue.shuffle is ShuffleState.On

    companion object {
        val Empty = R16SystemPlaybackState(PlaybackSnapshot.Empty, emptyList())
    }
}

object R16SystemPlaybackProjector {
    /**
     * The logical queue can be large, but system surfaces only need canonical presentation for what
     * may be shown or prepared now. The coordinator already owns this bounded window.
     */
    internal fun presentationRecordingIds(
        snapshot: PlaybackSnapshot,
        index: QueueIndex = QueueIndex(snapshot.queue),
    ): Set<RecordingId> =
        projectionEntryIds(snapshot)
            .asSequence()
            .mapNotNull { index.entry(it)?.recordingId }
            .toSet()

    /** Existing coordinator window plus the visible committed/current occurrence. */
    internal fun projectionEntryIds(snapshot: PlaybackSnapshot): List<QueueEntryId> =
        (snapshot.engineWindow +
                listOfNotNull(snapshot.committedQueueEntryId ?: snapshot.queue.currentQueueEntryId))
            .distinct()

    /** Immutable lookup built once for a queue revision; projection itself stays window-sized. */
    internal class QueueIndex(queue: QueueState) {
        val queue: QueueState = queue
        private val entriesById = queue.baseQueue.associateBy(QueueEntry::id)

        fun entry(id: QueueEntryId): QueueEntry? = entriesById[id]
    }

    fun project(
        snapshot: PlaybackSnapshot,
        presentations: Map<RecordingId, R16RecordingPresentation>,
    ): R16SystemPlaybackState = projectCached(snapshot, presentations, QueueIndex(snapshot.queue))

    internal fun projectCached(
        snapshot: PlaybackSnapshot,
        presentations: Map<RecordingId, R16RecordingPresentation>,
        index: QueueIndex,
    ): R16SystemPlaybackState {
        val traversal =
            projectionEntryIds(snapshot).map { queueEntryId ->
                val entry = checkNotNull(index.entry(queueEntryId))
                R16SystemQueueItem(
                    queueEntryId = queueEntryId,
                    recordingId = entry.recordingId,
                    presentation = presentations[entry.recordingId],
                )
            }
        return R16SystemPlaybackState(snapshot, traversal)
    }
}

/** Retains the one light occurrence lookup while an immutable [QueueState] remains current. */
internal class R16SystemProjectionIndexCache {
    private var index: R16SystemPlaybackProjector.QueueIndex? = null

    fun forSnapshot(snapshot: PlaybackSnapshot): R16SystemPlaybackProjector.QueueIndex {
        val current = index
        return if (current != null && current.queue === snapshot.queue) {
            current
        } else {
            R16SystemPlaybackProjector.QueueIndex(snapshot.queue).also { index = it }
        }
    }
}

/** Inactive shared read/command seam for all future R16 Android system surfaces. */
@OptIn(ExperimentalCoroutinesApi::class)
class R16SystemPlaybackBridge(
    parentScope: CoroutineScope,
    private val endpoint: R16PlaybackServiceEndpoint,
    presentations: R16PlaybackPresentationRepository,
) : PlaybackCommandRouter {
    private val bridgeJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + bridgeJob)
    private val projectionIndexCache = R16SystemProjectionIndexCache()

    /** Bounded queue UI reads and exact occurrence commands stay on the service-owned authority. */
    val queueEndpoint = R16QueuePageEndpoint(endpoint.snapshots, endpoint, presentations)

    val states: StateFlow<R16SystemPlaybackState> =
        endpoint.snapshots
            .flatMapLatest { snapshot ->
                val index = projectionIndexCache.forSnapshot(snapshot)
                val recordingIds =
                    R16SystemPlaybackProjector.presentationRecordingIds(snapshot, index)
                presentations.observe(recordingIds).map { metadata ->
                    R16SystemPlaybackProjector.projectCached(snapshot, metadata, index)
                }
            }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                R16SystemPlaybackProjector.projectCached(
                    endpoint.snapshots.value,
                    emptyMap(),
                    projectionIndexCache.forSnapshot(endpoint.snapshots.value),
                ),
            )

    override suspend fun dispatch(command: PlaybackCommand): PlaybackCommandResult =
        endpoint.dispatch(command)

    suspend fun release() {
        bridgeJob.cancelAndJoin()
    }
}

/** Stateless command translation shared by MediaSession, notification, widget, and receivers. */
class R16SystemPlaybackCommands(
    private val states: StateFlow<R16SystemPlaybackState>,
    private val router: PlaybackCommandRouter,
    private val shuffleSeed: () -> Long,
) {
    /**
     * Resumes only the occurrence the caller observed. This keeps Home's Continue action from
     * opening a changed current item after an asynchronous command round trip.
     */
    suspend fun resumeCurrent(
        expectedQueueEntryId: QueueEntryId,
        expectedRecordingId: RecordingId,
    ): R16ResumeCurrentResult {
        val current = states.value.displayItem ?: return R16ResumeCurrentResult.Rejected(null, null)
        if (
            current.queueEntryId != expectedQueueEntryId ||
                current.recordingId != expectedRecordingId
        ) {
            return R16ResumeCurrentResult.Rejected(current.queueEntryId, current.recordingId)
        }
        return when (
            val result =
                router.dispatch(
                    PlaybackCommand.ResumeCurrent(expectedQueueEntryId, expectedRecordingId)
                )
        ) {
            is PlaybackCommandResult.Accepted ->
                R16ResumeCurrentResult.Accepted(
                    queueEntryId = current.queueEntryId,
                    recordingId = current.recordingId,
                    generation = result.generation,
                )
            is PlaybackCommandResult.Rejected ->
                R16ResumeCurrentResult.Rejected(current.queueEntryId, current.recordingId)
        }
    }

    /**
     * Retries only the exact failed occurrence the Now Playing surface displayed. The authority
     * rechecks both identity and phase before dispatching the conditional retry command. The
     * playback authority repeats that check inside its serialized reducer.
     */
    suspend fun retryCurrent(
        expectedQueueEntryId: QueueEntryId,
        expectedRecordingId: RecordingId,
    ): PlaybackCommandResult {
        val state = states.value
        val current = state.displayItem
        val failed = state.playback.phase as? PlaybackPhase.Failed
        if (
            current == null ||
                current.queueEntryId != expectedQueueEntryId ||
                current.recordingId != expectedRecordingId ||
                failed?.entryId != expectedQueueEntryId
        ) {
            return PlaybackCommandResult.Rejected(PlaybackCommandRejection.ENTRY_NOT_FOUND)
        }
        return router.dispatch(
            PlaybackCommand.RetryCurrent(expectedQueueEntryId, expectedRecordingId)
        )
    }

    suspend fun playPause(): PlaybackCommandResult = requireQueueThenDispatch {
        if (states.value.playback.playWhenReady) PlaybackCommand.Pause else PlaybackCommand.Play
    }

    suspend fun play(): PlaybackCommandResult = requireQueueThenDispatch { PlaybackCommand.Play }

    suspend fun pause(): PlaybackCommandResult = requireQueueThenDispatch { PlaybackCommand.Pause }

    suspend fun next(): PlaybackCommandResult = requireQueueThenDispatch { PlaybackCommand.Next }

    suspend fun previous(): PlaybackCommandResult = requireQueueThenDispatch {
        PlaybackCommand.Previous
    }

    suspend fun seekTo(positionMs: Long): PlaybackCommandResult = requireQueueThenDispatch {
        PlaybackCommand.SeekTo(positionMs)
    }

    /** Routes a MediaSession index from the bounded system projection, never a stale full queue. */
    suspend fun goToQueueIndex(index: Long): PlaybackCommandResult {
        val entry =
            index
                .takeIf { it in 0L..Int.MAX_VALUE.toLong() }
                ?.toInt()
                ?.let { states.value.traversal.getOrNull(it) }
        return if (entry == null) {
            PlaybackCommandResult.Rejected(PlaybackCommandRejection.ENTRY_NOT_FOUND)
        } else {
            router.dispatch(PlaybackCommand.GoTo(entry.queueEntryId))
        }
    }

    suspend fun cycleRepeat(): PlaybackCommandResult =
        setRepeat(
            when (states.value.playback.repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
        )

    suspend fun setRepeat(mode: RepeatMode): PlaybackCommandResult = requireQueueThenDispatch {
        PlaybackCommand.SetRepeat(mode)
    }

    suspend fun toggleShuffle(): PlaybackCommandResult = setShuffle(!states.value.isShuffled)

    suspend fun setShuffle(enabled: Boolean): PlaybackCommandResult = requireQueueThenDispatch {
        PlaybackCommand.SetShuffle(enabled, shuffleSeed())
    }

    private suspend fun requireQueueThenDispatch(
        command: () -> PlaybackCommand
    ): PlaybackCommandResult =
        if (states.value.hasQueue) {
            router.dispatch(command())
        } else {
            PlaybackCommandResult.Rejected(PlaybackCommandRejection.EMPTY_QUEUE)
        }
}

/** A small acknowledged result for the identity-checked Home Continue command. */
sealed interface R16ResumeCurrentResult {
    data class Accepted(
        val queueEntryId: QueueEntryId,
        val recordingId: RecordingId,
        val generation: Long,
    ) : R16ResumeCurrentResult

    data class Rejected(
        val currentQueueEntryId: QueueEntryId?,
        val currentRecordingId: RecordingId?,
    ) : R16ResumeCurrentResult
}
