/*
 * Copyright (c) 2026 Shippy contributors
 * CrewRejoinLeaseTest.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.crew.rejoin

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewPlaybackState
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewSnapshot
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteId
import org.oxycblt.auxio.shippy.crew.invite.CrewRelayLocator
import org.oxycblt.auxio.shippy.crew.invite.CrewSessionLocator

class CrewRejoinLeaseTest {
    @Test
    fun `v2 lease codec round trips without exposing credential secret`() {
        val lease = fixtureLease()

        val decoded = CrewRejoinLeaseCodec.decode(CrewRejoinLeaseCodec.encode(lease))

        assertEquals(CrewRejoinLeaseDecodeResult.Accepted(lease), decoded)
        assertTrue(lease.toString().contains("secret=redacted"))
        assertFalse(lease.toString().contains(lease.credentialSecret))
    }

    @Test
    fun `legacy v1 bytes are rejected rather than migrated`() {
        val legacy =
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { it.writeByte(1) }
                bytes.toByteArray()
            }

        assertEquals(CrewRejoinLeaseDecodeResult.Rejected, CrewRejoinLeaseCodec.decode(legacy))
        assertEquals(null, CrewRejoinLeasePersistencePolicy.acceptedLease(CrewRejoinLeaseCodec.decode(legacy)))
    }

    @Test
    fun `lease rejects out of policy lifetime and malformed encoded secret`() {
        assertTrue(
            runCatching {
                    CrewRejoinLease(
                        sessionId = CrewSessionId("session_id", ProtocolVersion(1)),
                        memberId = CrewMemberId("member_id", ProtocolVersion(1)),
                        sessionLocator = CrewSessionLocator("session_locator"),
                        relayLocator = null,
                        rendezvousInviteId = CrewInviteId("invite_id"),
                        credentialId = "a".repeat(22),
                        credentialSecret = "not-a-256-bit-secret",
                        issuedAtEpochMs = 0L,
                        expiresAtEpochMs = CrewRejoinLease.MAX_LIFETIME_MS + 1L,
                    )
                }
                .isFailure
        )
    }

    @Test
    fun `restore policy refuses expired mismatched and orphaned credentials`() {
        val lease = fixtureLease()
        val checkpoint = fixtureCheckpoint(lease)

        assertEquals(CrewRejoinRestoreDecision.RESTORE, CrewRejoinRestorePolicy.decide(checkpoint, lease, 1_500L))
        assertEquals(CrewRejoinRestoreDecision.DISCARD_EXPIRED_LEASE, CrewRejoinRestorePolicy.decide(checkpoint, lease, 2_000L))
        assertEquals(CrewRejoinRestoreDecision.DISCARD_MISSING_LEASE, CrewRejoinRestorePolicy.decide(checkpoint, null, 1_500L))
        assertEquals(CrewRejoinRestoreDecision.DISCARD_ORPHANED_LEASE, CrewRejoinRestorePolicy.decide(null, lease, 1_500L))
        assertEquals(
            CrewRejoinRestoreDecision.DISCARD_MISMATCHED_LEASE,
            CrewRejoinRestorePolicy.decide(checkpoint, fixtureLease(session = "other_session"), 1_500L),
        )
    }

    @Test
    fun `registry verifies exact credential then revokes it`() {
        val registry = CrewRejoinCredentialRegistry(DeterministicRandom())
        val lease = issueFixture(registry)

        assertTrue(registry.verify(lease, 1_500L))
        assertFalse(registry.verify(lease.copySecret("B".repeat(43)), 1_500L))
        assertFalse(registry.verify(lease.copyMember("other_member"), 1_500L))
        assertFalse(registry.verify(lease, 2_000L))
        assertTrue(registry.revoke(lease.sessionId, lease.memberId, lease.credentialId))
        assertFalse(registry.verify(lease, 1_500L))
    }

    @Test
    fun `registry transfer snapshot is secret free and preserves verifier`() {
        val source = CrewRejoinCredentialRegistry(DeterministicRandom())
        val lease = issueFixture(source)
        val snapshot = source.exportTransferSnapshot(lease.sessionId)
        val target = CrewRejoinCredentialRegistry(DeterministicRandom())

        assertFalse(snapshot.toString().contains(lease.credentialSecret))
        target.importTransferSnapshot(snapshot)
        assertTrue(target.verify(lease, 1_500L))
        target.revokeSession(lease.sessionId)
        assertFalse(target.verify(lease, 1_500L))
    }

    private fun issueFixture(registry: CrewRejoinCredentialRegistry) =
        registry.issueAfterAdmission(
            sessionId = CrewSessionId("session_id", ProtocolVersion(1)),
            memberId = CrewMemberId("member_id", ProtocolVersion(1)),
            sessionLocator = CrewSessionLocator("session_locator"),
            relayLocator = CrewRelayLocator("https://relay.example"),
            rendezvousInviteId = CrewInviteId("invite_id"),
            issuedAtEpochMs = 1_000L,
            expiresAtEpochMs = 2_000L,
        )

    private fun fixtureLease(session: String = "session_id") =
        CrewRejoinLease(
            sessionId = CrewSessionId(session, ProtocolVersion(1)),
            memberId = CrewMemberId("member_id", ProtocolVersion(1)),
            sessionLocator = CrewSessionLocator("session_locator"),
            relayLocator = null,
            rendezvousInviteId = CrewInviteId("invite_id"),
            credentialId = "a".repeat(22),
            credentialSecret = "b".repeat(43),
            issuedAtEpochMs = 1_000L,
            expiresAtEpochMs = 2_000L,
        )

    private fun fixtureCheckpoint(lease: CrewRejoinLease) =
        CrewSnapshot(
            sessionId = lease.sessionId,
            protocolVersion = lease.protocolVersion,
            term = CoordinatorTerm(1),
            lastSequence = EventSequence(0),
            publisherMemberId = lease.memberId,
            coordinatorMemberId = lease.memberId,
            members = listOf(CrewMember(lease.memberId, "Member")),
            queue = emptyList(),
            playback = CrewPlaybackState(),
            shuffleEnabled = false,
            repeatMode = org.oxycblt.auxio.shippy.crew.core.CrewRepeatMode.OFF,
        )

    private fun CrewRejoinLease.copySecret(secret: String) =
        CrewRejoinLease(sessionId, memberId, sessionLocator, relayLocator, rendezvousInviteId, credentialId, secret, issuedAtEpochMs, expiresAtEpochMs)

    private fun CrewRejoinLease.copyMember(member: String) =
        CrewRejoinLease(sessionId, CrewMemberId(member, protocolVersion), sessionLocator, relayLocator, rendezvousInviteId, credentialId, credentialSecret, issuedAtEpochMs, expiresAtEpochMs)

    private class DeterministicRandom : CrewCredentialRandom {
        private var next = 0
        override fun nextBytes(size: Int) = ByteArray(size) { (next++ and 0x7f).toByte() }
    }
}
