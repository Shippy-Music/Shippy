/*
 * Copyright (c) 2026 Auxio Project
 * CrewSnapshot.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.core

import org.oxycblt.auxio.shippy.domain.QueueItem

@JvmInline
value class CrewSnapshotVersion(val value: Int) {
    init {
        require(value > 0) { "Crew snapshot version must be positive" }
    }

    companion object {
        val CURRENT = CrewSnapshotVersion(1)
    }
}

/**
 * A complete, versioned Crew control-plane checkpoint.
 *
 * Snapshots deliberately omit recent event IDs. The term and sequence are enough to reject old
 * events after a snapshot is installed, and omitting IDs keeps a long-running session bounded.
 */
data class CrewSnapshot(
    val snapshotVersion: CrewSnapshotVersion = CrewSnapshotVersion.CURRENT,
    val sessionId: CrewSessionId,
    val protocolVersion: ProtocolVersion,
    val term: CoordinatorTerm,
    val lastSequence: EventSequence,
    /** Coordinator/elected sequencer that published this authoritative checkpoint. */
    val publisherMemberId: CrewMemberId,
    val coordinatorMemberId: CrewMemberId,
    val members: List<CrewMember>,
    val queue: List<QueueItem>,
    val playback: CrewPlaybackState,
    val shuffleEnabled: Boolean,
    val repeatMode: CrewRepeatMode,
)

/**
 * One independently authenticated vote for an ungraceful next-term election.
 *
 * The transport/session layer must authenticate each voter and persist the rule that a member votes
 * at most once for a given base term and sequence. The reducer validates the resulting quorum
 * certificate; raw voter IDs from a snapshot payload are not sufficient.
 */
data class CrewElectionCheckpoint(
    val sessionId: CrewSessionId,
    val protocolVersion: ProtocolVersion,
    val term: CoordinatorTerm,
    val sequence: EventSequence,
    val coordinatorMemberId: CrewMemberId,
    val memberIds: List<CrewMemberId>,
) {
    init {
        require(memberIds.isNotEmpty() && memberIds.distinct().size == memberIds.size) {
            "Election checkpoint members must be non-empty and unique"
        }
        require(memberIds == memberIds.sortedBy(CrewMemberId::value)) {
            "Election checkpoint members must be in stable order"
        }
        require(memberIds.all { it.protocolVersion == protocolVersion }) {
            "Election checkpoint member protocols must match"
        }
        require(coordinatorMemberId in memberIds) {
            "Election checkpoint coordinator must be a member"
        }
    }
}

data class CrewElectionVote(
    val voterMemberId: CrewMemberId,
    val candidateMemberId: CrewMemberId,
    val checkpoint: CrewElectionCheckpoint,
)

sealed interface CrewSnapshotResult {
    data class Applied(val state: CrewState) : CrewSnapshotResult

    data class StaleRejected(
        val currentTerm: CoordinatorTerm,
        val currentSequence: EventSequence,
        val receivedTerm: CoordinatorTerm,
        val receivedSequence: EventSequence,
    ) : CrewSnapshotResult

    data class Rejected(val reason: String) : CrewSnapshotResult
}

fun CrewState.toSnapshot() =
    CrewSnapshot(
        sessionId = sessionId,
        protocolVersion = protocolVersion,
        term = term,
        lastSequence = lastSequence,
        publisherMemberId = coordinatorMemberId,
        coordinatorMemberId = coordinatorMemberId,
        members = members,
        queue = queue,
        playback = playback,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
    )

/** Restores the canonical in-memory state represented by this complete checkpoint. */
fun CrewSnapshot.toCrewState() =
    CrewState(
        sessionId = sessionId,
        protocolVersion = protocolVersion,
        term = term,
        lastSequence = lastSequence,
        coordinatorMemberId = coordinatorMemberId,
        members = members,
        queue = queue,
        playback = playback,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
    )

fun CrewState.toElectionCheckpoint() =
    CrewElectionCheckpoint(
        sessionId = sessionId,
        protocolVersion = protocolVersion,
        term = term,
        sequence = lastSequence,
        coordinatorMemberId = coordinatorMemberId,
        memberIds = members.map(CrewMember::id).sortedBy(CrewMemberId::value),
    )
