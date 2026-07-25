/*
 * Copyright (c) 2026 Shippy contributors
 * CrewRejoinLease.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.crew.rejoin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewSnapshot
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.invite.CrewInvite
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteId
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteSecret
import org.oxycblt.auxio.shippy.crew.invite.CrewRelayLocator
import org.oxycblt.auxio.shippy.crew.invite.CrewSessionLocator

/**
 * The one short-lived credential that may restore an active Crew after process death.
 *
 * Its invite secret is intentionally redacted from [toString]. It is stored only by the
 * Keystore-backed lease store, never in Room or generic preferences.
 */
class CrewRejoinLease(
    val sessionId: CrewSessionId,
    val memberId: CrewMemberId,
    val invite: CrewInvite,
) {
    init {
        require(sessionId.protocolVersion == memberId.protocolVersion) {
            "Crew rejoin member protocol must match session"
        }
        require(sessionId.protocolVersion == invite.protocolVersion) {
            "Crew rejoin invitation protocol must match session"
        }
    }

    val expiresAtEpochMs: Long get() = invite.expiresAtEpochMs

    override fun equals(other: Any?) =
        other is CrewRejoinLease &&
            sessionId == other.sessionId && memberId == other.memberId && invite == other.invite

    override fun hashCode() = arrayOf(sessionId, memberId, invite).contentHashCode()

    override fun toString() =
        "CrewRejoinLease(sessionId=${sessionId.value}, memberId=${memberId.value}, " +
            "expiresAtEpochMs=$expiresAtEpochMs, secret=redacted)"
}

sealed interface CrewRejoinLeaseDecodeResult {
    data class Accepted(val lease: CrewRejoinLease) : CrewRejoinLeaseDecodeResult

    data object Rejected : CrewRejoinLeaseDecodeResult
}

/** Bounded binary representation for the encrypted Android credential envelope. */
object CrewRejoinLeaseCodec {
    private const val FORMAT_VERSION = 1
    private const val MAX_FIELD_BYTES = 512

    fun encode(lease: CrewRejoinLease): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(FORMAT_VERSION)
                output.writeInt(lease.sessionId.protocolVersion.value)
                output.writeString(lease.sessionId.value)
                output.writeString(lease.memberId.value)
                output.writeLong(lease.invite.issuedAtEpochMs)
                output.writeLong(lease.invite.expiresAtEpochMs)
                output.writeString(lease.invite.sessionLocator.value)
                output.writeString(lease.invite.inviteId.value)
                output.writeString(lease.invite.secret.value)
                output.writeBoolean(lease.invite.relayLocator != null)
                lease.invite.relayLocator?.let { output.writeString(it.value) }
            }
            bytes.toByteArray()
        }

    fun decode(bytes: ByteArray): CrewRejoinLeaseDecodeResult =
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readUnsignedByte() != FORMAT_VERSION) return CrewRejoinLeaseDecodeResult.Rejected
                val protocol = ProtocolVersion(input.readInt())
                val sessionId = CrewSessionId(input.readString(), protocol)
                val memberId = CrewMemberId(input.readString(), protocol)
                val issuedAt = input.readLong()
                val expiresAt = input.readLong()
                val locator = CrewSessionLocator(input.readString())
                val inviteId = CrewInviteId(input.readString())
                val secret = CrewInviteSecret(input.readString())
                val relay =
                    when (input.readUnsignedByte()) {
                        0 -> null
                        1 -> CrewRelayLocator(input.readString())
                        else -> return CrewRejoinLeaseDecodeResult.Rejected
                    }
                if (input.available() != 0) return CrewRejoinLeaseDecodeResult.Rejected
                CrewRejoinLeaseDecodeResult.Accepted(
                    CrewRejoinLease(
                        sessionId,
                        memberId,
                        CrewInvite(protocol, locator, inviteId, secret, issuedAt, expiresAt, relay),
                    )
                )
            }
        } catch (_: Exception) {
            CrewRejoinLeaseDecodeResult.Rejected
        }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 1..MAX_FIELD_BYTES) { "Crew rejoin field is out of bounds" }
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readUnsignedShort()
        require(size in 1..MAX_FIELD_BYTES) { "Crew rejoin field is out of bounds" }
        val bytes = ByteArray(size)
        readFully(bytes)
        val value = bytes.toString(StandardCharsets.UTF_8)
        require(value.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) {
            "Crew rejoin field is not UTF-8"
        }
        return value
    }
}

enum class CrewRejoinRestoreDecision {
    RESTORE,
    DISCARD_ORPHANED_LEASE,
    DISCARD_MISSING_LEASE,
    DISCARD_EXPIRED_LEASE,
    DISCARD_MISMATCHED_LEASE,
    DISCARDED_REVOKED,
}

/** Pure restore policy. It never extends an invitation/credential lifetime. */
object CrewRejoinRestorePolicy {
    fun decide(
        checkpoint: CrewSnapshot?,
        lease: CrewRejoinLease?,
        nowEpochMs: Long,
    ): CrewRejoinRestoreDecision =
        when {
            checkpoint == null && lease == null -> CrewRejoinRestoreDecision.DISCARD_ORPHANED_LEASE
            checkpoint == null -> CrewRejoinRestoreDecision.DISCARD_ORPHANED_LEASE
            lease == null -> CrewRejoinRestoreDecision.DISCARD_MISSING_LEASE
            nowEpochMs >= lease.expiresAtEpochMs -> CrewRejoinRestoreDecision.DISCARD_EXPIRED_LEASE
            checkpoint.sessionId != lease.sessionId ||
                checkpoint.protocolVersion != lease.sessionId.protocolVersion ||
                checkpoint.members.none { it.id == lease.memberId } ->
                CrewRejoinRestoreDecision.DISCARD_MISMATCHED_LEASE
            else -> CrewRejoinRestoreDecision.RESTORE
        }
}

internal fun CrewSnapshot.toCrewState() =
    CrewState(
        sessionId = sessionId,
        protocolVersion = protocolVersion,
        term = term,
        lastSequence = lastSequence,
        coordinatorMemberId = coordinatorMemberId,
        members = members,
        queue = queue,
        playback = playback,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
    )
