/*
 * Copyright (c) 2026 Auxio Project
 * QueuePlaybackPlanTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.LocalTrackCandidateMapper
import org.oxycblt.auxio.shippy.domain.QueueItemFactory
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm

class QueuePlaybackPlanTest {
    @Test
    fun `ordered collection plan keeps selected occurrence and unique queue identities`() {
        val first = localTrack("first")
        val duplicate = localTrack("duplicate")
        val plan =
            queuePlaybackPlan(
                QueueItemFactory(LocalTrackCandidateMapper()),
                tracks = listOf(first, duplicate, first),
                selectedIndex = 2,
                contextId = "playlist:road-trip",
                contributorId = null,
            )

        assertEquals(listOf(first, duplicate, first), plan.items.map { it.track })
        assertEquals(plan.items[2].id, plan.selectedItemId)
        assertEquals("playlist:road-trip", plan.items[0].contextId)
        assertNotEquals(plan.items[0].id, plan.items[2].id)
        assertEquals(3, plan.items.map { it.id }.distinct().size)
    }

    private fun localTrack(id: String): Track {
        val trackId = TrackId("local:$id")
        return Track(
            id = trackId,
            realm = TrackRealm.LOCAL,
            title = id,
            artists = listOf("Artist"),
            candidates =
                listOf(
                    TrackCandidate(
                        id = CandidateId("local:$id"),
                        trackId = trackId,
                        kind = CandidateKind.LOCAL,
                        sourceId = "device-local",
                        sourceItemId = id,
                        availability = CandidateAvailability.AVAILABLE,
                        locator = "content://device/music/$id",
                    )
                ),
        )
    }
}
