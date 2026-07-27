/*
 * Copyright (c) 2026 Auxio Project
 * CrewSessionCapacityTest.kt is part of Auxio.
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
