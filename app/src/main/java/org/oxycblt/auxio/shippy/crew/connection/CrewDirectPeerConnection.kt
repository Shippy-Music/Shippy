/*
 * Copyright (c) 2026 Auxio Project
 * CrewDirectPeerConnection.kt is part of Auxio.
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

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.oxycblt.auxio.shippy.crew.auth.CrewInitiatorChallengeResult
import org.oxycblt.auxio.shippy.crew.auth.CrewInitiatorFinishedResult
import org.oxycblt.auxio.shippy.crew.auth.CrewJoinDecodeResult
import org.oxycblt.auxio.shippy.crew.auth.CrewJoinInitiator
import org.oxycblt.auxio.shippy.crew.auth.CrewJoinMessage
import org.oxycblt.auxio.shippy.crew.auth.CrewJoinMessageCodec
import org.oxycblt.auxio.shippy.crew.auth.CrewJoinResponder
import org.oxycblt.auxio.shippy.crew.auth.CrewResponderHelloResult
import org.oxycblt.auxio.shippy.crew.auth.CrewResponderProofResult
import org.oxycblt.auxio.shippy.crew.auth.CrewSdpFingerprint
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.invite.CrewInvite
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalConnectionState
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalPeer
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalSendResult
import org.oxycblt.auxio.shippy.crew.signaling.CrewSignalCloseReason
import org.oxycblt.auxio.shippy.crew.signaling.CrewSignalMessage
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport
import org.oxycblt.auxio.shippy.crew.transport.CrewSendResult
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

private const val INITIAL_NEGOTIATION_GENERATION = 0L
private const val OUTGOING_SIGNAL_CAPACITY = 256
private const val CLOSE_SIGNAL_TIMEOUT_MS = 500L

enum class CrewDirectPeerRole {
    INITIATOR,
    RESPONDER,
}

enum class CrewDirectPeerFailure {
    SIGNALING,
    NEGOTIATION,
    AUTHENTICATION,
    TRANSPORT,
}

sealed interface CrewDirectPeerState {
    data object New : CrewDirectPeerState

    data object Negotiating : CrewDirectPeerState

    data object Authenticating : CrewDirectPeerState

    data class Connected(val transport: CrewPeerTransport) : CrewDirectPeerState

    data class Failed(val reason: CrewDirectPeerFailure) : CrewDirectPeerState

    data object Closed : CrewDirectPeerState
}

/**
 * Drives one direct Crew peer from QR-authenticated signaling to a fingerprint-bound WebRTC
 * transport. Session state and playback remain outside this connection boundary.
 */
class CrewDirectPeerConnection(
    peerFactory: CrewRtcPeerFactory,
    private val signalingPeer: CrewSignalPeer,
    private val invite: CrewInvite,
    private val localMemberId: CrewMemberId,
    private val role: CrewDirectPeerRole,
    iceServers: List<CrewIceServer>,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Closeable {
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val resourcesReleased = AtomicBoolean(false)
    private val authenticated = AtomicBoolean(false)
    private val signalCallbackFailed = AtomicBoolean(false)
    private val generation = AtomicLong(INITIAL_NEGOTIATION_GENERATION)
    private val pendingRestartRequest = AtomicLong(-1L)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val outgoingSignals = Channel<CrewSignalMessage>(OUTGOING_SIGNAL_CAPACITY)
    private val localIceCandidates = Channel<CrewIceCandidate>(OUTGOING_SIGNAL_CAPACITY)
    private val renegotiationRequests = Channel<Unit>(Channel.CONFLATED)
    private val negotiationMutex = Mutex()
    private val authenticationMutex = Mutex()
    private val authenticationReady = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow<CrewDirectPeerState>(CrewDirectPeerState.New)
    private val signaledLocalGeneration = MutableStateFlow(-1L)
    private var localDescription: CrewSessionDescription? = null
    private var remoteDescription: CrewSessionDescription? = null
    private var initiatorAuthenticator: CrewJoinInitiator? = null
    private var responderAuthenticator: CrewJoinResponder? = null

    private val rtcPeer: CrewRtcNegotiationPeer =
        peerFactory
            .also {
                require(invite.protocolVersion == signalingPeer.sessionId.protocolVersion) {
                    "Crew invitation and signaling session protocols must match"
                }
                require(invite.protocolVersion == signalingPeer.remoteMemberClaim.protocolVersion) {
                    "Crew invitation and remote member protocols must match"
                }
                require(invite.protocolVersion == localMemberId.protocolVersion) {
                    "Crew invitation and local member protocols must match"
                }
            }
            .createPeer(
                signalingPeer.sessionId,
                signalingPeer.remoteMemberClaim,
                iceServers,
                role == CrewDirectPeerRole.INITIATOR,
                object : CrewRtcSignalSink {
                    override fun onLocalIceCandidate(candidate: CrewIceCandidate) {
                        if (!closed.get() && localIceCandidates.trySend(candidate).isFailure) {
                            signalCallbackFailed.set(true)
                            if (started.get()) {
                                scope.launch { fail(CrewDirectPeerFailure.SIGNALING) }
                            }
                        }
                    }

                    override fun onRenegotiationNeeded() {
                        if (!closed.get() && mutableState.value is CrewDirectPeerState.Connected) {
                            renegotiationRequests.trySend(Unit)
                        }
                    }
                },
            )

    val state: StateFlow<CrewDirectPeerState> = mutableState.asStateFlow()

    fun start() {
        check(!closed.get()) { "Crew direct peer connection is closed" }
        if (!started.compareAndSet(false, true)) return
        if (signalCallbackFailed.get()) {
            fail(CrewDirectPeerFailure.SIGNALING)
            return
        }
        mutableState.value = CrewDirectPeerState.Negotiating
        scope.launch { pumpOutgoingSignals() }
        scope.launch { pumpLocalIceCandidates() }
        scope.launch { collectIncomingSignals() }
        scope.launch { collectRtcState() }
        scope.launch { collectAuthenticationFrames() }
        scope.launch { collectRenegotiationRequests() }
        scope.launch {
            if (role == CrewDirectPeerRole.INITIATOR) {
                runCatching { createAndSendOffer(INITIAL_NEGOTIATION_GENERATION, false) }
                    .onFailure { fail(CrewDirectPeerFailure.NEGOTIATION) }
            }
        }
    }

    private suspend fun pumpOutgoingSignals() {
        for (message in outgoingSignals) {
            when (signalingPeer.send(message)) {
                CrewSignalSendResult.Sent -> {
                    if (message is CrewSignalMessage.SessionDescription) {
                        signaledLocalGeneration.value = message.value.generation
                    }
                }
                CrewSignalSendResult.Closed,
                CrewSignalSendResult.Failed -> {
                    fail(CrewDirectPeerFailure.SIGNALING)
                    return
                }
            }
        }
    }

    private suspend fun pumpLocalIceCandidates() {
        for (candidate in localIceCandidates) {
            signaledLocalGeneration.filter { it >= candidate.generation }.first()
            outgoingSignals.send(CrewSignalMessage.IceCandidate(candidate))
        }
    }

    private suspend fun collectIncomingSignals() {
        try {
            signalingPeer.incoming.collect { message ->
                when (message) {
                    is CrewSignalMessage.SessionDescription ->
                        handleRemoteDescription(message.value)
                    is CrewSignalMessage.IceCandidate -> {
                        if (!rtcPeer.addRemoteIceCandidate(message.value)) {
                            fail(CrewDirectPeerFailure.NEGOTIATION)
                        }
                    }
                    is CrewSignalMessage.EndOfCandidates -> {
                        if (message.generation != generation.get()) {
                            fail(CrewDirectPeerFailure.NEGOTIATION)
                        }
                    }
                    is CrewSignalMessage.IceRestartRequested ->
                        handleIceRestartRequest(message.generation)
                    is CrewSignalMessage.Close -> {
                        closeInternal(CrewDirectPeerState.Closed, sendClose = false)
                        return@collect
                    }
                }
            }
            if (!closed.get()) fail(CrewDirectPeerFailure.SIGNALING)
        } catch (_: Exception) {
            if (!closed.get()) fail(CrewDirectPeerFailure.SIGNALING)
        }
    }

    private suspend fun handleRemoteDescription(description: CrewSessionDescription) {
        negotiationMutex.withLock {
            when (role) {
                CrewDirectPeerRole.INITIATOR -> {
                    require(description.type == CrewSessionDescriptionType.ANSWER) {
                        "Crew initiator expected an answer"
                    }
                    require(remoteDescription == null) {
                        "Crew initiator already installed an answer"
                    }
                    require(description.generation == generation.get()) {
                        "Crew answer generation does not match"
                    }
                    rtcPeer.setRemoteDescription(description)
                    remoteDescription = description
                }
                CrewDirectPeerRole.RESPONDER -> {
                    require(description.type == CrewSessionDescriptionType.OFFER) {
                        "Crew responder expected an offer"
                    }
                    require(
                        if (remoteDescription == null) {
                            description.generation == generation.get()
                        } else {
                            description.generation > generation.get()
                        }
                    ) {
                        "Crew offer generation is stale"
                    }
                    generation.set(description.generation)
                    pendingRestartRequest.set(-1L)
                    rtcPeer.setRemoteDescription(description)
                    remoteDescription = description
                    val answer = rtcPeer.createAnswer(description.generation)
                    localDescription = answer
                    outgoingSignals.send(CrewSignalMessage.SessionDescription(answer))
                }
            }
        }
        maybeStartAuthentication()
    }

    private suspend fun handleIceRestartRequest(requestedGeneration: Long) {
        require(role == CrewDirectPeerRole.INITIATOR) {
            "Only the Crew offerer accepts ICE restart requests"
        }
        if (requestedGeneration <= generation.get()) return
        createAndSendOffer(requestedGeneration, restartIce = true)
    }

    private suspend fun collectRenegotiationRequests() {
        for (ignored in renegotiationRequests) {
            if (closed.get()) return
            try {
                val nextGeneration = Math.addExact(generation.get(), 1)
                if (role == CrewDirectPeerRole.INITIATOR) {
                    createAndSendOffer(nextGeneration, restartIce = true)
                } else if (pendingRestartRequest.compareAndSet(-1L, nextGeneration)) {
                    outgoingSignals.send(CrewSignalMessage.IceRestartRequested(nextGeneration))
                }
            } catch (_: Exception) {
                fail(CrewDirectPeerFailure.NEGOTIATION)
                return
            }
        }
    }

    private suspend fun createAndSendOffer(nextGeneration: Long, restartIce: Boolean) {
        negotiationMutex.withLock {
            if (restartIce) {
                if (remoteDescription == null) return@withLock
                rtcPeer.restartIce(nextGeneration)
            }
            generation.set(nextGeneration)
            remoteDescription = null
            val offer = rtcPeer.createOffer(nextGeneration)
            localDescription = offer
            outgoingSignals.send(CrewSignalMessage.SessionDescription(offer))
        }
    }

    private suspend fun collectRtcState() {
        try {
            rtcPeer.negotiationState.collect { rtcState ->
                when (rtcState) {
                    CrewTransportState.AUTHENTICATING -> {
                        if (!authenticated.get()) {
                            mutableState.value = CrewDirectPeerState.Authenticating
                            maybeStartAuthentication()
                        }
                    }
                    CrewTransportState.CONNECTED -> Unit
                    CrewTransportState.FAILED,
                    CrewTransportState.CLOSED -> {
                        if (!closed.get()) fail(CrewDirectPeerFailure.TRANSPORT)
                    }
                    CrewTransportState.NEW,
                    CrewTransportState.CONNECTING,
                    CrewTransportState.RECONNECTING -> {
                        if (!authenticated.get()) {
                            mutableState.value = CrewDirectPeerState.Negotiating
                        }
                    }
                }
            }
        } catch (_: Exception) {
            if (!closed.get()) fail(CrewDirectPeerFailure.AUTHENTICATION)
        }
    }

    private suspend fun maybeStartAuthentication() {
        authenticationMutex.withLock {
            if (
                authenticated.get() ||
                    initiatorAuthenticator != null ||
                    responderAuthenticator != null ||
                    rtcPeer.negotiationState.value != CrewTransportState.AUTHENTICATING
            ) {
                return
            }
            val local = localDescription ?: return
            val remote = remoteDescription ?: return
            mutableState.value = CrewDirectPeerState.Authenticating
            when (role) {
                CrewDirectPeerRole.INITIATOR -> {
                    val authenticator =
                        CrewJoinInitiator(
                            invite,
                            localMemberId,
                            CrewSdpFingerprint.fromSdp(local.sdp),
                            CrewSdpFingerprint.fromSdp(remote.sdp),
                            nowEpochMs(),
                        )
                    initiatorAuthenticator = authenticator
                    authenticationReady.complete(Unit)
                    sendAuthentication(authenticator.start())
                }
                CrewDirectPeerRole.RESPONDER -> {
                    responderAuthenticator =
                        CrewJoinResponder(
                            invite,
                            signalingPeer.sessionId,
                            localMemberId,
                            CrewSdpFingerprint.fromSdp(remote.sdp),
                            CrewSdpFingerprint.fromSdp(local.sdp),
                            nowEpochMs(),
                        )
                    authenticationReady.complete(Unit)
                }
            }
        }
    }

    private suspend fun collectAuthenticationFrames() {
        try {
            rtcPeer.authenticationIncoming.collect { frame ->
                authenticationReady.await()
                val decoded = CrewJoinMessageCodec.decode(frame.copyPayload())
                val message =
                    (decoded as? CrewJoinDecodeResult.Accepted)?.message
                        ?: throw IllegalArgumentException("Crew join frame is invalid")
                when (role) {
                    CrewDirectPeerRole.INITIATOR -> handleInitiatorAuthentication(message)
                    CrewDirectPeerRole.RESPONDER -> handleResponderAuthentication(message)
                }
            }
            if (!closed.get() && !authenticated.get()) {
                fail(CrewDirectPeerFailure.AUTHENTICATION)
            }
        } catch (_: Exception) {
            if (!closed.get()) fail(CrewDirectPeerFailure.AUTHENTICATION)
        }
    }

    private fun handleInitiatorAuthentication(message: CrewJoinMessage) {
        val authenticator =
            checkNotNull(initiatorAuthenticator) { "Crew initiator authentication has not started" }
        when (message) {
            is CrewJoinMessage.ResponderChallenge -> {
                when (val result = authenticator.accept(message)) {
                    is CrewInitiatorChallengeResult.Accepted -> sendAuthentication(result.response)
                    is CrewInitiatorChallengeResult.Rejected ->
                        fail(CrewDirectPeerFailure.AUTHENTICATION)
                }
            }
            is CrewJoinMessage.ResponderFinished -> {
                when (val result = authenticator.complete(message)) {
                    is CrewInitiatorFinishedResult.Authenticated ->
                        installAuthenticatedTransport(result.binding)
                    is CrewInitiatorFinishedResult.Rejected ->
                        fail(CrewDirectPeerFailure.AUTHENTICATION)
                }
            }
            else -> fail(CrewDirectPeerFailure.AUTHENTICATION)
        }
    }

    private fun handleResponderAuthentication(message: CrewJoinMessage) {
        val authenticator =
            checkNotNull(responderAuthenticator) { "Crew responder authentication has not started" }
        when (message) {
            is CrewJoinMessage.InitiatorHello -> {
                when (val result = authenticator.accept(message)) {
                    is CrewResponderHelloResult.Accepted -> sendAuthentication(result.challenge)
                    is CrewResponderHelloResult.Rejected ->
                        fail(CrewDirectPeerFailure.AUTHENTICATION)
                }
            }
            is CrewJoinMessage.InitiatorProof -> {
                when (val result = authenticator.complete(message)) {
                    is CrewResponderProofResult.Authenticated -> {
                        if (sendAuthentication(result.response)) {
                            installAuthenticatedTransport(result.binding)
                        }
                    }
                    is CrewResponderProofResult.Rejected ->
                        fail(CrewDirectPeerFailure.AUTHENTICATION)
                }
            }
            else -> fail(CrewDirectPeerFailure.AUTHENTICATION)
        }
    }

    private fun sendAuthentication(message: CrewJoinMessage): Boolean {
        val result =
            rtcPeer.trySendAuthentication(
                CrewAuthenticationFrame(CrewJoinMessageCodec.encode(message))
            )
        return if (result is CrewSendResult.Sent) {
            true
        } else {
            fail(CrewDirectPeerFailure.AUTHENTICATION)
            false
        }
    }

    private fun installAuthenticatedTransport(binding: CrewAuthenticatedPeerBinding) {
        if (!authenticated.compareAndSet(false, true)) {
            fail(CrewDirectPeerFailure.AUTHENTICATION)
            return
        }
        val transport =
            runCatching { rtcPeer.completeAuthentication(binding) }
                .getOrElse {
                    fail(CrewDirectPeerFailure.AUTHENTICATION)
                    return
                }
        mutableState.value = CrewDirectPeerState.Connected(transport)
    }

    private fun fail(reason: CrewDirectPeerFailure) {
        closeInternal(CrewDirectPeerState.Failed(reason), sendClose = true)
    }

    override fun close() {
        closeInternal(CrewDirectPeerState.Closed, sendClose = true)
    }

    private fun closeInternal(finalState: CrewDirectPeerState, sendClose: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        mutableState.value = finalState
        if (sendClose && signalingPeer.state.value == CrewSignalConnectionState.CONNECTED) {
            scope.launch {
                withTimeoutOrNull(CLOSE_SIGNAL_TIMEOUT_MS) {
                    signalingPeer.send(
                        CrewSignalMessage.Close(
                            if (finalState is CrewDirectPeerState.Failed) {
                                CrewSignalCloseReason.PROTOCOL_ERROR
                            } else {
                                CrewSignalCloseReason.NORMAL
                            }
                        )
                    )
                }
                releaseResources()
            }
        } else {
            releaseResources()
        }
    }

    private fun releaseResources() {
        if (!resourcesReleased.compareAndSet(false, true)) return
        outgoingSignals.close()
        localIceCandidates.close()
        renegotiationRequests.close()
        rtcPeer.close()
        signalingPeer.close()
        scope.cancel()
    }
}
