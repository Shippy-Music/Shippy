/*
 * Copyright (c) 2026 Shippy contributors
 * CrewReducerTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm

class CrewReducerTest {
    private val protocolVersion = ProtocolVersion(1)
    private val sessionId = CrewSessionId("crew", protocolVersion)
    private val coordinatorId = CrewMemberId("coordinator", protocolVersion)
    private val memberId = CrewMemberId("member", protocolVersion)
    private val reducer = CrewReducer()

    @Test
    fun `non-coordinator can replace insert move and remove queue items`() {
        var state = state()

        state =
            apply(
                state,
                event(
                    state,
                    1,
                    CrewAction.QueueReplaced(listOf(queueItem("one"), queueItem("two"))),
                ),
            )
        assertEquals(CrewPlaybackMode.PREPARING, state.playback.mode)
        state =
            apply(
                state,
                event(state, 2, CrewAction.QueueItemInserted(queueItem("three"), index = 1)),
            )
        state =
            apply(
                state,
                event(state, 3, CrewAction.QueueItemMoved(QueueItemId("one"), newIndex = 2)),
            )
        state =
            apply(
                state,
                event(state, 4, CrewAction.QueueItemRemoved(QueueItemId("three"))),
            )

        assertEquals(listOf(QueueItemId("two"), QueueItemId("one")), state.queue.map { it.id })
        assertEquals(EventSequence(4), state.lastSequence)
    }

    @Test
    fun `non-coordinator can play pause and seek`() {
        var state = state(queue = listOf(queueItem("one")))

        state = apply(state, event(state, 1, CrewAction.Play(100, 1_000)))
        assertEquals(CrewPlaybackMode.PLAYING, state.playback.mode)
        state = apply(state, event(state, 2, CrewAction.Seek(250, 1_100)))
        assertEquals(250L, state.playback.positionAtEpochMs)
        assertEquals(CrewPlaybackMode.PLAYING, state.playback.mode)
        state = apply(state, event(state, 3, CrewAction.Pause(300, 1_200)))

        assertEquals(CrewPlaybackMode.PAUSED, state.playback.mode)
        assertEquals(300L, state.playback.positionAtEpochMs)
    }

    @Test
    fun `duplicate event is rejected without changing state`() {
        val initial = state()
        val event = event(initial, 1, CrewAction.QueueReplaced(listOf(queueItem("one"))))
        val applied = apply(initial, event)

        val result = reducer.apply(applied, event, applied.coordinatorMemberId)

        assertTrue(result is CrewEventResult.DuplicateRejected)
        assertSame(applied, (result as CrewEventResult.DuplicateRejected).state)
    }

    @Test
    fun `event from stale coordinator term is rejected`() {
        val current = state(term = 2)
        val stale =
            event(
                current,
                sequence = 1,
                action = CrewAction.QueueReplaced(emptyList()),
                term = 1,
            )

        assertTrue(
            reducer.apply(current, stale, current.coordinatorMemberId) is
                CrewEventResult.StaleTermRejected
        )
    }

    @Test
    fun `sequence gap requires snapshot`() {
        val state = state()

        val result =
            reducer.apply(
                state,
                event(state, 2, CrewAction.QueueReplaced(listOf(queueItem("one")))),
                state.coordinatorMemberId,
            )

        assertTrue(result is CrewEventResult.SnapshotRequired)
        assertEquals(
            EventSequence(1),
            (result as CrewEventResult.SnapshotRequired).expectedSequence,
        )
    }

    @Test
    fun `new event id with old sequence is rejected`() {
        val initial = state()
        val applied =
            apply(
                initial,
                event(initial, 1, CrewAction.QueueReplaced(listOf(queueItem("one")))),
            )

        val result =
            reducer.apply(
                applied,
                event(applied, 1, CrewAction.QueueItemRemoved(QueueItemId("one"))),
                applied.coordinatorMemberId,
            )

        assertTrue(result is CrewEventResult.StaleSequenceRejected)
    }

    @Test
    fun `protocol mismatch is rejected`() {
        val state = state()
        val otherVersion = ProtocolVersion(2)
        val event =
            DurableCrewEvent(
                sessionId = CrewSessionId("crew", otherVersion),
                protocolVersion = otherVersion,
                term = state.term,
                sequence = EventSequence(1),
                id = DurableEventId("event"),
                publisherMemberId = coordinatorId,
                issuingMemberId = CrewMemberId("member", otherVersion),
                clientMonotonicTimestampMs = 1,
                action = CrewAction.QueueReplaced(emptyList()),
            )

        assertTrue(reducer.apply(state, event, coordinatorId) is CrewEventResult.Rejected)
    }

    @Test
    fun `member cannot forge a coordinator published event`() {
        val state = state()
        val forged =
            event(
                state,
                sequence = 1,
                action = CrewAction.ShuffleChanged(enabled = true),
                publisher = coordinatorId,
                issuer = memberId,
            )

        assertTrue(reducer.apply(state, forged, memberId) is CrewEventResult.Rejected)
    }

    @Test
    fun `authenticated non coordinator cannot publish an event`() {
        val state = state()
        val event =
            event(
                state,
                sequence = 1,
                action = CrewAction.ShuffleChanged(enabled = true),
                publisher = memberId,
            )

        assertTrue(reducer.apply(state, event, memberId) is CrewEventResult.Rejected)
    }

    @Test
    fun `coordinator transfer is rejected when transport publisher is not current coordinator`() {
        val state = state()
        val forged =
            event(
                state,
                sequence = 1,
                action = CrewAction.CoordinatorTransferred(memberId),
                term = state.term.next().value,
                publisher = memberId,
                issuer = coordinatorId,
            )

        assertTrue(reducer.apply(state, forged, memberId) is CrewEventResult.Rejected)
    }

    @Test
    fun `replacement rejects duplicate queue item IDs`() {
        val state = state()
        val duplicate = queueItem("same")

        val result =
            reducer.apply(
                state,
                event(state, 1, CrewAction.QueueReplaced(listOf(duplicate, duplicate))),
                state.coordinatorMemberId,
            )

        assertTrue(result is CrewEventResult.Rejected)
    }

    @Test
    fun `insert rejects queue item ID already in state`() {
        val state = state(queue = listOf(queueItem("one")))

        val result =
            reducer.apply(
                state,
                event(state, 1, CrewAction.QueueItemInserted(queueItem("one"), 1)),
                state.coordinatorMemberId,
            )

        assertTrue(result is CrewEventResult.Rejected)
    }

    @Test
    fun `removing current item selects next item and empty queue becomes idle`() {
        var state = state(queue = listOf(queueItem("one"), queueItem("two")))
        state = apply(state, event(state, 1, CrewAction.Play(0, 10)))

        state =
            apply(
                state,
                event(state, 2, CrewAction.QueueItemRemoved(QueueItemId("one"))),
            )
        assertEquals(QueueItemId("two"), state.playback.currentQueueItemId)
        assertEquals(CrewPlaybackMode.PREPARING, state.playback.mode)

        state =
            apply(
                state,
                event(state, 3, CrewAction.QueueItemRemoved(QueueItemId("two"))),
            )
        assertTrue(state.queue.isEmpty())
        assertEquals(CrewPlaybackState(), state.playback)
    }

    @Test
    fun `changing current item resets stale playback clock and prepares selection`() {
        var state = state(queue = listOf(queueItem("one"), queueItem("two")))
        state = apply(state, event(state, 1, CrewAction.Play(500, 1_000)))

        state =
            apply(
                state,
                event(state, 2, CrewAction.CurrentItemChanged(QueueItemId("two"))),
            )

        assertEquals(QueueItemId("two"), state.playback.currentQueueItemId)
        assertEquals(CrewPlaybackMode.PREPARING, state.playback.mode)
        assertEquals(0L, state.playback.positionAtEpochMs)
        assertEquals(0L, state.playback.sessionEpochMs)
    }

    @Test
    fun `current coordinator transfers to active member with new term`() {
        val state = state()
        val transfer =
            event(
                state,
                sequence = 1,
                action = CrewAction.CoordinatorTransferred(memberId),
                term = 2,
                issuer = coordinatorId,
            )

        val transferred = apply(state, transfer)

        assertEquals(CoordinatorTerm(2), transferred.term)
        assertEquals(EventSequence(1), transferred.lastSequence)
        assertEquals(memberId, transferred.coordinatorMemberId)
    }

    @Test
    fun `ordinary future-term event requires snapshot`() {
        val state = state()
        val event =
            event(
                state,
                sequence = 1,
                action = CrewAction.QueueReplaced(emptyList()),
                term = 2,
            )

        assertTrue(
            reducer.apply(state, event, state.coordinatorMemberId) is CrewEventResult.SnapshotRequired
        )
    }

    @Test
    fun `members can join update and leave after coordinator transfer`() {
        val newcomerId = CrewMemberId("newcomer", protocolVersion)
        var state = state()

        state =
            apply(
                state,
                event(state, 1, CrewAction.MemberJoined(CrewMember(newcomerId, "Newcomer"))),
            )
        state =
            apply(
                state,
                event(state, 2, CrewAction.MemberUpdated(CrewMember(newcomerId, "Updated name"))),
            )
        assertEquals("Updated name", state.members.single { it.id == newcomerId }.displayName)

        val blockedCoordinatorLeave =
            reducer.apply(
                state,
                event(state, 3, CrewAction.MemberLeft(coordinatorId)),
                state.coordinatorMemberId,
            )
        assertTrue(blockedCoordinatorLeave is CrewEventResult.Rejected)

        state =
            apply(
                state,
                event(
                    state,
                    sequence = 1,
                    action = CrewAction.CoordinatorTransferred(memberId),
                    term = state.term.next().value,
                    issuer = coordinatorId,
                ),
            )
        state =
            apply(
                state,
                event(
                    state,
                    sequence = 2,
                    action = CrewAction.MemberLeft(coordinatorId),
                    issuer = memberId,
                ),
            )

        assertEquals(memberId, state.coordinatorMemberId)
        assertFalse(state.members.any { it.id == coordinatorId })
        assertTrue(state.members.any { it.id == newcomerId })
    }

    @Test
    fun `equal members can synchronize shuffle and repeat`() {
        var state = state()

        state = apply(state, event(state, 1, CrewAction.ShuffleChanged(enabled = true)))
        state = apply(state, event(state, 2, CrewAction.RepeatChanged(CrewRepeatMode.ALL)))

        assertTrue(state.shuffleEnabled)
        assertEquals(CrewRepeatMode.ALL, state.repeatMode)
    }

    @Test
    fun `sequence gap recovers only through a newer authoritative snapshot`() {
        val initial = state()
        val gap = event(initial, 2, CrewAction.ShuffleChanged(enabled = true))
        assertTrue(
            reducer.apply(initial, gap, initial.coordinatorMemberId) is CrewEventResult.SnapshotRequired
        )

        val snapshot =
            initial
                .toSnapshot()
                .copy(
                    lastSequence = EventSequence(2),
                    queue = listOf(queueItem("one")),
                    playback =
                        CrewPlaybackState(
                            currentQueueItemId = QueueItemId("one"),
                            mode = CrewPlaybackMode.PAUSED,
                            positionAtEpochMs = 42,
                            sessionEpochMs = 100,
                        ),
                    shuffleEnabled = true,
                )
        val recoveredResult =
            reducer.applySnapshot(
                initial,
                snapshot,
                initial.coordinatorMemberId,
            )
        assertTrue(recoveredResult is CrewSnapshotResult.Applied)
        var recovered = (recoveredResult as CrewSnapshotResult.Applied).state
        assertTrue(recovered.appliedEventIds.isEmpty())
        assertEquals(EventSequence(2), recovered.lastSequence)
        assertTrue(recovered.shuffleEnabled)

        recovered = apply(recovered, event(recovered, 3, CrewAction.RepeatChanged(CrewRepeatMode.ONE)))
        assertEquals(CrewRepeatMode.ONE, recovered.repeatMode)
    }

    @Test
    fun `equal or unsupported snapshots are rejected`() {
        val current = apply(state(), event(state(), 1, CrewAction.ShuffleChanged(enabled = true)))

        assertTrue(
            reducer.applySnapshot(
                current,
                current.toSnapshot(),
                current.coordinatorMemberId,
            ) is CrewSnapshotResult.StaleRejected
        )
        assertTrue(
            reducer.applySnapshot(
                current,
                current.toSnapshot().copy(snapshotVersion = CrewSnapshotVersion(2)),
                current.coordinatorMemberId,
            ) is CrewSnapshotResult.Rejected
        )
    }

    @Test
    fun `same term snapshot must be published by the current coordinator`() {
        val current = state()
        val forged =
            current
                .toSnapshot()
                .copy(lastSequence = EventSequence(1), publisherMemberId = memberId)

        assertTrue(
            reducer.applySnapshot(
                current,
                forged,
                memberId,
            ) is CrewSnapshotResult.Rejected
        )
        assertTrue(
            reducer.applySnapshot(
                current,
                current.toSnapshot().copy(lastSequence = EventSequence(1)),
                memberId,
            ) is CrewSnapshotResult.Rejected
        )
        assertTrue(
            reducer.applySnapshot(
                current,
                current
                    .toSnapshot()
                    .copy(
                        lastSequence = EventSequence(1),
                        coordinatorMemberId = memberId,
                    ),
                current.coordinatorMemberId,
            ) is CrewSnapshotResult.Rejected
        )
    }

    @Test
    fun `higher term snapshot elects stable eligible winner after coordinator loss`() {
        val newcomerId = CrewMemberId("newcomer", protocolVersion)
        var state = state()
        state =
            apply(
                state,
                event(state, 1, CrewAction.MemberJoined(CrewMember(newcomerId, "Newcomer"))),
            )
        val electionVotes =
            listOf(
                electionVote(state, voter = memberId, candidate = memberId),
                electionVote(state, voter = newcomerId, candidate = memberId),
            )
        val electedSnapshot =
            state
                .toSnapshot()
                .copy(
                    term = state.term.next(),
                    lastSequence = EventSequence(1),
                    publisherMemberId = memberId,
                    coordinatorMemberId = memberId,
                    members =
                        listOf(
                            CrewMember(memberId, "Member"),
                            CrewMember(newcomerId, "Newcomer"),
                        ),
                )

        val elected = reducer.applySnapshot(state, electedSnapshot, memberId, electionVotes)

        assertTrue(elected is CrewSnapshotResult.Applied)
        assertEquals(memberId, (elected as CrewSnapshotResult.Applied).state.coordinatorMemberId)

        val forgedWinner =
            electedSnapshot.copy(
                publisherMemberId = newcomerId,
                coordinatorMemberId = newcomerId,
            )
        assertTrue(
            reducer.applySnapshot(state, forgedWinner, newcomerId, electionVotes) is
                CrewSnapshotResult.Rejected
        )
        assertTrue(
            reducer.applySnapshot(
                state,
                electedSnapshot,
                memberId,
                listOf(
                    electionVote(state, voter = coordinatorId, candidate = memberId),
                    electionVote(state, voter = memberId, candidate = memberId),
                ),
            ) is CrewSnapshotResult.Rejected
        )
        assertTrue(
            reducer.applySnapshot(
                state,
                electedSnapshot.copy(
                    publisherMemberId = newcomerId,
                    coordinatorMemberId = newcomerId,
                ),
                newcomerId,
                listOf(electionVote(state, voter = newcomerId, candidate = newcomerId)),
            ) is CrewSnapshotResult.Rejected
        )
        val otherSession = CrewSessionId("other-crew", protocolVersion)
        assertTrue(
            reducer.applySnapshot(
                state,
                electedSnapshot,
                memberId,
                electionVotes.map {
                    it.copy(checkpoint = it.checkpoint.copy(sessionId = otherSession))
                },
            ) is CrewSnapshotResult.Rejected
        )
    }

    @Test
    fun `two member Crew waits for coordinator or relay witness after ungraceful loss`() {
        val current = state()
        val survivorSnapshot =
            current
                .toSnapshot()
                .copy(
                    term = current.term.next(),
                    lastSequence = EventSequence(1),
                    publisherMemberId = memberId,
                    coordinatorMemberId = memberId,
                    members = listOf(CrewMember(memberId, "Member")),
                )

        assertTrue(
            reducer.applySnapshot(
                current,
                survivorSnapshot,
                memberId,
                listOf(electionVote(current, voter = memberId, candidate = memberId)),
            ) is CrewSnapshotResult.Rejected
        )
    }

    @Test
    fun `snapshot validation rejects an invalid authoritative state`() {
        val current = state()
        val duplicate = queueItem("duplicate")
        val invalid =
            current.toSnapshot().copy(
                lastSequence = EventSequence(1),
                queue = listOf(duplicate, duplicate),
                playback = CrewPlaybackState(currentQueueItemId = duplicate.id),
            )

        assertTrue(
            reducer.applySnapshot(
                current,
                invalid,
                current.coordinatorMemberId,
            ) is CrewSnapshotResult.Rejected
        )
    }

    @Test
    fun `recent event ID retention is bounded while sequence remains authoritative`() {
        var state = state()

        repeat(CrewState.MAX_APPLIED_EVENT_IDS + 1) { offset ->
            val sequence = offset + 1L
            state = apply(state, event(state, sequence, CrewAction.ShuffleChanged(offset % 2 == 0)))
        }

        assertEquals(CrewState.MAX_APPLIED_EVENT_IDS, state.appliedEventIds.size)
        assertFalse(DurableEventId("event-1-1") in state.appliedEventIds)
        assertTrue(
            DurableEventId("event-1-${CrewState.MAX_APPLIED_EVENT_IDS + 1}") in
                state.appliedEventIds
        )
        assertEquals(EventSequence((CrewState.MAX_APPLIED_EVENT_IDS + 1).toLong()), state.lastSequence)
        assertTrue(
            reducer.apply(
                state,
                event(state, 1, CrewAction.ShuffleChanged(enabled = false)),
                state.coordinatorMemberId,
            )
                is CrewEventResult.StaleSequenceRejected
        )
    }

    private fun state(
        term: Long = 1,
        queue: List<QueueItem> = emptyList(),
    ) =
        CrewState(
            sessionId = sessionId,
            protocolVersion = protocolVersion,
            term = CoordinatorTerm(term),
            lastSequence = EventSequence(0),
            coordinatorMemberId = coordinatorId,
            members =
                listOf(
                    CrewMember(coordinatorId, "Coordinator"),
                    CrewMember(memberId, "Member"),
                ),
            queue = queue,
            playback =
                if (queue.isEmpty()) {
                    CrewPlaybackState()
                } else {
                    CrewPlaybackState(currentQueueItemId = queue.first().id)
                },
        )

    private fun event(
        state: CrewState,
        sequence: Long,
        action: CrewAction,
        term: Long = state.term.value,
        publisher: CrewMemberId = state.coordinatorMemberId,
        issuer: CrewMemberId = memberId,
    ) =
        DurableCrewEvent(
            sessionId = sessionId,
            protocolVersion = protocolVersion,
            term = CoordinatorTerm(term),
            sequence = EventSequence(sequence),
            id = DurableEventId("event-$term-$sequence"),
            publisherMemberId = publisher,
            issuingMemberId = issuer,
            clientMonotonicTimestampMs = sequence,
            action = action,
        )

    private fun apply(
        state: CrewState,
        event: DurableCrewEvent,
    ): CrewState {
        val result = reducer.apply(state, event, state.coordinatorMemberId)
        assertTrue("Expected applied result but was $result", result is CrewEventResult.Applied)
        return (result as CrewEventResult.Applied).state
    }

    private fun electionVote(
        state: CrewState,
        voter: CrewMemberId,
        candidate: CrewMemberId,
    ) =
        CrewElectionVote(
            voterMemberId = voter,
            candidateMemberId = candidate,
            checkpoint = state.toElectionCheckpoint(),
        )

    private fun queueItem(id: String): QueueItem {
        val trackId = TrackId("track-$id")
        return QueueItem(
            id = QueueItemId(id),
            track =
                Track(
                    id = trackId,
                    realm = TrackRealm.PROVIDER,
                    title = "Track $id",
                    artists = listOf("Artist"),
                    candidates = emptyList(),
                ),
            contributorId = memberId.value,
        )
    }
}
