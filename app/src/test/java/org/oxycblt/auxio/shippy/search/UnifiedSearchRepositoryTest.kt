/*
 * Copyright (c) 2026 Auxio Project
 * UnifiedSearchRepositoryTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.search

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.provider.MusicProvider
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderDescriptor
import org.oxycblt.auxio.shippy.provider.ProviderEntity
import org.oxycblt.auxio.shippy.provider.ProviderEntityType
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderHealth
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.ProviderSelection
import org.oxycblt.auxio.shippy.provider.ProviderSettings
import org.oxycblt.auxio.shippy.provider.ResolvedStream
import org.oxycblt.auxio.shippy.provider.SearchPage
import org.oxycblt.auxio.shippy.provider.StreamConstraints

class UnifiedSearchRepositoryTest {
    @Test
    fun `providers complete independently and failures remain scoped`() = runBlocking {
        val success = FakeProvider("zeta", ProviderResult.Success(SearchPage(emptyList())))
        val failure =
            FakeProvider(
                "alpha",
                ProviderResult.Failure(ProviderFailureKind.NETWORK, retryable = true),
            )
        val repository =
            UnifiedSearchRepository(
                ProviderRegistry(setOf(success, failure)),
                FixedProviderSettings(listOf(failure.descriptor.id, success.descriptor.id)),
            )

        val snapshot = repository.search("  song  ")

        assertEquals("song", snapshot.query)
        assertEquals(listOf("alpha", "zeta"), snapshot.sections.map { it.provider.displayName })
        assertEquals(ProviderFailureKind.NETWORK, snapshot.sections.first().failure?.kind)
        assertNull(snapshot.sections.last().failure)
    }

    @Test
    fun `blank query avoids provider work`() = runBlocking {
        val provider = FakeProvider("provider", ProviderResult.Success(SearchPage(emptyList())))
        val repository =
            UnifiedSearchRepository(
                ProviderRegistry(setOf(provider)),
                FixedProviderSettings(listOf(provider.descriptor.id)),
            )

        val snapshot = repository.search(" ")

        assertEquals(ProviderSearchSnapshot.EMPTY, snapshot)
        assertTrue(provider.queries.isEmpty())
    }

    @Test
    fun `provider entities remain with their provider section`() = runBlocking {
        val entity =
            ProviderEntity(
                ProviderId("provider"),
                "fixture-album",
                ProviderEntityType.ALBUM,
                "Fixture Album",
                originalUrl = "https://example.test/album/fixture-album",
            )
        val provider =
            FakeProvider(
                "provider",
                ProviderResult.Success(SearchPage(emptyList(), entities = listOf(entity))),
            )
        val repository =
            UnifiedSearchRepository(
                ProviderRegistry(setOf(provider)),
                FixedProviderSettings(listOf(provider.descriptor.id)),
            )

        val snapshot = repository.search("fixture")

        assertEquals(listOf(entity), snapshot.sections.single().entities)
    }

    @Test
    fun `selected provider is the only provider searched`() = runBlocking {
        val jio = FakeProvider("JioSaavn", ProviderResult.Success(SearchPage(emptyList())))
        val youtube = FakeProvider("YouTube", ProviderResult.Success(SearchPage(emptyList())))
        val settings = FixedProviderSettings(listOf(jio.descriptor.id, youtube.descriptor.id))
        val repository = UnifiedSearchRepository(ProviderRegistry(setOf(jio, youtube)), settings)

        val snapshot = repository.search("fixture", youtube.descriptor.id)

        assertEquals(listOf("YouTube"), snapshot.sections.map { it.provider.displayName })
        assertTrue(jio.queries.isEmpty())
        assertEquals(listOf("fixture"), youtube.queries)
    }

    private class FakeProvider(name: String, private val searchResult: ProviderResult<SearchPage>) :
        MusicProvider {
        override val descriptor =
            ProviderDescriptor(ProviderId(name), name, setOf(ProviderCapability.SEARCH))
        val queries = mutableListOf<String>()

        override fun health() = ProviderHealth.AVAILABLE

        override suspend fun search(
            query: String,
            continuation: String?,
        ): ProviderResult<SearchPage> {
            queries += query
            return searchResult
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
