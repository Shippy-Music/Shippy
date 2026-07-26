/*
 * Copyright (c) 2026 Shippy contributors
 * CrewSessionCapacityTest.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.crew.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion

class CrewSessionCapacityTest {
    private val protocol = ProtocolVersion(1)

    @Test
    fun `session admits through eight members and then closes capacity`() {
        assertTrue(CrewSessionCapacity.hasRoom(state(CREW_MAX_SESSION_MEMBERS - 1)))
        assertFalse(CrewSessionCapacity.hasRoom(state(CREW_MAX_SESSION_MEMBERS)))
    }

    private fun state(memberCount: Int): CrewState {
        val members =
            List(memberCount) { index ->
                CrewMember(CrewMemberId("member-$index", protocol), "Member $index")
            }
        return CrewState(
            sessionId = CrewSessionId("session", protocol),
            protocolVersion = protocol,
            term = CoordinatorTerm(1),
            lastSequence = EventSequence(0),
            coordinatorMemberId = members.first().id,
            members = members,
        )
    }
}
