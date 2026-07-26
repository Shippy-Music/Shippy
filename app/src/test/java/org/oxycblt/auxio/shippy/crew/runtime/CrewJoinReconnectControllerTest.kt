/*
 * Copyright (c) 2026 Shippy contributors
 * CrewJoinReconnectControllerTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.runtime

import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport
import org.oxycblt.auxio.shippy.crew.transport.CrewSendResult
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportDrop
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportState

class CrewJoinReconnectControllerTest {
    private val protocol = ProtocolVersion(1)
    private val local = member("local")
    private val coordinator = member("coordinator")
    private val replacement = member("replacement")

    @Test
    fun `does not dial while coordinator transport is connected`() = runBlocking {
        val fixture = fixture(connected = true)
        fixture.controller.start()
        delay(20)
        assertEquals(0, fixture.dialCalls)
        fixture.controller.close()
    }

    @Test
    fun `authenticated result attaches and requests canonical snapshot`() = runBlocking {
        val fixture = fixture()
        val connection = FakeConnection()
        val transport = FakeTransport(coordinator)
        fixture.results.send(CrewReconnectDialResult.Authenticated(connection, transport))
        fixture.controller.start()
        await { fixture.port.attached.size == 1 }
        assertEquals(listOf(transport), fixture.port.attached)
        assertEquals(1, fixture.port.snapshotRequests)
        assertFalse(connection.closed)
        fixture.controller.close()
    }

    @Test
    fun `failed snapshot request closes the replacement and retries`() = runBlocking {
        val fixture = fixture()
        fixture.port.snapshotRequestResult = false
        val connection = FakeConnection()
        fixture.results.send(
            CrewReconnectDialResult.Authenticated(connection, FakeTransport(coordinator))
        )
        fixture.results.send(CrewReconnectDialResult.Expired)
        fixture.controller.start()
        await { connection.closed && fixture.dialCalls >= 2 }
        assertEquals(CrewJoinReconnectState.Expired, fixture.controller.state.value)
        fixture.controller.close()
    }

    @Test
    fun `wrong member result is closed and retried`() = runBlocking {
        val fixture = fixture()
        val wrongConnection = FakeConnection()
        fixture.results.send(CrewReconnectDialResult.Authenticated(wrongConnection, FakeTransport(replacement)))
        fixture.results.send(CrewReconnectDialResult.Retryable)
        fixture.controller.start()
        await { wrongConnection.closed && fixture.dialCalls >= 2 }
        assertTrue(fixture.port.attached.isEmpty())
        fixture.controller.close()
    }

    @Test
    fun `coordinator change closes stale result without attach`() = runBlocking {
        val fixture = fixture()
        val connection = FakeConnection()
        fixture.dialBeforeResult = { fixture.port.setCoordinator(replacement) }
        fixture.results.send(CrewReconnectDialResult.Authenticated(connection, FakeTransport(coordinator)))
        fixture.results.send(CrewReconnectDialResult.Retryable)
        fixture.controller.start()
        await { connection.closed }
        assertTrue(fixture.port.attached.isEmpty())
        fixture.controller.close()
    }

    @Test
    fun `backoff is bounded and resets after success`() = runBlocking {
        val fixture = fixture(initialDelay = 1, maximumDelay = 2)
        fixture.results.send(CrewReconnectDialResult.Retryable)
        fixture.results.send(CrewReconnectDialResult.Retryable)
        fixture.results.send(CrewReconnectDialResult.Authenticated(FakeConnection(), FakeTransport(coordinator)))
        fixture.controller.start()
        await { fixture.port.snapshotRequests == 1 }
        fixture.port.setConnected(false)
        fixture.controller.wake()
        await { fixture.dialCalls >= 4 }
        assertEquals(CrewJoinReconnectState.Reconnecting(1), fixture.controller.state.value)
        fixture.controller.close()
    }

    @Test
    fun `wake short circuits retry delay`() = runBlocking {
        val fixture = fixture(initialDelay = 5_000, maximumDelay = 5_000)
        fixture.results.send(CrewReconnectDialResult.Retryable)
        fixture.results.send(CrewReconnectDialResult.Retryable)
        fixture.controller.start()
        await { fixture.controller.state.value is CrewJoinReconnectState.Waiting }
        fixture.controller.wake()
        await { fixture.dialCalls == 2 }
        fixture.controller.close()
    }

    @Test
    fun `expiry and close stop attempts`() = runBlocking {
        val expired = fixture()
        expired.results.send(CrewReconnectDialResult.Expired)
        expired.controller.start()
        await { expired.controller.state.value == CrewJoinReconnectState.Expired }
        expired.controller.wake()
        delay(10)
        assertEquals(1, expired.dialCalls)

        val closed = fixture(initialDelay = 5_000, maximumDelay = 5_000)
        closed.results.send(CrewReconnectDialResult.Retryable)
        closed.controller.start()
        await { closed.controller.state.value is CrewJoinReconnectState.Waiting }
        closed.controller.close()
        closed.controller.wake()
        delay(10)
        assertEquals(1, closed.dialCalls)
    }

    @Test
    fun `only one dial is active at a time`() = runBlocking {
        val fixture = fixture()
        fixture.controller.start()
        await { fixture.dialCalls == 1 }
        repeat(20) { fixture.controller.wake() }
        delay(20)
        assertEquals(1, fixture.dialCalls)
        fixture.results.send(CrewReconnectDialResult.Expired)
        await { fixture.controller.state.value == CrewJoinReconnectState.Expired }
    }

    private fun fixture(
        connected: Boolean = false,
        initialDelay: Long = 1,
        maximumDelay: Long = 8,
    ): Fixture {
        val port = FakeSession(state(coordinator), coordinator, connected, ::state)
        val results = Channel<CrewReconnectDialResult>(Channel.UNLIMITED)
        lateinit var fixture: Fixture
        val dialer = CrewJoinedSessionReconnectDialer {
            fixture.dialCalls++
            fixture.dialBeforeResult?.invoke()
            results.receive()
        }
        val controller = CrewJoinReconnectController(local, port, dialer, initialDelay, maximumDelay, Dispatchers.Default)
        return Fixture(controller, port, results).also { fixture = it }
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(1_000) {
        while (!predicate()) delay(1)
    }

    private fun member(value: String) = CrewMemberId(value, protocol)

    private fun state(coordinator: CrewMemberId) = CrewState(
        sessionId = CrewSessionId("session", protocol), protocolVersion = protocol,
        term = CoordinatorTerm(1), lastSequence = EventSequence(0), coordinatorMemberId = coordinator,
        members = listOf(CrewMember(local, "Local"), CrewMember(this.coordinator, "Coordinator"), CrewMember(replacement, "Replacement")),
    )

    private data class Fixture(
        val controller: CrewJoinReconnectController,
        val port: FakeSession,
        val results: Channel<CrewReconnectDialResult>,
        var dialCalls: Int = 0,
        var dialBeforeResult: (() -> Unit)? = null,
    )

    private class FakeSession(
        initial: CrewState,
        coordinator: CrewMemberId,
        connected: Boolean,
        private val createState: (CrewMemberId) -> CrewState,
    ) : CrewJoinedSessionReconnectPort {
        private val mutableState = MutableStateFlow(initial)
        private val mutablePeerStates = MutableStateFlow(if (connected) mapOf(coordinator to CrewTransportState.CONNECTED) else emptyMap())
        override val state: StateFlow<CrewState> = mutableState
        override val peerStates: StateFlow<Map<CrewMemberId, CrewTransportState>> = mutablePeerStates
        val attached = mutableListOf<CrewPeerTransport>()
        var snapshotRequests = 0
        var snapshotRequestResult = true

        override fun attachPeer(transport: CrewPeerTransport) {
            attached += transport
            mutablePeerStates.value = mutablePeerStates.value + (transport.remoteMemberId to CrewTransportState.CONNECTED)
        }

        override suspend fun requestSnapshot(): Boolean {
            snapshotRequests++
            if (!snapshotRequestResult) setConnected(false)
            return snapshotRequestResult
        }

        fun setCoordinator(id: CrewMemberId) { mutableState.value = createState(id) }
        fun setConnected(connected: Boolean) {
            val id = mutableState.value.coordinatorMemberId
            mutablePeerStates.value = if (connected) mapOf(id to CrewTransportState.CONNECTED) else emptyMap()
        }
    }

    private class FakeConnection : Closeable {
        var closed = false
        override fun close() { closed = true }
    }

    private class FakeTransport(override val remoteMemberId: CrewMemberId) : CrewPeerTransport {
        override val state = MutableStateFlow(CrewTransportState.CONNECTED)
        override val incoming: Flow<CrewTransportFrame> = emptyFlow()
        override val drops: Flow<CrewTransportDrop> = emptyFlow()
        override fun trySend(frame: CrewTransportFrame) = CrewSendResult.ChannelNotOpen
        override fun bufferedBytes(channel: CrewTransportChannel) = 0L
        override fun close() = Unit
    }
}
