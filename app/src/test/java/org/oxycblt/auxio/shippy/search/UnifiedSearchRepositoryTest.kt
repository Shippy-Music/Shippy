/*
 * Copyright (c) 2026 Shippy contributors
 * UnifiedSearchRepositoryTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderHealth
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderResult
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
                ProviderResult.Failure(
                    ProviderFailureKind.NETWORK,
                    retryable = true,
                ),
            )
        val repository = UnifiedSearchRepository(ProviderRegistry(setOf(success, failure)))

        val snapshot = repository.search("  song  ")

        assertEquals("song", snapshot.query)
        assertEquals(listOf("alpha", "zeta"), snapshot.sections.map { it.provider.displayName })
        assertEquals(ProviderFailureKind.NETWORK, snapshot.sections.first().failure?.kind)
        assertNull(snapshot.sections.last().failure)
    }

    @Test
    fun `blank query avoids provider work`() = runBlocking {
        val provider = FakeProvider("provider", ProviderResult.Success(SearchPage(emptyList())))
        val repository = UnifiedSearchRepository(ProviderRegistry(setOf(provider)))

        val snapshot = repository.search(" ")

        assertEquals(ProviderSearchSnapshot.EMPTY, snapshot)
        assertTrue(provider.queries.isEmpty())
    }

    private class FakeProvider(
        name: String,
        private val searchResult: ProviderResult<SearchPage>,
    ) : MusicProvider {
        override val descriptor =
            ProviderDescriptor(
                ProviderId(name),
                name,
                setOf(ProviderCapability.SEARCH),
            )
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
            ProviderResult.Failure(
                ProviderFailureKind.UNSUPPORTED,
                retryable = false,
            )
    }
}
