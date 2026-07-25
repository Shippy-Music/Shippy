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

        val result = reducer.apply(applied, event)

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

        assertTrue(reducer.apply(current, stale) is CrewEventResult.StaleTermRejected)
    }

    @Test
    fun `sequence gap requires snapshot`() {
        val state = state()

        val result =
            reducer.apply(
                state,
                event(state, 2, CrewAction.QueueReplaced(listOf(queueItem("one")))),
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
                issuingMemberId = CrewMemberId("member", otherVersion),
                clientMonotonicTimestampMs = 1,
                action = CrewAction.QueueReplaced(emptyList()),
            )

        assertTrue(reducer.apply(state, event) is CrewEventResult.Rejected)
    }

    @Test
    fun `replacement rejects duplicate queue item IDs`() {
        val state = state()
        val duplicate = queueItem("same")

        val result =
            reducer.apply(
                state,
                event(state, 1, CrewAction.QueueReplaced(listOf(duplicate, duplicate))),
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

        assertTrue(reducer.apply(state, event) is CrewEventResult.SnapshotRequired)
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
        issuer: CrewMemberId = memberId,
    ) =
        DurableCrewEvent(
            sessionId = sessionId,
            protocolVersion = protocolVersion,
            term = CoordinatorTerm(term),
            sequence = EventSequence(sequence),
            id = DurableEventId("event-$term-$sequence"),
            issuingMemberId = issuer,
            clientMonotonicTimestampMs = sequence,
            action = action,
        )

    private fun apply(
        state: CrewState,
        event: DurableCrewEvent,
    ): CrewState {
        val result = reducer.apply(state, event)
        assertTrue("Expected applied result but was $result", result is CrewEventResult.Applied)
        return (result as CrewEventResult.Applied).state
    }

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
