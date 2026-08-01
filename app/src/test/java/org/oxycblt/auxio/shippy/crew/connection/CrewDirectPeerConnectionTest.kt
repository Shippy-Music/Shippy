/*
 * Copyright (c) 2026 Auxio Project
 * CrewDirectPeerConnectionTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.connection

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.invite.CrewInvite
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteId
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteSecret
import org.oxycblt.auxio.shippy.crew.invite.CrewSessionLocator
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalConnectionState
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalPeer
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalSendResult
import org.oxycblt.auxio.shippy.crew.signaling.CrewSignalMessage
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport
import org.oxycblt.auxio.shippy.crew.transport.CrewSendResult
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportDrop
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportState
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewAuthenticatedPeerBinding
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewAuthenticationFrame
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewIceCandidate
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewIceServer
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewRtcNegotiationPeer
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewRtcPeerFactory
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewRtcSignalSink
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewSessionDescription
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewSessionDescriptionType

class CrewDirectPeerConnectionTest {
    @Test
    fun `two peers negotiate and authenticate the same direct Crew`() {
        runBlocking {
            val fixture = Fixture()
            val signals =
                FakeSignalLink(
                    fixture.sessionId,
                    fixture.initiatorMemberId,
                    fixture.responderMemberId,
                )
            val rtc = FakeRtcLink()
            val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val initiator =
                CrewDirectPeerConnection(
                    rtc.factory(FakeSide.INITIATOR),
                    signals.initiator,
                    fixture.invite,
                    fixture.initiatorMemberId,
                    CrewDirectPeerRole.INITIATOR,
                    emptyList(),
                    { fixture.now },
                    dispatcher = dispatcher,
                )
            val responder =
                CrewDirectPeerConnection(
                    rtc.factory(FakeSide.RESPONDER),
                    signals.responder,
                    fixture.invite,
                    fixture.responderMemberId,
                    CrewDirectPeerRole.RESPONDER,
                    emptyList(),
                    { fixture.now },
                    dispatcher = dispatcher,
                )
            try {
                responder.start()
                initiator.start()

                val initiatorConnected =
                    withTimeout(ASYNC_TEST_TIMEOUT_MS) {
                        initiator.state.filterIsInstance<CrewDirectPeerState.Connected>().first()
                    }
                val responderConnected =
                    withTimeout(ASYNC_TEST_TIMEOUT_MS) {
                        responder.state.filterIsInstance<CrewDirectPeerState.Connected>().first()
                    }

                assertEquals(fixture.responderMemberId, initiatorConnected.transport.remoteMemberId)
                assertEquals(fixture.initiatorMemberId, responderConnected.transport.remoteMemberId)
                assertTrue(rtc.peer(FakeSide.INITIATOR).createsDataChannels)
                assertFalse(rtc.peer(FakeSide.RESPONDER).createsDataChannels)

                rtc.peer(FakeSide.RESPONDER).requestRenegotiation()
                rtc.peer(FakeSide.RESPONDER).requestRenegotiation()
                withTimeout(ASYNC_TEST_TIMEOUT_MS) {
                    rtc.peer(FakeSide.INITIATOR).generation.filter { it == 1L }.first()
                }
                withTimeout(ASYNC_TEST_TIMEOUT_MS) {
                    rtc.peer(FakeSide.RESPONDER).generation.filter { it == 1L }.first()
                }
            } finally {
                initiator.close()
                responder.close()
                dispatcher.close()
            }
        }
    }

    private class Fixture {
        val now = System.currentTimeMillis()
        private val protocol = ProtocolVersion(1)
        val sessionId = CrewSessionId("session", protocol)
        val initiatorMemberId = CrewMemberId("initiator", protocol)
        val responderMemberId = CrewMemberId("responder", protocol)
        val invite =
            CrewInvite(
                protocol,
                CrewSessionLocator("session_locator"),
                CrewInviteId("invite_id"),
                CrewInviteSecret("crew_secret_12345678901234567890"),
                now - 1_000,
                now + 60_000,
            )
    }

    private class FakeSignalLink(
        sessionId: CrewSessionId,
        initiatorMemberId: CrewMemberId,
        responderMemberId: CrewMemberId,
    ) {
        private val toInitiator = Channel<CrewSignalMessage>(Channel.UNLIMITED)
        private val toResponder = Channel<CrewSignalMessage>(Channel.UNLIMITED)

        val initiator: CrewSignalPeer =
            FakeSignalPeer(sessionId, responderMemberId, toInitiator, toResponder)
        val responder: CrewSignalPeer =
            FakeSignalPeer(sessionId, initiatorMemberId, toResponder, toInitiator)
    }

    private class FakeSignalPeer(
        override val sessionId: CrewSessionId,
        override val remoteMemberClaim: CrewMemberId,
        private val inbound: Channel<CrewSignalMessage>,
        private val outbound: Channel<CrewSignalMessage>,
    ) : CrewSignalPeer {
        private val mutableState = MutableStateFlow(CrewSignalConnectionState.CONNECTED)

        override val remoteDisplayName = "Test peer"
        override val state: StateFlow<CrewSignalConnectionState> = mutableState
        override val incoming: Flow<CrewSignalMessage> = inbound.receiveAsFlow()

        override suspend fun send(message: CrewSignalMessage): CrewSignalSendResult =
            if (mutableState.value == CrewSignalConnectionState.CONNECTED) {
                outbound.send(message)
                CrewSignalSendResult.Sent
            } else {
                CrewSignalSendResult.Closed
            }

        override fun close() {
            mutableState.value = CrewSignalConnectionState.CLOSED
        }
    }

    private enum class FakeSide {
        INITIATOR,
        RESPONDER,
    }

    private class FakeRtcLink {
        private val peers = ConcurrentHashMap<FakeSide, FakeRtcPeer>()

        fun factory(side: FakeSide) =
            object : CrewRtcPeerFactory {
                override fun createPeer(
                    expectedSessionId: CrewSessionId,
                    claimedRemoteMemberId: CrewMemberId,
                    iceServers: List<CrewIceServer>,
                    createsDataChannels: Boolean,
                    signalSink: CrewRtcSignalSink,
                ): CrewRtcNegotiationPeer {
                    assertTrue(iceServers.isEmpty())
                    return FakeRtcPeer(
                            side,
                            expectedSessionId,
                            claimedRemoteMemberId,
                            createsDataChannels,
                            signalSink,
                            this@FakeRtcLink,
                        )
                        .also { peers[side] = it }
                }
            }

        fun peer(side: FakeSide) = checkNotNull(peers[side])

        fun remote(side: FakeSide) =
            peer(
                when (side) {
                    FakeSide.INITIATOR -> FakeSide.RESPONDER
                    FakeSide.RESPONDER -> FakeSide.INITIATOR
                }
            )
    }

    private class FakeRtcPeer(
        private val side: FakeSide,
        private val expectedSessionId: CrewSessionId,
        private val claimedRemoteMemberId: CrewMemberId,
        val createsDataChannels: Boolean,
        private val signalSink: CrewRtcSignalSink,
        private val link: FakeRtcLink,
    ) : CrewRtcNegotiationPeer {
        private val mutableState = MutableStateFlow(CrewTransportState.CONNECTING)
        private val authenticationFrames = Channel<CrewAuthenticationFrame>(Channel.UNLIMITED)
        private val mutableGeneration = MutableStateFlow(-1L)

        override val negotiationState: StateFlow<CrewTransportState> = mutableState
        override val authenticationIncoming: Flow<CrewAuthenticationFrame> =
            authenticationFrames.receiveAsFlow()
        val generation: StateFlow<Long> = mutableGeneration

        override suspend fun createOffer(generation: Long): CrewSessionDescription {
            require(side == FakeSide.INITIATOR)
            mutableGeneration.value = generation
            emitCandidate(generation)
            return CrewSessionDescription(
                CrewSessionDescriptionType.OFFER,
                generation,
                fingerprintSdp(0x11),
            )
        }

        override suspend fun createAnswer(generation: Long): CrewSessionDescription {
            require(side == FakeSide.RESPONDER && generation == mutableGeneration.value)
            emitCandidate(generation)
            if (mutableState.value != CrewTransportState.CONNECTED) {
                mutableState.value = CrewTransportState.AUTHENTICATING
            }
            return CrewSessionDescription(
                CrewSessionDescriptionType.ANSWER,
                generation,
                fingerprintSdp(0x22),
            )
        }

        override suspend fun setRemoteDescription(description: CrewSessionDescription) {
            mutableGeneration.value = description.generation
            if (side == FakeSide.INITIATOR) {
                require(description.type == CrewSessionDescriptionType.ANSWER)
                if (mutableState.value != CrewTransportState.CONNECTED) {
                    mutableState.value = CrewTransportState.AUTHENTICATING
                }
            } else {
                require(description.type == CrewSessionDescriptionType.OFFER)
            }
        }

        override fun addRemoteIceCandidate(candidate: CrewIceCandidate) =
            candidate.generation == mutableGeneration.value

        override fun restartIce(generation: Long) {
            require(generation > mutableGeneration.value)
            mutableGeneration.value = generation
        }

        override fun trySendAuthentication(frame: CrewAuthenticationFrame): CrewSendResult {
            val sent = link.remote(side).authenticationFrames.trySend(frame).isSuccess
            return if (sent) CrewSendResult.Sent(0) else CrewSendResult.NativeRejected
        }

        override fun completeAuthentication(
            binding: CrewAuthenticatedPeerBinding
        ): CrewPeerTransport {
            require(binding.sessionId == expectedSessionId)
            require(binding.remoteMemberId == claimedRemoteMemberId)
            mutableState.value = CrewTransportState.CONNECTED
            return FakeTransport(claimedRemoteMemberId, mutableState)
        }

        override fun close() {
            mutableState.value = CrewTransportState.CLOSED
            authenticationFrames.close()
        }

        fun requestRenegotiation() = signalSink.onRenegotiationNeeded()

        private fun emitCandidate(generation: Long) {
            signalSink.onLocalIceCandidate(
                CrewIceCandidate(generation, "data", 0, "candidate:${side.name.lowercase()}")
            )
        }
    }

    private class FakeTransport(
        override val remoteMemberId: CrewMemberId,
        override val state: StateFlow<CrewTransportState>,
    ) : CrewPeerTransport {
        override val incoming: Flow<CrewTransportFrame> = emptyFlow()
        override val drops: Flow<CrewTransportDrop> = emptyFlow()

        override fun trySend(frame: CrewTransportFrame) = CrewSendResult.Sent(0)

        override fun bufferedBytes(channel: CrewTransportChannel) = 0L

        override fun close() = Unit
    }

    companion object {
        private const val ASYNC_TEST_TIMEOUT_MS = 15_000L

        private fun fingerprintSdp(byte: Int): String {
            val fingerprint = List(32) { "%02X".format(byte) }.joinToString(":")
            return "v=0\r\na=fingerprint:sha-256 $fingerprint\r\n"
        }
    }
}
