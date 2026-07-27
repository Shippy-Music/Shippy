/*
 * Copyright (c) 2026 Auxio Project
 * YouTubeLiveSmokeTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.provider.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.StreamConstraints

/**
 * Opt-in real-network verification for the extractor boundary. Normal unit runs skip this test;
 * invoke it with `SHIPPY_LIVE_PROVIDERS=true` when validating a release candidate.
 */
class YouTubeLiveSmokeTest {
    @Test
    fun `youtube music can search and resolve a live audio stream`() = runBlocking {
        assumeTrue(System.getenv("SHIPPY_LIVE_PROVIDERS") == "true")
        val provider = YouTubeMusicProvider(NewPipeYouTubeExtractionGateway())

        val search = provider.search("Daft Punk Get Lucky")

        assertTrue(search.toString(), search is ProviderResult.Success)
        assertTrue((search as ProviderResult.Success).value.tracks.isNotEmpty())
        val candidate =
            TrackCandidate(
                id = CandidateId("youtube_music:dQw4w9WgXcQ"),
                trackId = TrackId("youtube_music:dQw4w9WgXcQ"),
                kind = CandidateKind.PROVIDER,
                sourceId = "youtube_music",
                sourceItemId = "dQw4w9WgXcQ",
                availability = CandidateAvailability.RESOLVABLE,
                providerId = ProviderId("youtube_music"),
            )
        val resolved = provider.resolve(candidate, StreamConstraints())
        assertTrue(resolved.toString(), resolved is ProviderResult.Success)
        assertTrue((resolved as ProviderResult.Success).value.uri.startsWith("https://"))
    }
}
