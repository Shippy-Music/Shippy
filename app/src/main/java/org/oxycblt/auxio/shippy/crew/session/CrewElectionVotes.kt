/*
 * Copyright (c) 2026 Auxio Project
 * CrewElectionVotes.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.session

import org.oxycblt.auxio.shippy.crew.core.CrewElectionCheckpoint
import org.oxycblt.auxio.shippy.crew.core.CrewElectionVote
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSnapshot
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.core.toElectionCheckpoint

sealed interface CrewElectionVoteResult {
    data class Recorded(
        val vote: CrewElectionVote,
        val authenticatedVotes: List<CrewElectionVote>,
        val hasStrictMajority: Boolean,
    ) : CrewElectionVoteResult

    data class Duplicate(
        val vote: CrewElectionVote,
        val authenticatedVotes: List<CrewElectionVote>,
        val hasStrictMajority: Boolean,
    ) : CrewElectionVoteResult

    data class Rejected(val reason: String) : CrewElectionVoteResult
}

/**
 * Bounded in-memory collection of independently authenticated election votes.
 *
 * A transport-authenticated peer may contribute only its own vote. Collection is tied to the exact
 * persisted checkpoint and the liveness policy's deterministic candidate. Any state advance resets
 * the certificate, preventing votes from leaking across terms or sequences.
 */
class CrewElectionVoteCollector {
    private var checkpoint: CrewElectionCheckpoint? = null
    private var candidateMemberId: CrewMemberId? = null
    private val votes = linkedMapOf<CrewMemberId, CrewElectionVote>()

    @Synchronized
    fun record(
        state: CrewState,
        eligibility: CrewLivenessDecision.ElectionEligible,
        vote: CrewElectionVote,
        authenticatedVoter: CrewMemberId,
    ): CrewElectionVoteResult {
        val currentCheckpoint = state.toElectionCheckpoint()
        if (eligibility.checkpoint != currentCheckpoint || vote.checkpoint != currentCheckpoint) {
            return CrewElectionVoteResult.Rejected(
                "Election vote does not match the active checkpoint"
            )
        }
        if (authenticatedVoter != vote.voterMemberId) {
            return CrewElectionVoteResult.Rejected(
                "Election voter does not match authenticated transport"
            )
        }
        if (
            authenticatedVoter !in eligibility.connectedVoterIds ||
                state.members.none { it.id == authenticatedVoter } ||
                authenticatedVoter == state.coordinatorMemberId
        ) {
            return CrewElectionVoteResult.Rejected("Election voter is not eligible")
        }
        if (
            vote.candidateMemberId != eligibility.candidateMemberId ||
                vote.candidateMemberId !in eligibility.connectedVoterIds
        ) {
            return CrewElectionVoteResult.Rejected(
                "Election vote does not use the deterministic candidate"
            )
        }

        if (checkpoint != currentCheckpoint || candidateMemberId != eligibility.candidateMemberId) {
            checkpoint = currentCheckpoint
            candidateMemberId = eligibility.candidateMemberId
            votes.clear()
        }
        val existing = votes[authenticatedVoter]
        if (existing != null) {
            return if (existing == vote) {
                duplicate(state, vote)
            } else {
                CrewElectionVoteResult.Rejected(
                    "Election voter already voted differently for this checkpoint"
                )
            }
        }
        votes[authenticatedVoter] = vote
        return recorded(state, vote)
    }

    @Synchronized
    fun resetUnless(state: CrewState) {
        if (checkpoint != state.toElectionCheckpoint()) {
            reset()
        }
    }

    @Synchronized
    fun authenticatedCertificate(
        state: CrewState,
        eligibility: CrewLivenessDecision.ElectionEligible,
    ): List<CrewElectionVote>? {
        if (
            checkpoint != state.toElectionCheckpoint() ||
                checkpoint != eligibility.checkpoint ||
                candidateMemberId != eligibility.candidateMemberId ||
                votes.size <= state.members.size / 2
        ) {
            return null
        }
        return orderedVotes()
    }

    @Synchronized
    fun reset() {
        checkpoint = null
        candidateMemberId = null
        votes.clear()
    }

    private fun recorded(state: CrewState, vote: CrewElectionVote) =
        CrewElectionVoteResult.Recorded(
            vote = vote,
            authenticatedVotes = orderedVotes(),
            hasStrictMajority = votes.size > state.members.size / 2,
        )

    private fun duplicate(state: CrewState, vote: CrewElectionVote) =
        CrewElectionVoteResult.Duplicate(
            vote = vote,
            authenticatedVotes = orderedVotes(),
            hasStrictMajority = votes.size > state.members.size / 2,
        )

    private fun orderedVotes() = votes.values.sortedBy { it.voterMemberId.value }
}

fun CrewState.toElectedSnapshot(candidateMemberId: CrewMemberId): CrewSnapshot {
    require(candidateMemberId != coordinatorMemberId) {
        "Ungraceful election cannot retain the unavailable coordinator"
    }
    require(members.any { it.id == candidateMemberId }) {
        "Elected coordinator must be an active member"
    }
    return CrewSnapshot(
        sessionId = sessionId,
        protocolVersion = protocolVersion,
        term = term.next(),
        lastSequence = EventSequence(0),
        publisherMemberId = candidateMemberId,
        coordinatorMemberId = candidateMemberId,
        members = members.filterNot { it.id == coordinatorMemberId },
        queue = queue,
        playback = playback,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
    )
}
