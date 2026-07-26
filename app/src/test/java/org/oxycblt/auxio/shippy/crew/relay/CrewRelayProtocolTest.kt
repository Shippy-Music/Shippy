/*
 * Copyright (c) 2026 Shippy contributors
 * CrewRelayProtocolTest.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.crew.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.invite.CrewInvite
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteId
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteSecret
import org.oxycblt.auxio.shippy.crew.invite.CrewRelayLocator
import org.oxycblt.auxio.shippy.crew.invite.CrewSessionLocator

class CrewRelayProtocolTest {
    private val routeBytes = ByteArray(16) { it.toByte() }
    private val route = CrewRelayRouteId(routeBytes)
    private val invite =
        CrewInvite(
            ProtocolVersion(1),
            CrewSessionLocator("session_123"),
            CrewInviteId("invite_123"),
            CrewInviteSecret("secret_12345678901234567890"),
            1,
            2,
            CrewRelayLocator("https://relay.example.com/v1/crew"),
        )

    @Test
    fun `golden register wire matches node relay v1`() {
        val encoded =
            CrewRelayCodec.encode(
                CrewRelayFrame.Register(CrewRelayRole.HOST, 1, "s".toByteArray(), "i".toByteArray()),
            )
        assertArrayEquals(byteArrayOf(1, 1, 1, 1, 1, 's'.code.toByte(), 1, 'i'.code.toByte()), encoded)
    }

    @Test
    fun `golden route data and heartbeat wires match node relay v1`() {
        assertArrayEquals(byteArrayOf(1, 2, 2, 1) + routeBytes, CrewRelayCodec.encode(CrewRelayFrame.Registered(CrewRelayRole.JOIN, route)))
        assertArrayEquals(byteArrayOf(1, 3) + routeBytes + byteArrayOf(0), CrewRelayCodec.encode(CrewRelayFrame.RouteOpen(route)))
        assertArrayEquals(byteArrayOf(1, 4) + routeBytes + byteArrayOf(0), CrewRelayCodec.encode(CrewRelayFrame.RouteClose(route)))
        assertArrayEquals(byteArrayOf(1, 5) + routeBytes + byteArrayOf(9), CrewRelayCodec.encode(CrewRelayFrame.Data(route, byteArrayOf(9))))
        assertArrayEquals(byteArrayOf(1, 6, 3, 'B'.code.toByte(), 'A'.code.toByte(), 'D'.code.toByte()), CrewRelayCodec.encode(CrewRelayFrame.Error("BAD".toByteArray())))
        assertArrayEquals(byteArrayOf(1, 7), CrewRelayCodec.encode(CrewRelayFrame.Ping))
        assertArrayEquals(byteArrayOf(1, 8), CrewRelayCodec.encode(CrewRelayFrame.Pong))
    }

    @Test
    fun `host resume wire carries only exact opaque credential`() {
        val token = ByteArray(CREW_RELAY_RESUME_TOKEN_BYTES) { 7 }
        val registered = CrewRelayCodec.encode(CrewRelayFrame.Registered(CrewRelayRole.HOST, resumeToken = token))
        assertArrayEquals(byteArrayOf(1, 2, 1, 0) + token, registered)
        assertEquals(
            CrewRelayFrame.Registered(CrewRelayRole.HOST, resumeToken = token).role,
            (CrewRelayCodec.decode(registered) as CrewRelayFrame.Registered).role,
        )
        assertArrayEquals(
            token,
            (CrewRelayCodec.decode(registered) as CrewRelayFrame.Registered).resumeToken,
        )
        assertArrayEquals(
            byteArrayOf(1, 9, 1, 1, 's'.code.toByte(), 1, 'i'.code.toByte()) + token,
            CrewRelayCodec.encode(CrewRelayFrame.HostResume(1, "s".toByteArray(), "i".toByteArray(), token)),
        )
        assertFails {
            CrewRelayCodec.encode(CrewRelayFrame.HostResume(1, "s".toByteArray(), "i".toByteArray(), ByteArray(31)))
        }
    }

    @Test
    fun `directional crypto rejects wrong relay identity secret and replay`() {
        val host = CrewRelayRouteCrypto(invite, route, CrewRelayRole.HOST)
        val join = CrewRelayRouteCrypto(invite, route, CrewRelayRole.JOIN)
        val frame = host.encrypt("private SDP".toByteArray())
        assertArrayEquals("private SDP".toByteArray(), join.decrypt(frame))
        assertFails { join.decrypt(frame) }
        assertFails { CrewRelayRouteCrypto(invite.copy(secret = CrewInviteSecret("different_123456789012345678")), route, CrewRelayRole.JOIN).decrypt(frame) }
        assertFails { CrewRelayRouteCrypto(invite.copy(relayLocator = CrewRelayLocator("https://other.example.com/v1/crew")), route, CrewRelayRole.JOIN).decrypt(frame) }
    }

    @Test
    fun `hello rejects malformed utf8 and oversized values`() {
        assertEquals(CrewRelayHello("member", "name"), CrewRelayHelloCodec.decode(CrewRelayHelloCodec.encode(CrewRelayHello("member", "name"))))
        assertFails { CrewRelayHelloCodec.encode(CrewRelayHello("member", "n".repeat(81))) }
        assertFails { CrewRelayHelloCodec.decode(byteArrayOf(1, 0, 1, 0x80.toByte(), 0, 1, 'n'.code.toByte())) }
    }

    @Test
    fun `relay url changes only scheme`() {
        assertEquals("wss://relay.example.com:9443/custom/%2Fpath", CrewRelayLocatorWebSocketUrl("https://relay.example.com:9443/custom/%2Fpath"))
    }

    private fun assertFails(action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected failure")
        } catch (_: Exception) {
        }
    }
}
