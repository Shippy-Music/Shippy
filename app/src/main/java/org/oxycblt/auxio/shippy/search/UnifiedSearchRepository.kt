/*
 * Copyright (c) 2026 Auxio Project
 * UnifiedSearchRepository.kt is part of Auxio.
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

import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.provider.MusicProvider
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderDescriptor
import org.oxycblt.auxio.shippy.provider.ProviderEntity
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.ProviderSettings

data class ProviderSearchSnapshot(val query: String, val sections: List<ProviderSearchSection>) {
    companion object {
        val EMPTY = ProviderSearchSnapshot("", emptyList())
    }
}

data class ProviderSearchSection(
    val provider: ProviderDescriptor,
    val tracks: List<Track>,
    val continuation: String? = null,
    val failure: ProviderResult.Failure? = null,
    val entities: List<ProviderEntity> = emptyList(),
) {
    init {
        require(failure == null || (tracks.isEmpty() && entities.isEmpty())) {
            "A failed provider section cannot also contain fresh results"
        }
    }
}

class UnifiedSearchRepository
@Inject
constructor(
    private val providerRegistry: ProviderRegistry,
    private val providerSettings: ProviderSettings,
) {
    fun providers(): List<ProviderDescriptor> = searchableProviders().map(MusicProvider::descriptor)

    fun preferredProvider(): ProviderDescriptor? {
        val providers = providers()
        val byId = providers.associateBy(ProviderDescriptor::id)
        return providerSettings.selection(byId.keys).priority.firstNotNullOfOrNull(byId::get)
    }

    suspend fun search(query: String, providerId: ProviderId? = null): ProviderSearchSnapshot {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return ProviderSearchSnapshot.EMPTY

        val searchableProviders = searchableProviders()
        val byId = searchableProviders.associateBy { it.descriptor.id }
        val selection = providerSettings.selection(byId.keys)
        val selected =
            providerId?.let { id -> selection.priority.filter { it == id } } ?: selection.priority
        val sections = supervisorScope {
            selected
                .mapNotNull(byId::get)
                .map { provider -> async { provider.searchSection(normalizedQuery) } }
                .awaitAll()
        }
        return ProviderSearchSnapshot(normalizedQuery, sections)
    }

    /**
     * The configured priority is the product order. Registry discovery is intentionally not the
     * product order: it is only the capability/health eligibility filter.
     */
    private fun searchableProviders(): List<MusicProvider> {
        val eligible = providerRegistry.supporting(ProviderCapability.SEARCH)
        val byId = eligible.associateBy { it.descriptor.id }
        return providerSettings.selection(byId.keys).priority.mapNotNull(byId::get)
    }

    private suspend fun MusicProvider.searchSection(query: String): ProviderSearchSection =
        try {
            when (val result = search(query)) {
                is ProviderResult.Success ->
                    ProviderSearchSection(
                        provider = descriptor,
                        tracks = result.value.tracks,
                        entities = result.value.entities,
                        continuation = result.value.continuation,
                    )
                is ProviderResult.Failure ->
                    ProviderSearchSection(
                        provider = descriptor,
                        tracks = emptyList(),
                        failure = result,
                    )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ProviderSearchSection(
                provider = descriptor,
                tracks = emptyList(),
                failure =
                    ProviderResult.Failure(
                        kind = ProviderFailureKind.UNAVAILABLE,
                        retryable = true,
                        message = error.message,
                    ),
            )
        }
}
