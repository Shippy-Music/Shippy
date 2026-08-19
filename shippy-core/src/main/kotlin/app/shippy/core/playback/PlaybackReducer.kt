/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackReducer.kt is part of Auxio.
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
package app.shippy.core.playback

import app.shippy.core.identity.MediaAssetId
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.queue.QueueState

enum class RepeatMode {
    OFF,
    ONE,
    ALL,
}

data class PlaybackError(val code: String, val retryable: Boolean) {
    init {
        require(code.isNotBlank()) { "Playback error code cannot be blank" }
    }
}

sealed interface PlaybackPhase {
    data object Idle : PlaybackPhase

    data class Preparing(val entryId: QueueEntryId) : PlaybackPhase

    data class Buffering(val entryId: QueueEntryId) : PlaybackPhase

    data class Ready(val entryId: QueueEntryId) : PlaybackPhase

    data class Playing(val entryId: QueueEntryId) : PlaybackPhase

    data class Paused(val entryId: QueueEntryId) : PlaybackPhase

    data class Recovering(val entryId: QueueEntryId, val attempt: Int) : PlaybackPhase {
        init {
            require(attempt > 0) { "Recovery attempt must be positive" }
        }
    }

    data class Failed(val entryId: QueueEntryId?, val error: PlaybackError) : PlaybackPhase

    data object Ended : PlaybackPhase
}

data class PositionAnchor(
    val positionMs: Long,
    val sampledElapsedRealtimeMs: Long,
    val playbackSpeed: Double = 1.0,
    val advancing: Boolean = false,
) {
    init {
        require(positionMs >= 0) { "Playback position cannot be negative" }
        require(sampledElapsedRealtimeMs >= 0) { "Monotonic sample cannot be negative" }
        require(playbackSpeed.isFinite() && playbackSpeed > 0) {
            "Playback speed must be finite and positive"
        }
    }
}

data class PlaybackSourceHandle(
    val stableKey: String,
    val sourceReferenceId: SourceReferenceId?,
    val mediaAssetId: MediaAssetId?,
) {
    init {
        require(stableKey.isNotBlank()) { "Playback source handle cannot be blank" }
        require(sourceReferenceId != null || mediaAssetId != null) {
            "Playback source handle requires a source or asset identity"
        }
    }
}

data class PlaybackRequestTag(
    val generation: Long,
    val queueRevision: Long,
    val queueEntryId: QueueEntryId,
) {
    init {
        require(generation >= 0) { "Playback generation cannot be negative" }
        require(queueRevision >= 0) { "Queue revision cannot be negative" }
    }
}

data class PlaybackSnapshot(
    val generation: Long,
    val queueRevision: Long,
    val queue: QueueState,
    val committedQueueEntryId: QueueEntryId?,
    val phase: PlaybackPhase,
    val playWhenReady: Boolean,
    val repeatMode: RepeatMode,
    val position: PositionAnchor,
    val resolvedSources: Map<QueueEntryId, PlaybackSourceHandle>,
    val engineWindow: List<QueueEntryId>,
    val expectedEngineCommit: PlaybackRequestTag?,
    val currentError: PlaybackError?,
) {
    init {
        require(generation >= 0) { "Playback generation cannot be negative" }
        require(queueRevision >= 0) { "Queue revision cannot be negative" }
        val queueIds = queue.baseQueue.map { it.id }.toSet()
        require(committedQueueEntryId == null || committedQueueEntryId in queueIds) {
            "Committed QueueEntryId must exist in the queue"
        }
        require(resolvedSources.keys.all { it in queueIds }) {
            "Resolved source cannot outlive its QueueEntryId"
        }
        require(
            engineWindow.size == engineWindow.toSet().size && engineWindow.all { it in queueIds }
        ) {
            "Engine window must contain unique current queue identities"
        }
        require(
            expectedEngineCommit == null ||
                (expectedEngineCommit.generation == generation &&
                    expectedEngineCommit.queueRevision == queueRevision &&
                    expectedEngineCommit.queueEntryId in engineWindow)
        ) {
            "Expected engine commit must match the active generation, revision, and window"
        }
        phase.entryIdOrNull()?.let { require(it in queueIds) { "Playback phase entry is stale" } }
    }

    companion object {
        val Empty =
            PlaybackSnapshot(
                generation = 0,
                queueRevision = 0,
                queue = QueueState.Empty,
                committedQueueEntryId = null,
                phase = PlaybackPhase.Idle,
                playWhenReady = false,
                repeatMode = RepeatMode.OFF,
                position = PositionAnchor(0, 0),
                resolvedSources = emptyMap(),
                engineWindow = emptyList(),
                expectedEngineCommit = null,
                currentError = null,
            )
    }
}

sealed interface PlaybackCoreEvent {
    data class ReplaceContext(
        val queue: QueueState,
        val selectedEntryId: QueueEntryId?,
        val playWhenReady: Boolean,
    ) : PlaybackCoreEvent

    data class QueueChanged(val queue: QueueState) : PlaybackCoreEvent

    data class SelectEntry(val queueEntryId: QueueEntryId) : PlaybackCoreEvent

    data class SourceReady(val tag: PlaybackRequestTag, val source: PlaybackSourceHandle) :
        PlaybackCoreEvent

    data class SourceUnavailable(val tag: PlaybackRequestTag, val error: PlaybackError) :
        PlaybackCoreEvent

    data class EngineCommitted(val tag: PlaybackRequestTag) : PlaybackCoreEvent

    data class EnginePhaseChanged(
        val generation: Long,
        val queueEntryId: QueueEntryId,
        val phase: CommittedEnginePhase,
    ) : PlaybackCoreEvent

    data class EnginePosition(
        val generation: Long,
        val queueEntryId: QueueEntryId,
        val position: PositionAnchor,
    ) : PlaybackCoreEvent

    data class EngineFailed(
        val generation: Long,
        val queueEntryId: QueueEntryId,
        val error: PlaybackError,
    ) : PlaybackCoreEvent

    data class SetPlayWhenReady(val value: Boolean) : PlaybackCoreEvent

    data class SetRepeat(val mode: RepeatMode) : PlaybackCoreEvent
}

enum class CommittedEnginePhase {
    BUFFERING,
    READY,
    PLAYING,
    PAUSED,
    ENDED,
}

sealed interface PlaybackEffect {
    data class PrepareSource(val tag: PlaybackRequestTag) : PlaybackEffect

    data class ApplyEngineWindow(val tag: PlaybackRequestTag, val source: PlaybackSourceHandle) :
        PlaybackEffect

    data class SetEnginePlayWhenReady(val value: Boolean) : PlaybackEffect

    data class SetEngineRepeat(val mode: RepeatMode) : PlaybackEffect
}

data class PlaybackReduction(val snapshot: PlaybackSnapshot, val effects: List<PlaybackEffect>)

class PlaybackReducer {
    fun reduce(snapshot: PlaybackSnapshot, event: PlaybackCoreEvent): PlaybackReduction =
        when (event) {
            is PlaybackCoreEvent.ReplaceContext -> replaceContext(snapshot, event)
            is PlaybackCoreEvent.QueueChanged -> queueChanged(snapshot, event.queue)
            is PlaybackCoreEvent.SelectEntry -> selectEntry(snapshot, event.queueEntryId)
            is PlaybackCoreEvent.SourceReady -> sourceReady(snapshot, event)
            is PlaybackCoreEvent.SourceUnavailable -> sourceUnavailable(snapshot, event)
            is PlaybackCoreEvent.EngineCommitted -> engineCommitted(snapshot, event.tag)
            is PlaybackCoreEvent.EnginePhaseChanged -> enginePhaseChanged(snapshot, event)
            is PlaybackCoreEvent.EnginePosition -> enginePosition(snapshot, event)
            is PlaybackCoreEvent.EngineFailed -> engineFailed(snapshot, event)
            is PlaybackCoreEvent.SetPlayWhenReady ->
                PlaybackReduction(
                    snapshot.copy(playWhenReady = event.value),
                    listOf(PlaybackEffect.SetEnginePlayWhenReady(event.value)),
                )
            is PlaybackCoreEvent.SetRepeat ->
                PlaybackReduction(
                    snapshot.copy(repeatMode = event.mode),
                    listOf(PlaybackEffect.SetEngineRepeat(event.mode)),
                )
        }

    private fun selectEntry(
        snapshot: PlaybackSnapshot,
        queueEntryId: QueueEntryId,
    ): PlaybackReduction {
        require(snapshot.queue.baseQueue.any { it.id == queueEntryId }) {
            "Selected QueueEntryId must exist in the queue"
        }
        val revision = snapshot.queueRevision + 1
        val tag = PlaybackRequestTag(snapshot.generation, revision, queueEntryId)
        return PlaybackReduction(
            snapshot.copy(
                queueRevision = revision,
                queue = snapshot.queue.copy(currentQueueEntryId = queueEntryId),
                committedQueueEntryId = null,
                phase = PlaybackPhase.Preparing(queueEntryId),
                position = PositionAnchor(0, 0),
                engineWindow = emptyList(),
                expectedEngineCommit = null,
                currentError = null,
            ),
            listOf(PlaybackEffect.PrepareSource(tag)),
        )
    }

    private fun replaceContext(
        snapshot: PlaybackSnapshot,
        event: PlaybackCoreEvent.ReplaceContext,
    ): PlaybackReduction {
        val queueIds = event.queue.baseQueue.map { it.id }
        require(event.selectedEntryId == null || event.selectedEntryId in queueIds) {
            "Selected QueueEntryId must exist in replacement queue"
        }
        require(queueIds.isEmpty() == (event.selectedEntryId == null)) {
            "A non-empty replacement queue requires one selected occurrence"
        }
        val generation = snapshot.generation + 1
        val revision = snapshot.queueRevision + 1
        val queue = event.queue.copy(currentQueueEntryId = event.selectedEntryId)
        val tag = event.selectedEntryId?.let { PlaybackRequestTag(generation, revision, it) }
        val next =
            snapshot.copy(
                generation = generation,
                queueRevision = revision,
                queue = queue,
                committedQueueEntryId = null,
                phase = tag?.let { PlaybackPhase.Preparing(it.queueEntryId) } ?: PlaybackPhase.Idle,
                playWhenReady = event.playWhenReady,
                position = PositionAnchor(0, 0),
                resolvedSources = emptyMap(),
                engineWindow = emptyList(),
                expectedEngineCommit = null,
                currentError = null,
            )
        return PlaybackReduction(
            next,
            tag?.let { listOf(PlaybackEffect.PrepareSource(it)) }.orEmpty(),
        )
    }

    private fun queueChanged(snapshot: PlaybackSnapshot, queue: QueueState): PlaybackReduction {
        val revision = snapshot.queueRevision + 1
        val queueIds = queue.baseQueue.map { it.id }.toSet()
        val retainedCommitted = snapshot.committedQueueEntryId?.takeIf { it in queueIds }
        val pendingEntry = snapshot.phase.pendingEntryId()?.takeIf { it in queueIds }
        val target =
            pendingEntry ?: if (retainedCommitted == null) queue.currentQueueEntryId else null
        val tag = target?.let { PlaybackRequestTag(snapshot.generation, revision, it) }
        val phaseEntryWasRemoved = snapshot.phase.entryIdOrNull()?.let { it !in queueIds } == true
        val phase =
            when {
                tag != null -> PlaybackPhase.Preparing(tag.queueEntryId)
                queueIds.isEmpty() -> PlaybackPhase.Idle
                phaseEntryWasRemoved && retainedCommitted != null ->
                    PlaybackPhase.Ready(retainedCommitted)
                else -> snapshot.phase
            }
        val next =
            snapshot.copy(
                queueRevision = revision,
                queue = queue,
                committedQueueEntryId = retainedCommitted,
                phase = phase,
                resolvedSources = snapshot.resolvedSources.filterKeys { it in queueIds },
                engineWindow = snapshot.engineWindow.filter { it in queueIds },
                expectedEngineCommit = null,
                currentError = if (tag != null) null else snapshot.currentError,
            )
        return PlaybackReduction(
            next,
            tag?.let { listOf(PlaybackEffect.PrepareSource(it)) }.orEmpty(),
        )
    }

    private fun sourceReady(
        snapshot: PlaybackSnapshot,
        event: PlaybackCoreEvent.SourceReady,
    ): PlaybackReduction {
        if (
            !snapshot.accepts(event.tag) ||
                snapshot.phase.pendingEntryId() != event.tag.queueEntryId ||
                snapshot.expectedEngineCommit != null
        ) {
            return PlaybackReduction(snapshot, emptyList())
        }
        val next =
            snapshot.copy(
                phase = PlaybackPhase.Buffering(event.tag.queueEntryId),
                resolvedSources =
                    snapshot.resolvedSources + (event.tag.queueEntryId to event.source),
                engineWindow = listOf(event.tag.queueEntryId),
                expectedEngineCommit = event.tag,
                currentError = null,
            )
        return PlaybackReduction(
            next,
            listOf(PlaybackEffect.ApplyEngineWindow(event.tag, event.source)),
        )
    }

    private fun sourceUnavailable(
        snapshot: PlaybackSnapshot,
        event: PlaybackCoreEvent.SourceUnavailable,
    ): PlaybackReduction {
        if (
            !snapshot.accepts(event.tag) ||
                snapshot.phase.pendingEntryId() != event.tag.queueEntryId ||
                snapshot.expectedEngineCommit != null
        ) {
            return PlaybackReduction(snapshot, emptyList())
        }
        return PlaybackReduction(
            snapshot.copy(
                phase = PlaybackPhase.Failed(event.tag.queueEntryId, event.error),
                expectedEngineCommit = null,
                currentError = event.error,
            ),
            emptyList(),
        )
    }

    private fun engineCommitted(
        snapshot: PlaybackSnapshot,
        tag: PlaybackRequestTag,
    ): PlaybackReduction {
        if (snapshot.expectedEngineCommit != tag) return PlaybackReduction(snapshot, emptyList())
        return PlaybackReduction(
            snapshot.copy(
                committedQueueEntryId = tag.queueEntryId,
                phase = PlaybackPhase.Ready(tag.queueEntryId),
                expectedEngineCommit = null,
                currentError = null,
            ),
            emptyList(),
        )
    }

    private fun enginePhaseChanged(
        snapshot: PlaybackSnapshot,
        event: PlaybackCoreEvent.EnginePhaseChanged,
    ): PlaybackReduction {
        if (!snapshot.acceptsCommitted(event.generation, event.queueEntryId)) {
            return PlaybackReduction(snapshot, emptyList())
        }
        val phase =
            when (event.phase) {
                CommittedEnginePhase.BUFFERING -> PlaybackPhase.Buffering(event.queueEntryId)
                CommittedEnginePhase.READY -> PlaybackPhase.Ready(event.queueEntryId)
                CommittedEnginePhase.PLAYING -> PlaybackPhase.Playing(event.queueEntryId)
                CommittedEnginePhase.PAUSED -> PlaybackPhase.Paused(event.queueEntryId)
                CommittedEnginePhase.ENDED -> PlaybackPhase.Ended
            }
        return PlaybackReduction(snapshot.copy(phase = phase), emptyList())
    }

    private fun enginePosition(
        snapshot: PlaybackSnapshot,
        event: PlaybackCoreEvent.EnginePosition,
    ): PlaybackReduction =
        if (snapshot.acceptsCommitted(event.generation, event.queueEntryId)) {
            PlaybackReduction(snapshot.copy(position = event.position), emptyList())
        } else {
            PlaybackReduction(snapshot, emptyList())
        }

    private fun engineFailed(
        snapshot: PlaybackSnapshot,
        event: PlaybackCoreEvent.EngineFailed,
    ): PlaybackReduction {
        val pendingMatches =
            snapshot.expectedEngineCommit?.let {
                it.generation == event.generation && it.queueEntryId == event.queueEntryId
            } == true
        val committedMatches = snapshot.acceptsCommitted(event.generation, event.queueEntryId)
        if (!pendingMatches && !committedMatches) return PlaybackReduction(snapshot, emptyList())
        return PlaybackReduction(
            snapshot.copy(
                phase = PlaybackPhase.Failed(event.queueEntryId, event.error),
                expectedEngineCommit = null,
                currentError = event.error,
            ),
            emptyList(),
        )
    }

    private fun PlaybackSnapshot.accepts(tag: PlaybackRequestTag): Boolean =
        tag.generation == generation &&
            tag.queueRevision == queueRevision &&
            queue.baseQueue.any { it.id == tag.queueEntryId }

    private fun PlaybackSnapshot.acceptsCommitted(
        eventGeneration: Long,
        entryId: QueueEntryId,
    ): Boolean = eventGeneration == generation && entryId == committedQueueEntryId

    private fun PlaybackPhase.pendingEntryId(): QueueEntryId? =
        when (this) {
            is PlaybackPhase.Preparing -> entryId
            is PlaybackPhase.Buffering -> entryId
            is PlaybackPhase.Recovering -> entryId
            else -> null
        }
}

private fun PlaybackPhase.entryIdOrNull(): QueueEntryId? =
    when (this) {
        is PlaybackPhase.Preparing -> entryId
        is PlaybackPhase.Buffering -> entryId
        is PlaybackPhase.Ready -> entryId
        is PlaybackPhase.Playing -> entryId
        is PlaybackPhase.Paused -> entryId
        is PlaybackPhase.Recovering -> entryId
        is PlaybackPhase.Failed -> entryId
        PlaybackPhase.Idle,
        PlaybackPhase.Ended -> null
    }
