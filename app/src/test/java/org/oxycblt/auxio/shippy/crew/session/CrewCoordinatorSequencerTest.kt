/*
 * Copyright (c) 2026 Shippy contributors
 * CrewCoordinatorSequencerTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.DurableEventId
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm

class CrewCoordinatorSequencerTest {
    @Test
    fun `coordinator orders requests from equal active members`() {
        val fixture = Fixture()
        val sequencer = CrewCoordinatorSequencer(fixture.state, fixture.coordinatorId)

        val first =
            sequencer.sequence(
                fixture.request(
                    "request-1",
                    fixture.memberId,
                    CrewAction.QueueReplaced(listOf(fixture.queueItem)),
                ),
                fixture.memberId,
            )
        val second =
            sequencer.sequence(
                fixture.request(
                    "request-2",
                    fixture.memberId,
                    CrewAction.CurrentItemChanged(fixture.queueItem.id),
                ),
                fixture.memberId,
            )

        assertTrue(first is CrewSequenceResult.Published)
        assertTrue(second is CrewSequenceResult.Published)
        first as CrewSequenceResult.Published
        second as CrewSequenceResult.Published
        assertEquals(fixture.coordinatorId, first.event.publisherMemberId)
        assertEquals(fixture.memberId, first.event.issuingMemberId)
        assertEquals(EventSequence(1), first.event.sequence)
        assertEquals(EventSequence(2), second.event.sequence)
        assertEquals(fixture.queueItem.id, second.state.playback.currentQueueItemId)
    }

    @Test
    fun `duplicate request returns the original event without advancing state`() {
        val fixture = Fixture()
        val sequencer = CrewCoordinatorSequencer(fixture.state, fixture.coordinatorId)
        val request =
            fixture.request(
                "same-request",
                fixture.memberId,
                CrewAction.QueueReplaced(listOf(fixture.queueItem)),
            )

        val published = sequencer.sequence(request, fixture.memberId)
        val duplicate = sequencer.sequence(request, fixture.memberId)

        assertTrue(published is CrewSequenceResult.Published)
        assertTrue(duplicate is CrewSequenceResult.Duplicate)
        published as CrewSequenceResult.Published
        duplicate as CrewSequenceResult.Duplicate
        assertEquals(published.event, duplicate.event)
        assertEquals(EventSequence(1), duplicate.state.lastSequence)
    }

    @Test
    fun `rejected action does not consume a sequence number`() {
        val fixture = Fixture()
        val sequencer = CrewCoordinatorSequencer(fixture.state, fixture.coordinatorId)

        val rejected =
            sequencer.sequence(
                fixture.request(
                    "missing",
                    fixture.memberId,
                    CrewAction.CurrentItemChanged(QueueItemId("missing")),
                ),
                fixture.memberId,
            )
        val accepted =
            sequencer.sequence(
                fixture.request(
                    "valid",
                    fixture.memberId,
                    CrewAction.QueueReplaced(listOf(fixture.queueItem)),
                ),
                fixture.memberId,
            )

        assertEquals(
            CrewSequenceResult.Rejected(
                CrewSequenceRejection.ACTION_REJECTED,
                fixture.state,
            ),
            rejected,
        )
        assertTrue(accepted is CrewSequenceResult.Published)
        assertEquals(EventSequence(1), (accepted as CrewSequenceResult.Published).event.sequence)
    }

    @Test
    fun `authenticated requester must match the request issuer`() {
        val fixture = Fixture()
        val sequencer = CrewCoordinatorSequencer(fixture.state, fixture.coordinatorId)

        val result =
            sequencer.sequence(
                fixture.request(
                    "forged",
                    fixture.memberId,
                    CrewAction.QueueReplaced(listOf(fixture.queueItem)),
                ),
                fixture.coordinatorId,
            )

        assertEquals(
            CrewSequenceResult.Rejected(
                CrewSequenceRejection.REQUESTER_MISMATCH,
                fixture.state,
            ),
            result,
        )
    }

    @Test
    fun `ordinary member cannot admit or remove another member`() {
        val fixture = Fixture()
        val sequencer = CrewCoordinatorSequencer(fixture.state, fixture.coordinatorId)
        val outsider = CrewMember(CrewMemberId("outsider", fixture.memberId.protocolVersion), "Outsider")

        val admit =
            sequencer.sequence(
                fixture.request(
                    "admit",
                    fixture.memberId,
                    CrewAction.MemberJoined(outsider),
                ),
                fixture.memberId,
            )
        val remove =
            sequencer.sequence(
                fixture.request(
                    "remove",
                    fixture.memberId,
                    CrewAction.MemberLeft(fixture.coordinatorId),
                ),
                fixture.memberId,
            )

        assertTrue(admit is CrewSequenceResult.Rejected)
        assertTrue(remove is CrewSequenceResult.Rejected)
        assertEquals(EventSequence(0), sequencer.state().lastSequence)
    }

    @Test
    fun `restored replay id is not fabricated into a new event receipt`() {
        val fixture = Fixture()
        val replayId = DurableEventId("restored-request")
        val restored = fixture.state.copy(appliedEventIds = listOf(replayId))
        val sequencer = CrewCoordinatorSequencer(restored, fixture.coordinatorId)

        val result =
            sequencer.sequence(
                CrewActionRequest(
                    replayId,
                    fixture.memberId,
                    100,
                    CrewAction.ShuffleChanged(true),
                ),
                fixture.memberId,
            )

        assertEquals(
            CrewSequenceResult.Rejected(
                CrewSequenceRejection.DUPLICATE_RECEIPT_UNAVAILABLE,
                restored,
            ),
            result,
        )
        assertEquals(EventSequence(0), sequencer.state().lastSequence)
    }

    @Test
    fun `old coordinator stops publishing immediately after transfer`() {
        val fixture = Fixture()
        val sequencer = CrewCoordinatorSequencer(fixture.state, fixture.coordinatorId)

        val transfer =
            sequencer.sequence(
                fixture.request(
                    "transfer",
                    fixture.coordinatorId,
                    CrewAction.CoordinatorTransferred(fixture.memberId),
                ),
                fixture.coordinatorId,
            )
        assertTrue(transfer is CrewSequenceResult.Published)
        transfer as CrewSequenceResult.Published
        assertEquals(CoordinatorTerm(2), transfer.state.term)
        assertEquals(EventSequence(1), transfer.state.lastSequence)
        assertEquals(fixture.memberId, transfer.state.coordinatorMemberId)

        val transferRetry =
            sequencer.sequence(
                fixture.request(
                    "transfer",
                    fixture.coordinatorId,
                    CrewAction.CoordinatorTransferred(fixture.memberId),
                ),
                fixture.coordinatorId,
            )
        assertTrue(transferRetry is CrewSequenceResult.Duplicate)

        val afterTransfer =
            sequencer.sequence(
                fixture.request(
                    "stale-coordinator",
                    fixture.coordinatorId,
                    CrewAction.ShuffleChanged(true),
                ),
                fixture.coordinatorId,
            )
        assertEquals(
            CrewSequenceResult.Rejected(
                CrewSequenceRejection.NOT_COORDINATOR,
                transfer.state,
            ),
            afterTransfer,
        )
    }

    private class Fixture {
        private val protocol = ProtocolVersion(1)
        private val sessionId = CrewSessionId("session", protocol)
        val coordinatorId = CrewMemberId("coordinator", protocol)
        val memberId = CrewMemberId("member", protocol)
        val state =
            CrewState(
                sessionId,
                protocol,
                CoordinatorTerm(1),
                EventSequence(0),
                coordinatorId,
                listOf(
                    CrewMember(coordinatorId, "Coordinator"),
                    CrewMember(memberId, "Member"),
                ),
            )
        val queueItem =
            QueueItem(
                QueueItemId("queue-item"),
                Track(
                    TrackId("track"),
                    TrackRealm.LOCAL,
                    "Track",
                    listOf("Artist"),
                    candidates =
                        listOf(
                            TrackCandidate(
                                CandidateId("candidate"),
                                TrackId("track"),
                                CandidateKind.LOCAL,
                                "local",
                                "song",
                                CandidateAvailability.AVAILABLE,
                            ),
                        ),
                ),
                contributorId = memberId.value,
            )

        fun request(
            id: String,
            issuer: CrewMemberId,
            action: CrewAction,
        ) = CrewActionRequest(
            DurableEventId(id),
            issuer,
            100,
            action,
        )
    }
}
