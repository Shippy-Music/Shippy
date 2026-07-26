/*
 * Copyright (c) 2026 Shippy contributors
 * CrewSessionOrchestratorTest.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.crew.rejoin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
import org.oxycblt.auxio.shippy.crew.invite.CrewSessionLocator
import org.oxycblt.auxio.shippy.crew.session.CrewSessionEngine
import org.oxycblt.auxio.shippy.persistence.crew.CrewCheckpointLoadResult
import org.oxycblt.auxio.shippy.persistence.crew.CrewCheckpointRepository
import org.oxycblt.auxio.shippy.persistence.crew.CrewRejoinLeaseStore
import org.oxycblt.auxio.shippy.persistence.crew.PersistedCrewCheckpoint

class CrewSessionOrchestratorTest {
    @Test
    fun `restore starts engine and retains exact credential after retryable failure`() = runBlocking {
        val fixture = Fixture(CrewRejoinConnectResult.RetryableFailure)

        val result = fixture.orchestrator.restoreAfterProcessStart()

        assertEquals(CrewRestoreResult.RestoredAwaitingNetwork, result)
        assertEquals(1, fixture.factoryCalls)
        assertEquals(1, fixture.connectorCalls)
        assertEquals(fixture.lease, fixture.leases.active)
        assertEquals(fixture.snapshot, fixture.checkpoints.active)
        fixture.close()
    }

    @Test
    fun `authenticated revocation clears checkpoint and lease`() = runBlocking {
        val fixture = Fixture(CrewRejoinConnectResult.Revoked)

        val result = fixture.orchestrator.restoreAfterProcessStart()

        assertEquals(
            CrewRestoreResult.Discarded(CrewRejoinRestoreDecision.DISCARDED_REVOKED),
            result,
        )
        assertEquals(listOf(fixture.lease.sessionId), fixture.checkpoints.cleared)
        assertEquals(listOf(fixture.lease.sessionId), fixture.leases.cleared)
        assertEquals(null, fixture.checkpoints.active)
        assertEquals(null, fixture.leases.active)
    }

    @Test
    fun `accepted leave clears only its exact active session`() = runBlocking {
        val fixture = Fixture(CrewRejoinConnectResult.RetryableFailure)
        fixture.orchestrator.restoreAfterProcessStart()

        fixture.orchestrator.revokeAfterAcceptedLeave(fixture.lease.sessionId)

        assertEquals(listOf(fixture.lease.sessionId), fixture.checkpoints.cleared)
        assertEquals(listOf(fixture.lease.sessionId), fixture.leases.cleared)
        assertEquals(null, fixture.checkpoints.active)
        assertEquals(null, fixture.leases.active)
    }

    private class Fixture(result: CrewRejoinConnectResult) {
        private val protocol = ProtocolVersion(1)
        val sessionId = CrewSessionId("session_id", protocol)
        private val memberId = CrewMemberId("member_id", protocol)
        val lease =
            CrewRejoinLease(
                sessionId = sessionId,
                memberId = memberId,
                sessionLocator = CrewSessionLocator("session_locator"),
                relayLocator = null,
                rendezvousInviteId = CrewInviteId("invite_id"),
                credentialId = "a".repeat(22),
                credentialSecret = "b".repeat(43),
                issuedAtEpochMs = 1_000L,
                expiresAtEpochMs = 2_000L,
            )
        val snapshot =
            CrewSnapshot(
                sessionId = sessionId,
                protocolVersion = protocol,
                term = CoordinatorTerm(1),
                lastSequence = EventSequence(0),
                publisherMemberId = memberId,
                coordinatorMemberId = memberId,
                members = listOf(CrewMember(memberId, "Member")),
                queue = emptyList(),
                playback = CrewPlaybackState(),
                shuffleEnabled = false,
                repeatMode = org.oxycblt.auxio.shippy.crew.core.CrewRepeatMode.OFF,
            )
        val checkpoints = FakeCheckpointRepository(snapshot)
        val leases = FakeLeaseStore(lease)
        var factoryCalls = 0
        var connectorCalls = 0
        private var engine: CrewSessionEngine? = null
        val orchestrator =
            CrewSessionOrchestrator(
                checkpoints,
                leases,
                CrewSessionEngineFactory { _, checkpoint ->
                    factoryCalls++
                    CrewSessionEngine(
                        checkpoint.toCrewState(),
                        memberId,
                        checkpoints,
                        nowEpochMs = { 1_500L },
                        dispatcher = Dispatchers.Unconfined,
                    ).also { engine = it }
                },
                CrewRejoinConnector { _, _ ->
                    connectorCalls++
                    result
                },
                nowEpochMs = { 1_500L },
            )

        fun close() = engine?.close()
    }

    private class FakeCheckpointRepository(initial: CrewSnapshot?) : CrewCheckpointRepository {
        var active = initial
        val cleared = mutableListOf<CrewSessionId>()

        override suspend fun load() =
            active?.let { CrewCheckpointLoadResult.Loaded(PersistedCrewCheckpoint(it, 1_000L)) }
                ?: CrewCheckpointLoadResult.Empty

        override suspend fun save(snapshot: CrewSnapshot, nowEpochMs: Long) {
            active = snapshot
        }

        override suspend fun clear(sessionId: CrewSessionId): Boolean {
            cleared += sessionId
            return if (active?.sessionId == sessionId) {
                active = null
                true
            } else {
                false
            }
        }
    }

    private class FakeLeaseStore(initial: CrewRejoinLease?) : CrewRejoinLeaseStore {
        var active = initial
        val cleared = mutableListOf<CrewSessionId>()

        override suspend fun load() = active

        override suspend fun save(lease: CrewRejoinLease) {
            active = lease
        }

        override suspend fun clear(sessionId: CrewSessionId): Boolean {
            cleared += sessionId
            return if (active?.sessionId == sessionId) {
                active = null
                true
            } else {
                false
            }
        }
    }
}
