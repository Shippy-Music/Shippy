/*
 * Copyright (c) 2026 Auxio Project
 * CrewSessionEngineTest.kt is part of Auxio.
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
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewPlaybackMode
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewSnapshot
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.DurableEventId
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.core.toSnapshot
import org.oxycblt.auxio.shippy.crew.media.CrewAuthenticatedMediaLifecycle
import org.oxycblt.auxio.shippy.crew.media.CrewAuthenticatedMediaPeer
import org.oxycblt.auxio.shippy.crew.media.CrewMediaFrameResult
import org.oxycblt.auxio.shippy.crew.preparation.CrewAvailability
import org.oxycblt.auxio.shippy.crew.preparation.CrewAvailabilityAnnouncement
import org.oxycblt.auxio.shippy.crew.preparation.CrewAvailabilityEntry
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlFrameResult
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlFramer
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlMessage
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlReassembler
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport
import org.oxycblt.auxio.shippy.crew.transport.CrewSendResult
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
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
    fun `terminal event clears checkpoint without cancelling its outbound delivery`() =
        runBlocking {
            val store = FakeCheckpointRepository()
            store.latest = state().toSnapshot()
            val engine = CrewSessionEngine(state(), coordinatorId, store, nowEpochMs = { 100 })
            val peer = FakePeerTransport(memberId)
            engine.attachPeer(peer)
            val ended =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(2_000) {
                        engine.notices.filterIsInstance<CrewSessionNotice.SessionEnded>().first()
                    }
                }

            val request =
                CrewActionRequest(
                    DurableEventId("end-session"),
                    coordinatorId,
                    1,
                    CrewAction.SessionEnded,
                )
            assertEquals(CrewSubmitResult.Submitted(request), engine.submit(request, 1))
            assertEquals(CrewPlaybackMode.ENDED, ended.await().finalState.playback.mode)
            assertEquals(null, store.latest)
            withTimeout(2_000) {
                while (peer.sentFrames().isEmpty()) {
                    kotlinx.coroutines.delay(10)
                }
            }
            val reassembler = CrewControlReassembler()
            val delivered =
                peer
                    .sentFrames()
                    .mapNotNull { frame ->
                        (reassembler.accept(frame, nowMonotonicMs = 0)
                                as? CrewControlFrameResult.Complete)
                            ?.message
                    }
                    .filterIsInstance<CrewControlMessage.Event>()
                    .any { it.event.action == CrewAction.SessionEnded }
            assertTrue(delivered)
            engine.close()
        }

    @Test
    fun `coordinator fans a member availability announcement to active peers`() = runBlocking {
        val observerId = CrewMemberId("observer", protocol)
        val item = queueItem("available")
        val members =
            listOf(
                CrewMember(coordinatorId, "Coordinator"),
                CrewMember(memberId, "Member"),
                CrewMember(observerId, "Observer"),
            )
        val fixture =
            connectedEngines(
                state(queue = listOf(item), members = members),
                state(queue = listOf(item), members = members),
            )
        val observer = FakePeerTransport(observerId)
        fixture.coordinator.attachPeer(observer)

        assertTrue(
            fixture.member.publishLocalAvailability(mapOf(item.id to CrewAvailability.DOWNLOAD))
        )
        val coordinatorSummary =
            withTimeout(2_000) {
                fixture.coordinator.availability
                    .filter { it[item.id]?.availabilityFor(memberId) == CrewAvailability.DOWNLOAD }
                    .first()
            }
        assertEquals(
            CrewAvailability.DOWNLOAD,
            coordinatorSummary.getValue(item.id).availabilityFor(memberId),
        )
        withTimeout(2_000) {
            while (observer.sentFrames().isEmpty()) {
                kotlinx.coroutines.delay(10)
            }
        }
        val decoded =
            CrewControlReassembler().accept(observer.sentFrames().single(), nowMonotonicMs = 0)
        assertTrue(
            decoded is CrewControlFrameResult.Complete &&
                decoded.message is CrewControlMessage.AvailabilityAnnounced
        )
        fixture.close()
    }

    @Test
    fun `joiner only trusts availability relayed by the current coordinator`() = runBlocking {
        val item = queueItem("trusted")
        val engine =
            CrewSessionEngine(
                state(queue = listOf(item)),
                memberId,
                FakeCheckpointRepository(),
                nowEpochMs = { 100 },
            )
        val rogueId = CrewMemberId("rogue", protocol)
        val rogue = FakePeerTransport(rogueId)
        engine.attachPeer(rogue)
        val rejection =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000) {
                    engine.notices.filterIsInstance<CrewSessionNotice.ProtocolRejected>().first()
                }
            }
        rogue.receiveControl(announcement(item, publishingMemberId = coordinatorId))

        assertEquals(rogueId, rejection.await().memberId)
        assertEquals(
            CrewAvailability.UNAVAILABLE,
            engine.availability.value.getValue(item.id).availabilityFor(coordinatorId),
        )
        assertEquals(CrewTransportState.CONNECTED, rogue.state.value)
        engine.close()
    }

    @Test
    fun `coordinator rejects a forged publisher and stale checkpoint without detaching`() =
        runBlocking {
            val item = queueItem("forged")
            val engine =
                CrewSessionEngine(
                    state(queue = listOf(item)),
                    coordinatorId,
                    FakeCheckpointRepository(),
                    nowEpochMs = { 100 },
                )
            val transport = FakePeerTransport(memberId)
            engine.attachPeer(transport)
            val forged =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(2_000) {
                        engine.notices
                            .filterIsInstance<CrewSessionNotice.ProtocolRejected>()
                            .first()
                    }
                }
            transport.receiveControl(announcement(item, publishingMemberId = coordinatorId))
            assertEquals(memberId, forged.await().memberId)

            val stale =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(2_000) {
                        engine.notices
                            .filterIsInstance<CrewSessionNotice.ProtocolRejected>()
                            .first()
                    }
                }
            transport.receiveControl(
                announcement(item, publishingMemberId = memberId, knownSequence = EventSequence(99))
            )
            assertEquals(memberId, stale.await().memberId)
            assertEquals(
                CrewAvailability.UNAVAILABLE,
                engine.availability.value.getValue(item.id).availabilityFor(memberId),
            )
            assertEquals(CrewTransportState.CONNECTED, transport.state.value)
            engine.close()
        }

    @Test
    fun `local availability is path free and pruned when the canonical queue changes`() =
        runBlocking {
            val initial = queueItem("initial")
            val replacement = queueItem("replacement")
            val engine =
                CrewSessionEngine(
                    state(queue = listOf(initial)),
                    coordinatorId,
                    FakeCheckpointRepository(),
                    nowEpochMs = { 100 },
                )

            assertTrue(
                engine.publishLocalAvailability(mapOf(initial.id to CrewAvailability.LOCAL_EXACT))
            )
            assertEquals(
                CrewAvailability.LOCAL_EXACT,
                engine.availability.value.getValue(initial.id).availabilityFor(coordinatorId),
            )
            val request =
                CrewActionRequest(
                    id = DurableEventId("replace-availability"),
                    issuingMemberId = coordinatorId,
                    clientMonotonicTimestampMs = 1,
                    action = CrewAction.QueueReplaced(listOf(replacement)),
                )
            assertEquals(CrewSubmitResult.Submitted(request), engine.submit(request, 1))
            withTimeout(2_000) {
                engine.availability.filter { replacement.id in it && initial.id !in it }.first()
            }
            assertEquals(
                CrewAvailability.UNAVAILABLE,
                engine.availability.value.getValue(replacement.id).availabilityFor(coordinatorId),
            )
            engine.close()
        }

    @Test
    fun `media routes with its authenticated peer identity`() = runBlocking {
        val lifecycle = RecordingMediaLifecycle()
        val engine = engineWithMediaLifecycle(lifecycle)
        val transport = FakePeerTransport(memberId)

        engine.attachPeer(transport)
        val frame = CrewTransportFrame(CrewTransportChannel.MEDIA, byteArrayOf(1, 2, 3))
        transport.receive(frame)
        val received = withTimeout(2_000) { lifecycle.receivedFrames.receive() }

        assertEquals(listOf(transport), lifecycle.attached.map { it.transport })
        assertEquals(frame, received.second)
        assertEquals(memberId, received.first.memberId)
        assertEquals(transport, received.first.transport)
        engine.close()
        assertEquals(listOf(transport), lifecycle.detached.map { it.transport })
    }

    @Test
    fun `replacing a peer detaches its media lifecycle exactly once`() = runBlocking {
        val lifecycle = RecordingMediaLifecycle()
        val engine = engineWithMediaLifecycle(lifecycle)
        val first = FakePeerTransport(memberId)
        val replacement = FakePeerTransport(memberId)

        engine.attachPeer(first)
        engine.attachPeer(replacement)
        engine.close()

        assertEquals(
            listOf(
                "attach" to first,
                "detach" to first,
                "attach" to replacement,
                "detach" to replacement,
            ),
            lifecycle.events,
        )
    }

    @Test
    fun `rejected media detaches only its peer as a protocol violation`() = runBlocking {
        val lifecycle =
            RecordingMediaLifecycle(result = CrewMediaFrameResult.Rejected("invalid chunk"))
        val engine = engineWithMediaLifecycle(lifecycle)
        val transport = FakePeerTransport(memberId)
        engine.attachPeer(transport)

        val detachedNotice =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000) {
                    engine.notices
                        .filterIsInstance<CrewSessionNotice.PeerDetached>()
                        .filter { it.memberId == memberId }
                        .first()
                }
            }
        transport.receive(CrewTransportFrame(CrewTransportChannel.MEDIA, byteArrayOf(9)))

        assertEquals(CrewPeerDetachReason.PROTOCOL_VIOLATION, detachedNotice.await().reason)
        assertEquals(listOf(transport), lifecycle.detached.map { it.transport })
        assertEquals(CrewTransportState.CLOSED, transport.state.value)
        engine.close()
        assertEquals(listOf(transport), lifecycle.detached.map { it.transport })
    }

    @Test
    fun `media lifecycle failure detaches only its peer as an engine failure`() = runBlocking {
        val lifecycle = RecordingMediaLifecycle(failOnFrame = true)
        val engine = engineWithMediaLifecycle(lifecycle)
        val transport = FakePeerTransport(memberId)
        engine.attachPeer(transport)

        val detachedNotice =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000) {
                    engine.notices
                        .filterIsInstance<CrewSessionNotice.PeerDetached>()
                        .filter { it.memberId == memberId }
                        .first()
                }
            }
        transport.receive(CrewTransportFrame(CrewTransportChannel.MEDIA, byteArrayOf(9)))

        assertEquals(CrewPeerDetachReason.ENGINE_FAILURE, detachedNotice.await().reason)
        assertEquals(listOf(transport), lifecycle.detached.map { it.transport })
        engine.close()
    }

    @Test
    fun `control still converges when a media lifecycle is installed`() = runBlocking {
        val fixture =
            connectedEngines(
                state(),
                state(),
                coordinatorMediaLifecycle = RecordingMediaLifecycle(),
            )
        val request =
            CrewActionRequest(
                id = DurableEventId("media-seam-control"),
                issuingMemberId = memberId,
                clientMonotonicTimestampMs = 10,
                action = CrewAction.QueueReplaced(listOf(queueItem("media-seam"))),
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
        withTimeout(2_000) {
            while (fixture.memberStore.latest?.lastSequence != EventSequence(1)) {
                kotlinx.coroutines.delay(10)
            }
        }

        assertEquals(coordinatorState, memberState)
        fixture.close()
    }

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
        withTimeout(2_000) {
            while (fixture.coordinatorStore.latest?.lastSequence != EventSequence(1)) {
                yield()
            }
            while (fixture.memberStore.latest?.lastSequence != EventSequence(1)) {
                yield()
            }
        }
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
        val coordinatorState = state(queue = queue, sequence = 7)
        val memberState = state()
        val fixture = connectedEngines(coordinatorState, memberState)

        assertTrue(fixture.member.requestSnapshot())
        val restored =
            withTimeout(2_000) {
                fixture.member.state.filter { it.lastSequence == EventSequence(7) }.first()
            }

        assertEquals(queue, restored.queue)
        withTimeout(2_000) {
            while (fixture.memberStore.latest?.lastSequence != EventSequence(7)) {
                yield()
            }
        }
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
            withTimeout(2_000) { fixture.member.state.filter { it.shuffleEnabled }.first() }

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
        withTimeout(2_000) {
            // StateFlow publishes the accepted leave before checkpoint cleanup finishes.
            // Await that durable side effect explicitly instead of racing it on slower CI hosts.
            while (fixture.coordinatorStore.latest != null) {
                yield()
            }
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
        coordinatorMediaLifecycle: CrewAuthenticatedMediaLifecycle? = null,
    ): EngineFixture {
        val coordinatorStore = FakeCheckpointRepository()
        val memberStore = FakeCheckpointRepository()
        val coordinator =
            CrewSessionEngine(
                coordinatorState,
                coordinatorId,
                coordinatorStore,
                nowEpochMs = { 100 },
                mediaLifecycle = coordinatorMediaLifecycle,
            )
        val member = CrewSessionEngine(memberState, memberId, memberStore, nowEpochMs = { 100 })
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
        members: List<CrewMember> =
            listOf(CrewMember(coordinatorId, "Coordinator"), CrewMember(memberId, "Member")),
    ) =
        CrewState(
            sessionId = sessionId,
            protocolVersion = protocol,
            term = CoordinatorTerm(1),
            lastSequence = EventSequence(sequence),
            coordinatorMemberId = coordinatorId,
            members = members,
            queue = queue,
        )

    private fun announcement(
        item: QueueItem,
        publishingMemberId: CrewMemberId,
        knownSequence: EventSequence = EventSequence(0),
    ) =
        CrewControlMessage.AvailabilityAnnounced(
            CrewAvailabilityAnnouncement(
                sessionId = sessionId,
                protocolVersion = protocol,
                publishingMemberId = publishingMemberId,
                knownTerm = CoordinatorTerm(1),
                knownSequence = knownSequence,
                entries = listOf(CrewAvailabilityEntry(item.id, CrewAvailability.DOWNLOAD)),
            )
        )

    private fun engineWithMediaLifecycle(lifecycle: CrewAuthenticatedMediaLifecycle) =
        CrewSessionEngine(
            state(),
            coordinatorId,
            FakeCheckpointRepository(),
            nowEpochMs = { 100 },
            mediaLifecycle = lifecycle,
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
            latest?.let { CrewCheckpointLoadResult.Loaded(PersistedCrewCheckpoint(it, 100)) }
                ?: CrewCheckpointLoadResult.Empty

        override suspend fun save(snapshot: CrewSnapshot, nowEpochMs: Long) {
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
        private val recordedSentFrames = mutableListOf<CrewTransportFrame>()
        var counterpart: FakePeerTransport? = null

        override val state: StateFlow<CrewTransportState> = mutableState
        override val incoming: Flow<CrewTransportFrame> = incomingFrames.receiveAsFlow()
        override val drops: Flow<CrewTransportDrop> = emptyFlow()

        override fun trySend(frame: CrewTransportFrame): CrewSendResult {
            if (remainingBackpressure.getAndUpdate { maxOf(0, it - 1) } > 0) {
                return CrewSendResult.Backpressured(1, 2)
            }
            synchronized(recordedSentFrames) { recordedSentFrames += frame }
            val remote = counterpart
            return if (remote == null || remote.incomingFrames.trySend(frame).isSuccess) {
                CrewSendResult.Sent(0)
            } else {
                CrewSendResult.NativeRejected
            }
        }

        fun remainingBackpressure(): Int = remainingBackpressure.get()

        fun receive(frame: CrewTransportFrame) {
            check(incomingFrames.trySend(frame).isSuccess)
        }

        fun receiveControl(message: CrewControlMessage) {
            CrewControlFramer.encode(message).forEach(::receive)
        }

        fun sentFrames(): List<CrewTransportFrame> =
            synchronized(recordedSentFrames) { recordedSentFrames.toList() }

        override fun bufferedBytes(
            channel: org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
        ): Long = 0

        override fun close() {
            mutableState.value = CrewTransportState.CLOSED
        }
    }

    private class RecordingMediaLifecycle(
        private val result: CrewMediaFrameResult = CrewMediaFrameResult.Accepted,
        private val failOnFrame: Boolean = false,
    ) : CrewAuthenticatedMediaLifecycle {
        val attached = mutableListOf<CrewAuthenticatedMediaPeer>()
        val frames = mutableListOf<Pair<CrewAuthenticatedMediaPeer, CrewTransportFrame>>()
        val receivedFrames = Channel<Pair<CrewAuthenticatedMediaPeer, CrewTransportFrame>>(1)
        val detached = mutableListOf<CrewAuthenticatedMediaPeer>()
        val events = mutableListOf<Pair<String, CrewPeerTransport>>()

        override fun onPeerAttached(peer: CrewAuthenticatedMediaPeer) {
            attached += peer
            events += "attach" to peer.transport
        }

        override fun onMediaFrame(
            peer: CrewAuthenticatedMediaPeer,
            frame: CrewTransportFrame,
        ): CrewMediaFrameResult {
            if (failOnFrame) error("fixture media failure")
            frames += peer to frame
            check(receivedFrames.trySend(peer to frame).isSuccess)
            return result
        }

        override fun onPeerDetached(peer: CrewAuthenticatedMediaPeer) {
            detached += peer
            events += "detach" to peer.transport
        }
    }
}
