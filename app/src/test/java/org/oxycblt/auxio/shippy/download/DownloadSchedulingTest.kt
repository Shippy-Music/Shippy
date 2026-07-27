/*
 * Copyright (c) 2026 Auxio Project
 * DownloadSchedulingTest.kt is part of Auxio.
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
