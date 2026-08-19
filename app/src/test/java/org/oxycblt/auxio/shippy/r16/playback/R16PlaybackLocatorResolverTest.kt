/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaybackLocatorResolverTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback

import app.shippy.core.asset.AssetState
import app.shippy.core.asset.AudioTechnicalMetadata
import app.shippy.core.asset.MediaAssetKind
import app.shippy.core.identity.MediaAssetId
import app.shippy.core.identity.ProviderId as CoreProviderId
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.playback.PlaybackRequestTag
import app.shippy.core.source.AvailabilitySnapshot
import app.shippy.core.source.AvailabilityState
import app.shippy.core.source.IdentityStatus
import app.shippy.core.source.SourceItemType
import app.shippy.core.source.SourceKey
import app.shippy.core.source.SourceKind
import app.shippy.core.source.SourceReference
import app.shippy.data.playback.R16PlayableAsset
import app.shippy.data.playback.R16PlaybackSourceOptions
import app.shippy.data.playback.R16PlaybackSourceRepository
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.media.MediaObjectKey
import org.oxycblt.auxio.shippy.provider.MusicProvider
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderDescriptor
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderHealth
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.ResolvedStream
import org.oxycblt.auxio.shippy.provider.SearchPage
import org.oxycblt.auxio.shippy.provider.StreamConstraints

class R16PlaybackLocatorResolverTest {
    @Test
    fun `verified local asset resolves without invoking a provider`() = runBlocking {
        val recordingId = RecordingId(idValue(1))
        val asset =
            R16PlayableAsset(
                id = MediaAssetId(idValue(2)),
                recordingId = recordingId,
                sourceReferenceId = null,
                kind = MediaAssetKind.LOCAL_FILE,
                state = AssetState.AVAILABLE,
                locationType = "CONTENT_URI",
                location = "content://media/external/audio/42",
                technical = AudioTechnicalMetadata("audio/flac", "flac", null, null, null, 12L),
                lastVerifiedAt = Instant.EPOCH,
            )
        val resolver =
            R16PlaybackLocatorResolver(
                repository = FakeRepository(R16PlaybackSourceOptions(listOf(asset), emptyList())),
                providers = ProviderRegistry(emptySet()),
            )

        val result = resolver.resolve(request(recordingId, attempt = 1))

        val locator = (result as PlaybackLocatorResolution.Ready).locator
        assertEquals(asset.id, locator.mediaAssetId)
        assertEquals(asset.location, locator.uri)
        assertEquals(null, locator.cacheKey)
    }

    @Test
    fun `failed verified asset is excluded so recovery can use a known provider`() = runBlocking {
        val recordingId = RecordingId(idValue(1))
        val asset =
            R16PlayableAsset(
                id = MediaAssetId(idValue(2)),
                recordingId = recordingId,
                sourceReferenceId = null,
                kind = MediaAssetKind.LOCAL_FILE,
                state = AssetState.AVAILABLE,
                locationType = "CONTENT_URI",
                location = "content://media/external/audio/missing",
                technical = AudioTechnicalMetadata("audio/flac", "flac", null, null, null, 12L),
                lastVerifiedAt = Instant.EPOCH,
            )
        val youtube =
            FakeProvider(
                "youtube",
                ArrayDeque(
                    listOf(
                        ProviderResult.Success(
                            ResolvedStream(
                                candidateId = org.oxycblt.auxio.shippy.domain.CandidateId("ok"),
                                uri = "https://audio.example.invalid/fallback",
                            )
                        )
                    )
                ),
            )
        val resolver =
            R16PlaybackLocatorResolver(
                repository =
                    FakeRepository(
                        R16PlaybackSourceOptions(
                            assets = listOf(asset),
                            sources =
                                listOf(source(recordingId, 3, "youtube", SourceItemType.VIDEO)),
                        )
                    ),
                providers = ProviderRegistry(setOf(youtube)),
            )

        val result =
            resolver.resolve(
                request(recordingId, attempt = 1)
                    .copy(excludedStableKeys = setOf("asset:${asset.id.value}"))
            )

        val locator = (result as PlaybackLocatorResolution.Ready).locator
        assertEquals(SourceReferenceId(idValue(3)), locator.sourceReferenceId)
        assertEquals("https://audio.example.invalid/fallback", locator.uri)
    }

    @Test
    fun `final retry refreshes preferred provider then falls back without changing recording`() =
        runBlocking {
            val recordingId = RecordingId(idValue(1))
            val jio =
                FakeProvider(
                    "jiosaavn",
                    ArrayDeque(
                        listOf(
                            ProviderResult.Failure(ProviderFailureKind.NETWORK, true),
                            ProviderResult.Failure(ProviderFailureKind.NETWORK, true),
                        )
                    ),
                )
            val youtube =
                FakeProvider(
                    "youtube",
                    ArrayDeque(
                        listOf(
                            ProviderResult.Success(
                                ResolvedStream(
                                    candidateId = org.oxycblt.auxio.shippy.domain.CandidateId("ok"),
                                    uri = "https://audio.example.invalid/fresh",
                                    mimeType = "audio/webm",
                                    bitrateBps = 128_000,
                                    headers = mapOf("Authorization" to "redacted"),
                                )
                            )
                        )
                    ),
                )
            val options =
                R16PlaybackSourceOptions(
                    assets = emptyList(),
                    sources =
                        listOf(
                            source(recordingId, 2, "jiosaavn", SourceItemType.RECORDING),
                            source(recordingId, 3, "youtube", SourceItemType.VIDEO),
                        ),
                )
            val resolver =
                R16PlaybackLocatorResolver(
                    repository = FakeRepository(options),
                    providers = ProviderRegistry(setOf(jio, youtube)),
                )

            val first = resolver.resolve(request(recordingId, attempt = 1))
            val final = resolver.resolve(request(recordingId, attempt = 2))

            assertTrue(first is PlaybackLocatorResolution.Unavailable)
            assertEquals(2, jio.resolveCount)
            assertEquals(1, youtube.resolveCount)
            val locator = (final as PlaybackLocatorResolution.Ready).locator
            assertEquals(options.sources[1].id, locator.sourceReferenceId)
            assertEquals("https://audio.example.invalid/fresh", locator.uri)
            assertTrue(locator.cacheKey!!.startsWith(MediaObjectKey.CACHE_KEY_PREFIX))
        }

    private class FakeRepository(private val options: R16PlaybackSourceOptions) :
        R16PlaybackSourceRepository {
        override suspend fun options(recordingId: RecordingId) = options
    }

    private class FakeProvider(
        id: String,
        private val resolutions: ArrayDeque<ProviderResult<ResolvedStream>>,
    ) : MusicProvider {
        override val descriptor =
            ProviderDescriptor(ProviderId(id), id, setOf(ProviderCapability.STREAM))
        var resolveCount = 0

        override fun health() = ProviderHealth.AVAILABLE

        override suspend fun search(query: String, continuation: String?) =
            ProviderResult.Success(SearchPage(emptyList()))

        override suspend fun resolve(
            candidate: TrackCandidate,
            constraints: StreamConstraints,
        ): ProviderResult<ResolvedStream> {
            resolveCount += 1
            return resolutions.removeFirst()
        }
    }

    private fun source(
        recordingId: RecordingId,
        value: Int,
        providerId: String,
        itemType: SourceItemType,
    ) =
        SourceReference(
            id = SourceReferenceId(idValue(value)),
            recordingId = recordingId,
            source = SourceKey(CoreProviderId(providerId), itemType, "item-$value"),
            kind = if (providerId == "jiosaavn") SourceKind.JIOSAAVN else SourceKind.YOUTUBE,
            originalUrl = null,
            availability = AvailabilitySnapshot(AvailabilityState.RESOLVABLE, null, null, null),
            rawMetadataId = "observation-$value",
            identityStatus = IdentityStatus.AUTOMATICALLY_LINKED,
        )

    private fun request(recordingId: RecordingId, attempt: Int) =
        PlaybackPreparationRequest(
            tag = PlaybackRequestTag(1, 1, QueueEntryId(idValue(9))),
            recordingId = recordingId,
            attempt = attempt,
        )

    private fun idValue(value: Int) =
        "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}
