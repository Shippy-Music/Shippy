/*
 * Copyright (c) 2026 Auxio Project
 * CrewNearbyConnections.kt is part of Auxio.
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

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLongArray
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.invite.CrewInvite
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalConnectionState
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalSendResult
import org.oxycblt.auxio.shippy.crew.runtime.CrewPreconnectedSignalPeer
import org.oxycblt.auxio.shippy.crew.signaling.CrewSignalMessage
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport
import org.oxycblt.auxio.shippy.crew.transport.CrewSendResult
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportDrop
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportState

private const val SERVICE_ID = "org.oxycblt.auxio.shippy.crew.v1"
private const val WIRE_MAGIC = 0x5348504E
private const val WIRE_VERSION = 1
private const val TYPE_HELLO = 1
private const val TYPE_CHALLENGE = 2
private const val TYPE_FINISH = 3
private const val TYPE_READY = 4
private const val TYPE_FRAME = 5
private const val NONCE_BYTES = 32
private const val PROOF_BYTES = 32
private const val MAX_ID_BYTES = 128
private const val MAX_NAME_BYTES = 80
private const val DEFAULT_CONNECT_TIMEOUT_MS = 18_000L
private const val MAX_HOST_PEERS = 8
private const val INCOMING_FRAME_CAPACITY = 256

enum class CrewNearbyFailure {
    PERMISSION_DENIED,
    UNAVAILABLE,
    NOT_FOUND,
    CONNECTION_REJECTED,
    AUTHENTICATION_FAILED,
    PROTOCOL_ERROR,
    TIMED_OUT,
}

sealed interface CrewNearbyOperationState {
    data object Starting : CrewNearbyOperationState

    data object Active : CrewNearbyOperationState

    data class Failed(val reason: CrewNearbyFailure, val platformCode: Int? = null) :
        CrewNearbyOperationState

    data object Closed : CrewNearbyOperationState
}

sealed interface CrewNearbyConnectResult {
    data class Connected(val peer: CrewPreconnectedSignalPeer) : CrewNearbyConnectResult

    data class Failed(val reason: CrewNearbyFailure, val platformCode: Int? = null) :
        CrewNearbyConnectResult
}

@Singleton
class CrewNearbyConnections @Inject constructor(@ApplicationContext private val context: Context) {
    fun advertise(
        invite: CrewInvite,
        sessionId: CrewSessionId,
        localMember: CrewMember,
    ): CrewNearbyHost =
        CrewNearbyHost(
            Nearby.getConnectionsClient(context.applicationContext),
            invite,
            sessionId,
            localMember,
        )

    suspend fun connect(
        invite: CrewInvite,
        localMember: CrewMember,
        timeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    ): CrewNearbyConnectResult {
        val attempt =
            CrewNearbyJoinAttempt(
                Nearby.getConnectionsClient(context.applicationContext),
                invite,
                localMember,
            )
        var connected = false
        return try {
            attempt.start()
            val outcome =
                withTimeoutOrNull(timeoutMs) { attempt.result.await() }
                    ?: CrewNearbyConnectResult.Failed(CrewNearbyFailure.TIMED_OUT)
            connected = outcome is CrewNearbyConnectResult.Connected
            outcome
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            CrewNearbyConnectResult.Failed(CrewNearbyFailure.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            CrewNearbyConnectResult.Failed(CrewNearbyFailure.UNAVAILABLE)
        } finally {
            if (!connected) attempt.close()
        }
    }
}

class CrewNearbyHost
internal constructor(
    private val client: ConnectionsClient,
    private val invite: CrewInvite,
    private val sessionId: CrewSessionId,
    private val localMember: CrewMember,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val connections = ConcurrentHashMap<String, HostHandshake>()
    private val mutableState =
        MutableStateFlow<CrewNearbyOperationState>(CrewNearbyOperationState.Starting)
    private val mutablePeers =
        MutableSharedFlow<CrewPreconnectedSignalPeer>(extraBufferCapacity = 8)
    private val endpointInfo = NearbyWireCodec.endpointInfo(invite)

    val state: StateFlow<CrewNearbyOperationState> = mutableState.asStateFlow()
    val peers: SharedFlow<CrewPreconnectedSignalPeer> = mutablePeers.asSharedFlow()

    private val payloadCallback =
        object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                val bytes = payload.asBytes() ?: return disconnect(endpointId)
                val handshake = connections[endpointId] ?: return disconnect(endpointId)
                val transport = handshake.transport
                if (transport != null) {
                    if (!transport.accept(bytes)) disconnect(endpointId)
                } else {
                    handleHandshake(endpointId, handshake, bytes)
                }
            }

            override fun onPayloadTransferUpdate(
                endpointId: String,
                update: PayloadTransferUpdate,
            ) = Unit
        }

    private val lifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                if (
                    closed.get() ||
                        connections.size >= MAX_HOST_PEERS ||
                        !endpointInfo.contentEquals(info.endpointInfo)
                ) {
                    client.rejectConnection(endpointId)
                    return
                }
                connections[endpointId] = HostHandshake()
                client.acceptConnection(endpointId, payloadCallback).addOnFailureListener {
                    disconnect(endpointId)
                }
            }

            override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
                if (resolution.status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                    disconnect(endpointId)
                }
            }

            override fun onDisconnected(endpointId: String) {
                connections.remove(endpointId)?.transport?.disconnected()
            }
        }

    init {
        try {
            client
                .startAdvertising(
                    endpointInfo,
                    SERVICE_ID,
                    lifecycleCallback,
                    AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build(),
                )
                .addOnSuccessListener {
                    if (!closed.get()) mutableState.value = CrewNearbyOperationState.Active
                }
                .addOnFailureListener { error ->
                    if (!closed.get()) mutableState.value = error.toNearbyOperationFailure()
                }
        } catch (_: SecurityException) {
            mutableState.value =
                CrewNearbyOperationState.Failed(CrewNearbyFailure.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            mutableState.value = CrewNearbyOperationState.Failed(CrewNearbyFailure.UNAVAILABLE)
        }
    }

    private fun handleHandshake(endpointId: String, state: HostHandshake, bytes: ByteArray) {
        val message = NearbyWireCodec.decodeHandshake(bytes) ?: return disconnect(endpointId)
        when (message) {
            is NearbyHandshake.Hello -> {
                if (
                    state.clientNonce != null ||
                        System.currentTimeMillis() !in
                            invite.issuedAtEpochMs until invite.expiresAtEpochMs ||
                        message.memberId.protocolVersion != invite.protocolVersion ||
                        message.memberId == localMember.id ||
                        !NearbyWireCodec.verifyHello(invite, message)
                ) {
                    disconnect(endpointId)
                    return
                }
                val serverNonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
                state.clientNonce = message.nonce
                state.serverNonce = serverNonce
                state.remoteMember = CrewMember(message.memberId, message.displayName)
                sendRaw(
                    endpointId,
                    NearbyWireCodec.challenge(
                        invite,
                        sessionId,
                        localMember,
                        message.memberId,
                        message.nonce,
                        serverNonce,
                    ),
                )
            }
            is NearbyHandshake.Finish -> {
                val remoteMember = state.remoteMember ?: return disconnect(endpointId)
                val clientNonce = state.clientNonce ?: return disconnect(endpointId)
                val serverNonce = state.serverNonce ?: return disconnect(endpointId)
                if (
                    !NearbyWireCodec.verifyFinish(
                        invite,
                        sessionId,
                        remoteMember.id,
                        localMember.id,
                        clientNonce,
                        serverNonce,
                        message,
                    )
                ) {
                    disconnect(endpointId)
                    return
                }
                val transport = CrewNearbyPeerTransport(client, endpointId, remoteMember.id)
                state.transport = transport
                val peer =
                    NearbySignalPeer(
                        sessionId,
                        remoteMember.id,
                        remoteMember.displayName,
                        transport,
                    )
                sendRaw(endpointId, NearbyWireCodec.ready())
                if (!mutablePeers.tryEmit(peer)) disconnect(endpointId)
            }
            else -> disconnect(endpointId)
        }
    }

    private fun sendRaw(endpointId: String, bytes: ByteArray) {
        client.sendPayload(endpointId, Payload.fromBytes(bytes)).addOnFailureListener {
            disconnect(endpointId)
        }
    }

    private fun disconnect(endpointId: String) {
        connections.remove(endpointId)?.transport?.disconnected()
        runCatching { client.disconnectFromEndpoint(endpointId) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { client.stopAdvertising() }
        connections.keys.toList().forEach(::disconnect)
        mutableState.value = CrewNearbyOperationState.Closed
    }

    private class HostHandshake {
        var clientNonce: ByteArray? = null
        var serverNonce: ByteArray? = null
        var remoteMember: CrewMember? = null
        var transport: CrewNearbyPeerTransport? = null
    }
}

private class CrewNearbyJoinAttempt(
    private val client: ConnectionsClient,
    private val invite: CrewInvite,
    private val localMember: CrewMember,
) : Closeable {
    private val endpointInfo = NearbyWireCodec.endpointInfo(invite)
    private val selected = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var endpointId: String? = null
    private var clientNonce: ByteArray? = null
    private var serverNonce: ByteArray? = null
    private var remoteMember: CrewMember? = null
    private var sessionId: CrewSessionId? = null
    private var transport: CrewNearbyPeerTransport? = null
    val result = CompletableDeferred<CrewNearbyConnectResult>()

    private val payloadCallback =
        object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                val bytes = payload.asBytes() ?: return fail(CrewNearbyFailure.PROTOCOL_ERROR)
                val activeTransport = transport
                if (activeTransport != null && NearbyWireCodec.isFrame(bytes)) {
                    if (!activeTransport.accept(bytes)) fail(CrewNearbyFailure.PROTOCOL_ERROR)
                    return
                }
                when (val message = NearbyWireCodec.decodeHandshake(bytes)) {
                    is NearbyHandshake.Challenge -> handleChallenge(endpointId, message)
                    NearbyHandshake.Ready -> finishConnected(endpointId)
                    else -> fail(CrewNearbyFailure.PROTOCOL_ERROR)
                }
            }

            override fun onPayloadTransferUpdate(
                endpointId: String,
                update: PayloadTransferUpdate,
            ) = Unit
        }

    private val lifecycleCallback =
        object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                client.acceptConnection(endpointId, payloadCallback).addOnFailureListener {
                    fail(it.toNearbyFailure())
                }
            }

            override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
                if (resolution.status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                    fail(CrewNearbyFailure.CONNECTION_REJECTED, resolution.status.statusCode)
                    return
                }
                val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
                clientNonce = nonce
                sendRaw(endpointId, NearbyWireCodec.hello(invite, localMember, nonce))
            }

            override fun onDisconnected(endpointId: String) {
                transport?.disconnected()
                if (!result.isCompleted) fail(CrewNearbyFailure.CONNECTION_REJECTED)
            }
        }

    private val discoveryCallback =
        object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                if (
                    closed.get() ||
                        info.serviceId != SERVICE_ID ||
                        !endpointInfo.contentEquals(info.endpointInfo) ||
                        !selected.compareAndSet(false, true)
                ) {
                    return
                }
                this@CrewNearbyJoinAttempt.endpointId = endpointId
                client.stopDiscovery()
                client
                    .requestConnection(endpointInfo, endpointId, lifecycleCallback)
                    .addOnFailureListener { fail(it.toNearbyFailure()) }
            }

            override fun onEndpointLost(endpointId: String) {
                if (this@CrewNearbyJoinAttempt.endpointId == endpointId && !result.isCompleted) {
                    fail(CrewNearbyFailure.NOT_FOUND)
                }
            }
        }

    fun start() {
        client
            .startDiscovery(
                SERVICE_ID,
                discoveryCallback,
                DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build(),
            )
            .addOnFailureListener { fail(it.toNearbyFailure()) }
    }

    private fun handleChallenge(endpointId: String, message: NearbyHandshake.Challenge) {
        val nonce = clientNonce ?: return fail(CrewNearbyFailure.PROTOCOL_ERROR)
        if (
            message.memberId == localMember.id ||
                message.sessionId.protocolVersion != invite.protocolVersion ||
                !NearbyWireCodec.verifyChallenge(invite, localMember.id, nonce, message)
        ) {
            fail(CrewNearbyFailure.AUTHENTICATION_FAILED)
            return
        }
        serverNonce = message.serverNonce
        remoteMember = CrewMember(message.memberId, message.displayName)
        sessionId = message.sessionId
        transport = CrewNearbyPeerTransport(client, endpointId, message.memberId)
        sendRaw(
            endpointId,
            NearbyWireCodec.finish(
                invite,
                message.sessionId,
                localMember.id,
                message.memberId,
                nonce,
                message.serverNonce,
            ),
        )
    }

    private fun finishConnected(endpointId: String) {
        val activeTransport = transport ?: return fail(CrewNearbyFailure.PROTOCOL_ERROR)
        val remote = remoteMember ?: return fail(CrewNearbyFailure.PROTOCOL_ERROR)
        val activeSessionId = sessionId ?: return fail(CrewNearbyFailure.PROTOCOL_ERROR)
        if (this.endpointId != endpointId) return fail(CrewNearbyFailure.PROTOCOL_ERROR)
        val peer = NearbySignalPeer(activeSessionId, remote.id, remote.displayName, activeTransport)
        result.complete(CrewNearbyConnectResult.Connected(peer))
        runCatching { client.stopDiscovery() }
    }

    private fun sendRaw(endpointId: String, bytes: ByteArray) {
        client.sendPayload(endpointId, Payload.fromBytes(bytes)).addOnFailureListener {
            fail(it.toNearbyFailure())
        }
    }

    private fun fail(reason: CrewNearbyFailure, platformCode: Int? = null) {
        if (result.complete(CrewNearbyConnectResult.Failed(reason, platformCode))) close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { client.stopDiscovery() }
        endpointId?.let { endpoint -> runCatching { client.disconnectFromEndpoint(endpoint) } }
        if (!result.isCompleted)
            result.complete(CrewNearbyConnectResult.Failed(CrewNearbyFailure.UNAVAILABLE))
    }
}

private class NearbySignalPeer(
    override val sessionId: CrewSessionId,
    override val remoteMemberClaim: CrewMemberId,
    override val remoteDisplayName: String,
    override val preconnectedTransport: CrewPeerTransport,
) : CrewPreconnectedSignalPeer {
    private val closed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(CrewSignalConnectionState.CONNECTED)

    override val state: StateFlow<CrewSignalConnectionState> = mutableState.asStateFlow()
    override val incoming: Flow<CrewSignalMessage> = emptyFlow()

    override suspend fun send(message: CrewSignalMessage): CrewSignalSendResult =
        if (closed.get()) CrewSignalSendResult.Closed else CrewSignalSendResult.Failed

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        preconnectedTransport.close()
        mutableState.value = CrewSignalConnectionState.CLOSED
    }
}

private class CrewNearbyPeerTransport(
    private val client: ConnectionsClient,
    private val endpointId: String,
    override val remoteMemberId: CrewMemberId,
) : CrewPeerTransport {
    private val closed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(CrewTransportState.CONNECTED)
    private val incomingChannel = Channel<CrewTransportFrame>(INCOMING_FRAME_CAPACITY)
    private val mutableDrops = MutableSharedFlow<CrewTransportDrop>(extraBufferCapacity = 16)
    private val buffered = AtomicLongArray(CrewTransportChannel.entries.size)

    override val state: StateFlow<CrewTransportState> = mutableState.asStateFlow()
    override val incoming: Flow<CrewTransportFrame> = incomingChannel.receiveAsFlow()
    override val drops: Flow<CrewTransportDrop> = mutableDrops.asSharedFlow()

    fun accept(bytes: ByteArray): Boolean {
        if (closed.get()) return false
        val frame = NearbyWireCodec.decodeFrame(bytes) ?: return false
        if (incomingChannel.trySend(frame).isFailure) {
            fail()
            return false
        }
        return true
    }

    override fun trySend(frame: CrewTransportFrame): CrewSendResult {
        if (closed.get()) return CrewSendResult.Closed
        if (mutableState.value != CrewTransportState.CONNECTED) return CrewSendResult.ChannelNotOpen
        val index = frame.channel.ordinal
        val payloadBytes = frame.size.toLong()
        while (true) {
            val current = buffered.get(index)
            val next = current + payloadBytes
            if (next > frame.channel.maxBufferedBytes) {
                return CrewSendResult.Backpressured(current, frame.channel.maxBufferedBytes)
            }
            if (buffered.compareAndSet(index, current, next)) break
        }
        return try {
            client
                .sendPayload(endpointId, Payload.fromBytes(NearbyWireCodec.frame(frame)))
                .addOnCompleteListener { task ->
                    buffered.addAndGet(index, -payloadBytes)
                    if (!task.isSuccessful) fail()
                }
            CrewSendResult.Sent(buffered.get(index))
        } catch (_: RuntimeException) {
            buffered.addAndGet(index, -payloadBytes)
            fail()
            CrewSendResult.NativeRejected
        }
    }

    override fun bufferedBytes(channel: CrewTransportChannel): Long = buffered.get(channel.ordinal)

    fun disconnected() = fail()

    private fun fail() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { client.disconnectFromEndpoint(endpointId) }
        mutableState.value = CrewTransportState.FAILED
        incomingChannel.close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { client.disconnectFromEndpoint(endpointId) }
        mutableState.value = CrewTransportState.CLOSED
        incomingChannel.close()
    }
}

internal sealed interface NearbyHandshake {
    data class Hello(
        val memberId: CrewMemberId,
        val displayName: String,
        val nonce: ByteArray,
        val proof: ByteArray,
    ) : NearbyHandshake

    data class Challenge(
        val sessionId: CrewSessionId,
        val memberId: CrewMemberId,
        val displayName: String,
        val clientNonce: ByteArray,
        val serverNonce: ByteArray,
        val proof: ByteArray,
    ) : NearbyHandshake

    data class Finish(val proof: ByteArray) : NearbyHandshake

    data object Ready : NearbyHandshake
}

internal object NearbyWireCodec {
    fun endpointInfo(invite: CrewInvite): ByteArray = encode {
        writeInt(WIRE_MAGIC)
        writeByte(WIRE_VERSION)
        writeInt(invite.protocolVersion.value)
        writeBounded(invite.sessionLocator.value)
        writeBounded(invite.inviteId.value)
    }

    fun hello(invite: CrewInvite, member: CrewMember, nonce: ByteArray): ByteArray {
        val proof =
            proof(invite, "hello") {
                writeBounded(member.id.value)
                writeBounded(member.displayName)
                write(nonce)
            }
        return encode {
            writeByte(TYPE_HELLO)
            writeInt(member.id.protocolVersion.value)
            writeBounded(member.id.value)
            writeName(member.displayName)
            write(nonce)
            write(proof)
        }
    }

    fun verifyHello(invite: CrewInvite, hello: NearbyHandshake.Hello): Boolean =
        MessageDigest.isEqual(
            hello.proof,
            proof(invite, "hello") {
                writeBounded(hello.memberId.value)
                writeBounded(hello.displayName)
                write(hello.nonce)
            },
        )

    fun challenge(
        invite: CrewInvite,
        sessionId: CrewSessionId,
        host: CrewMember,
        clientId: CrewMemberId,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): ByteArray {
        val proof = challengeProof(invite, sessionId, clientId, host.id, clientNonce, serverNonce)
        return encode {
            writeByte(TYPE_CHALLENGE)
            writeInt(sessionId.protocolVersion.value)
            writeBounded(sessionId.value)
            writeBounded(host.id.value)
            writeName(host.displayName)
            write(clientNonce)
            write(serverNonce)
            write(proof)
        }
    }

    fun verifyChallenge(
        invite: CrewInvite,
        clientId: CrewMemberId,
        clientNonce: ByteArray,
        challenge: NearbyHandshake.Challenge,
    ): Boolean =
        challenge.clientNonce.contentEquals(clientNonce) &&
            MessageDigest.isEqual(
                challenge.proof,
                challengeProof(
                    invite,
                    challenge.sessionId,
                    clientId,
                    challenge.memberId,
                    clientNonce,
                    challenge.serverNonce,
                ),
            )

    fun finish(
        invite: CrewInvite,
        sessionId: CrewSessionId,
        clientId: CrewMemberId,
        hostId: CrewMemberId,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): ByteArray = encode {
        writeByte(TYPE_FINISH)
        write(finishProof(invite, sessionId, clientId, hostId, clientNonce, serverNonce))
    }

    fun verifyFinish(
        invite: CrewInvite,
        sessionId: CrewSessionId,
        clientId: CrewMemberId,
        hostId: CrewMemberId,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        finish: NearbyHandshake.Finish,
    ): Boolean =
        MessageDigest.isEqual(
            finish.proof,
            finishProof(invite, sessionId, clientId, hostId, clientNonce, serverNonce),
        )

    fun ready(): ByteArray = byteArrayOf(TYPE_READY.toByte())

    fun frame(frame: CrewTransportFrame): ByteArray = encode {
        writeByte(TYPE_FRAME)
        writeByte(frame.channel.ordinal)
        writeInt(frame.size)
        write(frame.copyPayload())
    }

    fun isFrame(bytes: ByteArray) = bytes.firstOrNull()?.toInt() == TYPE_FRAME

    fun decodeFrame(bytes: ByteArray): CrewTransportFrame? =
        runCatching {
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    require(input.readUnsignedByte() == TYPE_FRAME)
                    val channel = CrewTransportChannel.entries[input.readUnsignedByte()]
                    val size = input.readInt()
                    require(size in 1..channel.maxPayloadBytes)
                    val payload = ByteArray(size)
                    input.readFully(payload)
                    require(input.available() == 0)
                    CrewTransportFrame(channel, payload)
                }
            }
            .getOrNull()

    fun decodeHandshake(bytes: ByteArray): NearbyHandshake? =
        runCatching {
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    val message =
                        when (input.readUnsignedByte()) {
                            TYPE_HELLO -> {
                                val protocol = ProtocolVersion(input.readInt())
                                NearbyHandshake.Hello(
                                    CrewMemberId(input.readBounded(), protocol),
                                    input.readName(),
                                    input.readFixed(NONCE_BYTES),
                                    input.readFixed(PROOF_BYTES),
                                )
                            }
                            TYPE_CHALLENGE -> {
                                val protocol = ProtocolVersion(input.readInt())
                                NearbyHandshake.Challenge(
                                    CrewSessionId(input.readBounded(), protocol),
                                    CrewMemberId(input.readBounded(), protocol),
                                    input.readName(),
                                    input.readFixed(NONCE_BYTES),
                                    input.readFixed(NONCE_BYTES),
                                    input.readFixed(PROOF_BYTES),
                                )
                            }
                            TYPE_FINISH -> NearbyHandshake.Finish(input.readFixed(PROOF_BYTES))
                            TYPE_READY -> NearbyHandshake.Ready
                            else -> error("Unknown Nearby Crew message")
                        }
                    require(input.available() == 0)
                    message
                }
            }
            .getOrNull()

    private fun challengeProof(
        invite: CrewInvite,
        sessionId: CrewSessionId,
        clientId: CrewMemberId,
        hostId: CrewMemberId,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ) =
        proof(invite, "challenge") {
            writeBounded(sessionId.value)
            writeBounded(clientId.value)
            writeBounded(hostId.value)
            write(clientNonce)
            write(serverNonce)
        }

    private fun finishProof(
        invite: CrewInvite,
        sessionId: CrewSessionId,
        clientId: CrewMemberId,
        hostId: CrewMemberId,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ) =
        proof(invite, "finish") {
            writeBounded(sessionId.value)
            writeBounded(clientId.value)
            writeBounded(hostId.value)
            write(clientNonce)
            write(serverNonce)
        }

    private fun proof(
        invite: CrewInvite,
        label: String,
        body: DataOutputStream.() -> Unit,
    ): ByteArray {
        val transcript = encode {
            writeBounded(label)
            write(endpointInfo(invite))
            body()
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(invite.secret.value.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(transcript)
    }

    private fun encode(block: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(block)
        return bytes.toByteArray()
    }

    private fun DataOutputStream.writeBounded(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_ID_BYTES)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeName(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_NAME_BYTES)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBounded(): String = readString(MAX_ID_BYTES)

    private fun DataInputStream.readName(): String = readString(MAX_NAME_BYTES)

    private fun DataInputStream.readString(maxBytes: Int): String {
        val size = readUnsignedShort()
        require(size in 1..maxBytes)
        return readFixed(size).toString(Charsets.UTF_8).also { require(it.isNotBlank()) }
    }

    private fun DataInputStream.readFixed(size: Int): ByteArray = ByteArray(size).also(::readFully)
}

private fun Throwable.toNearbyFailure(): CrewNearbyFailure {
    val code = (this as? ApiException)?.statusCode
    return when (code) {
        ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION,
        ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_FINE_LOCATION,
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADVERTISE,
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_CONNECT,
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_SCAN,
        ConnectionsStatusCodes.MISSING_PERMISSION_NEARBY_WIFI_DEVICES ->
            CrewNearbyFailure.PERMISSION_DENIED
        else -> CrewNearbyFailure.UNAVAILABLE
    }
}

private fun Throwable.toNearbyOperationFailure() =
    CrewNearbyOperationState.Failed(toNearbyFailure(), (this as? ApiException)?.statusCode)
