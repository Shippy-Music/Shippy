/*
 * Copyright (c) 2026 Shippy contributors
 * CrewSessionEngineTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.session

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewSnapshot
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.DurableEventId
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport
import org.oxycblt.auxio.shippy.crew.transport.CrewSendResult
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportDrop
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportState
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.persistence.crew.CrewCheckpointLoadResult
import org.oxycblt.auxio.shippy.persistence.crew.CrewCheckpointRepository
import org.oxycblt.auxio.shippy.persistence.crew.PersistedCrewCheckpoint

class CrewSessionEngineTest {
    private val protocol = ProtocolVersion(1)
    private val sessionId = CrewSessionId("crew", protocol)
    private val coordinatorId = CrewMemberId("coordinator", protocol)
    private val memberId = CrewMemberId("member", protocol)

    @Test
    fun `member request is sequenced broadcast reconciled and persisted`() = runBlocking {
        val fixture = connectedEngines(state(), state())
        val request =
            CrewActionRequest(
                id = DurableEventId("replace"),
                issuingMemberId = memberId,
                clientMonotonicTimestampMs = 10,
                action = CrewAction.QueueReplaced(listOf(queueItem("one"))),
            )

        assertEquals(CrewSubmitResult.Submitted(request), fixture.member.submit(request, 10))
        val coordinatorState =
            withTimeout(2_000) {
                fixture.coordinator.state.filter { it.lastSequence == EventSequence(1) }.first()
            }
        val memberState =
            withTimeout(2_000) {
                fixture.member.state.filter { it.lastSequence == EventSequence(1) }.first()
            }

        assertEquals(coordinatorState, memberState)
        assertEquals(listOf(QueueItemId("queue-one")), memberState.queue.map { it.id })
        assertEquals(EventSequence(1), fixture.coordinatorStore.latest?.lastSequence)
        assertEquals(EventSequence(1), fixture.memberStore.latest?.lastSequence)
        fixture.close()
    }

    @Test
    fun `coordinator rejection rolls back the exact optimistic request`() = runBlocking {
        val fixture = connectedEngines(state(), state())
        val request =
            CrewActionRequest(
                id = DurableEventId("invalid-remove"),
                issuingMemberId = memberId,
                clientMonotonicTimestampMs = 10,
                action = CrewAction.QueueItemRemoved(QueueItemId("missing")),
            )

        val rejection =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000) {
                    fixture.member.notices
                        .filterIsInstance<CrewSessionNotice.OptimisticRejected>()
                        .filter { it.rejection.request.id == request.id }
                        .first()
                }
            }
        fixture.member.submit(request, 10)

        assertEquals(
            CrewOptimisticRejectionReason.COORDINATOR_REJECTED,
            rejection.await().rejection.reason,
        )
        assertEquals(EventSequence(0), fixture.coordinator.state.value.lastSequence)
        fixture.close()
    }

    @Test
    fun `reconnecting member installs a newer authenticated snapshot`() = runBlocking {
        val queue = listOf(queueItem("one"))
        val coordinatorState =
            state(queue = queue, sequence = 7)
        val memberState = state()
        val fixture = connectedEngines(coordinatorState, memberState)

        assertTrue(fixture.member.requestSnapshot())
        val restored =
            withTimeout(2_000) {
                fixture.member.state.filter { it.lastSequence == EventSequence(7) }.first()
            }

        assertEquals(queue, restored.queue)
        assertEquals(EventSequence(7), fixture.memberStore.latest?.lastSequence)
        fixture.close()
    }

    @Test
    fun `bounded transient backpressure preserves control order`() = runBlocking {
        val fixture = connectedEngines(state(), state(), memberToCoordinatorBackpressure = 3)
        val request =
            CrewActionRequest(
                id = DurableEventId("shuffle"),
                issuingMemberId = memberId,
                clientMonotonicTimestampMs = 20,
                action = CrewAction.ShuffleChanged(true),
            )

        fixture.member.submit(request, 20)
        val converged =
            withTimeout(2_000) {
                fixture.member.state.filter { it.shuffleEnabled }.first()
            }

        assertEquals(EventSequence(1), converged.lastSequence)
        assertEquals(0, fixture.memberToCoordinator.remainingBackpressure())
        fixture.close()
    }

    @Test
    fun `authenticated pending peer is admitted by event then canonical snapshot`() = runBlocking {
        val coordinatorState =
            CrewState(
                sessionId = sessionId,
                protocolVersion = protocol,
                term = CoordinatorTerm(1),
                lastSequence = EventSequence(0),
                coordinatorMemberId = coordinatorId,
                members = listOf(CrewMember(coordinatorId, "Coordinator")),
            )
        val fixture = connectedEngines(coordinatorState, state())
        val joiningMember = CrewMember(memberId, "Joined member")

        val result =
            fixture.coordinator.admitPeer(
                transportMemberId = memberId,
                member = joiningMember,
                requestId = DurableEventId("admit-member"),
                clientMonotonicTimestampMs = 30,
            )
        assertTrue(result is CrewAdmissionResult.Admitted)
        val joined =
            withTimeout(2_000) {
                fixture.member.state
                    .filter {
                        it.lastSequence == EventSequence(1) &&
                            it.members.any { member -> member == joiningMember }
                    }
                    .first()
            }

        assertEquals(joiningMember, joined.members.single { it.id == memberId })
        fixture.close()
    }

    @Test
    fun `coordinator gracefully transfers then leaves through new coordinator`() = runBlocking {
        val fixture = connectedEngines(state(), state())

        val result =
            fixture.coordinator.gracefulLeave(
                transferRequestId = DurableEventId("transfer"),
                leaveRequestId = DurableEventId("leave"),
                clientMonotonicTimestampMs = 40,
            )
        assertTrue(result is CrewGracefulLeaveResult.Requested)
        val remaining =
            withTimeout(2_000) {
                fixture.member.state
                    .filter {
                        it.coordinatorMemberId == memberId &&
                            it.members.map(CrewMember::id) == listOf(memberId)
                    }
                    .first()
            }
        withTimeout(2_000) {
            fixture.coordinator.state
                .filter { it.members.none { member -> member.id == coordinatorId } }
                .first()
        }

        assertEquals(CoordinatorTerm(2), remaining.term)
        assertEquals(EventSequence(2), remaining.lastSequence)
        assertEquals(null, fixture.coordinatorStore.latest)
        fixture.close()
    }

    private suspend fun connectedEngines(
        coordinatorState: CrewState,
        memberState: CrewState,
        memberToCoordinatorBackpressure: Int = 0,
    ): EngineFixture {
        val coordinatorStore = FakeCheckpointRepository()
        val memberStore = FakeCheckpointRepository()
        val coordinator =
            CrewSessionEngine(
                coordinatorState,
                coordinatorId,
                coordinatorStore,
                nowEpochMs = { 100 },
            )
        val member =
            CrewSessionEngine(
                memberState,
                memberId,
                memberStore,
                nowEpochMs = { 100 },
            )
        val coordinatorToMember = FakePeerTransport(memberId)
        val memberToCoordinator =
            FakePeerTransport(coordinatorId, backpressureAttempts = memberToCoordinatorBackpressure)
        coordinatorToMember.counterpart = memberToCoordinator
        memberToCoordinator.counterpart = coordinatorToMember
        coordinator.start()
        member.start()
        coordinator.attachPeer(coordinatorToMember)
        member.attachPeer(memberToCoordinator)
        return EngineFixture(
            coordinator,
            member,
            coordinatorStore,
            memberStore,
            coordinatorToMember,
            memberToCoordinator,
        )
    }

    private fun state(
        queue: List<QueueItem> = emptyList(),
        sequence: Long = 0,
    ) =
        CrewState(
            sessionId = sessionId,
            protocolVersion = protocol,
            term = CoordinatorTerm(1),
            lastSequence = EventSequence(sequence),
            coordinatorMemberId = coordinatorId,
            members =
                listOf(
                    CrewMember(coordinatorId, "Coordinator"),
                    CrewMember(memberId, "Member"),
                ),
            queue = queue,
        )

    private fun queueItem(suffix: String) =
        QueueItem(
            id = QueueItemId("queue-$suffix"),
            track =
                Track(
                    id = TrackId("track-$suffix"),
                    realm = TrackRealm.PROVIDER,
                    title = "Title $suffix",
                    artists = listOf("Artist"),
                    candidates = emptyList(),
                ),
            contributorId = memberId.value,
        )

    private data class EngineFixture(
        val coordinator: CrewSessionEngine,
        val member: CrewSessionEngine,
        val coordinatorStore: FakeCheckpointRepository,
        val memberStore: FakeCheckpointRepository,
        val coordinatorToMember: FakePeerTransport,
        val memberToCoordinator: FakePeerTransport,
    ) {
        fun close() {
            coordinator.close()
            member.close()
        }
    }

    private class FakeCheckpointRepository : CrewCheckpointRepository {
        @Volatile var latest: CrewSnapshot? = null

        override suspend fun load(): CrewCheckpointLoadResult =
            latest?.let {
                CrewCheckpointLoadResult.Loaded(PersistedCrewCheckpoint(it, 100))
            } ?: CrewCheckpointLoadResult.Empty

        override suspend fun save(
            snapshot: CrewSnapshot,
            nowEpochMs: Long,
        ) {
            latest = snapshot
        }

        override suspend fun clear(sessionId: CrewSessionId): Boolean {
            val current = latest
            return if (current?.sessionId == sessionId) {
                latest = null
                true
            } else {
                false
            }
        }
    }

    private class FakePeerTransport(
        override val remoteMemberId: CrewMemberId,
        backpressureAttempts: Int = 0,
    ) : CrewPeerTransport {
        private val incomingFrames = Channel<CrewTransportFrame>(capacity = 256)
        private val mutableState = MutableStateFlow(CrewTransportState.CONNECTED)
        private val remainingBackpressure = AtomicInteger(backpressureAttempts)
        lateinit var counterpart: FakePeerTransport

        override val state: StateFlow<CrewTransportState> = mutableState
        override val incoming: Flow<CrewTransportFrame> = incomingFrames.receiveAsFlow()
        override val drops: Flow<CrewTransportDrop> = emptyFlow()

        override fun trySend(frame: CrewTransportFrame): CrewSendResult {
            if (remainingBackpressure.getAndUpdate { maxOf(0, it - 1) } > 0) {
                return CrewSendResult.Backpressured(1, 2)
            }
            return if (counterpart.incomingFrames.trySend(frame).isSuccess) {
                CrewSendResult.Sent(0)
            } else {
                CrewSendResult.NativeRejected
            }
        }

        fun remainingBackpressure(): Int = remainingBackpressure.get()

        override fun bufferedBytes(
            channel: org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
        ): Long = 0

        override fun close() {
            mutableState.value = CrewTransportState.CLOSED
        }
    }
}
