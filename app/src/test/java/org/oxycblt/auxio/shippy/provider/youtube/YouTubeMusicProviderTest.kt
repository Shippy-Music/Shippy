/*
 * Copyright (c) 2026 Auxio Project
 * YouTubeMusicProviderTest.kt is part of Auxio.
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.StreamConstraints

class YouTubeMusicProviderTest {
    @Test
    fun `youtube music maps song search records through the music filter`() = runBlocking {
        val gateway = FakeGateway()
        val provider = YouTubeMusicProvider(gateway)

        val result = provider.search("  Signal  ")

        assertTrue(result is ProviderResult.Success)
        val track = (result as ProviderResult.Success).value.tracks.single()
        assertEquals(YouTubeSearchFilter.MUSIC_SONGS, gateway.lastFilter)
        assertEquals("Signal", gateway.lastQuery)
        assertEquals(ProviderId("youtube_music"), track.candidates.single().providerId)
        assertEquals("video-123", track.candidates.single().sourceItemId)
        assertEquals("https://www.youtube.com/watch?v=video-123", track.candidates.single().locator)
        assertEquals(185_000L, track.durationMs)
    }

    @Test
    fun `youtube uses normal video search and resolves the nearest preferred bitrate`() =
        runBlocking {
            val gateway = FakeGateway()
            val provider = YouTubeProvider(gateway)

            val search = provider.search("Signal") as ProviderResult.Success
            val candidate = search.value.tracks.single().candidates.single()
            val result =
                provider.resolve(candidate, StreamConstraints(preferredBitrateBps = 150_000))

            assertEquals(YouTubeSearchFilter.VIDEOS, gateway.lastFilter)
            assertTrue(result is ProviderResult.Success)
            val stream = (result as ProviderResult.Success).value
            assertEquals("https://media.test/160?expire=123", stream.uri)
            assertEquals(160_000, stream.bitrateBps)
            assertEquals(123_000L, stream.expiresAtEpochMs)
            assertEquals(candidate.id, stream.candidateId)
        }

    @Test
    fun `provider refuses a candidate from another provider`() = runBlocking {
        val provider = YouTubeProvider(FakeGateway())
        val foreign =
            TrackCandidate(
                id = CandidateId("foreign:video"),
                trackId = TrackId("foreign:video"),
                kind = CandidateKind.PROVIDER,
                sourceId = "foreign",
                sourceItemId = "video",
                availability = CandidateAvailability.RESOLVABLE,
                providerId = ProviderId("foreign"),
            )

        val result = provider.resolve(foreign, StreamConstraints())

        assertTrue(result is ProviderResult.Failure)
    }

    private class FakeGateway : YouTubeExtractionGateway {
        var lastQuery: String? = null
        var lastFilter: YouTubeSearchFilter? = null

        override suspend fun search(
            query: String,
            filter: YouTubeSearchFilter,
        ): List<YouTubeExtractedTrack> {
            lastQuery = query
            lastFilter = filter
            return listOf(
                YouTubeExtractedTrack(
                    videoId = "video-123",
                    title = "Signal",
                    uploader = "Artist",
                    durationMs = 185_000,
                    artwork = "https://images.test/signal.jpg",
                    originalUrl = "https://www.youtube.com/watch?v=video-123",
                )
            )
        }

        override suspend fun audioStreams(videoId: String): List<YouTubeExtractedAudio> =
            listOf(
                YouTubeExtractedAudio("https://media.test/128", "audio/mp4", 128_000),
                YouTubeExtractedAudio("https://media.test/160?expire=123", "audio/webm", 160_000),
            )
    }
}
