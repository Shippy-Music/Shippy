/*
 * Copyright (c) 2026 Shippy contributors
 * CrewReducer.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.core

import org.oxycblt.auxio.shippy.domain.QueueItem

class CrewReducer {
    fun apply(state: CrewState, event: DurableCrewEvent): CrewEventResult {
        validateEnvelope(state, event)?.let {
            return it
        }

        if (event.id in state.appliedEventIds) {
            return CrewEventResult.DuplicateRejected(state)
        }
        if (event.term.value < state.term.value) {
            return CrewEventResult.StaleTermRejected(state.term, event.term)
        }

        return if (event.action is CrewAction.CoordinatorTransferred) {
            applyTransfer(state, event, event.action)
        } else {
            applyOrderedAction(state, event)
        }
    }

    private fun validateEnvelope(
        state: CrewState,
        event: DurableCrewEvent,
    ): CrewEventResult.Rejected? {
        if (event.sessionId != state.sessionId) {
            return CrewEventResult.Rejected("Event belongs to a different Crew session")
        }
        if (
            event.protocolVersion != state.protocolVersion ||
                event.sessionId.protocolVersion != state.protocolVersion ||
                event.issuingMemberId.protocolVersion != state.protocolVersion
        ) {
            return CrewEventResult.Rejected("Event protocol version is incompatible")
        }
        if (state.members.none { it.id == event.issuingMemberId }) {
            return CrewEventResult.Rejected("Issuing member is not active in this Crew")
        }
        return null
    }

    private fun applyTransfer(
        state: CrewState,
        event: DurableCrewEvent,
        action: CrewAction.CoordinatorTransferred,
    ): CrewEventResult {
        if (event.term != state.term.next()) {
            return if (event.term == state.term) {
                CrewEventResult.Rejected("Coordinator transfer must increment the term")
            } else {
                snapshotRequired(state, event)
            }
        }
        if (event.sequence != EventSequence(1)) {
            return snapshotRequired(state, event)
        }
        if (event.issuingMemberId != state.coordinatorMemberId) {
            return CrewEventResult.Rejected("Only the current coordinator can transfer")
        }
        if (state.members.none { it.id == action.newCoordinatorMemberId }) {
            return CrewEventResult.Rejected("New coordinator must be an active Crew member")
        }

        return applied(
            state.copy(
                term = event.term,
                lastSequence = event.sequence,
                coordinatorMemberId = action.newCoordinatorMemberId,
            ),
            event,
        )
    }

    private fun applyOrderedAction(
        state: CrewState,
        event: DurableCrewEvent,
    ): CrewEventResult {
        if (event.term.value > state.term.value) {
            return snapshotRequired(state, event)
        }

        val expectedSequence = state.lastSequence.next()
        if (event.sequence.value < expectedSequence.value) {
            return CrewEventResult.StaleSequenceRejected(state.lastSequence, event.sequence)
        }
        if (event.sequence.value > expectedSequence.value) {
            return snapshotRequired(state, event)
        }

        val updated =
            when (val action = event.action) {
                is CrewAction.QueueReplaced -> replaceQueue(state, action)
                is CrewAction.QueueItemInserted -> insertQueueItem(state, action)
                is CrewAction.QueueItemMoved -> moveQueueItem(state, action)
                is CrewAction.QueueItemRemoved -> removeQueueItem(state, action)
                is CrewAction.CurrentItemChanged -> changeCurrentItem(state, action)
                is CrewAction.Play ->
                    updatePlayback(
                        state,
                        CrewPlaybackMode.PLAYING,
                        action.positionAtEpochMs,
                        action.sessionEpochMs,
                    )
                is CrewAction.Pause ->
                    updatePlayback(
                        state,
                        CrewPlaybackMode.PAUSED,
                        action.positionAtEpochMs,
                        action.sessionEpochMs,
                    )
                is CrewAction.Seek ->
                    updatePlayback(
                        state,
                        state.playback.mode,
                        action.positionAtEpochMs,
                        action.sessionEpochMs,
                    )
                is CrewAction.CoordinatorTransferred ->
                    error("Coordinator transfer is handled before ordered actions")
            }

        return when (updated) {
            is StateUpdate.Accepted -> applied(updated.state, event)
            is StateUpdate.Rejected -> CrewEventResult.Rejected(updated.reason)
        }
    }

    private fun replaceQueue(
        state: CrewState,
        action: CrewAction.QueueReplaced,
    ): StateUpdate {
        if (!hasUniqueIds(action.items)) {
            return StateUpdate.Rejected("Replacement queue item IDs must be unique")
        }
        val current = action.items.firstOrNull()?.id
        return StateUpdate.Accepted(
            state.copy(
                queue = action.items.toList(),
                playback =
                    if (current == null) {
                        CrewPlaybackState()
                    } else {
                        CrewPlaybackState(
                            currentQueueItemId = current,
                            mode = CrewPlaybackMode.PREPARING,
                        )
                    },
            )
        )
    }

    private fun insertQueueItem(
        state: CrewState,
        action: CrewAction.QueueItemInserted,
    ): StateUpdate {
        if (state.queue.any { it.id == action.item.id }) {
            return StateUpdate.Rejected("Queue item ID already exists")
        }
        if (action.index !in 0..state.queue.size) {
            return StateUpdate.Rejected("Queue insert index is out of bounds")
        }

        val queue = state.queue.toMutableList().apply { add(action.index, action.item) }
        val playback =
            if (state.playback.currentQueueItemId == null) {
                CrewPlaybackState(
                    currentQueueItemId = action.item.id,
                    mode = CrewPlaybackMode.PREPARING,
                )
            } else {
                state.playback
            }
        return StateUpdate.Accepted(state.copy(queue = queue, playback = playback))
    }

    private fun moveQueueItem(
        state: CrewState,
        action: CrewAction.QueueItemMoved,
    ): StateUpdate {
        val oldIndex = state.queue.indexOfFirst { it.id == action.itemId }
        if (oldIndex == -1) {
            return StateUpdate.Rejected("Queue item does not exist")
        }
        if (action.newIndex !in state.queue.indices) {
            return StateUpdate.Rejected("Queue move index is out of bounds")
        }

        val queue =
            state.queue.toMutableList().apply {
                val item = removeAt(oldIndex)
                add(action.newIndex, item)
            }
        return StateUpdate.Accepted(state.copy(queue = queue))
    }

    private fun removeQueueItem(
        state: CrewState,
        action: CrewAction.QueueItemRemoved,
    ): StateUpdate {
        val removedIndex = state.queue.indexOfFirst { it.id == action.itemId }
        if (removedIndex == -1) {
            return StateUpdate.Rejected("Queue item does not exist")
        }

        val queue = state.queue.toMutableList().apply { removeAt(removedIndex) }
        val playback =
            if (state.playback.currentQueueItemId != action.itemId) {
                state.playback
            } else {
                val nextItem = queue.getOrNull(removedIndex) ?: queue.lastOrNull()
                if (nextItem == null) {
                    CrewPlaybackState()
                } else {
                    CrewPlaybackState(
                        currentQueueItemId = nextItem.id,
                        mode = CrewPlaybackMode.PREPARING,
                    )
                }
            }
        return StateUpdate.Accepted(state.copy(queue = queue, playback = playback))
    }

    private fun changeCurrentItem(
        state: CrewState,
        action: CrewAction.CurrentItemChanged,
    ): StateUpdate {
        if (state.queue.none { it.id == action.itemId }) {
            return StateUpdate.Rejected("Current queue item must exist")
        }
        return StateUpdate.Accepted(
            state.copy(
                playback =
                    CrewPlaybackState(
                        currentQueueItemId = action.itemId,
                        mode = CrewPlaybackMode.PREPARING,
                    )
            )
        )
    }

    private fun updatePlayback(
        state: CrewState,
        mode: CrewPlaybackMode,
        positionAtEpochMs: Long,
        sessionEpochMs: Long,
    ): StateUpdate {
        if (state.playback.currentQueueItemId == null) {
            return StateUpdate.Rejected("Playback action requires a current queue item")
        }
        if (positionAtEpochMs < 0 || sessionEpochMs < 0) {
            return StateUpdate.Rejected("Playback position and epoch cannot be negative")
        }
        return StateUpdate.Accepted(
            state.copy(
                playback =
                    state.playback.copy(
                        mode = mode,
                        positionAtEpochMs = positionAtEpochMs,
                        sessionEpochMs = sessionEpochMs,
                    )
            )
        )
    }

    private fun applied(
        state: CrewState,
        event: DurableCrewEvent,
    ) =
        CrewEventResult.Applied(
            state.copy(
                lastSequence = event.sequence,
                appliedEventIds = state.appliedEventIds + event.id,
            )
        )

    private fun snapshotRequired(
        state: CrewState,
        event: DurableCrewEvent,
    ) =
        CrewEventResult.SnapshotRequired(
            currentTerm = state.term,
            expectedSequence =
                if (event.term == state.term) state.lastSequence.next() else EventSequence(1),
            receivedTerm = event.term,
            receivedSequence = event.sequence,
        )

    private fun hasUniqueIds(items: List<QueueItem>) =
        items.map(QueueItem::id).distinct().size == items.size

    private sealed interface StateUpdate {
        data class Accepted(val state: CrewState) : StateUpdate

        data class Rejected(val reason: String) : StateUpdate
    }
}
