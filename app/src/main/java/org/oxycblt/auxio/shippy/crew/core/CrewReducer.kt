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
    /**
     * Install an authoritative checkpoint after a sequence gap or reconnect.
     *
     * A snapshot must advance either the term or sequence. Accepting an equal/older checkpoint
     * would allow a delayed relay response to overwrite newer local state.
     */
    fun applySnapshot(
        state: CrewState,
        snapshot: CrewSnapshot,
        authenticatedPublisher: CrewMemberId,
        authenticatedElectionVotes: List<CrewElectionVote> = emptyList(),
    ): CrewSnapshotResult {
        validateSnapshotEnvelope(state, snapshot, authenticatedPublisher)?.let { return it }

        if (
            snapshot.term.value < state.term.value ||
                (snapshot.term == state.term && snapshot.lastSequence.value <= state.lastSequence.value)
        ) {
            return CrewSnapshotResult.StaleRejected(
                currentTerm = state.term,
                currentSequence = state.lastSequence,
                receivedTerm = snapshot.term,
                receivedSequence = snapshot.lastSequence,
            )
        }
        validateSnapshotAuthority(state, snapshot, authenticatedElectionVotes)?.let { return it }

        val snapshotState =
            try {
                CrewState(
                    sessionId = snapshot.sessionId,
                    protocolVersion = snapshot.protocolVersion,
                    term = snapshot.term,
                    lastSequence = snapshot.lastSequence,
                    coordinatorMemberId = snapshot.coordinatorMemberId,
                    members = snapshot.members.toList(),
                    queue = snapshot.queue.toList(),
                    playback = snapshot.playback,
                    shuffleEnabled = snapshot.shuffleEnabled,
                    repeatMode = snapshot.repeatMode,
                    // The snapshot sequence is authoritative; bounded replay IDs are local only.
                    appliedEventIds = emptyList(),
                )
            } catch (error: IllegalArgumentException) {
                return CrewSnapshotResult.Rejected(error.message ?: "Snapshot state is invalid")
            }

        return CrewSnapshotResult.Applied(snapshotState)
    }

    fun apply(
        state: CrewState,
        event: DurableCrewEvent,
        authenticatedPublisher: CrewMemberId,
    ): CrewEventResult {
        validateEnvelope(state, event, authenticatedPublisher)?.let {
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
        authenticatedPublisher: CrewMemberId,
    ): CrewEventResult.Rejected? {
        if (event.sessionId != state.sessionId) {
            return CrewEventResult.Rejected("Event belongs to a different Crew session")
        }
        if (
                event.protocolVersion != state.protocolVersion ||
                event.sessionId.protocolVersion != state.protocolVersion ||
                event.publisherMemberId.protocolVersion != state.protocolVersion ||
                authenticatedPublisher.protocolVersion != state.protocolVersion ||
                event.issuingMemberId.protocolVersion != state.protocolVersion
        ) {
            return CrewEventResult.Rejected("Event protocol version is incompatible")
        }
        if (state.members.none { it.id == event.issuingMemberId }) {
            return CrewEventResult.Rejected("Issuing member is not active in this Crew")
        }
        if (event.publisherMemberId != authenticatedPublisher) {
            return CrewEventResult.Rejected("Event publisher does not match authenticated transport")
        }
        if (state.members.none { it.id == authenticatedPublisher }) {
            return CrewEventResult.Rejected("Authenticated event publisher is not active in this Crew")
        }
        if (authenticatedPublisher != state.coordinatorMemberId) {
            return CrewEventResult.Rejected("Only the current coordinator may publish Crew events")
        }
        return null
    }

    private fun validateSnapshotEnvelope(
        state: CrewState,
        snapshot: CrewSnapshot,
        authenticatedPublisher: CrewMemberId,
    ): CrewSnapshotResult.Rejected? {
        if (snapshot.snapshotVersion != CrewSnapshotVersion.CURRENT) {
            return CrewSnapshotResult.Rejected("Crew snapshot version is unsupported")
        }
        if (snapshot.sessionId != state.sessionId) {
            return CrewSnapshotResult.Rejected("Snapshot belongs to a different Crew session")
        }
        if (
                snapshot.protocolVersion != state.protocolVersion ||
                snapshot.sessionId.protocolVersion != state.protocolVersion ||
                snapshot.publisherMemberId.protocolVersion != state.protocolVersion ||
                authenticatedPublisher.protocolVersion != state.protocolVersion ||
                snapshot.coordinatorMemberId.protocolVersion != state.protocolVersion
        ) {
            return CrewSnapshotResult.Rejected("Snapshot protocol version is incompatible")
        }
        if (snapshot.publisherMemberId != authenticatedPublisher) {
            return CrewSnapshotResult.Rejected("Snapshot publisher does not match authenticated transport")
        }
        return null
    }

    private fun validateSnapshotAuthority(
        state: CrewState,
        snapshot: CrewSnapshot,
        authenticatedElectionVotes: List<CrewElectionVote>,
    ): CrewSnapshotResult.Rejected? {
        if (snapshot.term == state.term) {
            return if (
                snapshot.publisherMemberId == state.coordinatorMemberId &&
                    snapshot.coordinatorMemberId == state.coordinatorMemberId
            ) {
                null
            } else {
                CrewSnapshotResult.Rejected(
                    "Same-term snapshot cannot replace the current coordinator"
                )
            }
        }
        if (snapshot.term != state.term.next()) {
            return CrewSnapshotResult.Rejected("Higher-term snapshot must advance exactly one term")
        }
        if (authenticatedElectionVotes.isEmpty()) {
            return CrewSnapshotResult.Rejected("Higher-term snapshot requires an election quorum")
        }
        val voters = authenticatedElectionVotes.map(CrewElectionVote::voterMemberId)
        if (voters.distinct().size != voters.size) {
            return CrewSnapshotResult.Rejected("Election certificate contains duplicate voters")
        }
        if (
            authenticatedElectionVotes.any {
                it.voterMemberId.protocolVersion != state.protocolVersion ||
                    it.candidateMemberId.protocolVersion != state.protocolVersion
            }
        ) {
            return CrewSnapshotResult.Rejected("Election certificate protocol version is incompatible")
        }
        if (voters.any { voter -> state.members.none { it.id == voter } }) {
            return CrewSnapshotResult.Rejected("Election certificate contains a non-member voter")
        }
        if (state.coordinatorMemberId in voters) {
            return CrewSnapshotResult.Rejected(
                "Ungraceful election certificate cannot include the old coordinator"
            )
        }
        val checkpoint = state.toElectionCheckpoint()
        if (authenticatedElectionVotes.any { it.checkpoint != checkpoint }) {
            return CrewSnapshotResult.Rejected("Election certificate is for a different checkpoint")
        }
        val candidates =
            authenticatedElectionVotes.map(CrewElectionVote::candidateMemberId).distinct()
        if (candidates.size != 1) {
            return CrewSnapshotResult.Rejected("Election certificate does not agree on one candidate")
        }
        if (voters.size <= state.members.size / 2) {
            return CrewSnapshotResult.Rejected("Election certificate does not contain a majority")
        }
        val elected = candidates.single()
        if (elected !in voters || elected != voters.minBy { it.value }) {
            return CrewSnapshotResult.Rejected(
                "Election candidate is not the deterministic quorum winner"
            )
        }
        if (snapshot.publisherMemberId != elected || snapshot.coordinatorMemberId != elected) {
            return CrewSnapshotResult.Rejected("Higher-term snapshot publisher is not the elected coordinator")
        }
        if (snapshot.members.any { it.id == state.coordinatorMemberId }) {
            return CrewSnapshotResult.Rejected("Higher-term snapshot still contains the old coordinator")
        }
        val expectedMembers = state.members.map(CrewMember::id).toSet() - state.coordinatorMemberId
        if (snapshot.members.map(CrewMember::id).toSet() != expectedMembers) {
            return CrewSnapshotResult.Rejected(
                "Higher-term snapshot membership must preserve non-coordinator members"
            )
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
                is CrewAction.MemberJoined -> joinMember(state, action)
                is CrewAction.MemberUpdated -> updateMember(state, action)
                is CrewAction.MemberLeft -> leaveMember(state, action)
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
                is CrewAction.ShuffleChanged ->
                    StateUpdate.Accepted(state.copy(shuffleEnabled = action.enabled))
                is CrewAction.RepeatChanged -> StateUpdate.Accepted(state.copy(repeatMode = action.mode))
                is CrewAction.CoordinatorTransferred ->
                    error("Coordinator transfer is handled before ordered actions")
            }

        return when (updated) {
            is StateUpdate.Accepted -> applied(updated.state, event)
            is StateUpdate.Rejected -> CrewEventResult.Rejected(updated.reason)
        }
    }

    private fun joinMember(
        state: CrewState,
        action: CrewAction.MemberJoined,
    ): StateUpdate {
        if (action.member.id.protocolVersion != state.protocolVersion) {
            return StateUpdate.Rejected("Joined member protocol version is incompatible")
        }
        if (state.members.any { it.id == action.member.id }) {
            return StateUpdate.Rejected("Crew member already exists")
        }
        return StateUpdate.Accepted(state.copy(members = state.members + action.member))
    }

    private fun updateMember(
        state: CrewState,
        action: CrewAction.MemberUpdated,
    ): StateUpdate {
        if (action.member.id.protocolVersion != state.protocolVersion) {
            return StateUpdate.Rejected("Updated member protocol version is incompatible")
        }
        val memberIndex = state.members.indexOfFirst { it.id == action.member.id }
        if (memberIndex == -1) {
            return StateUpdate.Rejected("Crew member does not exist")
        }
        return StateUpdate.Accepted(
            state.copy(
                members = state.members.toMutableList().apply { set(memberIndex, action.member) }
            )
        )
    }

    private fun leaveMember(
        state: CrewState,
        action: CrewAction.MemberLeft,
    ): StateUpdate {
        if (action.memberId == state.coordinatorMemberId) {
            return StateUpdate.Rejected("Coordinator must transfer before leaving Crew")
        }
        val memberIndex = state.members.indexOfFirst { it.id == action.memberId }
        if (memberIndex == -1) {
            return StateUpdate.Rejected("Crew member does not exist")
        }
        if (state.members.size == 1) {
            return StateUpdate.Rejected("The last Crew member cannot leave an active session")
        }
        return StateUpdate.Accepted(
            state.copy(members = state.members.toMutableList().apply { removeAt(memberIndex) })
        )
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
                appliedEventIds = retainEventId(state.appliedEventIds, event.id),
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

    private fun retainEventId(
        existing: List<DurableEventId>,
        eventId: DurableEventId,
    ): List<DurableEventId> =
        (existing + eventId).takeLast(CrewState.MAX_APPLIED_EVENT_IDS)

    private sealed interface StateUpdate {
        data class Accepted(val state: CrewState) : StateUpdate

        data class Rejected(val reason: String) : StateUpdate
    }
}
