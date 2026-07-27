/*
 * Copyright (c) 2026 Auxio Project
 * CrewRelayIceServerProviderTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.invite.CrewRelayLocator

class CrewRelayIceServerProviderTest {
    @Test
    fun `ice URL keeps relay origin and discards endpoint path`() {
        assertEquals(
            "https://relay.example.com:8443/v1/ice",
            CrewRelayIceUrl.derive(CrewRelayLocator("https://relay.example.com:8443/v1/crew")),
        )
    }

    @Test
    fun `parser accepts bounded unexpired turn credentials`() {
        val parsed =
            CrewRelayIceResponseParser.parse(
                200,
                """{"iceServers":[{"urls":["turn:turn.example.com:3478?transport=udp","turns:turn.example.com:5349"],"username":"u","credential":"c"}],"expiresAtEpochMs":2000}""",
                nowEpochMs = 1000,
            )
        assertEquals(
            listOf("turn:turn.example.com:3478?transport=udp", "turns:turn.example.com:5349"),
            parsed?.single()?.urls,
        )
        assertEquals("u", parsed?.single()?.username)
    }

    @Test
    fun `parser rejects expired malformed or non-turn credentials`() {
        assertNull(
            CrewRelayIceResponseParser.parse(
                200,
                """{"iceServers":[],"expiresAtEpochMs":2000}""",
                1000,
            )
        )
        assertNull(
            CrewRelayIceResponseParser.parse(
                200,
                """{"iceServers":[{"urls":["stun:stun.example.com:3478"],"username":"u","credential":"c"}],"expiresAtEpochMs":1000}""",
                1000,
            )
        )
        assertNull(
            CrewRelayIceResponseParser.parse(
                200,
                """{"iceServers":[{"urls":["turn:turn.example.com:3478"],"username":"u","credential":"c"}],"expiresAtEpochMs":7200001}""",
                1000,
            )
        )
    }
}
