/*
 * Copyright (c) 2026 Shippy contributors
 * CrewActiveMediaRuntimeTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.media

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.cache.CrewTemporaryMediaIndex
import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewPlaybackState
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.MediaDescriptor
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm

class CrewActiveMediaRuntimeTest {
    @Test
    fun `local scheduler uses coordinator redistribution and skips verified media`() {
        val coordinator = member("coordinator")
        val contributor = member("contributor")
        val joiner = member("joiner")
        val item = localItem("one", contributor)
        val state =
            CrewState(
                session("crew"),
                ProtocolVersion(1),
                CoordinatorTerm(1),
                EventSequence(0),
                coordinator,
                listOf(
                    CrewMember(coordinator, "Coordinator"),
                    CrewMember(contributor, "Contributor"),
                    CrewMember(joiner, "Joiner"),
                ),
                queue = listOf(item),
                playback = CrewPlaybackState(currentQueueItemId = item.id),
            )
        val key = CrewLocalMediaKey(item.id, item.track.candidates.single().id)

        assertEquals(
            listOf(CrewLocalMediaDesired(key, contributor)),
            planLocalMedia(state, coordinator, pushPullEnabled = true).desired,
        )
        assertEquals(
            listOf(CrewLocalMediaDesired(key, coordinator)),
            planLocalMedia(state, joiner, pushPullEnabled = true).desired,
        )
        assertTrue(
            planLocalMedia(
                state,
                joiner,
                pushPullEnabled = true,
                available = setOf(key),
            ).desired.isEmpty()
        )
    }

    @Test
    fun `disabled push pull blocks only missing current exact local item`() {
        val coordinator = member("coordinator")
        val contributor = member("contributor")
        val current = localItem("current", contributor)
        val next = localItem("next", contributor)
        val state =
            CrewState(
                session("crew"),
                ProtocolVersion(1),
                CoordinatorTerm(1),
                EventSequence(0),
                coordinator,
                listOf(
                    CrewMember(coordinator, "Coordinator"),
                    CrewMember(contributor, "Contributor"),
                ),
                queue = listOf(current, next),
                playback = CrewPlaybackState(currentQueueItemId = current.id),
            )

        val plan = planLocalMedia(state, coordinator, pushPullEnabled = false)
        assertTrue(plan.blockedCurrent)
        assertTrue(plan.desired.isEmpty())
    }

    @Test
    fun `exact available local candidate is selected`() {
        val fixture = Fixture(CandidateKind.LOCAL, "content://media/local", 7)
        assertTrue(fixture.select() is CrewActiveMediaSelector.Selection.Content)
    }

    @Test
    fun `verified download supplies the original requested provider candidate`() {
        val fixture = Fixture(CandidateKind.PROVIDER, null, null)
        val downloads = mapOf(
            CrewActiveMediaSelector.DownloadKey(fixture.item.track.id, fixture.candidate.id) to
                CrewActiveMediaSelector.DownloadSource("content://downloads/audio", 7, "audio/test"),
        )
        val selected = fixture.select(downloads = downloads)
        assertEquals("content://downloads/audio", (selected as CrewActiveMediaSelector.Selection.Content).uri)
    }

    @Test
    fun `verified temporary media wins and active index clears exactly`() {
        val fixture = Fixture(CandidateKind.LOCAL, "content://media/local", 7)
        val file = File.createTempFile("crew-runtime", ".media").also { it.writeBytes(ByteArray(7)) }
        fixture.index.beginSession(fixture.session)
        assertTrue(fixture.index.complete(fixture.manifest(), file))
        assertTrue(fixture.select() is CrewActiveMediaSelector.Selection.Temporary)
        assertTrue(fixture.index.augmentActive(fixture.item) != null)
        fixture.index.endSession(fixture.session)
        assertNull(fixture.index.findActive(fixture.session, fixture.item.id, fixture.candidate.id))
        assertNull(fixture.index.augmentActive(fixture.item))
    }

    @Test
    fun `wrong transfer identity and unsafe candidates are rejected`() {
        val fixture = Fixture(CandidateKind.PROVIDER, "https://provider/audio", 7)
        assertNull(fixture.select())
        assertNull(fixture.select(requester = member("outsider")))
        assertNull(fixture.select(transfer = fixture.transfer.copy(sessionId = session("other"))))
        assertNull(fixture.select(transfer = fixture.transfer.copy(candidateId = CandidateId("other"))))
        assertNull(Fixture(CandidateKind.LOCAL, "content://media/local", CREW_MEDIA_MAX_OBJECT_BYTES + 1).select())
        assertNull(Fixture(CandidateKind.CREW_PEER, "content://peer/audio", 7).select())
    }

    private class Fixture(kind: CandidateKind, locator: String?, length: Long?) {
        val session = session("crew")
        val local = member("local")
        private val remote = member("remote")
        val candidate = TrackCandidate(CandidateId("candidate"), TrackId("track"), kind, "source", "item", CandidateAvailability.AVAILABLE, locator = locator, media = MediaDescriptor("audio/test", contentLength = length))
        val item = QueueItem(QueueItemId("queue"), Track(TrackId("track"), TrackRealm.PROVIDER, "Track", listOf("Artist"), candidates = listOf(candidate)))
        val index = CrewTemporaryMediaIndex()
        val transfer = CrewMediaTransferRef(session, CrewMediaRequestId("request"), item.id, candidate.id, remote, local)
        private val state = CrewState(session, ProtocolVersion(1), CoordinatorTerm(1), EventSequence(0), local, listOf(CrewMember(local, "Local"), CrewMember(remote, "Remote")), queue = listOf(item))

        fun select(
            downloads: Map<CrewActiveMediaSelector.DownloadKey, CrewActiveMediaSelector.DownloadSource> = emptyMap(),
            requester: CrewMemberId = remote,
            transfer: CrewMediaTransferRef = this.transfer,
        ) = CrewActiveMediaSelector.select(session, local, state, transfer, requester, index, downloads)

        fun manifest(): CrewMediaManifest {
            val bytes = ByteArray(7)
            val digest = CrewMediaDigest.sha256(bytes)
            return CrewMediaManifest(transfer, "audio/test", 7, digest, listOf(CrewMediaChunkDescriptor(0, 7, digest)))
        }
    }

    private companion object {
        fun session(value: String) = CrewSessionId(value, ProtocolVersion(1))
        fun member(value: String) = CrewMemberId(value, ProtocolVersion(1))

        fun localItem(value: String, contributor: CrewMemberId): QueueItem {
            val trackId = TrackId("track-$value")
            return QueueItem(
                QueueItemId("queue-$value"),
                Track(
                    trackId,
                    TrackRealm.LOCAL,
                    value,
                    listOf("Artist"),
                    candidates =
                        listOf(
                            TrackCandidate(
                                CandidateId("candidate-$value"),
                                trackId,
                                CandidateKind.LOCAL,
                                "local",
                                value,
                                CandidateAvailability.AVAILABLE,
                                locator = "content://local/$value",
                                media = MediaDescriptor("audio/test", contentLength = 7),
                            )
                        ),
                ),
                contributorId = contributor.value,
            )
        }
    }
}
