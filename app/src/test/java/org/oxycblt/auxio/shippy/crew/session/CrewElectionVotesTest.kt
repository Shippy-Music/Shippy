/*
 * Copyright (c) 2026 Auxio Project
 * CrewElectionVotesTest.kt is part of Auxio.
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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewElectionVote
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.core.toElectionCheckpoint

class CrewElectionVotesTest {
    private val protocol = ProtocolVersion(1)
    private val coordinator = CrewMemberId("coordinator", protocol)
    private val alpha = CrewMemberId("alpha", protocol)
    private val beta = CrewMemberId("beta", protocol)
    private val state =
        CrewState(
            sessionId = CrewSessionId("crew", protocol),
            protocolVersion = protocol,
            term = CoordinatorTerm(4),
            lastSequence = EventSequence(9),
            coordinatorMemberId = coordinator,
            members =
                listOf(
                    CrewMember(coordinator, "Coordinator"),
                    CrewMember(alpha, "Alpha"),
                    CrewMember(beta, "Beta"),
                ),
        )
    private val eligibility =
        CrewLivenessDecision.ElectionEligible(
            checkpoint = state.toElectionCheckpoint(),
            candidateMemberId = alpha,
            connectedVoterIds = listOf(alpha, beta),
        )

    @Test
    fun `strict majority is assembled from independently authenticated voters`() {
        val collector = CrewElectionVoteCollector()
        val alphaVote = vote(alpha)
        val betaVote = vote(beta)

        val first = collector.record(state, eligibility, alphaVote, alpha)
        assertTrue(first is CrewElectionVoteResult.Recorded)
        assertFalse((first as CrewElectionVoteResult.Recorded).hasStrictMajority)

        val second = collector.record(state, eligibility, betaVote, beta)
        assertTrue(second is CrewElectionVoteResult.Recorded)
        second as CrewElectionVoteResult.Recorded
        assertTrue(second.hasStrictMajority)
        assertEquals(listOf(alphaVote, betaVote), second.authenticatedVotes)
    }

    @Test
    fun `transport identity and deterministic candidate are enforced`() {
        val collector = CrewElectionVoteCollector()

        assertTrue(
            collector.record(state, eligibility, vote(alpha), beta)
                is CrewElectionVoteResult.Rejected
        )
        assertTrue(
            collector.record(
                state,
                eligibility,
                CrewElectionVote(alpha, beta, state.toElectionCheckpoint()),
                alpha,
            ) is CrewElectionVoteResult.Rejected
        )
    }

    @Test
    fun `state advance clears the prior checkpoint certificate`() {
        val collector = CrewElectionVoteCollector()
        collector.record(state, eligibility, vote(alpha), alpha)
        val advanced = state.copy(lastSequence = EventSequence(10))
        collector.resetUnless(advanced)

        val advancedEligibility = eligibility.copy(checkpoint = advanced.toElectionCheckpoint())
        val result =
            collector.record(
                advanced,
                advancedEligibility,
                CrewElectionVote(beta, alpha, advanced.toElectionCheckpoint()),
                beta,
            )

        assertTrue(result is CrewElectionVoteResult.Recorded)
        assertFalse((result as CrewElectionVoteResult.Recorded).hasStrictMajority)
    }

    @Test
    fun `elected snapshot advances term and removes unavailable coordinator`() {
        val snapshot = state.toElectedSnapshot(alpha)

        assertEquals(CoordinatorTerm(5), snapshot.term)
        assertEquals(EventSequence(0), snapshot.lastSequence)
        assertEquals(alpha, snapshot.publisherMemberId)
        assertEquals(alpha, snapshot.coordinatorMemberId)
        assertEquals(listOf(alpha, beta), snapshot.members.map(CrewMember::id))
    }

    private fun vote(voter: CrewMemberId) =
        CrewElectionVote(
            voterMemberId = voter,
            candidateMemberId = alpha,
            checkpoint = state.toElectionCheckpoint(),
        )
}
