/*
 * Copyright (c) 2026 Shippy contributors
 * UnifiedSearchRepository.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.search

import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.provider.MusicProvider
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderDescriptor
import org.oxycblt.auxio.shippy.provider.ProviderEntity
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.ProviderSettings

data class ProviderSearchSnapshot(
    val query: String,
    val sections: List<ProviderSearchSection>,
) {
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
    suspend fun search(query: String): ProviderSearchSnapshot {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return ProviderSearchSnapshot.EMPTY

        val searchableProviders = providerRegistry.supporting(ProviderCapability.SEARCH)
        val byId = searchableProviders.associateBy { it.descriptor.id }
        val selection = providerSettings.selection(byId.keys)
        val sections =
            supervisorScope {
                selection.priority
                    .mapNotNull(byId::get)
                    .map { provider ->
                        async { provider.searchSection(normalizedQuery) }
                    }
                    .awaitAll()
            }
        return ProviderSearchSnapshot(normalizedQuery, sections)
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
