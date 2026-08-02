/*
 * Copyright (c) 2026 Auxio Project
 * CrewAdversarialTransportHarnessTest.kt is part of Auxio.
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

import kotlin.random.Random
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewPlaybackMode
import org.oxycblt.auxio.shippy.crew.core.CrewPlaybackState
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewSnapshot
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.DurableEventId
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
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

/**
 * Seeded, reproducible network abuse around the real session engine, codecs, sequencer, reducer,
 * gap recovery, and snapshots. MEDIA transfer has deeper range/loss tests in [CrewMediaTest].
 */
class CrewAdversarialTransportHarnessTest {
    private val protocol = ProtocolVersion(3)
    private val sessionId = CrewSessionId("adversarial", protocol)
    private val coordinatorId = CrewMemberId("coordinator", protocol)
    private val alphaId = CrewMemberId("alpha", protocol)
    private val betaId = CrewMemberId("beta", protocol)

    @Test
    fun `seeded duplicate and reordered rapid controls converge once`() = runBlocking {
        val seed = 0x5A17L
        val network = SeededNetwork(seed)
        val item = item("current", coordinatorId)
        val initial =
            state(
                queue = listOf(item),
                playback = CrewPlaybackState(item.id, CrewPlaybackMode.PLAYING, 0, 100),
                members = members(alpha = true, beta = false),
            )
        val coordinator = engine(initial, coordinatorId)
        val alpha = engine(initial, alphaId)
        val (coordinatorPeer, alphaPeer) = network.pair(alphaId, coordinatorId)
        coordinator.start()
        alpha.start()
        coordinator.attachPeer(coordinatorPeer)
        alpha.attachPeer(alphaPeer)

        listOf(CrewAction.Pause(100, 100), CrewAction.Seek(450, 100), CrewAction.Pause(450, 100))
            .forEachIndexed { index, action ->
                val request =
                    CrewActionRequest(
                        DurableEventId("rapid-$index"),
                        alphaId,
                        index.toLong(),
                        action,
                    )
                assertEquals(
                    CrewSubmitResult.Submitted(request),
                    alpha.submit(request, index.toLong()),
                )
            }

        drive(network) {
            coordinator.state.value.lastSequence == EventSequence(3) &&
                alpha.state.value.lastSequence == EventSequence(3)
        }
        assertCanonicalEquals(seed, network, coordinator.state.value, alpha.state.value)
        repeat(8) {
            network.deliverOneBatch()
            delay(5)
        }
        assertEquals(
            "duplicate delivery emitted an echo; seed=$seed",
            EventSequence(3),
            coordinator.state.value.lastSequence,
        )
        coordinator.close()
        alpha.close()
    }

    @Test
    fun `seeded concurrent queue edits recover gaps and converge on all engines`() = runBlocking {
        val seed = 0xC0FFEEL
        val network = SeededNetwork(seed)
        val initial = state(queue = listOf(item("base", coordinatorId)), members = members())
        val coordinator = engine(initial, coordinatorId)
        val alpha = engine(initial, alphaId)
        val beta = engine(initial, betaId)
        val (coordinatorAlpha, alphaPeer) = network.pair(alphaId, coordinatorId)
        val (coordinatorBeta, betaPeer) = network.pair(betaId, coordinatorId)
        coordinator.start()
        alpha.start()
        beta.start()
        coordinator.attachPeer(coordinatorAlpha)
        coordinator.attachPeer(coordinatorBeta)
        alpha.attachPeer(alphaPeer)
        beta.attachPeer(betaPeer)

        val alphaItem = item("alpha", alphaId)
        val betaItem = item("beta", betaId)
        alpha.submit(
            CrewActionRequest(
                DurableEventId("insert-alpha"),
                alphaId,
                1,
                CrewAction.QueueItemInserted(alphaItem, 1),
            ),
            1,
        )
        beta.submit(
            CrewActionRequest(
                DurableEventId("insert-beta"),
                betaId,
                1,
                CrewAction.QueueItemInserted(betaItem, 1),
            ),
            1,
        )

        drive(network) {
            listOf(coordinator, alpha, beta).all { it.state.value.lastSequence == EventSequence(2) }
        }
        val expected = coordinator.state.value
        assertCanonicalEquals(seed, network, expected, alpha.state.value)
        assertCanonicalEquals(seed, network, expected, beta.state.value)
        assertEquals(
            setOf(QueueItemId("queue-base"), alphaItem.id, betaItem.id),
            expected.queue.map(QueueItem::id).toSet(),
        )
        coordinator.close()
        alpha.close()
        beta.close()
    }

    private suspend fun drive(network: SeededNetwork, converged: () -> Boolean) {
        repeat(300) {
            network.deliverOneBatch()
            if (converged()) return
            delay(5)
        }
        error("adversarial network did not converge; trace=${network.trace()}")
    }

    private fun assertCanonicalEquals(
        seed: Long,
        network: SeededNetwork,
        expected: CrewState,
        actual: CrewState,
    ) {
        // The bounded applied-event cache is local reducer bookkeeping and is intentionally not
        // serialized in snapshots. Everything user-visible and authoritative must still converge.
        assertEquals(
            "seed=$seed trace=${network.trace()}",
            expected.copy(appliedEventIds = emptyList()),
            actual.copy(appliedEventIds = emptyList()),
        )
    }

    private fun engine(initial: CrewState, local: CrewMemberId) =
        CrewSessionEngine(initial, local, MemoryCheckpointRepository(), nowEpochMs = { 100 })

    private fun members(alpha: Boolean = true, beta: Boolean = true) = buildList {
        add(CrewMember(coordinatorId, "Coordinator"))
        if (alpha) add(CrewMember(alphaId, "Alpha"))
        if (beta) add(CrewMember(betaId, "Beta"))
    }

    private fun state(
        queue: List<QueueItem>,
        playback: CrewPlaybackState = CrewPlaybackState(),
        members: List<CrewMember>,
    ) =
        CrewState(
            sessionId,
            protocol,
            CoordinatorTerm(1),
            EventSequence(0),
            coordinatorId,
            members,
            queue,
            playback,
        )

    private fun item(suffix: String, contributor: CrewMemberId) =
        QueueItem(
            QueueItemId("queue-$suffix"),
            Track(
                TrackId("track-$suffix"),
                TrackRealm.PROVIDER,
                "Title $suffix",
                listOf("Artist"),
                candidates = emptyList(),
            ),
            contributor.value,
        )

    private class MemoryCheckpointRepository : CrewCheckpointRepository {
        private var snapshot: CrewSnapshot? = null

        override suspend fun load(): CrewCheckpointLoadResult =
            snapshot?.let { CrewCheckpointLoadResult.Loaded(PersistedCrewCheckpoint(it, 100)) }
                ?: CrewCheckpointLoadResult.Empty

        override suspend fun save(snapshot: CrewSnapshot, nowEpochMs: Long) {
            this.snapshot = snapshot
        }

        override suspend fun clear(sessionId: CrewSessionId): Boolean {
            if (snapshot?.sessionId != sessionId) return false
            snapshot = null
            return true
        }
    }

    private class SeededNetwork(seed: Long) {
        private val random = Random(seed)
        private val pending = mutableListOf<Delivery>()
        private val events = ArrayDeque<String>()

        fun pair(
            coordinatorRemoteId: CrewMemberId,
            memberRemoteId: CrewMemberId,
        ): Pair<Endpoint, Endpoint> {
            val coordinator = Endpoint(coordinatorRemoteId, this)
            val member = Endpoint(memberRemoteId, this)
            coordinator.remote = member
            member.remote = coordinator
            return coordinator to member
        }

        @Synchronized
        fun enqueue(target: Endpoint, frame: CrewTransportFrame) {
            pending += Delivery(target, frame)
            if (frame.channel == CrewTransportChannel.CONTROL && random.nextBoolean()) {
                pending += Delivery(target, frame)
                record("duplicate:${frame.channel}")
            }
        }

        fun deliverOneBatch() {
            val batch =
                synchronized(this) {
                    if (pending.isEmpty()) return
                    val take = random.nextInt(1, minOf(5, pending.size) + 1)
                    List(take) { pending.removeAt(random.nextInt(pending.size)) }
                }
            batch.forEach {
                record("deliver:${it.frame.channel}:${it.frame.copyPayload().size}")
                it.target.deliver(it.frame)
            }
        }

        @Synchronized fun trace(): String = events.joinToString("|")

        @Synchronized
        private fun record(event: String) {
            if (events.size == 80) events.removeFirst()
            events.addLast(event)
        }

        private data class Delivery(val target: Endpoint, val frame: CrewTransportFrame)
    }

    private class Endpoint(
        override val remoteMemberId: CrewMemberId,
        private val network: SeededNetwork,
    ) : CrewPeerTransport {
        private val frames = Channel<CrewTransportFrame>(capacity = 512)
        private val mutableState = MutableStateFlow(CrewTransportState.CONNECTED)
        lateinit var remote: Endpoint

        override val state: StateFlow<CrewTransportState> = mutableState
        override val incoming: Flow<CrewTransportFrame> = frames.receiveAsFlow()
        override val drops: Flow<CrewTransportDrop> = emptyFlow()

        override fun trySend(frame: CrewTransportFrame): CrewSendResult {
            network.enqueue(remote, frame)
            return CrewSendResult.Sent(0)
        }

        fun deliver(frame: CrewTransportFrame) {
            check(frames.trySend(frame).isSuccess)
        }

        override fun bufferedBytes(channel: CrewTransportChannel): Long = 0

        override fun close() {
            mutableState.value = CrewTransportState.CLOSED
        }
    }
}
