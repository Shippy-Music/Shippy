/*
 * Copyright (c) 2026 Shippy contributors
 * PlaybackResolutionCoordinatorTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.provider.MusicProvider
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderDescriptor
import org.oxycblt.auxio.shippy.provider.ProviderHealth
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.ResolvedStream
import org.oxycblt.auxio.shippy.provider.SearchPage
import org.oxycblt.auxio.shippy.provider.StreamConstraints

class PlaybackResolutionCoordinatorTest {
    private val provider = FakeProvider()
    private val coordinator =
        PlaybackResolutionCoordinator(
            PlaybackResolver(),
            ProviderRegistry(setOf(provider)),
        )

    @Test
    fun `provider resolution becomes canonical resolved playback`() = runBlocking {
        val item = queueItem("one")

        val result =
            coordinator.prepare(
                item,
                ResolutionPolicy(listOf(provider.descriptor.id), pushPullEnabled = false),
            )

        assertTrue(result is PlaybackPreparation.Ready)
        val ready = (result as PlaybackPreparation.Ready).value
        assertEquals(item.id, ready.playback.queueItemId)
        assertEquals("https://media.test/track.m4a", ready.playback.uri)
        assertEquals(mapOf("Referer" to "https://provider.test"), ready.playback.headers)
    }

    @Test
    fun `duplicate tracks retain different queue item identity`() = runBlocking {
        val first = queueItem("first")
        val second = first.copy(id = QueueItemId("second"))
        val policy = ResolutionPolicy(listOf(provider.descriptor.id), pushPullEnabled = false)

        val firstReady = coordinator.prepare(first, policy) as PlaybackPreparation.Ready
        val secondReady = coordinator.prepare(second, policy) as PlaybackPreparation.Ready

        assertNotEquals(firstReady.value.playback.queueItemId, secondReady.value.playback.queueItemId)
        assertEquals(first.track.id, second.track.id)
    }

    @Test
    fun `exact local queue item bypasses provider resolution and retains identity`() = runBlocking {
        val callsBefore = provider.resolveCalls
        val localTrackId = TrackId("local:umas123e4567-e89b-12d3-a456-426614174000")
        val localCandidate =
            TrackCandidate(
                id = CandidateId("local:umas123e4567-e89b-12d3-a456-426614174000"),
                trackId = localTrackId,
                kind = CandidateKind.LOCAL,
                sourceId = "device-local",
                sourceItemId = "umas123e4567-e89b-12d3-a456-426614174000",
                availability = CandidateAvailability.AVAILABLE,
                locator = "content://device/music/exact-song",
            )
        val item =
            QueueItem(
                id = QueueItemId("playlist-occurrence-2"),
                track =
                    Track(
                        id = localTrackId,
                        realm = TrackRealm.LOCAL,
                        title = "Exact local song",
                        artists = listOf("Artist"),
                        candidates = listOf(localCandidate),
                    ),
            )

        val result =
            coordinator.prepare(
                item,
                ResolutionPolicy(listOf(provider.descriptor.id), pushPullEnabled = false),
            )

        assertTrue(result is PlaybackPreparation.Ready)
        val playback = (result as PlaybackPreparation.Ready).value.playback
        assertEquals(item.id, playback.queueItemId)
        assertEquals(localCandidate.id, playback.candidateId)
        assertEquals(localCandidate.locator, playback.uri)
        assertEquals(callsBefore, provider.resolveCalls)
    }

    private fun queueItem(queueId: String): QueueItem {
        val providerId = provider.descriptor.id
        val trackId = TrackId("provider:track")
        return QueueItem(
            id = QueueItemId(queueId),
            track =
                Track(
                    id = trackId,
                    realm = TrackRealm.PROVIDER,
                    title = "Track",
                    artists = listOf("Artist"),
                    candidates =
                        listOf(
                            TrackCandidate(
                                id = CandidateId("provider:track"),
                                trackId = trackId,
                                kind = CandidateKind.PROVIDER,
                                sourceId = providerId.value,
                                sourceItemId = "track",
                                availability = CandidateAvailability.RESOLVABLE,
                                providerId = providerId,
                            )
                        ),
                ),
        )
    }

    private class FakeProvider : MusicProvider {
        var resolveCalls = 0

        override val descriptor =
            ProviderDescriptor(
                id = ProviderId("provider"),
                displayName = "Provider",
                capabilities = setOf(ProviderCapability.STREAM),
            )

        override fun health() = ProviderHealth.AVAILABLE

        override suspend fun search(query: String, continuation: String?) =
            ProviderResult.Success(SearchPage(emptyList()))

        override suspend fun resolve(
            candidate: TrackCandidate,
            constraints: StreamConstraints,
        ): ProviderResult<ResolvedStream> {
            resolveCalls++
            return ProviderResult.Success(
                ResolvedStream(
                    candidateId = candidate.id,
                    uri = "https://media.test/track.m4a",
                    mimeType = "audio/mp4",
                    headers = mapOf("Referer" to "https://provider.test"),
                )
            )
        }
    }
}
