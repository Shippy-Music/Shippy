/*
 * Copyright (c) 2026 Auxio Project
 * R16ProviderObservationRepositoryTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.source

import app.shippy.core.identity.ProviderId as R16ProviderId
import app.shippy.core.source.SourceItemType
import app.shippy.core.source.SourceKind
import app.shippy.sources.provider.SourceDiscoveryFailureKind
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.domain.TrackVersion
import org.oxycblt.auxio.shippy.provider.MusicProvider
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderDescriptor
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderHealth
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.ProviderSelection
import org.oxycblt.auxio.shippy.provider.ProviderSettings
import org.oxycblt.auxio.shippy.provider.ResolvedStream
import org.oxycblt.auxio.shippy.provider.SearchPage
import org.oxycblt.auxio.shippy.provider.StreamConstraints
import org.oxycblt.auxio.shippy.search.UnifiedSearchRepository

class R16ProviderObservationRepositoryTest {
    @Test
    fun `youtube surfaces share source identity without leaking playback locators`() = runBlocking {
        val videoId = "dQw4w9WgXcQ"
        val youtube =
            FakeProvider(
                "youtube",
                ProviderResult.Success(
                    SearchPage(
                        listOf(
                            track(
                                providerId = "youtube",
                                sourceItemId = videoId,
                                locator = "https://transient.example/video-token",
                                artwork = "http://private.example/art.jpg",
                            )
                        )
                    )
                ),
            )
        val youtubeMusic =
            FakeProvider(
                "youtube_music",
                ProviderResult.Success(
                    SearchPage(
                        listOf(
                            track(
                                providerId = "youtube_music",
                                sourceItemId = videoId,
                                locator = "https://transient.example/music-token",
                                artwork = "https://images.example/art.jpg",
                                title = "Fixture (Live)",
                                version = TrackVersion(explicit = true),
                            )
                        )
                    )
                ),
            )
        val repository = repository(youtube, youtubeMusic)

        val first = repository.search("fixture")
        val second = repository.search("fixture")
        val observations = first.sections.map { it.tracks.single() }

        assertEquals(
            listOf("youtube", "youtube_music"),
            first.sections.map { it.provider.id.value },
        )
        assertEquals(observations[0].sourceKey, observations[1].sourceKey)
        assertEquals(R16ProviderId("youtube"), observations[0].sourceKey.providerId)
        assertEquals(SourceItemType.VIDEO, observations[0].sourceKey.itemType)
        assertEquals(SourceKind.YOUTUBE, observations[0].sourceKind)
        assertEquals(SourceKind.YOUTUBE_MUSIC, observations[1].sourceKind)
        assertEquals("https://www.youtube.com/watch?v=$videoId", observations[0].originalUrl)
        assertNull(observations[0].asset)
        assertTrue(observations[0].artwork.isEmpty())
        assertEquals("https://images.example/art.jpg", observations[1].artwork.single().value)
        assertEquals(app.shippy.core.music.VersionKind.LIVE, observations[1].version.kind)
        assertEquals(app.shippy.core.music.Explicitness.EXPLICIT, observations[1].explicitness)
        assertEquals(
            first.sections.map { it.tracks.map { observation -> observation.sourceKey } },
            second.sections.map { it.tracks.map { observation -> observation.sourceKey } },
        )
    }

    @Test
    fun `provider failures remain scoped beside successful observations`() = runBlocking {
        val success =
            FakeProvider(
                "jiosaavn",
                ProviderResult.Success(SearchPage(listOf(track("jiosaavn", "song-1")))),
            )
        val failure =
            FakeProvider(
                "youtube",
                ProviderResult.Failure(ProviderFailureKind.NETWORK, retryable = true),
            )
        val snapshot = repository(success, failure).search("fixture")

        assertEquals(1, snapshot.sections[0].tracks.size)
        assertNull(snapshot.sections[0].failure)
        assertTrue(snapshot.sections[1].tracks.isEmpty())
        assertEquals(SourceDiscoveryFailureKind.NETWORK, snapshot.sections[1].failure?.kind)
        assertTrue(snapshot.sections[1].failure?.retryable == true)
    }

    @Test
    fun `provider cancellation is never converted into a failed section`() = runBlocking {
        val provider = FakeProvider("youtube", cancellation = CancellationException("superseded"))

        try {
            repository(provider).search("fixture")
            fail("Expected provider cancellation")
        } catch (_: CancellationException) {
            Unit
        }
    }

    private fun repository(vararg providers: FakeProvider): R16ProviderObservationRepository {
        val priority = providers.map { it.descriptor.id }
        return R16ProviderObservationRepository(
            UnifiedSearchRepository(
                ProviderRegistry(providers.toSet()),
                FixedProviderSettings(priority),
            ),
            Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC),
        )
    }

    private fun track(
        providerId: String,
        sourceItemId: String,
        locator: String? = null,
        artwork: String? = null,
        title: String = "Fixture",
        version: TrackVersion = TrackVersion(),
    ): Track {
        val id = TrackId("$providerId:$sourceItemId")
        val provider = ProviderId(providerId)
        return Track(
            id = id,
            realm = TrackRealm.PROVIDER,
            title = title,
            artists = listOf("Artist"),
            album = "Release",
            durationMs = 180_000,
            version = version,
            artwork = artwork,
            candidates =
                listOf(
                    TrackCandidate(
                        id = CandidateId("$providerId:$sourceItemId"),
                        trackId = id,
                        kind = CandidateKind.PROVIDER,
                        sourceId = providerId,
                        sourceItemId = sourceItemId,
                        availability = CandidateAvailability.RESOLVABLE,
                        locator = locator,
                        providerId = provider,
                    )
                ),
        )
    }

    private class FakeProvider(
        id: String,
        private val result: ProviderResult<SearchPage> =
            ProviderResult.Success(SearchPage(emptyList())),
        private val cancellation: CancellationException? = null,
    ) : MusicProvider {
        override val descriptor =
            ProviderDescriptor(ProviderId(id), id, setOf(ProviderCapability.SEARCH))

        override fun health() = ProviderHealth.AVAILABLE

        override suspend fun search(
            query: String,
            continuation: String?,
        ): ProviderResult<SearchPage> {
            cancellation?.let { throw it }
            return result
        }

        override suspend fun resolve(
            candidate: TrackCandidate,
            constraints: StreamConstraints,
        ): ProviderResult<ResolvedStream> =
            ProviderResult.Failure(ProviderFailureKind.UNSUPPORTED, retryable = false)
    }

    private class FixedProviderSettings(private val priority: List<ProviderId>) : ProviderSettings {
        override fun registerListener(listener: ProviderSettings.Listener) = Unit

        override fun unregisterListener(listener: ProviderSettings.Listener) = Unit

        override fun selection(available: Collection<ProviderId>) =
            ProviderSelection(priority.filter(available::contains))

        override fun setPriority(priority: List<ProviderId>) = Unit
    }
}
