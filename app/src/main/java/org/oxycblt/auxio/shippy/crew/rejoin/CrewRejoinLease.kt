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
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewSnapshot
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteId
import org.oxycblt.auxio.shippy.crew.invite.CrewInvite
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteSecret
import org.oxycblt.auxio.shippy.crew.invite.CrewRelayLocator
import org.oxycblt.auxio.shippy.crew.invite.CrewSessionLocator

/** The local, short-lived credential for recovering an already admitted Crew member. */
class CrewRejoinLease(
    val sessionId: CrewSessionId,
    val memberId: CrewMemberId,
    val sessionLocator: CrewSessionLocator,
    val relayLocator: CrewRelayLocator?,
    val rendezvousInviteId: CrewInviteId,
    val credentialId: String,
    val credentialSecret: String,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
) {
    init {
        require(sessionId.protocolVersion == memberId.protocolVersion) {
            "Crew rejoin member protocol must match session"
        }
        require(credentialId.isBoundedCredentialId()) { "Crew credential ID is invalid" }
        require(credentialSecret.isEncodedCredentialSecret()) { "Crew credential secret is invalid" }
        require(issuedAtEpochMs >= 0L && expiresAtEpochMs > issuedAtEpochMs) {
            "Crew credential lifetime is invalid"
        }
        require(expiresAtEpochMs - issuedAtEpochMs <= MAX_LIFETIME_MS) {
            "Crew credential lifetime exceeds maximum"
        }
    }

    val protocolVersion: ProtocolVersion get() = sessionId.protocolVersion

    override fun equals(other: Any?) =
        other is CrewRejoinLease &&
            sessionId == other.sessionId && memberId == other.memberId &&
            sessionLocator == other.sessionLocator && relayLocator == other.relayLocator &&
            rendezvousInviteId == other.rendezvousInviteId && credentialId == other.credentialId &&
            credentialSecret == other.credentialSecret && issuedAtEpochMs == other.issuedAtEpochMs &&
            expiresAtEpochMs == other.expiresAtEpochMs

    override fun hashCode() =
        arrayOf(
                sessionId,
                memberId,
                sessionLocator,
                relayLocator,
                rendezvousInviteId,
                credentialId,
                issuedAtEpochMs,
                expiresAtEpochMs,
            )
            .contentHashCode()

    override fun toString() =
        "CrewRejoinLease(sessionId=${sessionId.value}, memberId=${memberId.value}, " +
            "credentialId=$credentialId, expiresAtEpochMs=$expiresAtEpochMs, secret=redacted)"

    companion object {
        const val MAX_LIFETIME_MS = 8 * 60 * 60 * 1000L
    }
}

sealed interface CrewRejoinLeaseDecodeResult {
    data class Accepted(val lease: CrewRejoinLease) : CrewRejoinLeaseDecodeResult

    /** Includes legacy v1 envelopes, which intentionally cannot be migrated because they embed invite secrets. */
    data object Rejected : CrewRejoinLeaseDecodeResult
}

/** Pure persistence seam: malformed and legacy envelopes must be deleted, never retained. */
object CrewRejoinLeasePersistencePolicy {
    fun acceptedLease(decoded: CrewRejoinLeaseDecodeResult?): CrewRejoinLease? =
        (decoded as? CrewRejoinLeaseDecodeResult.Accepted)?.lease
}

/** Bounded binary representation for the encrypted Android credential envelope. */
object CrewRejoinLeaseCodec {
    private const val FORMAT_VERSION = 2
    private const val MAX_FIELD_BYTES = 512

    fun encode(lease: CrewRejoinLease): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(FORMAT_VERSION)
                output.writeInt(lease.protocolVersion.value)
                output.writeString(lease.sessionId.value)
                output.writeString(lease.memberId.value)
                output.writeString(lease.sessionLocator.value)
                output.writeBoolean(lease.relayLocator != null)
                lease.relayLocator?.let(output::writeString)
                output.writeString(lease.rendezvousInviteId.value)
                output.writeString(lease.credentialId)
                output.writeString(lease.credentialSecret)
                output.writeLong(lease.issuedAtEpochMs)
                output.writeLong(lease.expiresAtEpochMs)
            }
            bytes.toByteArray()
        }

    fun decode(bytes: ByteArray): CrewRejoinLeaseDecodeResult =
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                // v1 carried a full public invite secret. Reject rather than migrate it.
                if (input.readUnsignedByte() != FORMAT_VERSION) return CrewRejoinLeaseDecodeResult.Rejected
                val protocol = ProtocolVersion(input.readInt())
                val sessionId = CrewSessionId(input.readString(), protocol)
                val memberId = CrewMemberId(input.readString(), protocol)
                val locator = CrewSessionLocator(input.readString())
                val relay =
                    when (input.readUnsignedByte()) {
                        0 -> null
                        1 -> CrewRelayLocator(input.readString())
                        else -> return CrewRejoinLeaseDecodeResult.Rejected
                    }
                val inviteId = CrewInviteId(input.readString())
                val credentialId = input.readString()
                val credentialSecret = input.readString()
                val issuedAt = input.readLong()
                val expiresAt = input.readLong()
                if (input.available() != 0) return CrewRejoinLeaseDecodeResult.Rejected
                CrewRejoinLeaseDecodeResult.Accepted(
                    CrewRejoinLease(
                        sessionId,
                        memberId,
                        locator,
                        relay,
                        inviteId,
                        credentialId,
                        credentialSecret,
                        issuedAt,
                        expiresAt,
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

/** Injectable only to make credential issuance deterministic in JVM tests. */
fun interface CrewCredentialRandom {
    fun nextBytes(size: Int): ByteArray
}

object SecureCrewCredentialRandom : CrewCredentialRandom {
    private val random = SecureRandom()
    override fun nextBytes(size: Int) = ByteArray(size).also(random::nextBytes)
}

/**
 * Current-coordinator authority for active-member rejoin credentials. This is intentionally
 * in-memory: a process restart cannot recreate credential authority without an authenticated
 * transport. The registry stores SHA-256 verifiers, never raw member secrets.
 */
class CrewRejoinCredentialRegistry(
    private val random: CrewCredentialRandom = SecureCrewCredentialRandom,
) {
    private val records = linkedMapOf<String, CredentialRecord>()

    /** Caller invokes this only after the ordinary Crew admission flow has accepted the member. */
    @Synchronized
    fun issueAfterAdmission(
        sessionId: CrewSessionId,
        memberId: CrewMemberId,
        sessionLocator: CrewSessionLocator,
        relayLocator: CrewRelayLocator?,
        rendezvousInviteId: CrewInviteId,
        issuedAtEpochMs: Long,
        expiresAtEpochMs: Long,
    ): CrewRejoinLease {
        require(sessionId.protocolVersion == memberId.protocolVersion) { "Crew member protocol must match session" }
        require(expiresAtEpochMs - issuedAtEpochMs <= CrewRejoinLease.MAX_LIFETIME_MS) {
            "Crew credential lifetime exceeds maximum"
        }
        // A member can hold exactly one usable reconnect credential at a time. Reissuing one
        // replaces the old verifier rather than giving a stale device another valid path in.
        records.entries.removeAll { entry ->
            entry.value.sessionId == sessionId && entry.value.memberId == memberId
        }
        val credentialId = encodeRandom(CREDENTIAL_ID_BYTES)
        check(records[credentialId] == null) { "Crew credential collision" }
        val secret = encodeRandom(CREDENTIAL_SECRET_BYTES)
        val lease =
            CrewRejoinLease(
                sessionId,
                memberId,
                sessionLocator,
                relayLocator,
                rendezvousInviteId,
                credentialId,
                secret,
                issuedAtEpochMs,
                expiresAtEpochMs,
            )
        records[credentialId] = CredentialRecord.from(lease)
        return lease
    }

    @Synchronized
    fun verify(lease: CrewRejoinLease, nowEpochMs: Long): Boolean {
        val record = records[lease.credentialId] ?: return false
        if (nowEpochMs >= record.expiresAtEpochMs || nowEpochMs < record.issuedAtEpochMs) return false
        if (
            record.sessionId != lease.sessionId || record.memberId != lease.memberId ||
                record.protocolVersion != lease.protocolVersion || record.sessionLocator != lease.sessionLocator ||
                record.relayLocator != lease.relayLocator || record.rendezvousInviteId != lease.rendezvousInviteId ||
                record.issuedAtEpochMs != lease.issuedAtEpochMs || record.expiresAtEpochMs != lease.expiresAtEpochMs
        ) return false
        return MessageDigest.isEqual(record.secretVerifier, sha256(lease.credentialSecret))
    }

    /**
     * Returns only currently usable reconnect invitations for this exact rendezvous. These are
     * synthetic private invitations: their secret is the stored SHA-256 verifier, never the
     * lease secret. The host uses them only to decrypt a joiner's first encrypted hello and then
     * verifies the hello's claimed member against the candidate record.
     */
    @Synchronized
    fun activeInviteCandidates(
        sessionId: CrewSessionId,
        sessionLocator: CrewSessionLocator,
        rendezvousInviteId: CrewInviteId,
        nowEpochMs: Long,
        limit: Int = MAX_ACTIVE_CANDIDATES,
    ): List<CrewRejoinInviteCandidate> {
        require(limit in 1..MAX_ACTIVE_CANDIDATES) { "Crew reconnect candidate limit is invalid" }
        return records.values.asSequence()
            .filter {
                it.sessionId == sessionId && it.sessionLocator == sessionLocator &&
                    it.rendezvousInviteId == rendezvousInviteId &&
                    nowEpochMs in it.issuedAtEpochMs until it.expiresAtEpochMs
            }
            .take(limit)
            .map { record ->
                CrewRejoinInviteCandidate(record.memberId, record.toReconnectInvite())
            }
            .toList()
    }

    @Synchronized
    fun revoke(sessionId: CrewSessionId, memberId: CrewMemberId, credentialId: String): Boolean {
        val record = records[credentialId] ?: return false
        if (record.sessionId != sessionId || record.memberId != memberId) return false
        records.remove(credentialId)
        return true
    }

    @Synchronized
    fun revokeSession(sessionId: CrewSessionId) {
        records.entries.removeAll { it.value.sessionId == sessionId }
    }

    @Synchronized
    fun exportTransferSnapshot(sessionId: CrewSessionId): CrewRejoinCredentialTransferSnapshot =
        CrewRejoinCredentialTransferSnapshot(
            sessionId = sessionId,
            records = records.values.filter { it.sessionId == sessionId }.map(CredentialRecord::toTransfer),
        )

    @Synchronized
    fun importTransferSnapshot(snapshot: CrewRejoinCredentialTransferSnapshot) {
        require(snapshot.records.size <= MAX_TRANSFER_RECORDS) { "Crew rejoin transfer is too large" }
        snapshot.records.forEach { transfer ->
            require(transfer.sessionId == snapshot.sessionId) { "Crew transfer session mismatch" }
            val record = CredentialRecord.from(transfer)
            require(records[record.credentialId] == null) { "Crew credential already exists" }
            records[record.credentialId] = record
        }
    }

    private fun encodeRandom(size: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(random.nextBytes(size))

    private companion object {
        const val CREDENTIAL_ID_BYTES = 16
        const val CREDENTIAL_SECRET_BYTES = 32
        const val MAX_TRANSFER_RECORDS = 8
        const val MAX_ACTIVE_CANDIDATES = 8
    }
}

/** Secret-free, bounded coordinator-handoff data. It is not a device lease. */
data class CrewRejoinCredentialTransferSnapshot(
    val sessionId: CrewSessionId,
    val records: List<CrewRejoinCredentialTransferRecord>,
) {
    init {
        require(records.size <= 8) { "Crew rejoin transfer is too large" }
    }
}

data class CrewRejoinCredentialTransferRecord(
    val sessionId: CrewSessionId,
    val memberId: CrewMemberId,
    val protocolVersion: ProtocolVersion,
    val sessionLocator: CrewSessionLocator,
    val relayLocator: CrewRelayLocator?,
    val rendezvousInviteId: CrewInviteId,
    val credentialId: String,
    val secretVerifier: ByteArray,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
) {
    init {
        require(sessionId.protocolVersion == protocolVersion && memberId.protocolVersion == protocolVersion)
        require(credentialId.isBoundedCredentialId())
        require(secretVerifier.size == SHA256_BYTES)
        require(issuedAtEpochMs >= 0L && expiresAtEpochMs > issuedAtEpochMs)
        require(expiresAtEpochMs - issuedAtEpochMs <= CrewRejoinLease.MAX_LIFETIME_MS)
    }

    override fun toString() =
        "CrewRejoinCredentialTransferRecord(sessionId=${sessionId.value}, memberId=${memberId.value}, " +
            "credentialId=$credentialId, verifier=redacted, expiresAtEpochMs=$expiresAtEpochMs)"
}

private data class CredentialRecord(
    val sessionId: CrewSessionId,
    val memberId: CrewMemberId,
    val protocolVersion: ProtocolVersion,
    val sessionLocator: CrewSessionLocator,
    val relayLocator: CrewRelayLocator?,
    val rendezvousInviteId: CrewInviteId,
    val credentialId: String,
    val secretVerifier: ByteArray,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
) {
    fun toReconnectInvite() =
        CrewInvite(
            protocolVersion = protocolVersion,
            sessionLocator = sessionLocator,
            inviteId = rendezvousInviteId,
            secret = CrewInviteSecret(Base64.getUrlEncoder().withoutPadding().encodeToString(secretVerifier)),
            issuedAtEpochMs = issuedAtEpochMs,
            expiresAtEpochMs = expiresAtEpochMs,
            relayLocator = relayLocator,
        )
    fun toTransfer() =
        CrewRejoinCredentialTransferRecord(
            sessionId, memberId, protocolVersion, sessionLocator, relayLocator, rendezvousInviteId,
            credentialId, secretVerifier.copyOf(), issuedAtEpochMs, expiresAtEpochMs,
        )

    companion object {
        fun from(lease: CrewRejoinLease) =
            CredentialRecord(
                lease.sessionId, lease.memberId, lease.protocolVersion, lease.sessionLocator,
                lease.relayLocator, lease.rendezvousInviteId, lease.credentialId,
                sha256(lease.credentialSecret), lease.issuedAtEpochMs, lease.expiresAtEpochMs,
            )

        fun from(transfer: CrewRejoinCredentialTransferRecord) =
            CredentialRecord(
                transfer.sessionId, transfer.memberId, transfer.protocolVersion, transfer.sessionLocator,
                transfer.relayLocator, transfer.rendezvousInviteId, transfer.credentialId,
                transfer.secretVerifier.copyOf(), transfer.issuedAtEpochMs, transfer.expiresAtEpochMs,
            )
    }
}

/** A bounded candidate the host can try after the ordinary public invite has expired. */
data class CrewRejoinInviteCandidate(
    val memberId: CrewMemberId,
    val invite: CrewInvite,
)

/**
 * Derives the same private reconnect invitation on the joining device without retaining a QR
 * secret. The only secret stored in this invitation is a one-way verifier of the lease secret.
 */
object CrewRejoinInviteFactory {
    fun fromLease(lease: CrewRejoinLease): CrewInvite =
        CrewInvite(
            protocolVersion = lease.protocolVersion,
            sessionLocator = lease.sessionLocator,
            inviteId = lease.rendezvousInviteId,
            secret = CrewInviteSecret(Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(lease.credentialSecret))),
            issuedAtEpochMs = lease.issuedAtEpochMs,
            expiresAtEpochMs = lease.expiresAtEpochMs,
            relayLocator = lease.relayLocator,
        )
}

private const val SHA256_BYTES = 32

private fun sha256(value: String) =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))

private fun String.isBoundedCredentialId(): Boolean =
    length in 22..64 && all { it.isLetterOrDigit() || it == '-' || it == '_' }

private fun String.isEncodedCredentialSecret(): Boolean =
    length == 43 && all { it.isLetterOrDigit() || it == '-' || it == '_' }

enum class CrewRejoinRestoreDecision {
    RESTORE,
    DISCARD_ORPHANED_LEASE,
    DISCARD_MISSING_LEASE,
    DISCARD_EXPIRED_LEASE,
    DISCARD_MISMATCHED_LEASE,
    DISCARDED_REVOKED,
}

/** Pure restore policy. It never extends a credential lifetime. */
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
            nowEpochMs >= lease.expiresAtEpochMs || nowEpochMs < lease.issuedAtEpochMs ->
                CrewRejoinRestoreDecision.DISCARD_EXPIRED_LEASE
            checkpoint.sessionId != lease.sessionId ||
                checkpoint.protocolVersion != lease.protocolVersion ||
                checkpoint.members.none { it.id == lease.memberId } ->
                CrewRejoinRestoreDecision.DISCARD_MISMATCHED_LEASE
            else -> CrewRejoinRestoreDecision.RESTORE
        }
}
