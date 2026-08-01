/*
 * Copyright (c) 2026 Auxio Project
 * CrewPreconnectedPeerTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.connection.CrewDirectPeerFailure
import org.oxycblt.auxio.shippy.crew.connection.CrewDirectPeerState
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalConnectionState
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalSendResult
import org.oxycblt.auxio.shippy.crew.signaling.CrewSignalMessage
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport
import org.oxycblt.auxio.shippy.crew.transport.CrewSendResult
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportDrop
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportState

class CrewPreconnectedPeerTest {
    private val protocol = ProtocolVersion(1)
    private val remoteId = CrewMemberId("remote", protocol)

    @Test
    fun `connected proximity transport enters the existing direct-peer state machine`() {
        val transport = FakeTransport(remoteId)
        val handle = CrewPreconnectedConnectionHandle(FakePeer(transport))

        handle.start()

        val connected = handle.state.value
        assertTrue(connected is CrewDirectPeerState.Connected)
        assertSame(transport, (connected as CrewDirectPeerState.Connected).transport)
        handle.close()
        assertEquals(CrewDirectPeerState.Closed, handle.state.value)
        assertEquals(CrewTransportState.CLOSED, transport.state.value)
    }

    @Test
    fun `transport failure is mirrored to admission and reconnect owners`() = runBlocking {
        val transport = FakeTransport(remoteId)
        val handle = CrewPreconnectedConnectionHandle(FakePeer(transport))
        handle.start()

        transport.fail()

        val failed = withTimeout(2_000) { handle.state.first { it is CrewDirectPeerState.Failed } }
        assertEquals(CrewDirectPeerState.Failed(CrewDirectPeerFailure.TRANSPORT), failed)
        handle.close()
    }

    private class FakePeer(override val preconnectedTransport: CrewPeerTransport) :
        CrewPreconnectedSignalPeer {
        override val sessionId = CrewSessionId("session", ProtocolVersion(1))
        override val remoteMemberClaim = preconnectedTransport.remoteMemberId
        override val remoteDisplayName = "Nearby member"
        override val state = MutableStateFlow(CrewSignalConnectionState.CONNECTED)
        override val incoming: Flow<CrewSignalMessage> = MutableSharedFlow()

        override suspend fun send(message: CrewSignalMessage) = CrewSignalSendResult.Failed

        override fun close() {
            preconnectedTransport.close()
            state.value = CrewSignalConnectionState.CLOSED
        }
    }

    private class FakeTransport(override val remoteMemberId: CrewMemberId) : CrewPeerTransport {
        override val state = MutableStateFlow(CrewTransportState.CONNECTED)
        override val incoming: Flow<CrewTransportFrame> = MutableSharedFlow()
        override val drops: Flow<CrewTransportDrop> = MutableSharedFlow()

        override fun trySend(frame: CrewTransportFrame) = CrewSendResult.Sent(0)

        override fun bufferedBytes(channel: CrewTransportChannel) = 0L

        fun fail() {
            state.value = CrewTransportState.FAILED
        }

        override fun close() {
            state.value = CrewTransportState.CLOSED
        }
    }
}
