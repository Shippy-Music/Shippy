/*
 * Copyright (c) 2026 Auxio Project
 * CrewTemporaryMediaIndexTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.cache

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.media.CrewMediaChunkDescriptor
import org.oxycblt.auxio.shippy.crew.media.CrewMediaDigest
import org.oxycblt.auxio.shippy.crew.media.CrewMediaManifest
import org.oxycblt.auxio.shippy.crew.media.CrewMediaRequestId
import org.oxycblt.auxio.shippy.crew.media.CrewMediaTransferRef
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm

class CrewTemporaryMediaIndexTest {
    @Test
    fun `index is session scoped augments locally and clears without changing canonical item`() {
        val session = session("one")
        val item = queueItem("queue-1")
        val index = CrewTemporaryMediaIndex()
        val file = temporaryFile()
        val manifest = manifest(session, item.id, CandidateId("provider"))

        index.beginSession(session)
        assertTrue(index.complete(manifest, file))
        val augmented = requireNotNull(index.augment(session, item))
        assertEquals(item.track.id, augmented.track.id)
        assertEquals(
            1,
            augmented.track.candidates.count { it.kind == CandidateKind.CREW_TEMPORARY },
        )
        assertTrue(augmented.track.candidates.last().locator!!.startsWith("file:"))
        assertFalse(item.track.candidates.any { it.kind == CandidateKind.CREW_TEMPORARY })
        assertNull(index.augment(session("other"), item))
        index.endSession(session)
        assertNull(index.augment(session, item))
    }

    @Test
    fun `duplicate queue items receive distinct deterministic temporary candidates`() {
        val session = session("one")
        val first = queueItem("queue-1")
        val second = first.copy(id = QueueItemId("queue-2"))
        val index = CrewTemporaryMediaIndex()
        index.beginSession(session)
        assertTrue(
            index.complete(manifest(session, first.id, CandidateId("provider")), temporaryFile())
        )
        assertTrue(
            index.complete(manifest(session, second.id, CandidateId("provider")), temporaryFile())
        )

        val firstCandidate = requireNotNull(index.augment(session, first)).track.candidates.last()
        val secondCandidate = requireNotNull(index.augment(session, second)).track.candidates.last()
        assertFalse(firstCandidate.id == secondCandidate.id)
        assertEquals(
            firstCandidate.id,
            requireNotNull(index.augment(session, first)).track.candidates.last().id,
        )
    }

    @Test
    fun `index rejects a file whose size does not match the verified manifest`() {
        val session = session("one")
        val item = queueItem("queue-1")
        val index = CrewTemporaryMediaIndex()
        index.beginSession(session)

        assertFalse(
            index.complete(
                manifest(session, item.id, CandidateId("source")),
                temporaryFile().also { it.writeBytes(byteArrayOf(1, 2)) },
            )
        )
        assertNull(index.augment(session, item))
    }

    private fun manifest(
        session: CrewSessionId,
        queueItemId: QueueItemId,
        candidateId: CandidateId,
    ): CrewMediaManifest {
        val bytes = byteArrayOf(1)
        val digest = CrewMediaDigest.sha256(bytes)
        return CrewMediaManifest(
            CrewMediaTransferRef(
                session,
                CrewMediaRequestId("request-$queueItemId"),
                queueItemId,
                candidateId,
                member("target"),
                member("supplier"),
            ),
            "audio/test",
            1,
            digest,
            listOf(CrewMediaChunkDescriptor(0, 1, digest)),
        )
    }

    private fun queueItem(id: String): QueueItem {
        val trackId = TrackId("track")
        return QueueItem(
            QueueItemId(id),
            Track(
                trackId,
                TrackRealm.PROVIDER,
                "Track",
                listOf("Artist"),
                candidates =
                    listOf(
                        TrackCandidate(
                            CandidateId("provider"),
                            trackId,
                            CandidateKind.PROVIDER,
                            "provider",
                            "item",
                            CandidateAvailability.RESOLVABLE,
                            providerId = org.oxycblt.auxio.shippy.domain.ProviderId("provider"),
                        )
                    ),
            ),
        )
    }

    private fun session(value: String) = CrewSessionId(value, ProtocolVersion(1))

    private fun member(value: String) = CrewMemberId(value, ProtocolVersion(1))

    private fun temporaryFile() =
        File.createTempFile("crew-index", ".media").also { it.writeBytes(byteArrayOf(1)) }
}
