/*
 * Copyright (c) 2026 Shippy contributors
 * DownloadSchedulingTest.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm

class DownloadSchedulingTest {
    @Test
    fun `network constraint follows requested candidate not unrelated local candidate`() {
        val track = mixedTrack()

        assertTrue(downloadRequiresNetwork(track, CandidateId("provider")))
        assertFalse(downloadRequiresNetwork(track, CandidateId("local")))
        assertTrue(downloadRequiresNetwork(track, CandidateId("missing")))
    }

    private fun mixedTrack(): Track {
        val trackId = TrackId("track")
        return Track(
            id = trackId,
            realm = TrackRealm.PROVIDER,
            title = "Track",
            artists = listOf("Artist"),
            candidates =
                listOf(
                    TrackCandidate(
                        id = CandidateId("provider"),
                        trackId = trackId,
                        kind = CandidateKind.PROVIDER,
                        sourceId = "jiosaavn",
                        sourceItemId = "provider",
                        availability = CandidateAvailability.RESOLVABLE,
                        providerId = ProviderId("jiosaavn"),
                    ),
                    TrackCandidate(
                        id = CandidateId("local"),
                        trackId = trackId,
                        kind = CandidateKind.DOWNLOAD,
                        sourceId = "download",
                        sourceItemId = "local",
                        availability = CandidateAvailability.AVAILABLE,
                        locator = "content://downloads/local",
                    ),
                ),
        )
    }
}
