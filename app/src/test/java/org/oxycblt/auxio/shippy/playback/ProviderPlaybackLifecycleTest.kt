/*
 * Copyright (c) 2026 Auxio Project
 * ProviderPlaybackLifecycleTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.PlaybackResolutionCoordinator
import org.oxycblt.auxio.shippy.domain.PlaybackResolver
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.ResolutionPolicy
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.provider.MusicProvider
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderDescriptor
import org.oxycblt.auxio.shippy.provider.ProviderHealth
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.ResolvedStream
import org.oxycblt.auxio.shippy.provider.SearchPage
import org.oxycblt.auxio.shippy.provider.StreamConstraints

class ProviderPlaybackLifecycleTest {
    @Test
    fun `expiring provider locator refreshes before playback`() = runBlocking {
        var now = 1_000L
        val provider = MutableProvider { call ->
            "https://media.test/$call" to (if (call == 1) 2_000L else 200_000L)
        }
        val lifecycle = lifecycle(provider) { now }
        val item = providerItem("current")

        val initial =
            lifecycle.prepareInitial(listOf(item), 0, policy(), StreamConstraints())
                as ProviderPlaybackLifecycle.InitialPreparation.Ready
        assertEquals("https://media.test/1", initial.items.single().playback.uri)

        now = 10_000L
        val refreshed = lifecycle.resolveNearPlayback(item.id, listOf(item.id)).single()

        assertEquals("https://media.test/2", refreshed.playback.uri)
        assertEquals(2, provider.calls)
    }

    @Test
    fun `offline unresolved occurrence remains ordered and recovers later`() = runBlocking {
        val provider = MutableProvider { call -> "https://media.test/$call" to 200_000L }
        val lifecycle = lifecycle(provider)
        val selected = providerItem("selected")
        val offline = providerItem("offline")
        provider.blocked += offline.id.value

        val initial =
            lifecycle.prepareInitial(listOf(selected, offline), 0, policy(), StreamConstraints())
                as ProviderPlaybackLifecycle.InitialPreparation.Ready
        assertEquals(listOf(selected.id, offline.id), initial.items.map { it.item.id })
        assertTrue(initial.items[1].playback.deferred)

        provider.blocked -= offline.id.value
        val recovered =
            lifecycle.resolveNearPlayback(offline.id, listOf(selected.id, offline.id)).single()

        assertEquals(offline.id, recovered.item.id)
        assertFalse(recovered.playback.deferred)
        assertEquals("https://media.test/3", recovered.playback.uri)
    }

    @Test
    fun `duplicate occurrences retain exact order and identity`() = runBlocking {
        val provider = MutableProvider { call -> "https://media.test/$call" to 200_000L }
        val lifecycle = lifecycle(provider)
        val first = providerItem("duplicate-a")
        val second = first.copy(id = QueueItemId("duplicate-b"))
        val third = first.copy(id = QueueItemId("duplicate-c"))

        val initial =
            lifecycle.prepareInitial(listOf(first, second, third), 0, policy(), StreamConstraints())
                as ProviderPlaybackLifecycle.InitialPreparation.Ready

        assertEquals(listOf(first.id, second.id, third.id), initial.items.map { it.item.id })
        assertEquals(3, initial.items.map { it.playback.queueItemId }.distinct().size)
        assertEquals(3, provider.calls)
    }

    private fun lifecycle(provider: MutableProvider, clock: () -> Long = { 1_000L }) =
        ProviderPlaybackLifecycle(
            PlaybackResolutionCoordinator(PlaybackResolver(), ProviderRegistry(setOf(provider))),
            clock,
        )

    private fun policy() = ResolutionPolicy(listOf(ProviderId("provider")), pushPullEnabled = false)

    private fun providerItem(id: String): QueueItem {
        val trackId = TrackId("provider:track")
        return QueueItem(
            QueueItemId(id),
            Track(
                trackId,
                TrackRealm.PROVIDER,
                "Track",
                listOf("Artist"),
                candidates =
                    listOf(
                        TrackCandidate(
                            CandidateId("provider:$id"),
                            trackId,
                            CandidateKind.PROVIDER,
                            "provider",
                            id,
                            CandidateAvailability.RESOLVABLE,
                            providerId = ProviderId("provider"),
                        )
                    ),
            ),
        )
    }

    private class MutableProvider(private val response: (Int) -> Pair<String, Long>) :
        MusicProvider {
        var calls = 0
        val blocked = mutableSetOf<String>()

        override val descriptor =
            ProviderDescriptor(ProviderId("provider"), "Provider", setOf(ProviderCapability.STREAM))

        override fun health() = ProviderHealth.AVAILABLE

        override suspend fun search(query: String, continuation: String?) =
            ProviderResult.Success(SearchPage(emptyList()))

        override suspend fun resolve(
            candidate: TrackCandidate,
            constraints: StreamConstraints,
        ): ProviderResult<ResolvedStream> {
            calls += 1
            if (candidate.sourceItemId in blocked) {
                return ProviderResult.Failure(
                    org.oxycblt.auxio.shippy.provider.ProviderFailureKind.NETWORK,
                    retryable = true,
                )
            }
            val (uri, expiry) = response(calls)
            return ProviderResult.Success(
                ResolvedStream(candidate.id, uri, expiresAtEpochMs = expiry)
            )
        }
    }
}
