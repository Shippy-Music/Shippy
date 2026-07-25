/*
 * Copyright (c) 2026 Shippy contributors
 * CrewRejoinLeaseTest.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.crew.rejoin

import org.junit.Assert.assertEquals
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
import org.oxycblt.auxio.shippy.crew.invite.CrewInvite
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteId
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteSecret
import org.oxycblt.auxio.shippy.crew.invite.CrewSessionLocator

class CrewRejoinLeaseTest {
    @Test
    fun `lease codec round trips without exposing secret in diagnostic text`() {
        val lease = fixtureLease()

        val decoded = CrewRejoinLeaseCodec.decode(CrewRejoinLeaseCodec.encode(lease))

        assertEquals(CrewRejoinLeaseDecodeResult.Accepted(lease), decoded)
        assertTrue(lease.toString().contains("secret=redacted"))
        assertTrue(!lease.toString().contains(lease.invite.secret.value))
    }

    @Test
    fun `restore policy refuses expired mismatched and orphaned credentials`() {
        val lease = fixtureLease()
        val checkpoint = fixtureCheckpoint(lease)

        assertEquals(
            CrewRejoinRestoreDecision.RESTORE,
            CrewRejoinRestorePolicy.decide(checkpoint, lease, 1_500L),
        )
        assertEquals(
            CrewRejoinRestoreDecision.DISCARD_EXPIRED_LEASE,
            CrewRejoinRestorePolicy.decide(checkpoint, lease, 2_000L),
        )
        assertEquals(
            CrewRejoinRestoreDecision.DISCARD_MISSING_LEASE,
            CrewRejoinRestorePolicy.decide(checkpoint, null, 1_500L),
        )
        assertEquals(
            CrewRejoinRestoreDecision.DISCARD_ORPHANED_LEASE,
            CrewRejoinRestorePolicy.decide(null, lease, 1_500L),
        )
        val wrongSession = fixtureLease(session = "other_session")
        assertEquals(
            CrewRejoinRestoreDecision.DISCARD_MISMATCHED_LEASE,
            CrewRejoinRestorePolicy.decide(checkpoint, wrongSession, 1_500L),
        )
    }

    private fun fixtureLease(session: String = "session_id") : CrewRejoinLease {
        val protocol = ProtocolVersion(1)
        return CrewRejoinLease(
            CrewSessionId(session, protocol),
            CrewMemberId("member_id", protocol),
            CrewInvite(
                protocol,
                CrewSessionLocator("session_locator"),
                CrewInviteId("invite_id"),
                CrewInviteSecret("secret_abcdefghijklmnopqrstuvwxyz"),
                1_000L,
                2_000L,
            ),
        )
    }

    private fun fixtureCheckpoint(lease: CrewRejoinLease) =
        CrewSnapshot(
            sessionId = lease.sessionId,
            protocolVersion = lease.sessionId.protocolVersion,
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
}
