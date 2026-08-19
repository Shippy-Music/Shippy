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

/** One queue occurrence plus its canonical, source-neutral presentation. */
data class R16SystemQueueItem(
    val queueEntryId: QueueEntryId,
    val recordingId: RecordingId,
    val presentation: R16RecordingPresentation?,
)

/**
 * Shared derived state for MediaSession, notification, widgets, Android Auto, and system controls.
 * It is rebuilt from [PlaybackSnapshot] and never becomes a second queue authority.
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
    fun project(
        snapshot: PlaybackSnapshot,
        presentations: Map<RecordingId, R16RecordingPresentation>,
    ): R16SystemPlaybackState {
        val entriesById = snapshot.queue.baseQueue.associateBy { it.id }
        val traversal =
            snapshot.queue.traversalOrder.map { queueEntryId ->
                val entry = checkNotNull(entriesById[queueEntryId])
                R16SystemQueueItem(
                    queueEntryId = queueEntryId,
                    recordingId = entry.recordingId,
                    presentation = presentations[entry.recordingId],
                )
            }
        return R16SystemPlaybackState(snapshot, traversal)
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

    val states: StateFlow<R16SystemPlaybackState> =
        endpoint.snapshots
            .flatMapLatest { snapshot ->
                val recordingIds = snapshot.queue.baseQueue.mapTo(mutableSetOf()) { it.recordingId }
                presentations.observe(recordingIds).map { metadata ->
                    R16SystemPlaybackProjector.project(snapshot, metadata)
                }
            }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                R16SystemPlaybackProjector.project(endpoint.snapshots.value, emptyMap()),
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
