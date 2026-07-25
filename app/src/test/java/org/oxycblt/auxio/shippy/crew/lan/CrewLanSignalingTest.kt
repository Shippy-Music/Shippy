/*
 * Copyright (c) 2026 Shippy contributors
 * CrewLanSignalingTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.lan

import java.net.InetAddress
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.invite.CrewInvite
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteId
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteSecret
import org.oxycblt.auxio.shippy.crew.invite.CrewSessionLocator
import org.oxycblt.auxio.shippy.crew.signaling.CrewSignalCloseReason
import org.oxycblt.auxio.shippy.crew.signaling.CrewSignalMessage

class CrewLanSignalingTest {
    @Test
    fun `authenticated loopback peers exchange encrypted signals in both directions`() =
        runBlocking {
            val fixture = Fixture()
            val host =
                CrewLanSignalingHost(
                    fixture.invite,
                    fixture.sessionId,
                    fixture.hostMemberId,
                    fixture.now,
                )
            try {
                val accepted = async { withTimeout(5_000) { host.peers.first() } }
                val result =
                    CrewLanSignalingClient.connect(
                        fixture.rendezvous(host.port),
                        fixture.invite,
                        fixture.joinerMemberId,
                        fixture.now,
                    )
                assertTrue(result is CrewLanSignalConnectResult.Connected)
                val clientPeer = (result as CrewLanSignalConnectResult.Connected).peer
                val hostPeer = accepted.await()
                try {
                    assertEquals(fixture.sessionId, clientPeer.sessionId)
                    assertEquals(fixture.hostMemberId, clientPeer.remoteMemberClaim)
                    assertEquals(fixture.joinerMemberId, hostPeer.remoteMemberClaim)

                    val fromClient = CrewSignalMessage.EndOfCandidates(4)
                    assertEquals(CrewSignalSendResult.Sent, clientPeer.send(fromClient))
                    assertEquals(fromClient, withTimeout(5_000) { hostPeer.incoming.first() })

                    val fromHost =
                        CrewSignalMessage.Close(CrewSignalCloseReason.SESSION_ENDED)
                    assertEquals(CrewSignalSendResult.Sent, hostPeer.send(fromHost))
                    assertEquals(fromHost, withTimeout(5_000) { clientPeer.incoming.first() })
                } finally {
                    clientPeer.close()
                    hostPeer.close()
                }
            } finally {
                host.close()
            }
        }

    @Test
    fun `wrong invitation secret cannot establish a signaling peer`() =
        runBlocking {
            val fixture = Fixture()
            val host =
                CrewLanSignalingHost(
                    fixture.invite,
                    fixture.sessionId,
                    fixture.hostMemberId,
                    fixture.now,
                )
            try {
                val result =
                    CrewLanSignalingClient.connect(
                        fixture.rendezvous(host.port),
                        fixture.invite.copy(
                            secret = CrewInviteSecret("wrong_secret_12345678901234567890"),
                        ),
                        fixture.joinerMemberId,
                        fixture.now,
                    )

                assertEquals(
                    CrewLanSignalConnectResult.Failed(
                        CrewLanSignalConnectFailure.AUTHENTICATION_FAILED,
                    ),
                    result,
                )
            } finally {
                host.close()
            }
        }

    @Test(expected = IllegalArgumentException::class)
    fun `host rejects an invitation that is not short lived`() {
        val fixture = Fixture()
        CrewLanSignalingHost(
            fixture.invite.copy(expiresAtEpochMs = fixture.now + 16 * 60 * 1000L),
            fixture.sessionId,
            fixture.hostMemberId,
            fixture.now,
        )
    }

    private class Fixture {
        val now = System.currentTimeMillis()
        private val protocolVersion = ProtocolVersion(1)
        val invite =
            CrewInvite(
                protocolVersion,
                CrewSessionLocator("session_locator"),
                CrewInviteId("invite_id"),
                CrewInviteSecret("correct_secret_123456789012345678"),
                now - 1_000,
                now + 60_000,
            )
        val sessionId = CrewSessionId("canonical_session", protocolVersion)
        val hostMemberId = CrewMemberId("host_member", protocolVersion)
        val joinerMemberId = CrewMemberId("joiner_member", protocolVersion)

        fun rendezvous(port: Int) =
            CrewLanRendezvous(
                CrewLanIdentity(
                    invite.protocolVersion,
                    invite.sessionLocator,
                    invite.inviteId,
                ),
                "Shippy test",
                listOf(InetAddress.getLoopbackAddress()),
                port,
                null,
            )
    }
}
