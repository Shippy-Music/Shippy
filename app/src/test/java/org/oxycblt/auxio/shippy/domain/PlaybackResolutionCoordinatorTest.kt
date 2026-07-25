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
        ) =
            ProviderResult.Success(
                ResolvedStream(
                    candidateId = candidate.id,
                    uri = "https://media.test/track.m4a",
                    mimeType = "audio/mp4",
                    headers = mapOf("Referer" to "https://provider.test"),
                )
            )
    }
}
