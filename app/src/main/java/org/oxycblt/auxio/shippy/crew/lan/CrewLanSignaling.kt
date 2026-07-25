/*
 * Copyright (c) 2026 Shippy contributors
 * CrewLanSignaling.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.lan

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.invite.CrewInvite
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteId
import org.oxycblt.auxio.shippy.crew.invite.CrewSessionLocator
import org.oxycblt.auxio.shippy.crew.signaling.CrewSignalDecodeResult
import org.oxycblt.auxio.shippy.crew.signaling.CrewSignalMessage
import org.oxycblt.auxio.shippy.crew.signaling.CrewSignalMessageCodec
import org.oxycblt.auxio.shippy.crew.signaling.MAX_CREW_SIGNAL_MESSAGE_BYTES

private const val SIGNAL_HANDSHAKE_MAGIC = 0x53485059
private const val SIGNAL_HANDSHAKE_VERSION = 2
private const val SIGNAL_NONCE_BYTES = 32
private const val SIGNAL_PROOF_BYTES = 32
private const val SIGNAL_KEY_BYTES = 32
private const val SIGNAL_GCM_NONCE_BYTES = 12
private const val SIGNAL_GCM_TAG_BITS = 128
private const val SIGNAL_GCM_TAG_BYTES = SIGNAL_GCM_TAG_BITS / Byte.SIZE_BITS
private const val SIGNAL_MAX_ID_BYTES = 128
private const val SIGNAL_MAX_DISPLAY_NAME_BYTES = 80
private const val SIGNAL_CONNECT_TIMEOUT_MS = 5_000
private const val SIGNAL_HANDSHAKE_TIMEOUT_MS = 8_000
private const val SIGNAL_MAX_ENCRYPTED_FRAME_BYTES =
    MAX_CREW_SIGNAL_MESSAGE_BYTES + SIGNAL_GCM_TAG_BYTES + Long.SIZE_BYTES
private const val SIGNAL_MIN_ENCRYPTED_FRAME_BYTES =
    Long.SIZE_BYTES + SIGNAL_GCM_TAG_BYTES + 3
private const val SIGNAL_INCOMING_CAPACITY = 64
private const val DEFAULT_MAX_SIGNAL_PEERS = 8
private const val SIGNAL_CLOCK_SKEW_TOLERANCE_MS = 2 * 60 * 1000L
private const val SIGNAL_MAX_INVITE_LIFETIME_MS = 15 * 60 * 1000L

enum class CrewSignalConnectionState {
    CONNECTED,
    FAILED,
    CLOSED,
}

sealed interface CrewSignalSendResult {
    data object Sent : CrewSignalSendResult

    data object Closed : CrewSignalSendResult

    data object Failed : CrewSignalSendResult
}

/**
 * A QR-secret-authenticated signaling peer.
 *
 * [remoteMemberClaim] proves possession of the active invitation secret but is still a claim until
 * the SDP-fingerprint-bound Crew join authenticator completes over WebRTC. [remoteDisplayName] is
 * likewise only a claim, usable after WebRTC proves the same remote member ID.
 */
interface CrewSignalPeer : Closeable {
    val sessionId: CrewSessionId
    val remoteMemberClaim: CrewMemberId
    val remoteDisplayName: String
    val state: StateFlow<CrewSignalConnectionState>
    val incoming: Flow<CrewSignalMessage>

    suspend fun send(message: CrewSignalMessage): CrewSignalSendResult
}

sealed interface CrewLanSignalConnectResult {
    data class Connected(val peer: CrewSignalPeer) : CrewLanSignalConnectResult

    data class Failed(val reason: CrewLanSignalConnectFailure) : CrewLanSignalConnectResult
}

enum class CrewLanSignalConnectFailure {
    INVITATION_MISMATCH,
    INVITATION_INACTIVE,
    NO_REACHABLE_ADDRESS,
    AUTHENTICATION_FAILED,
    PROTOCOL_ERROR,
}

fun interface CrewSignalNonceSource {
    fun nextNonce(): ByteArray
}

object SecureCrewSignalNonceSource : CrewSignalNonceSource {
    private val random = SecureRandom()

    override fun nextNonce() = ByteArray(SIGNAL_NONCE_BYTES).also(random::nextBytes)
}

/**
 * Bounded local signaling listener. Pair [port] with [CrewLanDiscovery.advertise].
 *
 * Each accepted socket proves possession of the active QR secret before the peer is emitted.
 * Signaling payloads are then AES-256-GCM encrypted with directional HKDF-derived keys.
 */
class CrewLanSignalingHost(
    private val invite: CrewInvite,
    private val sessionId: CrewSessionId,
    private val localMemberId: CrewMemberId,
    private val localDisplayName: String,
    nowEpochMs: Long,
    private val maxPeers: Int = DEFAULT_MAX_SIGNAL_PEERS,
    private val nonceSource: CrewSignalNonceSource = SecureCrewSignalNonceSource,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val inFlight = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverSocket =
        createSignalServerSocket(
            maxPeers.also {
                validateHostConfig(invite, sessionId, localMemberId, localDisplayName, nowEpochMs, it)
            },
        )
    private val acceptedPeers = Channel<CrewSignalPeer>(maxPeers)
    private val livePeers = ConcurrentHashMap.newKeySet<SecureCrewSignalPeer>()
    private val handshakeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val acceptJob: Job

    val port: Int = serverSocket.localPort
    val peers: Flow<CrewSignalPeer> = acceptedPeers.receiveAsFlow()

    init {
        acceptJob = scope.launch { acceptLoop() }
    }

    private suspend fun acceptLoop() {
        while (!closed.get()) {
            val socket =
                try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    if (!closed.get()) close()
                    return
                }
            if (livePeers.size + inFlight.get() >= maxPeers) {
                socket.closeQuietly()
                continue
            }
            inFlight.incrementAndGet()
            handshakeSockets += socket
            scope.launch {
                try {
                    val handshake =
                        serverHandshake(
                            socket,
                            invite,
                            sessionId,
                            localMemberId,
                            localDisplayName,
                            nonceSource,
                        )
                    val peer =
                        SecureCrewSignalPeer(
                            socket,
                            handshake.sessionId,
                            handshake.remoteMemberId,
                            handshake.remoteDisplayName,
                            handshake.keys.serverToClientKey,
                            handshake.keys.clientToServerKey,
                            handshake.keys.serverNoncePrefix,
                            handshake.keys.clientNoncePrefix,
                            handshake.keys.transcriptHash,
                        ) {
                            livePeers.remove(it)
                        }
                    livePeers += peer
                    if (acceptedPeers.trySend(peer).isFailure) peer.close()
                    else peer.start()
                } catch (_: Exception) {
                    socket.closeQuietly()
                } finally {
                    handshakeSockets -= socket
                    inFlight.decrementAndGet()
                }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        serverSocket.closeQuietly()
        acceptJob.cancel()
        handshakeSockets.toList().forEach { it.closeQuietly() }
        handshakeSockets.clear()
        livePeers.toList().forEach { it.close() }
        livePeers.clear()
        acceptedPeers.close()
        scope.cancel()
    }
}

object CrewLanSignalingClient {
    suspend fun connect(
        rendezvous: CrewLanRendezvous,
        invite: CrewInvite,
        localMemberId: CrewMemberId,
        localDisplayName: String,
        nowEpochMs: Long,
        nonceSource: CrewSignalNonceSource = SecureCrewSignalNonceSource,
    ): CrewLanSignalConnectResult =
        withContext(Dispatchers.IO) {
            val expectedIdentity =
                CrewLanIdentity(invite.protocolVersion, invite.sessionLocator, invite.inviteId)
            if (rendezvous.identity != expectedIdentity) {
                return@withContext CrewLanSignalConnectResult.Failed(
                    CrewLanSignalConnectFailure.INVITATION_MISMATCH,
                )
            }
            if (!isActiveSignalInvite(invite, nowEpochMs)) {
                return@withContext CrewLanSignalConnectResult.Failed(
                    CrewLanSignalConnectFailure.INVITATION_INACTIVE,
                )
            }
            if (localMemberId.protocolVersion != invite.protocolVersion) {
                return@withContext CrewLanSignalConnectResult.Failed(
                    CrewLanSignalConnectFailure.PROTOCOL_ERROR,
                )
            }
            requireValidSignalDisplayName(localDisplayName)
            var authenticationFailed = false
            for (address in rendezvous.addresses) {
                val socket = Socket()
                try {
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    rendezvous.network?.bindSocket(socket)
                    socket.connect(
                        InetSocketAddress(address, rendezvous.port),
                        SIGNAL_CONNECT_TIMEOUT_MS,
                    )
                    val handshake =
                        clientHandshake(socket, invite, localMemberId, localDisplayName, nonceSource)
                    val peer =
                        SecureCrewSignalPeer(
                            socket,
                            handshake.sessionId,
                            handshake.remoteMemberId,
                            handshake.remoteDisplayName,
                            handshake.keys.clientToServerKey,
                            handshake.keys.serverToClientKey,
                            handshake.keys.clientNoncePrefix,
                            handshake.keys.serverNoncePrefix,
                            handshake.keys.transcriptHash,
                        )
                    peer.start()
                    return@withContext CrewLanSignalConnectResult.Connected(peer)
                } catch (_: SignalAuthenticationException) {
                    authenticationFailed = true
                    socket.closeQuietly()
                } catch (_: SignalProtocolException) {
                    socket.closeQuietly()
                    return@withContext CrewLanSignalConnectResult.Failed(
                        CrewLanSignalConnectFailure.PROTOCOL_ERROR,
                    )
                } catch (error: CancellationException) {
                    socket.closeQuietly()
                    throw error
                } catch (_: Exception) {
                    socket.closeQuietly()
                }
            }
            CrewLanSignalConnectResult.Failed(
                if (authenticationFailed) {
                    CrewLanSignalConnectFailure.AUTHENTICATION_FAILED
                } else {
                    CrewLanSignalConnectFailure.NO_REACHABLE_ADDRESS
                },
            )
        }
}

private class SecureCrewSignalPeer(
    private val socket: Socket,
    override val sessionId: CrewSessionId,
    override val remoteMemberClaim: CrewMemberId,
    override val remoteDisplayName: String,
    sendKey: ByteArray,
    receiveKey: ByteArray,
    private val sendNoncePrefix: ByteArray,
    private val receiveNoncePrefix: ByteArray,
    private val transcriptHash: ByteArray,
    private val onClosed: ((SecureCrewSignalPeer) -> Unit)? = null,
) : CrewSignalPeer {
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val output = DataOutputStream(socket.getOutputStream())
    private val input = DataInputStream(socket.getInputStream())
    private val sendSecret = SecretKeySpec(sendKey.copyOf(), "AES")
    private val receiveSecret = SecretKeySpec(receiveKey.copyOf(), "AES")
    private val sendMutex = Mutex()
    private val incomingChannel = Channel<CrewSignalMessage>(SIGNAL_INCOMING_CAPACITY)
    private val mutableState =
        MutableStateFlow(CrewSignalConnectionState.CONNECTED)
    private var sendSequence = 0L
    private var receiveSequence = 0L

    override val state = mutableState.asStateFlow()
    override val incoming = incomingChannel.receiveAsFlow()

    init {
        require(sendNoncePrefix.size == SIGNAL_GCM_NONCE_BYTES - Long.SIZE_BYTES)
        require(receiveNoncePrefix.size == SIGNAL_GCM_NONCE_BYTES - Long.SIZE_BYTES)
        require(transcriptHash.size == SIGNAL_PROOF_BYTES)
        sendKey.fill(0)
        receiveKey.fill(0)
    }

    fun start() {
        if (!closed.get() && started.compareAndSet(false, true)) {
            scope.launch { readLoop() }
        }
    }

    override suspend fun send(message: CrewSignalMessage): CrewSignalSendResult =
        sendMutex.withLock {
            if (closed.get()) return@withLock CrewSignalSendResult.Closed
            if (sendSequence == Long.MAX_VALUE) {
                fail()
                return@withLock CrewSignalSendResult.Failed
            }
            try {
                withContext(Dispatchers.IO) {
                    val plain = CrewSignalMessageCodec.encode(message)
                    val sequence = sendSequence++
                    val encrypted =
                        encryptFrame(
                            sendSecret,
                            sendNoncePrefix,
                            transcriptHash,
                            sequence,
                            plain,
                        )
                    output.writeInt(Long.SIZE_BYTES + encrypted.size)
                    output.writeLong(sequence)
                    output.write(encrypted)
                    output.flush()
                }
                CrewSignalSendResult.Sent
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                fail()
                CrewSignalSendResult.Failed
            }
        }

    private suspend fun readLoop() {
        try {
            while (!closed.get()) {
                val frameSize = input.readInt()
                require(
                    frameSize in
                        SIGNAL_MIN_ENCRYPTED_FRAME_BYTES..
                            SIGNAL_MAX_ENCRYPTED_FRAME_BYTES,
                ) {
                    "Crew encrypted signaling frame has invalid size"
                }
                val sequence = input.readLong()
                require(sequence == receiveSequence && receiveSequence < Long.MAX_VALUE) {
                    "Crew signaling sequence is invalid"
                }
                val encrypted = ByteArray(frameSize - Long.SIZE_BYTES)
                input.readFully(encrypted)
                val plain =
                    decryptFrame(
                        receiveSecret,
                        receiveNoncePrefix,
                        transcriptHash,
                        sequence,
                        encrypted,
                    )
                receiveSequence++
                val decoded = CrewSignalMessageCodec.decode(plain)
                val message =
                    (decoded as? CrewSignalDecodeResult.Accepted)?.message
                        ?: throw IllegalArgumentException("Crew signaling payload is invalid")
                if (incomingChannel.trySend(message).isFailure) {
                    throw IllegalStateException("Crew signaling receive window is full")
                }
                if (message is CrewSignalMessage.Close) {
                    close()
                    return
                }
            }
        } catch (_: EOFException) {
            close()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            fail()
        }
    }

    private fun fail() = closeInternal(CrewSignalConnectionState.FAILED)

    override fun close() = closeInternal(CrewSignalConnectionState.CLOSED)

    private fun closeInternal(finalState: CrewSignalConnectionState) {
        if (!closed.compareAndSet(false, true)) return
        mutableState.value = finalState
        incomingChannel.close()
        socket.closeQuietly()
        scope.cancel()
        onClosed?.invoke(this)
    }

    override fun toString() =
        "SecureCrewSignalPeer(sessionId=$sessionId, remoteMemberClaim=$remoteMemberClaim, " +
            "endpoint=redacted)"
}

private data class SignalHandshakeResult(
    val sessionId: CrewSessionId,
    val remoteMemberId: CrewMemberId,
    val remoteDisplayName: String,
    val keys: SignalSessionKeys,
)

private class SignalSessionKeys(
    val clientToServerKey: ByteArray,
    val serverToClientKey: ByteArray,
    val clientNoncePrefix: ByteArray,
    val serverNoncePrefix: ByteArray,
    val transcriptHash: ByteArray,
)

private fun serverHandshake(
    socket: Socket,
    invite: CrewInvite,
    sessionId: CrewSessionId,
    localMemberId: CrewMemberId,
    localDisplayName: String,
    nonceSource: CrewSignalNonceSource,
): SignalHandshakeResult {
    requireActiveSignalInvite(invite, System.currentTimeMillis())
    socket.soTimeout = SIGNAL_HANDSHAKE_TIMEOUT_MS
    socket.tcpNoDelay = true
    socket.keepAlive = true
    val input = DataInputStream(socket.getInputStream())
    val output = DataOutputStream(socket.getOutputStream())
    require(input.readInt() == SIGNAL_HANDSHAKE_MAGIC) { "Crew signaling magic is invalid" }
    require(input.readUnsignedByte() == SIGNAL_HANDSHAKE_VERSION) {
        "Crew signaling handshake version is unsupported"
    }
    val protocolVersion = ProtocolVersion(input.readInt())
    val sessionLocator = CrewSessionLocator(input.readSizedSignalString())
    val inviteId = CrewInviteId(input.readSizedSignalString())
    val remoteMemberId = input.readSignalMemberId()
    val remoteDisplayName = input.readSignalDisplayName()
    val clientNonce = input.readSignalBytes(SIGNAL_NONCE_BYTES)
    require(
        protocolVersion == invite.protocolVersion &&
            remoteMemberId.protocolVersion == invite.protocolVersion
    ) {
        "Crew signaling protocol does not match"
    }
    require(sessionLocator == invite.sessionLocator && inviteId == invite.inviteId) {
        "Crew signaling invitation does not match"
    }
    val serverNonce = nonceSource.nextCheckedNonce()
    val transcript =
        SignalTranscript(
            invite,
            sessionId,
            remoteMemberId,
            localMemberId,
            remoteDisplayName,
            localDisplayName,
            clientNonce,
            serverNonce,
        )
    output.writeSignalSessionId(sessionId)
    output.writeSignalMemberId(localMemberId)
    output.writeSignalDisplayName(localDisplayName)
    output.write(serverNonce)
    output.write(transcript.proof("server"))
    output.flush()
    val clientProof = input.readSignalBytes(SIGNAL_PROOF_BYTES)
    if (!MessageDigest.isEqual(clientProof, transcript.proof("client"))) {
        throw SignalAuthenticationException()
    }
    output.write(transcript.proof("finished"))
    output.flush()
    socket.soTimeout = 0
    return SignalHandshakeResult(sessionId, remoteMemberId, remoteDisplayName, transcript.keys())
}

private fun clientHandshake(
    socket: Socket,
    invite: CrewInvite,
    localMemberId: CrewMemberId,
    localDisplayName: String,
    nonceSource: CrewSignalNonceSource,
): SignalHandshakeResult {
    socket.soTimeout = SIGNAL_HANDSHAKE_TIMEOUT_MS
    val input = DataInputStream(socket.getInputStream())
    val output = DataOutputStream(socket.getOutputStream())
    val clientNonce = nonceSource.nextCheckedNonce()
    output.writeInt(SIGNAL_HANDSHAKE_MAGIC)
    output.writeByte(SIGNAL_HANDSHAKE_VERSION)
    output.writeInt(invite.protocolVersion.value)
    output.writeSizedSignalString(invite.sessionLocator.value)
    output.writeSizedSignalString(invite.inviteId.value)
    output.writeSignalMemberId(localMemberId)
    output.writeSignalDisplayName(localDisplayName)
    output.write(clientNonce)
    output.flush()
    val sessionId = input.readSignalSessionId()
    val remoteMemberId = input.readSignalMemberId()
    val remoteDisplayName = input.readSignalDisplayName()
    val serverNonce = input.readSignalBytes(SIGNAL_NONCE_BYTES)
    val transcript =
        SignalTranscript(
            invite,
            sessionId,
            localMemberId,
            remoteMemberId,
            localDisplayName,
            remoteDisplayName,
            clientNonce,
            serverNonce,
        )
    val serverProof = input.readSignalBytes(SIGNAL_PROOF_BYTES)
    if (!MessageDigest.isEqual(serverProof, transcript.proof("server"))) {
        throw SignalAuthenticationException()
    }
    if (
        sessionId.protocolVersion != invite.protocolVersion ||
            remoteMemberId.protocolVersion != invite.protocolVersion
    ) {
        throw SignalProtocolException()
    }
    output.write(transcript.proof("client"))
    output.flush()
    val finished = input.readSignalBytes(SIGNAL_PROOF_BYTES)
    if (!MessageDigest.isEqual(finished, transcript.proof("finished"))) {
        throw SignalAuthenticationException()
    }
    socket.soTimeout = 0
    return SignalHandshakeResult(sessionId, remoteMemberId, remoteDisplayName, transcript.keys())
}

private class SignalTranscript(
    private val invite: CrewInvite,
    sessionId: CrewSessionId,
    clientMemberId: CrewMemberId,
    serverMemberId: CrewMemberId,
    clientDisplayName: String,
    serverDisplayName: String,
    clientNonce: ByteArray,
    serverNonce: ByteArray,
) {
    private val bytes =
        java.io.ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeSizedSignalString("shippy-lan-signal-v2")
                output.writeInt(invite.protocolVersion.value)
                output.writeSizedSignalString(invite.sessionLocator.value)
                output.writeSizedSignalString(invite.inviteId.value)
                output.writeLong(invite.issuedAtEpochMs)
                output.writeLong(invite.expiresAtEpochMs)
                output.writeSignalSessionId(sessionId)
                output.writeSignalMemberId(clientMemberId)
                output.writeSignalMemberId(serverMemberId)
                output.writeSignalDisplayName(clientDisplayName)
                output.writeSignalDisplayName(serverDisplayName)
                output.write(clientNonce)
                output.write(serverNonce)
            }
            buffer.toByteArray()
        }
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun proof(role: String): ByteArray =
        hmac(
            invite.secret.value.toByteArray(Charsets.UTF_8),
            role.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + hash,
        )

    fun keys(): SignalSessionKeys {
        val pseudoRandomKey =
            hmac(hash, invite.secret.value.toByteArray(Charsets.UTF_8))
        return SignalSessionKeys(
            clientToServerKey = hkdfExpand(pseudoRandomKey, "client-to-server", hash),
            serverToClientKey = hkdfExpand(pseudoRandomKey, "server-to-client", hash),
            clientNoncePrefix = noncePrefix("client", hash),
            serverNoncePrefix = noncePrefix("server", hash),
            transcriptHash = hash.copyOf(),
        )
    }
}

private fun encryptFrame(
    key: SecretKeySpec,
    noncePrefix: ByteArray,
    transcriptHash: ByteArray,
    sequence: Long,
    plain: ByteArray,
): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.ENCRYPT_MODE,
        key,
        GCMParameterSpec(SIGNAL_GCM_TAG_BITS, frameNonce(noncePrefix, sequence)),
    )
    cipher.updateAAD(frameAad(transcriptHash, sequence))
    return cipher.doFinal(plain)
}

private fun decryptFrame(
    key: SecretKeySpec,
    noncePrefix: ByteArray,
    transcriptHash: ByteArray,
    sequence: Long,
    encrypted: ByteArray,
): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        key,
        GCMParameterSpec(SIGNAL_GCM_TAG_BITS, frameNonce(noncePrefix, sequence)),
    )
    cipher.updateAAD(frameAad(transcriptHash, sequence))
    return cipher.doFinal(encrypted)
}

private fun frameNonce(prefix: ByteArray, sequence: Long): ByteArray {
    require(prefix.size == SIGNAL_GCM_NONCE_BYTES - Long.SIZE_BYTES)
    return java.nio.ByteBuffer.allocate(SIGNAL_GCM_NONCE_BYTES)
        .put(prefix)
        .putLong(sequence)
        .array()
}

private fun frameAad(transcriptHash: ByteArray, sequence: Long): ByteArray =
    transcriptHash + java.nio.ByteBuffer.allocate(Long.SIZE_BYTES).putLong(sequence).array()

private fun noncePrefix(label: String, transcriptHash: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256")
        .digest(label.toByteArray(Charsets.UTF_8) + transcriptHash)
        .copyOf(SIGNAL_GCM_NONCE_BYTES - Long.SIZE_BYTES)

private fun hkdfExpand(
    pseudoRandomKey: ByteArray,
    label: String,
    transcriptHash: ByteArray,
): ByteArray {
    val info = label.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + transcriptHash
    return hmac(pseudoRandomKey, info + byteArrayOf(1)).copyOf(SIGNAL_KEY_BYTES)
}

private fun hmac(key: ByteArray, value: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(value)
}

private fun DataOutputStream.writeSizedSignalString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.size in 1..SIGNAL_MAX_ID_BYTES) {
        "Crew signaling handshake value has invalid size"
    }
    writeShort(bytes.size)
    write(bytes)
}

private fun DataInputStream.readSizedSignalString(): String {
    val size = readUnsignedShort()
    require(size in 1..SIGNAL_MAX_ID_BYTES) {
        "Crew signaling handshake value has invalid size"
    }
    val bytes = readSignalBytes(size)
    return bytes.toString(Charsets.UTF_8).also {
        require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
            "Crew signaling handshake value is not valid UTF-8"
        }
    }
}

private fun DataOutputStream.writeSignalDisplayName(value: String) {
    requireValidSignalDisplayName(value)
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeShort(bytes.size)
    write(bytes)
}

private fun DataInputStream.readSignalDisplayName(): String {
    val size = readUnsignedShort()
    require(size in 1..SIGNAL_MAX_DISPLAY_NAME_BYTES) {
        "Crew signaling display name has invalid size"
    }
    val bytes = readSignalBytes(size)
    return bytes.toString(Charsets.UTF_8).also {
        require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
            "Crew signaling display name is not valid UTF-8"
        }
        requireValidSignalDisplayName(it)
    }
}

private fun DataOutputStream.writeSignalMemberId(memberId: CrewMemberId) {
    writeInt(memberId.protocolVersion.value)
    writeSizedSignalString(memberId.value)
}

private fun DataInputStream.readSignalMemberId(): CrewMemberId {
    val protocolVersion = ProtocolVersion(readInt())
    return CrewMemberId(readSizedSignalString(), protocolVersion)
}

private fun DataOutputStream.writeSignalSessionId(sessionId: CrewSessionId) {
    writeInt(sessionId.protocolVersion.value)
    writeSizedSignalString(sessionId.value)
}

private fun DataInputStream.readSignalSessionId(): CrewSessionId {
    val protocolVersion = ProtocolVersion(readInt())
    return CrewSessionId(readSizedSignalString(), protocolVersion)
}

private fun DataInputStream.readSignalBytes(size: Int) =
    ByteArray(size).also { readFully(it) }

private fun CrewSignalNonceSource.nextCheckedNonce() =
    nextNonce().copyOf().also {
        require(it.size == SIGNAL_NONCE_BYTES) { "Crew signaling nonce must be 256 bits" }
    }

private fun requireActiveSignalInvite(invite: CrewInvite, nowEpochMs: Long) {
    require(isActiveSignalInvite(invite, nowEpochMs)) {
        "Crew signaling invitation is not active"
    }
}

private fun isActiveSignalInvite(invite: CrewInvite, nowEpochMs: Long) =
    nowEpochMs >= 0 &&
        invite.expiresAtEpochMs - invite.issuedAtEpochMs <= SIGNAL_MAX_INVITE_LIFETIME_MS &&
        invite.issuedAtEpochMs - nowEpochMs <= SIGNAL_CLOCK_SKEW_TOLERANCE_MS &&
        nowEpochMs < invite.expiresAtEpochMs

private fun validateHostConfig(
    invite: CrewInvite,
    sessionId: CrewSessionId,
    localMemberId: CrewMemberId,
    localDisplayName: String,
    nowEpochMs: Long,
    maxPeers: Int,
) {
    require(maxPeers in 1..32) { "Crew LAN signaling peer limit is invalid" }
    require(sessionId.protocolVersion == invite.protocolVersion) {
        "Crew signaling session protocol must match the invitation"
    }
    require(localMemberId.protocolVersion == invite.protocolVersion) {
        "Crew signaling member protocol must match the invitation"
    }
    requireValidSignalDisplayName(localDisplayName)
    requireActiveSignalInvite(invite, nowEpochMs)
}

private fun requireValidSignalDisplayName(value: String) {
    require(value.isNotBlank()) { "Crew signaling display name must not be blank" }
    require(value.toByteArray(Charsets.UTF_8).size <= SIGNAL_MAX_DISPLAY_NAME_BYTES) {
        "Crew signaling display name exceeds $SIGNAL_MAX_DISPLAY_NAME_BYTES UTF-8 bytes"
    }
}

private fun createSignalServerSocket(maxPeers: Int) =
    ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(0), maxPeers)
    }

private fun Socket.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
        Unit
    }
}

private fun ServerSocket.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
        Unit
    }
}

private class SignalAuthenticationException : Exception()

private class SignalProtocolException : Exception()
