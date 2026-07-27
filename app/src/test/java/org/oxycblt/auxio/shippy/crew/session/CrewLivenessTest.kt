/*
 * Copyright (c) 2026 Auxio Project
 * CrewLivenessTest.kt is part of Auxio.
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.core.toElectionCheckpoint

class CrewLivenessTest {
    private val protocol = ProtocolVersion(1)
    private val coordinator = CrewMemberId("a", protocol)
    private val memberB = CrewMemberId("b", protocol)
    private val memberC = CrewMemberId("c", protocol)
    private val policy = CrewReconnectPolicy(memberGraceMs = 10_000, coordinatorGraceMs = 5_000)

    @Test
    fun `coordinator waits then may remove an expired ordinary member`() {
        val state = state(listOf(coordinator, memberB))
        val tracker = CrewLivenessTracker(state, coordinator, policy)
        tracker.connected(memberB, 0)
        tracker.disconnected(memberB, 1_000)

        assertEquals(
            listOf(CrewLivenessDecision.AwaitingReconnect(memberB, 5_000)),
            tracker.evaluate(state, 6_000),
        )
        assertEquals(
            listOf(CrewLivenessDecision.MemberRemovalEligible(memberB)),
            tracker.evaluate(state, 11_000),
        )
    }

    @Test
    fun `three member coordinator loss yields deterministic majority election`() {
        val state = state(listOf(coordinator, memberB, memberC))
        val tracker = CrewLivenessTracker(state, memberB, policy)
        tracker.connected(memberC, 0)
        tracker.connected(coordinator, 0)
        tracker.disconnected(coordinator, 1_000)

        val decision = tracker.evaluate(state, 6_000).single()

        assertTrue(decision is CrewLivenessDecision.ElectionEligible)
        decision as CrewLivenessDecision.ElectionEligible
        assertEquals(memberB, decision.candidateMemberId)
        assertEquals(listOf(memberB, memberC), decision.connectedVoterIds)
        assertEquals(state.toElectionCheckpoint(), decision.checkpoint)
    }

    @Test
    fun `two member coordinator loss refuses split brain and reconnect cancels expiry`() {
        val state = state(listOf(coordinator, memberB))
        val tracker = CrewLivenessTracker(state, memberB, policy)
        tracker.connected(coordinator, 0)
        tracker.disconnected(coordinator, 1_000)

        assertEquals(
            CrewLivenessDecision.CoordinatorUnavailableWithoutQuorum(coordinator),
            tracker.evaluate(state, 6_000).single(),
        )

        tracker.connected(coordinator, 6_100)
        assertTrue(tracker.evaluate(state, 20_000).isEmpty())
    }

    private fun state(memberIds: List<CrewMemberId>) =
        CrewState(
            sessionId = CrewSessionId("crew", protocol),
            protocolVersion = protocol,
            term = CoordinatorTerm(1),
            lastSequence = EventSequence(0),
            coordinatorMemberId = coordinator,
            members = memberIds.map { CrewMember(it, it.value.uppercase()) },
        )
}
