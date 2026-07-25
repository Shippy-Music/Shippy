/*
 * Copyright (c) 2026 Shippy contributors
 * CrewJoinBootstrapAccumulatorTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.runtime

import org.junit.Assert.assertEquals
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.CrewElectionVote
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.DurableCrewEvent
import org.oxycblt.auxio.shippy.crew.core.DurableEventId
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.core.toElectionCheckpoint
import org.oxycblt.auxio.shippy.crew.core.toSnapshot
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlFrameResult
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlFramer
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlMessage
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame

class CrewJoinBootstrapAccumulatorTest {
    private val protocol = ProtocolVersion(1)
    private val sessionId = CrewSessionId("session", protocol)
    private val coordinatorId = CrewMemberId("coordinator", protocol)
    private val joiningMemberId = CrewMemberId("joining", protocol)
    private val accumulator = CrewJoinBootstrapAccumulator(sessionId, joiningMemberId)

    @Test
    fun `event before admission snapshot waits then accepts coordinator snapshot`() {
        val event =
            CrewControlMessage.Event(
                DurableCrewEvent(
                    sessionId = sessionId,
                    protocolVersion = protocol,
                    term = CoordinatorTerm(1),
                    sequence = EventSequence(1),
                    id = DurableEventId("joined"),
                    publisherMemberId = coordinatorId,
                    issuingMemberId = coordinatorId,
                    clientMonotonicTimestampMs = 1,
                    action = CrewAction.MemberJoined(CrewMember(joiningMemberId, "Joining")),
                )
            )

        assertEquals(CrewJoinBootstrapResult.Waiting, accept(coordinatorId, event, 0))

        val snapshot = state().toSnapshot()
        assertEquals(CrewJoinBootstrapResult.Accepted(snapshot), accept(coordinatorId, CrewControlMessage.SnapshotInstalled(snapshot), 1))
    }

    @Test
    fun `forged coordinator snapshot is rejected`() {
        assertEquals(
            CrewJoinBootstrapResult.Rejected.ForgedCoordinator,
            accept(joiningMemberId, CrewControlMessage.SnapshotInstalled(state().toSnapshot()), 0),
        )
    }

    @Test
    fun `snapshot without joining member is rejected`() {
        val snapshot = state(members = listOf(CrewMember(coordinatorId, "Coordinator"))).toSnapshot()

        assertEquals(
            CrewJoinBootstrapResult.Rejected.LocalMemberMissingOrDuplicate,
            accept(coordinatorId, CrewControlMessage.SnapshotInstalled(snapshot), 0),
        )
    }

    @Test
    fun `wrong channel and malformed control frame are rejected`() {
        assertEquals(
            CrewJoinBootstrapResult.Rejected.WrongChannel,
            accumulator.accept(
                coordinatorId,
                CrewTransportFrame(CrewTransportChannel.MEDIA, byteArrayOf(1)),
                nowMonotonicMs = 0,
            ),
        )
        assertEquals(
            CrewJoinBootstrapResult.Rejected.Framing(CrewControlFrameResult.Rejected.MALFORMED),
            accumulator.accept(
                coordinatorId,
                CrewTransportFrame(CrewTransportChannel.CONTROL, byteArrayOf(1)),
                nowMonotonicMs = 1,
            ),
        )
    }

    @Test
    fun `election certificate snapshot is rejected during bootstrap`() {
        val state = state()
        val vote = CrewElectionVote(coordinatorId, coordinatorId, state.toElectionCheckpoint())

        assertEquals(
            CrewJoinBootstrapResult.Rejected.ElectionCertificateBootstrap,
            accept(coordinatorId, CrewControlMessage.SnapshotInstalled(state.toSnapshot(), listOf(vote)), 0),
        )
    }

    private fun accept(
        authenticatedMemberId: CrewMemberId,
        message: CrewControlMessage,
        nowMonotonicMs: Long,
    ): CrewJoinBootstrapResult =
        CrewControlFramer.encode(message).single().let {
            accumulator.accept(authenticatedMemberId, it, nowMonotonicMs)
        }

    private fun state(members: List<CrewMember> = listOf(CrewMember(coordinatorId, "Coordinator"), CrewMember(joiningMemberId, "Joining"))) =
        CrewState(
            sessionId = sessionId,
            protocolVersion = protocol,
            term = CoordinatorTerm(1),
            lastSequence = EventSequence(1),
            coordinatorMemberId = coordinatorId,
            members = members,
        )
}
