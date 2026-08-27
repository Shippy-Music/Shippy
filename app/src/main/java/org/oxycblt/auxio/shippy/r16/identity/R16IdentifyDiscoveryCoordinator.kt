/*
 * Copyright (c) 2026 Auxio Project
 * R16IdentifyDiscoveryCoordinator.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.identity

import app.shippy.core.identity.ProviderId
import app.shippy.sources.observation.SourceTrackObservation
import app.shippy.sources.provider.SourceDiscoveryFailure
import app.shippy.sources.provider.SourceDiscoveryRepository
import app.shippy.sources.provider.SourceProviderDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A bounded, generation-guarded metadata-discovery path. It never resolves playback. */
internal class R16IdentifyDiscoveryCoordinator(
    private val scope: CoroutineScope,
    private val discovery: SourceDiscoveryRepository,
    private val localSearch: suspend (String) -> List<IdentifyCandidate>,
) {
    private val mutableState = MutableStateFlow(IdentifyDiscoveryState())
    private var generation = 0L
    private var searchJob: Job? = null

    val state: StateFlow<IdentifyDiscoveryState> = mutableState.asStateFlow()

    fun submit(rawQuery: CharSequence?) {
        val query = rawQuery?.toString()?.trim().orEmpty()
        val requestGeneration = ++generation
        searchJob?.cancel()
        if (query.isEmpty()) {
            mutableState.value = IdentifyDiscoveryState()
            return
        }

        val providers = discovery.providers().distinctBy { it.id }.take(MAX_PROVIDER_COUNT)
        mutableState.value =
            IdentifyDiscoveryState(
                query = query,
                localPending = true,
                pendingProviders = providers.map { it.id }.toSet(),
            )
        searchJob =
            scope.launch {
                launch {
                    val local = localSearch(query).take(MAX_LOCAL_RESULTS)
                    updateIfCurrent(requestGeneration) { current ->
                        current.copy(localCandidates = local, localPending = false)
                    }
                }
                providers.forEach { provider ->
                    launch { searchProvider(query, provider, requestGeneration) }
                }
            }
    }

    private suspend fun searchProvider(
        query: String,
        provider: SourceProviderDescriptor,
        requestGeneration: Long,
    ) {
        try {
            val section =
                discovery.search(query, provider.id).sections.firstOrNull {
                    it.provider.id == provider.id
                }
            val candidates =
                section
                    ?.tracks
                    ?.take(MAX_RESULTS_PER_PROVIDER)
                    ?.map { IdentifyCandidate.fromProvider(it, provider) }
                    .orEmpty()
            updateIfCurrent(requestGeneration) { current ->
                current.copy(
                    providerCandidates = current.providerCandidates + (provider.id to candidates),
                    pendingProviders = current.pendingProviders - provider.id,
                    providerFailures =
                        section?.failure?.let { current.providerFailures + (provider.id to it) }
                            ?: current.providerFailures,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            updateIfCurrent(requestGeneration) { current ->
                current.copy(
                    pendingProviders = current.pendingProviders - provider.id,
                    providerFailures =
                        current.providerFailures +
                            (provider.id to
                                SourceDiscoveryFailure(
                                    kind =
                                        app.shippy.sources.provider.SourceDiscoveryFailureKind
                                            .UNAVAILABLE,
                                    retryable = true,
                                    message = error.message,
                                )),
                )
            }
        }
    }

    private fun updateIfCurrent(
        requestGeneration: Long,
        transform: (IdentifyDiscoveryState) -> IdentifyDiscoveryState,
    ) {
        if (requestGeneration == generation) mutableState.value = transform(mutableState.value)
    }

    private companion object {
        const val MAX_PROVIDER_COUNT = 4
        const val MAX_LOCAL_RESULTS = 20
        const val MAX_RESULTS_PER_PROVIDER = 20
    }
}

internal data class IdentifyDiscoveryState(
    val query: String = "",
    val localPending: Boolean = false,
    val localCandidates: List<IdentifyCandidate> = emptyList(),
    val providerCandidates: Map<ProviderId, List<IdentifyCandidate>> = emptyMap(),
    val pendingProviders: Set<ProviderId> = emptySet(),
    val providerFailures: Map<ProviderId, SourceDiscoveryFailure> = emptyMap(),
) {
    val isLoading: Boolean
        get() = localPending || pendingProviders.isNotEmpty()

    val candidates: List<IdentifyCandidate>
        get() =
            (localCandidates + providerCandidates.values.flatten()).distinctBy(
                IdentifyCandidate::stableId
            )
}

internal fun IdentifyCandidate.Companion.fromProvider(
    observation: SourceTrackObservation,
    provider: SourceProviderDescriptor,
): IdentifyCandidate =
    IdentifyCandidate(
        stableId =
            "source:${observation.sourceKey.providerId.value}:${observation.sourceKey.itemType}:${observation.sourceKey.sourceItemId}",
        recordingId = null,
        title = observation.title.orEmpty(),
        artist = observation.artistNames.joinToString(", "),
        album = observation.releaseTitle,
        artworkLocation = observation.artwork.firstOrNull()?.value,
        confidence = IdentifyConfidenceKind.PROBABLE,
        provenance = provider.displayName,
        sourceObservation = observation,
    )
