/*
 * Copyright (c) 2026 Shippy contributors
 * CrewHostAdmissionCoordinatorTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.connection.CrewDirectPeerState
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.DurableEventId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalConnectionState
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalPeer
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalSendResult
import org.oxycblt.auxio.shippy.crew.session.CrewAdmissionRejection
import org.oxycblt.auxio.shippy.crew.session.CrewAdmissionResult
import org.oxycblt.auxio.shippy.crew.signaling.CrewSignalMessage
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport
import org.oxycblt.auxio.shippy.crew.transport.CrewSendResult
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportDrop
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportState

class CrewHostAdmissionCoordinatorTest {
    private val protocol = ProtocolVersion(1)
    private val sessionId = CrewSessionId("session", protocol)
    private val localMemberId = CrewMemberId("host", protocol)

    @Test
    fun `connected matching transport attaches and admits claimed display name`() = runBlocking {
        val admission = FakeAdmissionPort()
        val factory = FakeFactory()
        val coordinator = coordinator(admission, factory)
        val peer = FakeSignalPeer(member("guest"), "Guest Name")

        coordinator.accept(peer)
        val handle = factory.handle(peer)
        handle.connected(FakeTransport(member("guest")))

        coordinator.awaitState(member("guest")) { it is CrewHostAdmissionState.Active }
        assertEquals(listOf(member("guest")), admission.attached.map(CrewPeerTransport::remoteMemberId))
        assertEquals(CrewMember(member("guest"), "Guest Name"), admission.admittedMember)
        assertEquals(DurableEventId("admission-1"), admission.admittedRequestId)
        coordinator.close()
    }

    @Test
    fun `mismatched transport never attaches`() = runBlocking {
        val admission = FakeAdmissionPort()
        val factory = FakeFactory()
        val coordinator = coordinator(admission, factory)
        val peer = FakeSignalPeer(member("guest"), "Untrusted")

        coordinator.accept(peer)
        factory.handle(peer).connected(FakeTransport(member("other")))

        assertEquals(
            CrewHostAdmissionState.Rejected(CrewHostAdmissionFailure.MemberIdMismatch),
            coordinator.awaitState(member("guest")) { it is CrewHostAdmissionState.Rejected },
        )
        assertTrue(admission.attached.isEmpty())
        coordinator.close()
    }

    @Test
    fun `invalid later signal does not replace active member state`() = runBlocking {
        val admission = FakeAdmissionPort()
        val factory = FakeFactory()
        val coordinator = coordinator(admission, factory)
        val activePeer = FakeSignalPeer(member("guest"), "Guest")

        coordinator.accept(activePeer)
        val activeHandle = factory.handle(activePeer)
        activeHandle.connected(FakeTransport(member("guest")))
        val active = coordinator.awaitState(member("guest")) { it is CrewHostAdmissionState.Active }
        val invalidPeer =
            FakeSignalPeer(member("guest"), "Forged", CrewSessionId("other-session", protocol))

        coordinator.accept(invalidPeer)

        assertEquals(active, coordinator.states.value[member("guest")])
        assertFalse(activeHandle.closed)
        assertTrue(invalidPeer.closed)
        coordinator.close()
    }

    @Test
    fun `duplicate claim replaces and closes exact prior handle`() = runBlocking {
        val admission = FakeAdmissionPort()
        val factory = FakeFactory()
        val coordinator = coordinator(admission, factory)
        val firstPeer = FakeSignalPeer(member("guest"), "First")
        val secondPeer = FakeSignalPeer(member("guest"), "Second")

        coordinator.accept(firstPeer)
        val first = factory.handle(firstPeer)
        coordinator.accept(secondPeer)
        val second = factory.handle(secondPeer)

        coordinator.awaitState(member("guest")) { it is CrewHostAdmissionState.Connecting }
        assertTrue(first.closed)
        assertTrue(firstPeer.closed)
        assertFalse(second.closed)
        coordinator.close()
    }

    @Test
    fun `rejected admission closes only rejected peer`() = runBlocking {
        val admission = FakeAdmissionPort(result = CrewAdmissionResult.Rejected(CrewAdmissionRejection.SEQUENCER_REJECTED))
        val factory = FakeFactory()
        val coordinator = coordinator(admission, factory)
        val rejectedPeer = FakeSignalPeer(member("rejected"), "Rejected")
        val otherPeer = FakeSignalPeer(member("other"), "Other")

        coordinator.accept(rejectedPeer)
        val rejected = factory.handle(rejectedPeer)
        coordinator.accept(otherPeer)
        val other = factory.handle(otherPeer)
        rejected.connected(FakeTransport(member("rejected")))

        coordinator.awaitState(member("rejected")) { it is CrewHostAdmissionState.Rejected }
        assertTrue(rejected.closed)
        assertTrue(rejectedPeer.closed)
        assertFalse(other.closed)
        assertFalse(otherPeer.closed)
        coordinator.close()
    }

    @Test
    fun `coordinator close closes every active handle and signal peer`() = runBlocking {
        val factory = FakeFactory()
        val coordinator = coordinator(FakeAdmissionPort(), factory)
        val onePeer = FakeSignalPeer(member("one"), "One")
        val twoPeer = FakeSignalPeer(member("two"), "Two")

        coordinator.accept(onePeer)
        val one = factory.handle(onePeer)
        coordinator.accept(twoPeer)
        val two = factory.handle(twoPeer)
        coordinator.close()

        assertTrue(one.closed && two.closed)
        assertTrue(onePeer.closed && twoPeer.closed)
    }

    private fun coordinator(admission: FakeAdmissionPort, factory: FakeFactory) =
        CrewHostAdmissionCoordinator(
            sessionId,
            localMemberId,
            admission,
            factory,
            CrewAdmissionEventIdSource { DurableEventId("admission-1") },
            Dispatchers.Unconfined,
        )

    private fun member(value: String) = CrewMemberId(value, protocol)

    private suspend fun CrewHostAdmissionCoordinator.awaitState(
        memberId: CrewMemberId,
        predicate: (CrewHostAdmissionState) -> Boolean,
    ): CrewHostAdmissionState =
        withTimeout(2_000) { states.filter { predicate(it[memberId] ?: return@filter false) }.first()[memberId]!! }

    private class FakeAdmissionPort(
        private val result: CrewAdmissionResult? = null,
    ) : CrewSessionAdmissionPort {
        val attached = mutableListOf<CrewPeerTransport>()
        var admittedMember: CrewMember? = null
        var admittedRequestId: DurableEventId? = null

        override fun attachPeer(transport: CrewPeerTransport) {
            attached += transport
        }

        override suspend fun admitPeer(
            transportMemberId: CrewMemberId,
            member: CrewMember,
            requestId: DurableEventId,
        ): CrewAdmissionResult {
            admittedMember = member
            admittedRequestId = requestId
            return result ?: CrewAdmissionResult.AlreadyActive(member)
        }
    }

    private class FakeFactory : CrewDirectResponderHandleFactory {
        private val handles = mutableMapOf<CrewSignalPeer, FakeHandle>()

        override fun createResponder(signalingPeer: CrewSignalPeer) =
            FakeHandle().also { handles[signalingPeer] = it }

        fun handle(peer: CrewSignalPeer) = checkNotNull(handles[peer])
    }

    private class FakeHandle : CrewDirectConnectionHandle {
        private val mutableState = MutableStateFlow<CrewDirectPeerState>(CrewDirectPeerState.New)
        override val state: StateFlow<CrewDirectPeerState> = mutableState
        var started = false
        var closed = false

        override fun start() {
            started = true
        }

        fun connected(transport: CrewPeerTransport) {
            mutableState.value = CrewDirectPeerState.Connected(transport)
        }

        override fun close() {
            closed = true
            mutableState.value = CrewDirectPeerState.Closed
        }
    }

    private class FakeSignalPeer(
        override val remoteMemberClaim: CrewMemberId,
        override val remoteDisplayName: String,
        override val sessionId: CrewSessionId = CrewSessionId("session", remoteMemberClaim.protocolVersion),
    ) : CrewSignalPeer {
        override val state = MutableStateFlow(CrewSignalConnectionState.CONNECTED)
        override val incoming: Flow<CrewSignalMessage> = MutableSharedFlow()
        var closed = false

        override suspend fun send(message: CrewSignalMessage) = CrewSignalSendResult.Sent

        override fun close() {
            closed = true
            state.value = CrewSignalConnectionState.CLOSED
        }
    }

    private class FakeTransport(
        override val remoteMemberId: CrewMemberId,
    ) : CrewPeerTransport {
        override val state = MutableStateFlow(CrewTransportState.CONNECTED)
        override val incoming: Flow<CrewTransportFrame> = MutableSharedFlow()
        override val drops: Flow<CrewTransportDrop> = MutableSharedFlow()

        override fun trySend(frame: CrewTransportFrame) = CrewSendResult.Sent(0)

        override fun bufferedBytes(channel: CrewTransportChannel) = 0L

        override fun close() = Unit
    }
}
