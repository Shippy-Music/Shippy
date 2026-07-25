/*
 * Copyright (c) 2026 Shippy contributors
 * CrewControlProtocolTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.CrewElectionVote
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewPlaybackMode
import org.oxycblt.auxio.shippy.crew.core.CrewPlaybackState
import org.oxycblt.auxio.shippy.crew.core.CrewRepeatMode
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.DurableCrewEvent
import org.oxycblt.auxio.shippy.crew.core.DurableEventId
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.core.toElectionCheckpoint
import org.oxycblt.auxio.shippy.crew.core.toSnapshot
import org.oxycblt.auxio.shippy.crew.session.CrewActionRequest
import org.oxycblt.auxio.shippy.crew.session.CrewSequenceRejection
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.MediaDescriptor
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.domain.TrackVersion

class CrewControlProtocolTest {
    private val protocol = ProtocolVersion(1)
    private val sessionId = CrewSessionId("session", protocol)
    private val coordinatorId = CrewMemberId("coordinator", protocol)
    private val memberId = CrewMemberId("member", protocol)

    @Test
    fun `every durable action round trips in requests and events`() {
        val item = queueItem("one")
        val actions =
            listOf(
                CrewAction.MemberJoined(CrewMember(memberId, "Member")),
                CrewAction.MemberUpdated(CrewMember(memberId, "Renamed")),
                CrewAction.MemberLeft(memberId),
                CrewAction.QueueReplaced(listOf(item, queueItem("two"))),
                CrewAction.QueueItemInserted(item, 1),
                CrewAction.QueueItemMoved(item.id, 2),
                CrewAction.QueueItemRemoved(item.id),
                CrewAction.CurrentItemChanged(item.id),
                CrewAction.Play(200, 1_000),
                CrewAction.Pause(300, 1_100),
                CrewAction.Seek(400, 1_200),
                CrewAction.ShuffleChanged(true),
                CrewAction.RepeatChanged(CrewRepeatMode.ONE),
                CrewAction.CoordinatorTransferred(memberId),
            )

        actions.forEachIndexed { index, action ->
            val request =
                CrewActionRequest(
                    id = DurableEventId("request-$index"),
                    issuingMemberId = memberId,
                    clientMonotonicTimestampMs = index.toLong(),
                    action = action,
                )
            assertRoundTrip(CrewControlMessage.Request(request))

            val event =
                DurableCrewEvent(
                    sessionId = sessionId,
                    protocolVersion = protocol,
                    term = CoordinatorTerm(1),
                    sequence = EventSequence(index + 1L),
                    id = request.id,
                    publisherMemberId = coordinatorId,
                    issuingMemberId = memberId,
                    clientMonotonicTimestampMs = index.toLong(),
                    action = action,
                )
            assertRoundTrip(CrewControlMessage.Event(event))
        }
    }

    @Test
    fun `snapshot election request and rejection messages round trip`() {
        val state =
            org.oxycblt.auxio.shippy.crew.core.CrewState(
                sessionId = sessionId,
                protocolVersion = protocol,
                term = CoordinatorTerm(2),
                lastSequence = EventSequence(9),
                coordinatorMemberId = coordinatorId,
                members =
                    listOf(
                        CrewMember(coordinatorId, "Coordinator"),
                        CrewMember(memberId, "Member"),
                    ),
                queue = listOf(queueItem("one")),
                playback =
                    CrewPlaybackState(
                        currentQueueItemId = QueueItemId("queue-one"),
                        mode = CrewPlaybackMode.PLAYING,
                        positionAtEpochMs = 800,
                        sessionEpochMs = 4_000,
                    ),
                shuffleEnabled = true,
                repeatMode = CrewRepeatMode.ALL,
            )
        val checkpoint = state.toElectionCheckpoint()
        val votes =
            listOf(
                CrewElectionVote(coordinatorId, memberId, checkpoint),
                CrewElectionVote(memberId, memberId, checkpoint),
            )

        assertRoundTrip(CrewControlMessage.SnapshotInstalled(state.toSnapshot(), votes))
        assertRoundTrip(CrewControlMessage.ElectionVoteCast(votes.last()))
        assertRoundTrip(
            CrewControlMessage.SnapshotRequested(
                CrewSnapshotRequest(
                    sessionId = sessionId,
                    protocolVersion = protocol,
                    requestingMemberId = memberId,
                    knownTerm = state.term,
                    knownSequence = state.lastSequence,
                )
            )
        )
        CrewSequenceRejection.entries.forEach { reason ->
            assertRoundTrip(
                CrewControlMessage.RequestRejected(
                    sessionId = sessionId,
                    protocolVersion = protocol,
                    requestId = DurableEventId("rejected-${reason.name}"),
                    coordinatorMemberId = coordinatorId,
                    reason = reason,
                )
            )
        }
    }

    @Test
    fun `large playlist message is chunked and reassembled out of order`() {
        val items = List(700) { queueItem("item-$it") }
        val message =
            CrewControlMessage.Request(
                CrewActionRequest(
                    id = DurableEventId("large-playlist"),
                    issuingMemberId = memberId,
                    clientMonotonicTimestampMs = 10,
                    action = CrewAction.QueueReplaced(items),
                )
            )
        val frames = CrewControlFramer.encode(message)
        assertTrue(frames.size > 1)

        val reassembler = CrewControlReassembler()
        var result: CrewControlFrameResult? = null
        frames.reversed().forEachIndexed { index, frame ->
            result = reassembler.accept(frame, index.toLong())
        }

        assertEquals(CrewControlFrameResult.Complete(message), result)
    }

    @Test
    fun `frame integrity corruption is rejected`() {
        val message =
            CrewControlMessage.Request(
                CrewActionRequest(
                    id = DurableEventId("request"),
                    issuingMemberId = memberId,
                    clientMonotonicTimestampMs = 1,
                    action = CrewAction.ShuffleChanged(true),
                )
            )
        val bytes = CrewControlFramer.encode(message).single().copyPayload()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()

        assertEquals(
            CrewControlFrameResult.Rejected.INTEGRITY_FAILED,
            CrewControlReassembler()
                .accept(
                    CrewTransportFrame(CrewTransportChannel.CONTROL, bytes),
                    nowMonotonicMs = 0,
                ),
        )
    }

    @Test
    fun `codec rejects trailing bytes malformed payloads and unsupported versions`() {
        val message =
            CrewControlMessage.RequestRejected(
                sessionId = sessionId,
                protocolVersion = protocol,
                requestId = DurableEventId("request"),
                coordinatorMemberId = coordinatorId,
                reason = CrewSequenceRejection.ACTION_REJECTED,
            )
        val encoded = CrewControlCodec.encode(message)

        assertEquals(
            CrewControlDecodeResult.Rejected.MALFORMED,
            CrewControlCodec.decode(encoded + byteArrayOf(1)),
        )
        assertEquals(
            CrewControlDecodeResult.Rejected.UNSUPPORTED_FORMAT,
            CrewControlCodec.decode(encoded.copyOf().also { it[4] = 99.toByte() }),
        )
        assertEquals(
            CrewControlDecodeResult.Rejected.MALFORMED,
            CrewControlCodec.decode(byteArrayOf(1, 2, 3)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized metadata is rejected before framing`() {
        val item =
            queueItem("oversized").copy(
                track = queueItem("oversized").track.copy(title = "x".repeat(16 * 1024 + 1))
            )
        CrewControlCodec.encode(
            CrewControlMessage.Request(
                CrewActionRequest(
                    id = DurableEventId("oversized"),
                    issuingMemberId = memberId,
                    clientMonotonicTimestampMs = 1,
                    action = CrewAction.QueueReplaced(listOf(item)),
                )
            )
        )
    }

    private fun assertRoundTrip(message: CrewControlMessage) {
        assertEquals(
            CrewControlDecodeResult.Accepted(message),
            CrewControlCodec.decode(CrewControlCodec.encode(message)),
        )
    }

    private fun queueItem(suffix: String): QueueItem {
        val trackId = TrackId("track-$suffix")
        return QueueItem(
            id = QueueItemId("queue-$suffix"),
            track =
                Track(
                    id = trackId,
                    realm = TrackRealm.PROVIDER,
                    title = "Title $suffix",
                    artists = listOf("Artist", "Guest"),
                    album = "Album",
                    durationMs = 180_000,
                    version =
                        TrackVersion(
                            label = "Deluxe",
                            explicit = false,
                            isLive = false,
                            isRemix = true,
                        ),
                    artwork = "https://example.test/art/$suffix",
                    candidates =
                        listOf(
                            TrackCandidate(
                                id = CandidateId("candidate-$suffix"),
                                trackId = trackId,
                                kind = CandidateKind.PROVIDER,
                                sourceId = "jiosaavn",
                                sourceItemId = "source-$suffix",
                                availability = CandidateAvailability.RESOLVABLE,
                                locator = "https://example.test/song/$suffix",
                                providerId = ProviderId("jiosaavn"),
                                media =
                                    MediaDescriptor(
                                        mimeType = "audio/mp4",
                                        container = "m4a",
                                        bitrateBps = 320_000,
                                        contentLength = 5_000_000,
                                    ),
                            )
                        ),
                ),
            contextId = "playlist",
            contributorId = memberId.value,
        )
    }
}
