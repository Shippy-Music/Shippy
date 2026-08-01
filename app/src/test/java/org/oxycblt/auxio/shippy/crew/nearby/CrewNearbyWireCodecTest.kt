/*
 * Copyright (c) 2026 Auxio Project
 * CrewNearbyWireCodecTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.invite.CrewInvite
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteId
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteSecret
import org.oxycblt.auxio.shippy.crew.invite.CrewSessionLocator
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame

class CrewNearbyWireCodecTest {
    private val protocol = ProtocolVersion(1)
    private val invite =
        CrewInvite(
            protocol,
            CrewSessionLocator("session-locator"),
            CrewInviteId("invite-id"),
            CrewInviteSecret("0123456789abcdefghijklmnop"),
            issuedAtEpochMs = 1_000,
            expiresAtEpochMs = 61_000,
        )
    private val client = CrewMember(CrewMemberId("client", protocol), "Client")
    private val host = CrewMember(CrewMemberId("host", protocol), "Host")
    private val sessionId = CrewSessionId("session", protocol)

    @Test
    fun `endpoint identity never contains invitation secret`() {
        val text = NearbyWireCodec.endpointInfo(invite).toString(Charsets.ISO_8859_1)

        assertFalse(text.contains(invite.secret.value))
        assertTrue(text.contains(invite.sessionLocator.value))
        assertTrue(text.contains(invite.inviteId.value))
    }

    @Test
    fun `hello proof is invite bound and tampering is rejected`() {
        val nonce = ByteArray(32) { it.toByte() }
        val hello = NearbyWireCodec.decodeHandshake(NearbyWireCodec.hello(invite, client, nonce))
        require(hello is NearbyHandshake.Hello)

        assertTrue(NearbyWireCodec.verifyHello(invite, hello))
        assertFalse(NearbyWireCodec.verifyHello(invite, hello.copy(displayName = "Mallory")))
    }

    @Test
    fun `challenge and finish bind both authenticated member identities`() {
        val clientNonce = ByteArray(32) { it.toByte() }
        val serverNonce = ByteArray(32) { (it + 32).toByte() }
        val challenge =
            NearbyWireCodec.decodeHandshake(
                NearbyWireCodec.challenge(
                    invite,
                    sessionId,
                    host,
                    client.id,
                    clientNonce,
                    serverNonce,
                )
            )
        require(challenge is NearbyHandshake.Challenge)
        assertTrue(NearbyWireCodec.verifyChallenge(invite, client.id, clientNonce, challenge))

        val finish =
            NearbyWireCodec.decodeHandshake(
                NearbyWireCodec.finish(
                    invite,
                    sessionId,
                    client.id,
                    host.id,
                    clientNonce,
                    serverNonce,
                )
            )
        require(finish is NearbyHandshake.Finish)
        assertTrue(
            NearbyWireCodec.verifyFinish(
                invite,
                sessionId,
                client.id,
                host.id,
                clientNonce,
                serverNonce,
                finish,
            )
        )
        assertFalse(
            NearbyWireCodec.verifyFinish(
                invite,
                sessionId,
                CrewMemberId("other", protocol),
                host.id,
                clientNonce,
                serverNonce,
                finish,
            )
        )
    }

    @Test
    fun `transport frame round trips with channel and bytes intact`() {
        val original = CrewTransportFrame(CrewTransportChannel.CONTROL, byteArrayOf(1, 2, 3, 4))

        val decoded = NearbyWireCodec.decodeFrame(NearbyWireCodec.frame(original))

        assertEquals(original, decoded)
    }
}
