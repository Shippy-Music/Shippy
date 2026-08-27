/*
 * Copyright (c) 2026 Auxio Project
 * R16GlobalSearchViewModel.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shippy.core.identity.ProviderId
import app.shippy.data.browser.R16MediaBrowserId
import app.shippy.data.browser.R16MediaBrowserIdCodec
import app.shippy.data.browser.R16MediaBrowserPageRequest
import app.shippy.sources.observation.SourceTrackObservation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.oxycblt.auxio.shippy.r16.authority.R16ActiveDataRuntimeOwner
import org.oxycblt.auxio.shippy.r16.source.DataSourceRepository
import org.oxycblt.auxio.shippy.r16.source.R16ProviderObservationRepository
import org.oxycblt.auxio.shippy.search.UnifiedSearchRepository

/** ACTIVE-only global Search model. It never opens a Room runtime on its own. */
@HiltViewModel
class R16GlobalSearchViewModel
@Inject
constructor(activeRuntimeOwner: R16ActiveDataRuntimeOwner, unifiedSearch: UnifiedSearchRepository) :
    ViewModel() {
    private val runtime = activeRuntimeOwner.activeRuntimeOrNull()
    private val dataSources = runtime?.let { DataSourceRepository(it.sources, it.ingestion) }
    private val providerDiscovery = R16ProviderObservationRepository(unifiedSearch)
    private val mutablePlayRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private val coordinator =
        R16GlobalSearchCoordinator(
            scope = viewModelScope,
            configuredProviders = ::configuredProviders,
            localSearch = ::searchCanonical,
            providerSearch = ::searchProviders,
            persistAndPlay = ::persistProviderThenRequestPlay,
        )

    internal val state = coordinator.state
    internal val playRequests: SharedFlow<String> = mutablePlayRequests.asSharedFlow()

    internal fun submitQuery(query: CharSequence?) = coordinator.submitQuery(query)

    internal fun retryProvider(providerId: ProviderId) = coordinator.retryProvider(providerId)

    internal fun selectProvider(observation: SourceTrackObservation) {
        coordinator.selectProvider(observation) { Unit }
    }

    private suspend fun searchCanonical(query: String): List<R16CanonicalSearchResult> {
        val browser = runtime?.mediaBrowser ?: return emptyList()
        return browser.searchCanonical(query, R16MediaBrowserPageRequest()).items.map {
            R16CanonicalSearchResult(
                recordingId = it.recordingId,
                title = it.title,
                artistDisplay = it.artistDisplay,
                artworkLocation = it.artworkLocation,
            )
        }
    }

    private fun configuredProviders() =
        providerDiscovery.providers().map { R16SearchProvider(it.id, it.displayName) }

    private suspend fun searchProviders(
        query: String,
        providerId: ProviderId?,
    ): List<R16ProviderSearchResult> =
        providerDiscovery.search(query, providerId).sections.map {
            R16ProviderSearchResult(
                provider = R16SearchProvider(it.provider.id, it.provider.displayName),
                observations = it.tracks,
                failure = it.failure,
            )
        }

    /**
     * Persists one exact provider source and routes only its returned canonical RecordingId to
     * MediaSession.
     */
    private suspend fun persistProviderThenRequestPlay(
        observation: SourceTrackObservation
    ): R16SearchPlayResult {
        val sources = dataSources ?: return R16SearchPlayResult.Rejected("R16 is unavailable")
        sources.upsertExactProvider(observation)
        val source =
            sources.exact(observation.sourceKey)
                ?: return R16SearchPlayResult.Rejected("Saved provider source was not found")
        val recordingId =
            source.recordingId
                ?: return R16SearchPlayResult.Rejected("Provider source has no canonical recording")
        val mediaId = R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Recording(recordingId))
        mutablePlayRequests.emit(mediaId)
        return R16SearchPlayResult.Played(mediaId)
    }
}
