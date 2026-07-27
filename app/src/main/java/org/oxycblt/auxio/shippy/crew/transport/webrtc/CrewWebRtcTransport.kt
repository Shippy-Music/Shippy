/*
 * Copyright (c) 2026 Auxio Project
 * CrewWebRtcTransport.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.transport.webrtc

import android.content.Context
import java.io.Closeable
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport
import org.oxycblt.auxio.shippy.crew.transport.CrewSendResult
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportDrop
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportDropReason
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportState

private const val MAX_SDP_BYTES = 1024 * 1024
private const val MAX_ICE_CANDIDATE_BYTES = 64 * 1024
private const val MAX_SDP_MID_BYTES = 256
private const val CONTROL_FRAME_CAPACITY = 64
private const val CLOCK_FRAME_CAPACITY = 16
private const val REACTION_FRAME_CAPACITY = 32
private const val MEDIA_FRAME_CAPACITY = 64
private const val AUTHENTICATION_FRAME_CAPACITY = 8
private const val MAX_PENDING_REMOTE_ICE_CANDIDATES = 256
private const val MIN_VERIFIED_TRANSCRIPT_BYTES = 32
private val ICE_SERVER_PATTERN =
    Regex(
        "^(stun|stuns|turn|turns):[A-Za-z0-9.-]+(?::([0-9]{1,5}))?" + "(?:\\?transport=(udp|tcp))?$"
    )

/** STUN/TURN credentials are deliberately redacted from diagnostics. */
class CrewIceServer(urls: List<String>, val username: String = "", val credential: String = "") {
    val urls = urls.toList()

    init {
        require(this.urls.isNotEmpty()) { "ICE server requires at least one URL" }
        require(this.urls.size <= 8) { "ICE server URL count is unreasonably large" }
        this.urls.forEach(::validateIceServerUrl)
        require(username.length <= 256 && credential.length <= 512) {
            "ICE server credentials are too large"
        }
    }

    internal fun toNative(): PeerConnection.IceServer =
        PeerConnection.IceServer.builder(urls)
            .setUsername(username)
            .setPassword(credential)
            .createIceServer()

    override fun toString() = "CrewIceServer(urls=$urls, credentials=redacted)"
}

enum class CrewSessionDescriptionType {
    OFFER,
    ANSWER,
}

class CrewSessionDescription(
    val type: CrewSessionDescriptionType,
    val generation: Long,
    val sdp: String,
) {
    init {
        require(generation >= 0) { "Negotiation generation cannot be negative" }
        val size = sdp.toByteArray(Charsets.UTF_8).size
        require(size in 1..MAX_SDP_BYTES) { "Session description has invalid size" }
    }

    internal fun toNative() =
        SessionDescription(
            when (type) {
                CrewSessionDescriptionType.OFFER -> SessionDescription.Type.OFFER
                CrewSessionDescriptionType.ANSWER -> SessionDescription.Type.ANSWER
            },
            sdp,
        )

    override fun equals(other: Any?) =
        other is CrewSessionDescription &&
            type == other.type &&
            generation == other.generation &&
            sdp == other.sdp

    override fun hashCode() = 31 * (31 * type.hashCode() + generation.hashCode()) + sdp.hashCode()

    override fun toString() =
        "CrewSessionDescription(type=$type, generation=$generation, sdp=redacted)"
}

class CrewIceCandidate(
    val generation: Long,
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val sdp: String,
) {
    init {
        require(generation >= 0) { "ICE candidate generation cannot be negative" }
        require(sdpMLineIndex >= 0) { "ICE candidate media line cannot be negative" }
        require((sdpMid?.toByteArray(Charsets.UTF_8)?.size ?: 0) <= MAX_SDP_MID_BYTES) {
            "ICE candidate media ID is too large"
        }
        require(sdp.toByteArray(Charsets.UTF_8).size in 1..MAX_ICE_CANDIDATE_BYTES) {
            "ICE candidate has invalid size"
        }
    }

    internal fun toNative() = IceCandidate(sdpMid, sdpMLineIndex, sdp)

    override fun equals(other: Any?) =
        other is CrewIceCandidate &&
            generation == other.generation &&
            sdpMid == other.sdpMid &&
            sdpMLineIndex == other.sdpMLineIndex &&
            sdp == other.sdp

    override fun hashCode(): Int {
        var result = generation.hashCode()
        result = 31 * result + (sdpMid?.hashCode() ?: 0)
        result = 31 * result + sdpMLineIndex
        return 31 * result + sdp.hashCode()
    }

    override fun toString() =
        "CrewIceCandidate(generation=$generation, sdpMid=redacted, sdp=redacted)"
}

/**
 * Created only after the invitation/session authenticator verifies the shared-secret transcript.
 *
 * The digest binds the session, member identities, nonces, roles, and SDP fingerprints. The
 * transport deliberately cannot manufacture this proof from a claimed member ID.
 */
class CrewAuthenticatedPeerBinding
internal constructor(
    val sessionId: CrewSessionId,
    val remoteMemberId: CrewMemberId,
    verifiedTranscriptHash: ByteArray,
) {
    private val transcriptHash = verifiedTranscriptHash.copyOf()

    init {
        require(sessionId.protocolVersion == remoteMemberId.protocolVersion) {
            "Authenticated peer protocol versions must match"
        }
        require(transcriptHash.size >= MIN_VERIFIED_TRANSCRIPT_BYTES) {
            "Authenticated transcript hash is too short"
        }
    }

    internal fun matchesTranscript(other: ByteArray) = MessageDigest.isEqual(transcriptHash, other)

    override fun toString() =
        "CrewAuthenticatedPeerBinding(sessionId=$sessionId, remoteMemberId=$remoteMemberId, " +
            "transcript=redacted)"
}

class CrewAuthenticationFrame(payload: ByteArray) {
    private val bytes = payload.copyOf()

    init {
        require(bytes.isNotEmpty() && bytes.size <= CrewTransportChannel.CONTROL.maxPayloadBytes) {
            "Authentication frame has invalid size"
        }
    }

    fun copyPayload() = bytes.copyOf()

    override fun toString() = "CrewAuthenticationFrame(size=${bytes.size})"
}

interface CrewRtcSignalSink {
    fun onLocalIceCandidate(candidate: CrewIceCandidate)

    fun onRenegotiationNeeded()
}

interface CrewRtcNegotiationPeer : Closeable {
    val negotiationState: StateFlow<CrewTransportState>
    val authenticationIncoming: Flow<CrewAuthenticationFrame>

    suspend fun createOffer(generation: Long): CrewSessionDescription

    suspend fun createAnswer(generation: Long): CrewSessionDescription

    suspend fun setRemoteDescription(description: CrewSessionDescription)

    fun addRemoteIceCandidate(candidate: CrewIceCandidate): Boolean

    fun restartIce(generation: Long)

    fun trySendAuthentication(frame: CrewAuthenticationFrame): CrewSendResult

    fun completeAuthentication(binding: CrewAuthenticatedPeerBinding): CrewPeerTransport
}

fun interface CrewRtcPeerFactory {
    fun createPeer(
        expectedSessionId: CrewSessionId,
        claimedRemoteMemberId: CrewMemberId,
        iceServers: List<CrewIceServer>,
        createsDataChannels: Boolean,
        signalSink: CrewRtcSignalSink,
    ): CrewRtcNegotiationPeer
}

/**
 * Owns the data-only WebRTC factory. It does not request microphone/camera tracks and does not own
 * signaling, invitation authentication, session authority, or media playback.
 */
class CrewWebRtcRuntime(context: Context) : Closeable, CrewRtcPeerFactory {
    private val closed = AtomicBoolean(false)
    private val factory: PeerConnectionFactory

    init {
        if (WEB_RTC_INITIALIZED.compareAndSet(false, true)) {
            val options =
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
        }
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    override fun createPeer(
        expectedSessionId: CrewSessionId,
        claimedRemoteMemberId: CrewMemberId,
        iceServers: List<CrewIceServer>,
        createsDataChannels: Boolean,
        signalSink: CrewRtcSignalSink,
    ): CrewWebRtcPeer {
        check(!closed.get()) { "Crew WebRTC runtime is closed" }
        require(expectedSessionId.protocolVersion == claimedRemoteMemberId.protocolVersion) {
            "Crew peer protocol versions must match"
        }
        val observer = PeerObserverBridge()
        val configuration =
            PeerConnection.RTCConfiguration(iceServers.flatMap { listOf(it.toNative()) }).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                iceCandidatePoolSize = 2
            }
        val connection =
            checkNotNull(factory.createPeerConnection(configuration, observer)) {
                "WebRTC could not create a peer connection"
            }
        connection.setAudioPlayout(false)
        connection.setAudioRecording(false)
        val peer = CrewWebRtcPeer(expectedSessionId, claimedRemoteMemberId, connection, signalSink)
        observer.delegate = peer
        peer.initialize(createsDataChannels)
        return peer
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) factory.dispose()
    }

    companion object {
        private val WEB_RTC_INITIALIZED = AtomicBoolean(false)
    }
}

class CrewWebRtcPeer
internal constructor(
    private val expectedSessionId: CrewSessionId,
    private val claimedRemoteMemberId: CrewMemberId,
    private val connection: PeerConnection,
    private val signalSink: CrewRtcSignalSink,
) : CrewRtcNegotiationPeer {
    private val closed = AtomicBoolean(false)
    private val channelLock = Any()
    private val negotiationLock = Any()
    private val channels = mutableMapOf<CrewTransportChannel, DataChannel>()
    private val incomingChannels =
        CrewTransportChannel.entries.associateWith { purpose ->
            Channel<CrewTransportFrame>(purpose.incomingCapacity)
        }
    private val authenticationFrames =
        Channel<CrewAuthenticationFrame>(AUTHENTICATION_FRAME_CAPACITY)
    private val mutableDrops =
        MutableSharedFlow<CrewTransportDrop>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val mutableState = MutableStateFlow(CrewTransportState.NEW)
    private val signalingMutex = Mutex()
    private var peerConnected = false
    private var authenticatedBinding: CrewAuthenticatedPeerBinding? = null
    private var currentNegotiationGeneration = -1L
    private var remoteDescriptionSet = false
    private val pendingRemoteIceCandidates = ArrayDeque<CrewIceCandidate>()

    override val negotiationState: StateFlow<CrewTransportState> = mutableState.asStateFlow()
    override val authenticationIncoming: Flow<CrewAuthenticationFrame> =
        authenticationFrames.receiveAsFlow()

    internal fun initialize(createsDataChannels: Boolean) {
        mutableState.value = CrewTransportState.CONNECTING
        if (createsDataChannels) {
            CrewTransportChannel.entries.forEach { purpose ->
                attachChannel(
                    purpose,
                    connection.createDataChannel(purpose.wireLabel, purpose.toInit()),
                )
            }
        }
    }

    override suspend fun createOffer(generation: Long): CrewSessionDescription =
        signalingMutex.withLock {
            if (currentGeneration() < generation) beginNegotiation(generation)
            require(currentGeneration() == generation) {
                "Offer generation must match the active negotiation"
            }
            val native = createDescription(CrewSessionDescriptionType.OFFER)
            setLocalDescription(native)
            native.toCrew(generation)
        }

    override suspend fun createAnswer(generation: Long): CrewSessionDescription =
        signalingMutex.withLock {
            require(currentGeneration() == generation && isRemoteDescriptionSet()) {
                "Remote offer must be installed before creating a Crew answer"
            }
            val native = createDescription(CrewSessionDescriptionType.ANSWER)
            setLocalDescription(native)
            native.toCrew(generation)
        }

    override suspend fun setRemoteDescription(description: CrewSessionDescription) {
        signalingMutex.withLock {
            val generation = currentGeneration()
            when {
                generation < 0 || description.generation > generation ->
                    beginNegotiation(description.generation)
                description.generation < generation ->
                    throw IllegalArgumentException("Stale Crew session description")
            }
            val completion = CompletableDeferred<Unit>()
            connection.setRemoteDescription(
                SetDescriptionObserver(completion),
                description.toNative(),
            )
            completion.await()
            val pending =
                synchronized(negotiationLock) {
                    remoteDescriptionSet = true
                    pendingRemoteIceCandidates.toList().also { pendingRemoteIceCandidates.clear() }
                }
            check(pending.all { connection.addIceCandidate(it.toNative()) }) {
                "WebRTC rejected a queued remote ICE candidate"
            }
        }
    }

    override fun addRemoteIceCandidate(candidate: CrewIceCandidate): Boolean {
        if (closed.get()) return false
        val installNow =
            synchronized(negotiationLock) {
                if (candidate.generation != currentNegotiationGeneration) return false
                if (remoteDescriptionSet) true
                else {
                    if (pendingRemoteIceCandidates.size >= MAX_PENDING_REMOTE_ICE_CANDIDATES) {
                        return false
                    }
                    pendingRemoteIceCandidates.addLast(candidate)
                    false
                }
            }
        return !installNow || connection.addIceCandidate(candidate.toNative())
    }

    override fun restartIce(generation: Long) {
        if (!closed.get()) {
            beginNegotiation(generation)
            connection.restartIce()
        }
    }

    override fun trySendAuthentication(frame: CrewAuthenticationFrame): CrewSendResult =
        trySendFrame(
            CrewTransportFrame(CrewTransportChannel.CONTROL, frame.copyPayload()),
            requireAuthentication = false,
        )

    /** Opens the ordinary Crew frame surface only after a verified join transcript is supplied. */
    override fun completeAuthentication(binding: CrewAuthenticatedPeerBinding): CrewPeerTransport {
        check(!closed.get()) { "Crew peer is closed" }
        require(binding.sessionId == expectedSessionId) {
            "Authenticated session does not match the negotiated Crew"
        }
        require(binding.remoteMemberId == claimedRemoteMemberId) {
            "Authenticated member does not match the negotiated peer"
        }
        synchronized(channelLock) {
            check(authenticatedBinding == null) { "Crew peer is already authenticated" }
            authenticatedBinding = binding
        }
        updateChannelReadiness()
        return AuthenticatedPeerTransport(binding)
    }

    private fun trySendFrame(
        frame: CrewTransportFrame,
        requireAuthentication: Boolean,
    ): CrewSendResult {
        if (closed.get()) return CrewSendResult.Closed
        return synchronized(channelLock) {
            if (requireAuthentication && authenticatedBinding == null) {
                return@synchronized CrewSendResult.ChannelNotOpen
            }
            val channel =
                channels[frame.channel] ?: return@synchronized CrewSendResult.ChannelNotOpen
            if (channel.state() != DataChannel.State.OPEN) {
                return@synchronized CrewSendResult.ChannelNotOpen
            }
            try {
                val before = channel.bufferedAmount()
                if (before + frame.size > frame.channel.maxBufferedBytes) {
                    return@synchronized CrewSendResult.Backpressured(
                        before,
                        frame.channel.maxBufferedBytes,
                    )
                }
                val sent =
                    channel.send(DataChannel.Buffer(ByteBuffer.wrap(frame.copyPayload()), true))
                if (sent) CrewSendResult.Sent(channel.bufferedAmount())
                else CrewSendResult.NativeRejected
            } catch (_: RuntimeException) {
                if (closed.get()) CrewSendResult.Closed else CrewSendResult.NativeRejected
            }
        }
    }

    private fun bufferedBytesInternal(channel: CrewTransportChannel): Long =
        synchronized(channelLock) {
            try {
                channels[channel]?.bufferedAmount() ?: 0
            } catch (_: RuntimeException) {
                0
            }
        }

    internal fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
        if (closed.get()) return
        when (state) {
            PeerConnection.PeerConnectionState.NEW,
            PeerConnection.PeerConnectionState.CONNECTING -> {
                peerConnected = false
                mutableState.value = CrewTransportState.CONNECTING
            }
            PeerConnection.PeerConnectionState.CONNECTED -> {
                peerConnected = true
                updateChannelReadiness()
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                peerConnected = false
                mutableState.value = CrewTransportState.RECONNECTING
            }
            PeerConnection.PeerConnectionState.FAILED -> failAndClose()
            PeerConnection.PeerConnectionState.CLOSED -> closeInternal(CrewTransportState.CLOSED)
        }
    }

    internal fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState) {
        if (closed.get()) return
        when (state) {
            PeerConnection.IceConnectionState.DISCONNECTED ->
                mutableState.value = CrewTransportState.RECONNECTING
            PeerConnection.IceConnectionState.FAILED -> failAndClose()
            else -> Unit
        }
    }

    internal fun onLocalIceCandidate(candidate: IceCandidate) {
        val generation = currentGeneration()
        if (!closed.get() && generation >= 0) {
            signalSink.onLocalIceCandidate(
                CrewIceCandidate(
                    generation,
                    candidate.sdpMid,
                    candidate.sdpMLineIndex,
                    candidate.sdp,
                )
            )
        }
    }

    internal fun onRemoteDataChannel(channel: DataChannel) {
        val purpose = CrewTransportChannel.fromWireLabel(channel.label())
        if (purpose == null) {
            channel.close()
            channel.dispose()
            return
        }
        attachChannel(purpose, channel)
    }

    internal fun onRenegotiationNeeded() {
        if (!closed.get()) signalSink.onRenegotiationNeeded()
    }

    private fun attachChannel(purpose: CrewTransportChannel, channel: DataChannel) {
        val accepted =
            synchronized(channelLock) {
                if (closed.get() || channels.containsKey(purpose)) false
                else {
                    channels[purpose] = channel
                    true
                }
            }
        if (!accepted) {
            channel.close()
            channel.dispose()
            return
        }
        channel.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() {
                    if (channel.state() == DataChannel.State.CLOSED && !closed.get()) {
                        mutableState.value = CrewTransportState.RECONNECTING
                    } else {
                        updateChannelReadiness()
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (!buffer.binary) {
                        failAndClose()
                        return
                    }
                    val payload = ByteArray(buffer.data.remaining())
                    buffer.data.get(payload)
                    val frame =
                        try {
                            CrewTransportFrame(purpose, payload)
                        } catch (_: IllegalArgumentException) {
                            failAndClose()
                            return
                        }
                    val authenticated = synchronized(channelLock) { authenticatedBinding != null }
                    if (!authenticated) {
                        if (
                            purpose != CrewTransportChannel.CONTROL ||
                                authenticationFrames
                                    .trySend(CrewAuthenticationFrame(payload))
                                    .isFailure
                        ) {
                            failAndClose()
                        }
                        return
                    }
                    val incoming = checkNotNull(incomingChannels[purpose])
                    if (incoming.trySend(frame).isFailure) {
                        when (purpose) {
                            CrewTransportChannel.CONTROL -> failAndClose()
                            CrewTransportChannel.CLOCK,
                            CrewTransportChannel.REACTION ->
                                mutableDrops.tryEmit(
                                    CrewTransportDrop(
                                        purpose,
                                        frame.size,
                                        CrewTransportDropReason.TRANSIENT_CHANNEL_FULL,
                                    )
                                )
                            CrewTransportChannel.MEDIA ->
                                mutableDrops.tryEmit(
                                    CrewTransportDrop(
                                        purpose,
                                        frame.size,
                                        CrewTransportDropReason.MEDIA_WINDOW_FULL,
                                    )
                                )
                        }
                    }
                }
            }
        )
        updateChannelReadiness()
    }

    private fun updateChannelReadiness() {
        if (closed.get()) return
        val allOpen =
            synchronized(channelLock) {
                CrewTransportChannel.entries.all { purpose ->
                    channels[purpose]?.state() == DataChannel.State.OPEN
                }
            }
        mutableState.value =
            when {
                !peerConnected || !allOpen -> CrewTransportState.CONNECTING
                synchronized(channelLock) { authenticatedBinding == null } ->
                    CrewTransportState.AUTHENTICATING
                else -> CrewTransportState.CONNECTED
            }
    }

    private suspend fun createDescription(type: CrewSessionDescriptionType): SessionDescription {
        check(!closed.get()) { "Crew peer is closed" }
        val completion = CompletableDeferred<SessionDescription>()
        val observer = CreateDescriptionObserver(completion)
        when (type) {
            CrewSessionDescriptionType.OFFER -> connection.createOffer(observer, MediaConstraints())
            CrewSessionDescriptionType.ANSWER ->
                connection.createAnswer(observer, MediaConstraints())
        }
        return completion.await()
    }

    private suspend fun setLocalDescription(description: SessionDescription) {
        val completion = CompletableDeferred<Unit>()
        connection.setLocalDescription(SetDescriptionObserver(completion), description)
        completion.await()
    }

    private fun failAndClose() = closeInternal(CrewTransportState.FAILED)

    override fun close() = closeInternal(CrewTransportState.CLOSED)

    private fun closeInternal(finalState: CrewTransportState) {
        if (!closed.compareAndSet(false, true)) return
        mutableState.value = finalState
        val ownedChannels =
            synchronized(channelLock) { channels.values.toList().also { channels.clear() } }
        incomingChannels.values.forEach { it.close() }
        authenticationFrames.close()
        ownedChannels.forEach { channel ->
            channel.unregisterObserver()
            channel.close()
            channel.dispose()
        }
        connection.close()
        connection.dispose()
    }

    private fun beginNegotiation(generation: Long) {
        require(generation >= 0) { "Negotiation generation cannot be negative" }
        synchronized(negotiationLock) {
            require(generation > currentNegotiationGeneration) {
                "Negotiation generation must increase"
            }
            currentNegotiationGeneration = generation
            remoteDescriptionSet = false
            pendingRemoteIceCandidates.clear()
        }
    }

    private fun currentGeneration() = synchronized(negotiationLock) { currentNegotiationGeneration }

    private fun isRemoteDescriptionSet() = synchronized(negotiationLock) { remoteDescriptionSet }

    private inner class AuthenticatedPeerTransport(binding: CrewAuthenticatedPeerBinding) :
        CrewPeerTransport {
        override val remoteMemberId = binding.remoteMemberId
        override val state: StateFlow<CrewTransportState> = negotiationState
        override val incoming: Flow<CrewTransportFrame> =
            merge(
                checkNotNull(incomingChannels[CrewTransportChannel.CONTROL]).receiveAsFlow(),
                checkNotNull(incomingChannels[CrewTransportChannel.CLOCK]).receiveAsFlow(),
                checkNotNull(incomingChannels[CrewTransportChannel.REACTION]).receiveAsFlow(),
                checkNotNull(incomingChannels[CrewTransportChannel.MEDIA]).receiveAsFlow(),
            )
        override val drops: Flow<CrewTransportDrop> = mutableDrops.asSharedFlow()

        override fun trySend(frame: CrewTransportFrame) =
            trySendFrame(frame, requireAuthentication = true)

        override fun bufferedBytes(channel: CrewTransportChannel) = bufferedBytesInternal(channel)

        override fun close() = this@CrewWebRtcPeer.close()
    }
}

private class PeerObserverBridge : PeerConnection.Observer {
    var delegate: CrewWebRtcPeer? = null

    override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
        delegate?.onIceConnectionStateChanged(newState)
    }

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
        delegate?.onConnectionStateChanged(newState)
    }

    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit

    override fun onIceCandidate(candidate: IceCandidate) {
        delegate?.onLocalIceCandidate(candidate)
    }

    override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit

    override fun onAddStream(stream: MediaStream) = Unit

    override fun onRemoveStream(stream: MediaStream) = Unit

    override fun onDataChannel(channel: DataChannel) {
        delegate?.onRemoteDataChannel(channel)
    }

    override fun onRenegotiationNeeded() {
        delegate?.onRenegotiationNeeded()
    }

    override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) = Unit
}

private class CreateDescriptionObserver(
    private val completion: CompletableDeferred<SessionDescription>
) : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) {
        completion.complete(description)
    }

    override fun onCreateFailure(error: String) {
        completion.completeExceptionally(IllegalStateException(error.take(512)))
    }

    override fun onSetSuccess() = Unit

    override fun onSetFailure(error: String) = Unit
}

private class SetDescriptionObserver(private val completion: CompletableDeferred<Unit>) :
    SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit

    override fun onCreateFailure(error: String) = Unit

    override fun onSetSuccess() {
        completion.complete(Unit)
    }

    override fun onSetFailure(error: String) {
        completion.completeExceptionally(IllegalStateException(error.take(512)))
    }
}

private fun CrewTransportChannel.toInit() =
    DataChannel.Init().also { init ->
        init.ordered = ordered
        init.maxRetransmits = maxRetransmits ?: -1
        init.maxRetransmitTimeMs = -1
        init.protocol = wireLabel
    }

private fun SessionDescription.toCrew(generation: Long): CrewSessionDescription {
    val type =
        when (type) {
            SessionDescription.Type.OFFER -> CrewSessionDescriptionType.OFFER
            SessionDescription.Type.ANSWER -> CrewSessionDescriptionType.ANSWER
            else -> throw IllegalArgumentException("Unsupported Crew session description type")
        }
    return CrewSessionDescription(type, generation, description)
}

private val CrewTransportChannel.incomingCapacity: Int
    get() =
        when (this) {
            CrewTransportChannel.CONTROL -> CONTROL_FRAME_CAPACITY
            CrewTransportChannel.CLOCK -> CLOCK_FRAME_CAPACITY
            CrewTransportChannel.REACTION -> REACTION_FRAME_CAPACITY
            CrewTransportChannel.MEDIA -> MEDIA_FRAME_CAPACITY
        }

private fun validateIceServerUrl(value: String) {
    require(value.length <= 512 && ICE_SERVER_PATTERN.matches(value)) {
        "ICE server URL is invalid"
    }
    val port = ICE_SERVER_PATTERN.matchEntire(value)?.groupValues?.get(2).orEmpty()
    require(port.isEmpty() || port.toIntOrNull() in 1..65535) { "ICE server port is invalid" }
    require(runCatching { URI(value) }.isSuccess) { "ICE server URL is invalid" }
}
