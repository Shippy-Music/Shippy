/*
 * Copyright (c) 2026 Shippy contributors
 * CrewJoinAuthenticator.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.auth

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.invite.CrewInvite
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteId
import org.oxycblt.auxio.shippy.crew.invite.CrewSessionLocator
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewAuthenticatedPeerBinding

private const val JOIN_FORMAT_VERSION = 1
private const val JOIN_NONCE_BYTES = 32
private const val JOIN_PROOF_BYTES = 32
private const val SDP_FINGERPRINT_BYTES = 32
private const val MAX_JOIN_FRAME_BYTES = 4 * 1024
private const val MAX_ID_BYTES = 128
private const val JOIN_CLOCK_SKEW_TOLERANCE_MS = 2 * 60 * 1000L
private val SDP_SHA_256_FINGERPRINT =
    Regex(
        "^a=fingerprint:sha-256 ((?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2})\\r?$",
        setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE),
    )

class CrewJoinNonce(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.size == JOIN_NONCE_BYTES) { "Crew join nonce must be 256 bits" }
    }

    internal fun copyBytes() = value.copyOf()

    override fun equals(other: Any?) =
        other is CrewJoinNonce && MessageDigest.isEqual(value, other.value)

    override fun hashCode() = value.contentHashCode()

    override fun toString() = "CrewJoinNonce(redacted)"
}

class CrewJoinProof(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.size == JOIN_PROOF_BYTES) { "Crew join proof must be 256 bits" }
    }

    internal fun copyBytes() = value.copyOf()

    override fun equals(other: Any?) =
        other is CrewJoinProof && MessageDigest.isEqual(value, other.value)

    override fun hashCode() = value.contentHashCode()

    override fun toString() = "CrewJoinProof(redacted)"
}

class CrewSdpFingerprint private constructor(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.size == SDP_FINGERPRINT_BYTES) {
            "Crew SDP fingerprint must be SHA-256"
        }
    }

    internal fun copyBytes() = value.copyOf()

    override fun equals(other: Any?) =
        other is CrewSdpFingerprint && MessageDigest.isEqual(value, other.value)

    override fun hashCode() = value.contentHashCode()

    override fun toString() = "CrewSdpFingerprint(redacted)"

    companion object {
        fun fromSdp(sdp: String): CrewSdpFingerprint {
            val fingerprints =
                SDP_SHA_256_FINGERPRINT
                    .findAll(sdp)
                    .map { match ->
                        match.groupValues[1]
                            .split(':')
                            .map { it.toInt(16).toByte() }
                            .toByteArray()
                    }
                    .toList()
            require(
                fingerprints.isNotEmpty() &&
                    fingerprints.drop(1).all { it.contentEquals(fingerprints.first()) },
            ) {
                "Crew SDP must contain one distinct SHA-256 DTLS fingerprint"
            }
            return CrewSdpFingerprint(fingerprints.first())
        }
    }
}

sealed interface CrewJoinMessage {
    data class InitiatorHello(
        val protocolVersion: ProtocolVersion,
        val sessionLocator: CrewSessionLocator,
        val inviteId: CrewInviteId,
        val initiatorMemberId: CrewMemberId,
        val initiatorNonce: CrewJoinNonce,
    ) : CrewJoinMessage

    data class ResponderChallenge(
        val sessionId: CrewSessionId,
        val responderMemberId: CrewMemberId,
        val responderNonce: CrewJoinNonce,
        val responderProof: CrewJoinProof,
    ) : CrewJoinMessage

    data class InitiatorProof(val proof: CrewJoinProof) : CrewJoinMessage

    data class ResponderFinished(val proof: CrewJoinProof) : CrewJoinMessage
}

sealed interface CrewJoinDecodeResult {
    data class Accepted(val message: CrewJoinMessage) : CrewJoinDecodeResult

    enum class Rejected : CrewJoinDecodeResult {
        MALFORMED,
        UNSUPPORTED_FORMAT,
    }
}

/** Bounded binary framing for messages sent on the pre-authenticated control channel. */
object CrewJoinMessageCodec {
    fun encode(message: CrewJoinMessage): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(JOIN_FORMAT_VERSION)
                when (message) {
                    is CrewJoinMessage.InitiatorHello -> {
                        output.writeByte(1)
                        output.writeInt(message.protocolVersion.value)
                        output.writeSizedString(message.sessionLocator.value)
                        output.writeSizedString(message.inviteId.value)
                        output.writeMemberId(message.initiatorMemberId)
                        output.write(message.initiatorNonce.copyBytes())
                    }
                    is CrewJoinMessage.ResponderChallenge -> {
                        output.writeByte(2)
                        output.writeSessionId(message.sessionId)
                        output.writeMemberId(message.responderMemberId)
                        output.write(message.responderNonce.copyBytes())
                        output.write(message.responderProof.copyBytes())
                    }
                    is CrewJoinMessage.InitiatorProof -> {
                        output.writeByte(3)
                        output.write(message.proof.copyBytes())
                    }
                    is CrewJoinMessage.ResponderFinished -> {
                        output.writeByte(4)
                        output.write(message.proof.copyBytes())
                    }
                }
            }
            bytes.toByteArray().also {
                require(it.size <= MAX_JOIN_FRAME_BYTES) { "Crew join message is too large" }
            }
        }

    fun decode(frame: ByteArray): CrewJoinDecodeResult {
        if (frame.isEmpty() || frame.size > MAX_JOIN_FRAME_BYTES) {
            return CrewJoinDecodeResult.Rejected.MALFORMED
        }
        return try {
            val message =
                DataInputStream(ByteArrayInputStream(frame)).use { input ->
                    if (input.readUnsignedByte() != JOIN_FORMAT_VERSION) {
                        return CrewJoinDecodeResult.Rejected.UNSUPPORTED_FORMAT
                    }
                    val decoded =
                        when (input.readUnsignedByte()) {
                            1 ->
                                CrewJoinMessage.InitiatorHello(
                                    protocolVersion = ProtocolVersion(input.readInt()),
                                    sessionLocator = CrewSessionLocator(input.readSizedString()),
                                    inviteId = CrewInviteId(input.readSizedString()),
                                    initiatorMemberId = input.readMemberId(),
                                    initiatorNonce = CrewJoinNonce(input.readNBytesExact(JOIN_NONCE_BYTES)),
                                )
                            2 ->
                                CrewJoinMessage.ResponderChallenge(
                                    sessionId = input.readSessionId(),
                                    responderMemberId = input.readMemberId(),
                                    responderNonce = CrewJoinNonce(input.readNBytesExact(JOIN_NONCE_BYTES)),
                                    responderProof = CrewJoinProof(input.readNBytesExact(JOIN_PROOF_BYTES)),
                                )
                            3 ->
                                CrewJoinMessage.InitiatorProof(
                                    CrewJoinProof(input.readNBytesExact(JOIN_PROOF_BYTES)),
                                )
                            4 ->
                                CrewJoinMessage.ResponderFinished(
                                    CrewJoinProof(input.readNBytesExact(JOIN_PROOF_BYTES)),
                                )
                            else -> return CrewJoinDecodeResult.Rejected.MALFORMED
                        }
                    require(input.available() == 0) { "Crew join frame has trailing data" }
                    decoded
                }
            CrewJoinDecodeResult.Accepted(message)
        } catch (_: IllegalArgumentException) {
            CrewJoinDecodeResult.Rejected.MALFORMED
        } catch (_: Exception) {
            CrewJoinDecodeResult.Rejected.MALFORMED
        }
    }
}

enum class CrewJoinRejectionReason {
    SESSION_MISMATCH,
    INVITE_MISMATCH,
    PROTOCOL_MISMATCH,
    PROOF_INVALID,
}

sealed interface CrewResponderHelloResult {
    data class Accepted(val challenge: CrewJoinMessage.ResponderChallenge) :
        CrewResponderHelloResult

    data class Rejected(val reason: CrewJoinRejectionReason) : CrewResponderHelloResult
}

sealed interface CrewInitiatorChallengeResult {
    data class Accepted(val response: CrewJoinMessage.InitiatorProof) :
        CrewInitiatorChallengeResult

    data class Rejected(val reason: CrewJoinRejectionReason) : CrewInitiatorChallengeResult
}

sealed interface CrewResponderProofResult {
    data class Authenticated(
        val binding: CrewAuthenticatedPeerBinding,
        val response: CrewJoinMessage.ResponderFinished,
    ) : CrewResponderProofResult

    data class Rejected(val reason: CrewJoinRejectionReason) : CrewResponderProofResult
}

sealed interface CrewInitiatorFinishedResult {
    data class Authenticated(val binding: CrewAuthenticatedPeerBinding) :
        CrewInitiatorFinishedResult

    data class Rejected(val reason: CrewJoinRejectionReason) : CrewInitiatorFinishedResult
}

fun interface CrewJoinNonceSource {
    fun nextNonce(): CrewJoinNonce
}

object SecureCrewJoinNonceSource : CrewJoinNonceSource {
    private val random = SecureRandom()

    override fun nextNonce() = CrewJoinNonce(ByteArray(JOIN_NONCE_BYTES).also(random::nextBytes))
}

/**
 * Per-peer initiator handshake. The QR secret never travels over signaling or WebRTC.
 *
 * Both peers independently bind the proof to the exact offer/answer DTLS fingerprints, so a
 * signaling intermediary cannot substitute a different WebRTC endpoint and still pass proof.
 */
class CrewJoinInitiator(
    private val invite: CrewInvite,
    private val localMemberId: CrewMemberId,
    private val initiatorSdpFingerprint: CrewSdpFingerprint,
    private val responderSdpFingerprint: CrewSdpFingerprint,
    nowEpochMs: Long,
    nonceSource: CrewJoinNonceSource = SecureCrewJoinNonceSource,
) {
    private enum class State {
        NEW,
        HELLO_SENT,
        PROOF_SENT,
        COMPLETE,
        REJECTED,
    }

    private var state = State.NEW
    private val initiatorNonce = nonceSource.nextNonce()
    private var pendingTranscript: CrewJoinTranscript? = null
    private var pendingSessionId: CrewSessionId? = null
    private var pendingResponderMemberId: CrewMemberId? = null

    init {
        requireActiveInvite(invite, nowEpochMs)
        require(localMemberId.protocolVersion == invite.protocolVersion) {
            "Initiator protocol must match the Crew invitation"
        }
    }

    @Synchronized
    fun start(): CrewJoinMessage.InitiatorHello {
        check(state == State.NEW) { "Crew join initiator has already started" }
        state = State.HELLO_SENT
        return CrewJoinMessage.InitiatorHello(
            invite.protocolVersion,
            invite.sessionLocator,
            invite.inviteId,
            localMemberId,
            initiatorNonce,
        )
    }

    @Synchronized
    fun accept(challenge: CrewJoinMessage.ResponderChallenge): CrewInitiatorChallengeResult {
        check(state == State.HELLO_SENT) { "Crew join initiator is not awaiting a challenge" }
        val rejection =
            when {
                challenge.sessionId.protocolVersion != invite.protocolVersion ||
                    challenge.responderMemberId.protocolVersion != invite.protocolVersion ->
                    CrewJoinRejectionReason.PROTOCOL_MISMATCH
                else -> null
            }
        if (rejection != null) {
            state = State.REJECTED
            return CrewInitiatorChallengeResult.Rejected(rejection)
        }
        val transcript =
            CrewJoinTranscript(
                invite,
                challenge.sessionId,
                localMemberId,
                challenge.responderMemberId,
                initiatorNonce,
                challenge.responderNonce,
                initiatorSdpFingerprint,
                responderSdpFingerprint,
            )
        if (!transcript.verifyProof("responder", invite, challenge.responderProof)) {
            state = State.REJECTED
            return CrewInitiatorChallengeResult.Rejected(
                CrewJoinRejectionReason.PROOF_INVALID,
            )
        }
        val response = CrewJoinMessage.InitiatorProof(transcript.proof("initiator", invite))
        pendingTranscript = transcript
        pendingSessionId = challenge.sessionId
        pendingResponderMemberId = challenge.responderMemberId
        state = State.PROOF_SENT
        return CrewInitiatorChallengeResult.Accepted(response)
    }

    @Synchronized
    fun complete(finished: CrewJoinMessage.ResponderFinished): CrewInitiatorFinishedResult {
        check(state == State.PROOF_SENT) {
            "Crew join initiator is not awaiting responder confirmation"
        }
        val transcript = checkNotNull(pendingTranscript)
        if (!transcript.verifyProof("finished", invite, finished.proof)) {
            state = State.REJECTED
            clearPending()
            return CrewInitiatorFinishedResult.Rejected(CrewJoinRejectionReason.PROOF_INVALID)
        }
        val binding =
            CrewAuthenticatedPeerBinding(
                checkNotNull(pendingSessionId),
                checkNotNull(pendingResponderMemberId),
                transcript.bindingHash(),
            )
        state = State.COMPLETE
        clearPending()
        return CrewInitiatorFinishedResult.Authenticated(binding)
    }

    private fun clearPending() {
        pendingTranscript = null
        pendingSessionId = null
        pendingResponderMemberId = null
    }
}

/** Per-peer responder handshake. Create one instance for each claimed signaling peer. */
class CrewJoinResponder(
    private val invite: CrewInvite,
    private val sessionId: CrewSessionId,
    private val localMemberId: CrewMemberId,
    private val initiatorSdpFingerprint: CrewSdpFingerprint,
    private val responderSdpFingerprint: CrewSdpFingerprint,
    nowEpochMs: Long,
    nonceSource: CrewJoinNonceSource = SecureCrewJoinNonceSource,
) {
    private enum class State {
        NEW,
        CHALLENGE_SENT,
        COMPLETE,
        REJECTED,
    }

    private var state = State.NEW
    private val responderNonce = nonceSource.nextNonce()
    private var pendingTranscript: CrewJoinTranscript? = null

    init {
        requireActiveInvite(invite, nowEpochMs)
        require(sessionId.protocolVersion == invite.protocolVersion) {
            "Responder session protocol must match the Crew invitation"
        }
        require(localMemberId.protocolVersion == invite.protocolVersion) {
            "Responder protocol must match the Crew invitation"
        }
    }

    @Synchronized
    fun accept(hello: CrewJoinMessage.InitiatorHello): CrewResponderHelloResult {
        check(state == State.NEW) { "Crew join responder has already received a hello" }
        val rejection =
            when {
                hello.protocolVersion != invite.protocolVersion ||
                    hello.initiatorMemberId.protocolVersion != invite.protocolVersion ->
                    CrewJoinRejectionReason.PROTOCOL_MISMATCH
                hello.sessionLocator != invite.sessionLocator ->
                    CrewJoinRejectionReason.SESSION_MISMATCH
                hello.inviteId != invite.inviteId -> CrewJoinRejectionReason.INVITE_MISMATCH
                else -> null
            }
        if (rejection != null) {
            state = State.REJECTED
            return CrewResponderHelloResult.Rejected(rejection)
        }
        val transcript =
            CrewJoinTranscript(
                invite,
                sessionId,
                hello.initiatorMemberId,
                localMemberId,
                hello.initiatorNonce,
                responderNonce,
                initiatorSdpFingerprint,
                responderSdpFingerprint,
            )
        pendingTranscript = transcript
        state = State.CHALLENGE_SENT
        return CrewResponderHelloResult.Accepted(
            CrewJoinMessage.ResponderChallenge(
                sessionId,
                localMemberId,
                responderNonce,
                transcript.proof("responder", invite),
            ),
        )
    }

    @Synchronized
    fun complete(proof: CrewJoinMessage.InitiatorProof): CrewResponderProofResult {
        check(state == State.CHALLENGE_SENT) { "Crew join responder is not awaiting proof" }
        val transcript = checkNotNull(pendingTranscript)
        if (!transcript.verifyProof("initiator", invite, proof.proof)) {
            state = State.REJECTED
            pendingTranscript = null
            return CrewResponderProofResult.Rejected(CrewJoinRejectionReason.PROOF_INVALID)
        }
        state = State.COMPLETE
        pendingTranscript = null
        return CrewResponderProofResult.Authenticated(
            CrewAuthenticatedPeerBinding(
                sessionId,
                transcript.initiatorMemberId,
                transcript.bindingHash(),
            ),
            CrewJoinMessage.ResponderFinished(transcript.proof("finished", invite)),
        )
    }
}

private class CrewJoinTranscript(
    invite: CrewInvite,
    sessionId: CrewSessionId,
    val initiatorMemberId: CrewMemberId,
    responderMemberId: CrewMemberId,
    initiatorNonce: CrewJoinNonce,
    responderNonce: CrewJoinNonce,
    initiatorFingerprint: CrewSdpFingerprint,
    responderFingerprint: CrewSdpFingerprint,
) {
    private val bytes =
        ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeSizedString("shippy-crew-join-v1")
                output.writeInt(invite.protocolVersion.value)
                output.writeSizedString(invite.sessionLocator.value)
                output.writeSizedString(invite.inviteId.value)
                output.writeLong(invite.issuedAtEpochMs)
                output.writeLong(invite.expiresAtEpochMs)
                output.writeSessionId(sessionId)
                output.writeMemberId(initiatorMemberId)
                output.writeMemberId(responderMemberId)
                output.write(initiatorNonce.copyBytes())
                output.write(responderNonce.copyBytes())
                output.write(initiatorFingerprint.copyBytes())
                output.write(responderFingerprint.copyBytes())
            }
            buffer.toByteArray()
        }

    fun proof(role: String, invite: CrewInvite): CrewJoinProof {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(invite.secret.value.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(role.toByteArray(Charsets.UTF_8))
        mac.update(0.toByte())
        return CrewJoinProof(mac.doFinal(bytes))
    }

    fun verifyProof(role: String, invite: CrewInvite, candidateProof: CrewJoinProof) =
        MessageDigest.isEqual(
            proof(role, invite).copyBytes(),
            candidateProof.copyBytes(),
        )

    fun bindingHash(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("shippy-crew-binding-v1".toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        return digest.digest(bytes)
    }
}

private fun DataOutputStream.writeMemberId(memberId: CrewMemberId) {
    writeInt(memberId.protocolVersion.value)
    writeSizedString(memberId.value)
}

private fun DataInputStream.readMemberId(): CrewMemberId {
    val protocolVersion = ProtocolVersion(readInt())
    return CrewMemberId(readSizedString(), protocolVersion)
}

private fun DataOutputStream.writeSessionId(sessionId: CrewSessionId) {
    writeInt(sessionId.protocolVersion.value)
    writeSizedString(sessionId.value)
}

private fun DataInputStream.readSessionId(): CrewSessionId {
    val protocolVersion = ProtocolVersion(readInt())
    return CrewSessionId(readSizedString(), protocolVersion)
}

private fun DataOutputStream.writeSizedString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.size in 1..MAX_ID_BYTES) { "Crew join identifier has invalid size" }
    writeShort(bytes.size)
    write(bytes)
}

private fun DataInputStream.readSizedString(): String {
    val size = readUnsignedShort()
    require(size in 1..MAX_ID_BYTES) { "Crew join identifier has invalid size" }
    val bytes = readNBytesExact(size)
    return bytes.toString(Charsets.UTF_8).also {
        require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
            "Crew join identifier is not valid UTF-8"
        }
    }
}

private fun DataInputStream.readNBytesExact(size: Int): ByteArray =
    ByteArray(size).also { readFully(it) }

private fun requireActiveInvite(invite: CrewInvite, nowEpochMs: Long) {
    require(
        nowEpochMs >= 0 &&
            invite.issuedAtEpochMs - nowEpochMs <= JOIN_CLOCK_SKEW_TOLERANCE_MS &&
            nowEpochMs < invite.expiresAtEpochMs,
    ) {
        "Crew invitation is not active"
    }
}
