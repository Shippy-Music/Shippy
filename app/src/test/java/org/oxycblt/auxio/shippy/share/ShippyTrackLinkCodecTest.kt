/*
 * Copyright (c) 2026 Auxio Project
 * ShippyTrackLinkCodecTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

class ShippyTrackLinkCodecTest {
    @Test
    fun `round trip preserves recording metadata and provider provenance only`() {
        val original = track(locator = "https://expired.example/audio?token=secret")
        val link = requireNotNull(ShippyTrackLinkCodec.encode(original))

        val decoded = requireNotNull(ShippyTrackLinkCodec.decode(link))

        assertEquals(original.id, decoded.id)
        assertEquals(original.title, decoded.title)
        assertEquals(original.artists, decoded.artists)
        assertEquals(
            original.candidates.single().sourceItemId,
            decoded.candidates.single().sourceItemId,
        )
        assertNull(decoded.candidates.single().locator)
        assertTrue(!link.contains("expired.example"))
        assertTrue(!link.contains("secret"))
    }

    @Test
    fun `rejects corrupt and unsupported links`() {
        val link = requireNotNull(ShippyTrackLinkCodec.encode(track()))
        assertNull(ShippyTrackLinkCodec.decode(link.dropLast(1) + "A"))
        assertNull(ShippyTrackLinkCodec.decode("shippy://track/v2/anything"))
        assertNull(ShippyTrackLinkCodec.decode("shippy://crew/v1/anything"))
    }

    @Test
    fun `original link is only derived from validated youtube ids`() {
        assertEquals(
            "https://music.youtube.com/watch?v=abc123_defG",
            ProviderTrackSharing.originalLink(track(sourceItemId = "abc123_defG")),
        )
        assertNull(ProviderTrackSharing.originalLink(track(sourceItemId = "bad/id")))
    }

    private fun track(locator: String? = null, sourceItemId: String = "abc123_defG") =
        Track(
            id = TrackId("youtube_music:$sourceItemId"),
            realm = TrackRealm.PROVIDER,
            title = "Fixture Song",
            artists = listOf("Fixture Artist"),
            album = "Fixture Album",
            durationMs = 123_000,
            candidates =
                listOf(
                    TrackCandidate(
                        id = CandidateId("youtube_music:$sourceItemId"),
                        trackId = TrackId("youtube_music:$sourceItemId"),
                        kind = CandidateKind.PROVIDER,
                        sourceId = "youtube_music",
                        sourceItemId = sourceItemId,
                        availability = CandidateAvailability.RESOLVABLE,
                        locator = locator,
                        providerId = ProviderId("youtube_music"),
                    )
                ),
        )
}
