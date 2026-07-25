/*
 * Copyright (c) 2026 Shippy contributors
 * CrewJoinCoordinatorTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.connection.CrewDirectPeerFailure
import org.oxycblt.auxio.shippy.crew.connection.CrewDirectPeerState
import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewSnapshot
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.core.toSnapshot
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalConnectionState
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalPeer
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalSendResult
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlFramer
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlMessage
import org.oxycblt.auxio.shippy.crew.signaling.CrewSignalMessage
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport
import org.oxycblt.auxio.shippy.crew.transport.CrewSendResult
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportDrop
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportState

class CrewJoinCoordinatorTest {
    private val protocol = ProtocolVersion(1)
    private val sessionId = CrewSessionId("session", protocol)
    private val localId = CrewMemberId("joiner", protocol)
    private val coordinatorId = CrewMemberId("coordinator", protocol)

    @Test
    fun `authenticated snapshot starts exact joined session and attaches same transport`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.start()
        fixture.handle.connected(fixture.transport)
        fixture.coordinator.await { it == CrewJoinState.Bootstrapping }

        val snapshot = snapshot()
        fixture.transport.receive(CrewControlFramer.encode(CrewControlMessage.SnapshotInstalled(snapshot)).single())

        assertEquals(CrewJoinState.Active(snapshot), fixture.coordinator.await { it is CrewJoinState.Active })
        assertEquals(listOf(snapshot), fixture.sessions.snapshots)
        assertEquals(1, fixture.session.starts)
        assertEquals(listOf(fixture.transport), fixture.session.attached)
        fixture.coordinator.close()
    }

    @Test
    fun `transport identity mismatch closes owned attempt`() = runBlocking {
        val fixture = fixture(transportRemote = CrewMemberId("other", protocol))
        fixture.coordinator.start()
        fixture.handle.connected(fixture.transport)

        assertEquals(
            CrewJoinState.Rejected(CrewJoinFailure.MemberIdMismatch),
            fixture.coordinator.await { it is CrewJoinState.Rejected },
        )
        assertTrue(fixture.handle.closed && fixture.peer.closed)
        assertTrue(fixture.sessions.snapshots.isEmpty())
    }

    @Test
    fun `bootstrap rejection closes handle signal and no session`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.start()
        fixture.handle.connected(fixture.transport)
        fixture.coordinator.await { it == CrewJoinState.Bootstrapping }
        fixture.transport.receive(CrewTransportFrame(CrewTransportChannel.MEDIA, byteArrayOf(1)))

        val state = fixture.coordinator.await { it is CrewJoinState.Rejected } as CrewJoinState.Rejected
        assertEquals(CrewJoinFailure.BootstrapRejected(CrewJoinBootstrapResult.Rejected.WrongChannel), state.reason)
        assertTrue(fixture.handle.closed && fixture.peer.closed)
        assertTrue(fixture.sessions.snapshots.isEmpty())
    }

    @Test
    fun `connection setup failure is distinct and closes signaling`() {
        val peer = FakeSignalPeer(coordinatorId)
        val session = FakeSession(startFails = false)
        val coordinator =
            CrewJoinCoordinator(
                sessionId,
                localId,
                peer,
                CrewDirectInitiatorHandleFactory { error("factory failed") },
                FakeSessions(session),
                dispatcher = Dispatchers.Unconfined,
            )

        coordinator.start()

        assertEquals(
            CrewJoinState.Rejected(CrewJoinFailure.ConnectionSetupFailed),
            coordinator.state.value,
        )
        assertTrue(peer.closed)
        assertFalse(session.closed)
    }

    @Test
    fun `timeout connection failure and explicit close have precise rejection`() = runBlocking {
        val timeout = fixture(timeoutMs = 1)
        timeout.coordinator.start()
        assertEquals(CrewJoinState.Rejected(CrewJoinFailure.TimedOut), timeout.coordinator.await { it is CrewJoinState.Rejected })
        assertTrue(timeout.handle.closed && timeout.peer.closed)

        val failed = fixture()
        failed.coordinator.start()
        failed.handle.fail(CrewDirectPeerFailure.NEGOTIATION)
        assertEquals(
            CrewJoinState.Rejected(CrewJoinFailure.ConnectionFailed(CrewDirectPeerFailure.NEGOTIATION)),
            failed.coordinator.await { it is CrewJoinState.Rejected },
        )

        val closed = fixture()
        closed.coordinator.start()
        closed.coordinator.close()
        assertEquals(CrewJoinState.Rejected(CrewJoinFailure.Closed), closed.coordinator.state.value)
        assertTrue(closed.handle.closed && closed.peer.closed)
    }

    @Test
    fun `duplicate connected states and snapshots activate once`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.start()
        fixture.handle.connected(fixture.transport)
        fixture.coordinator.await { it == CrewJoinState.Bootstrapping }
        val frame = CrewControlFramer.encode(CrewControlMessage.SnapshotInstalled(snapshot())).single()
        fixture.transport.receive(frame)
        fixture.coordinator.await { it is CrewJoinState.Active }
        fixture.handle.connected(fixture.transport)
        fixture.transport.receive(frame)

        assertEquals(1, fixture.sessions.snapshots.size)
        assertEquals(1, fixture.session.starts)
        fixture.coordinator.close()
    }

    @Test
    fun `session failure remains isolated and closes all owned resources`() = runBlocking {
        val fixture = fixture(sessionStartFails = true)
        fixture.coordinator.start()
        fixture.handle.connected(fixture.transport)
        fixture.coordinator.await { it == CrewJoinState.Bootstrapping }
        fixture.transport.receive(CrewControlFramer.encode(CrewControlMessage.SnapshotInstalled(snapshot())).single())

        assertEquals(CrewJoinState.Rejected(CrewJoinFailure.SessionFailure), fixture.coordinator.await { it is CrewJoinState.Rejected })
        assertTrue(fixture.handle.closed && fixture.peer.closed && fixture.session.closed)
    }

    private fun fixture(
        timeoutMs: Long = 2_000,
        transportRemote: CrewMemberId = coordinatorId,
        sessionStartFails: Boolean = false,
    ): Fixture {
        val peer = FakeSignalPeer(coordinatorId)
        val handle = FakeHandle()
        val transport = FakeTransport(transportRemote)
        val session = FakeSession(sessionStartFails)
        val sessions = FakeSessions(session)
        return Fixture(
            CrewJoinCoordinator(sessionId, localId, peer, CrewDirectInitiatorHandleFactory { handle }, sessions, timeoutMs, { 1L }, Dispatchers.Unconfined),
            peer,
            handle,
            transport,
            session,
            sessions,
        )
    }

    private fun snapshot(): CrewSnapshot =
        CrewState(sessionId, protocol, CoordinatorTerm(1), EventSequence(1), coordinatorId, listOf(CrewMember(coordinatorId, "Coordinator"), CrewMember(localId, "Joiner"))).toSnapshot()

    private suspend fun CrewJoinCoordinator.await(predicate: (CrewJoinState) -> Boolean): CrewJoinState =
        withTimeout(2_000) { state.filter(predicate).first() }

    private data class Fixture(
        val coordinator: CrewJoinCoordinator,
        val peer: FakeSignalPeer,
        val handle: FakeHandle,
        val transport: FakeTransport,
        val session: FakeSession,
        val sessions: FakeSessions,
    )

    private class FakeSessions(private val session: FakeSession) : CrewJoinSessionFactory {
        val snapshots = mutableListOf<CrewSnapshot>()
        override fun create(snapshot: CrewSnapshot) = session.also { snapshots += snapshot }
    }

    private class FakeSession(private val startFails: Boolean) : CrewJoinedSessionPort {
        var starts = 0
        var closed = false
        val attached = mutableListOf<CrewPeerTransport>()
        override suspend fun start() { starts += 1; check(!startFails) }
        override fun attachPeer(transport: CrewPeerTransport) { attached += transport }
        override fun close() { closed = true }
    }

    private class FakeHandle : CrewDirectConnectionHandle {
        private val mutableState = MutableStateFlow<CrewDirectPeerState>(CrewDirectPeerState.New)
        override val state: StateFlow<CrewDirectPeerState> = mutableState
        var closed = false
        override fun start() = Unit
        fun connected(transport: CrewPeerTransport) { mutableState.value = CrewDirectPeerState.Connected(transport) }
        fun fail(reason: CrewDirectPeerFailure) { mutableState.value = CrewDirectPeerState.Failed(reason) }
        override fun close() { closed = true; mutableState.value = CrewDirectPeerState.Closed }
    }

    private class FakeSignalPeer(override val remoteMemberClaim: CrewMemberId) : CrewSignalPeer {
        override val remoteDisplayName = "Coordinator"
        override val sessionId = CrewSessionId("session", remoteMemberClaim.protocolVersion)
        override val state = MutableStateFlow(CrewSignalConnectionState.CONNECTED)
        override val incoming: Flow<CrewSignalMessage> = MutableSharedFlow()
        var closed = false
        override suspend fun send(message: CrewSignalMessage) = CrewSignalSendResult.Sent
        override fun close() { closed = true; state.value = CrewSignalConnectionState.CLOSED }
    }

    private class FakeTransport(override val remoteMemberId: CrewMemberId) : CrewPeerTransport {
        private val frames = MutableSharedFlow<CrewTransportFrame>(extraBufferCapacity = 8)
        override val state = MutableStateFlow(CrewTransportState.CONNECTED)
        override val incoming: Flow<CrewTransportFrame> = frames
        override val drops: Flow<CrewTransportDrop> = MutableSharedFlow()
        override fun trySend(frame: CrewTransportFrame) = CrewSendResult.Sent(0)
        override fun bufferedBytes(channel: CrewTransportChannel) = 0L
        fun receive(frame: CrewTransportFrame) { assertTrue(frames.tryEmit(frame)) }
        override fun close() = Unit
    }
}
