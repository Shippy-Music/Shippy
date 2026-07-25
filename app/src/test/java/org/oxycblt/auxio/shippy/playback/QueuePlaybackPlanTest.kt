/*
 * Copyright (c) 2026 Shippy contributors
 * QueuePlaybackPlanTest.kt is part of Shippy.
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
