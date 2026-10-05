/*
 * Copyright (c) 2026 Auxio Project
 * R16GlobalSearchCoordinator.kt is part of Auxio.
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

import app.shippy.core.identity.ProviderId
import app.shippy.core.identity.RecordingId
import app.shippy.sources.observation.SourceTrackObservation
import app.shippy.sources.provider.SourceDiscoveryFailure
import app.shippy.sources.provider.SourceDiscoveryFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class R16CanonicalSearchResult(
    val recordingId: RecordingId,
    val title: String,
    val artistDisplay: String,
    val artworkLocation: String? = null,
)

/** Configured, source-mapped provider identity shown to the user. */
data class R16SearchProvider(val id: ProviderId, val displayName: String)

data class R16ProviderSearchResult(
    val provider: R16SearchProvider,
    val observations: List<SourceTrackObservation>,
    val failure: SourceDiscoveryFailure? = null,
) {
    init {
        require(failure == null || observations.isEmpty())
    }
}

sealed interface R16ProviderSearchContent {
    data object Idle : R16ProviderSearchContent

    data object Loading : R16ProviderSearchContent

    data class Results(val observations: List<SourceTrackObservation>) : R16ProviderSearchContent

    data class Failure(val failure: SourceDiscoveryFailure) : R16ProviderSearchContent
}

data class R16ProviderSearchSection(
    val provider: R16SearchProvider,
    val content: R16ProviderSearchContent,
)

sealed interface R16SearchPlayResult {
    data class Played(val mediaId: String) : R16SearchPlayResult

    data class Rejected(val reason: String) : R16SearchPlayResult
}

data class R16GlobalSearchState(
    val query: String = "",
    val localResults: List<R16CanonicalSearchResult> = emptyList(),
    val providers: List<R16ProviderSearchSection> = emptyList(),
    val selectingSourceKey: String? = null,
)

/**
 * One bounded global-search coordinator. Local canonical data remains first; remote sections stay
 * provider-neutral, independently retryable, and cannot overwrite a newer query generation.
 */
class R16GlobalSearchCoordinator(
    private val scope: CoroutineScope,
    private val configuredProviders: () -> List<R16SearchProvider>,
    private val localSearch: suspend (String) -> List<R16CanonicalSearchResult>,
    private val providerSearch: suspend (String, ProviderId?) -> List<R16ProviderSearchResult>,
    private val persistAndPlay: suspend (SourceTrackObservation) -> R16SearchPlayResult,
    private val remoteDebounceMs: Long = REMOTE_DEBOUNCE_MS,
) {
    private val mutableState = MutableStateFlow(R16GlobalSearchState())
    private var generation = 0L
    private var queryJob: Job? = null
    private val providerAttempts = mutableMapOf<String, Long>()
    private val retryJobs = mutableMapOf<ProviderId, Job>()

    val state: StateFlow<R16GlobalSearchState> = mutableState.asStateFlow()

    fun submitQuery(rawQuery: CharSequence?) {
        val query = rawQuery?.toString()?.trim().orEmpty()
        val requestGeneration = ++generation
        queryJob?.cancel()
        retryJobs.values.forEach { it.cancel() }
        retryJobs.clear()
        providerAttempts.clear()
        if (query.isEmpty()) {
            mutableState.value = R16GlobalSearchState()
            return
        }

        val providers = configuredProviders().distinctBy { it.id }
        mutableState.value =
            R16GlobalSearchState(
                query = query,
                providers =
                    providers.map { R16ProviderSearchSection(it, R16ProviderSearchContent.Loading) },
            )
        queryJob =
            scope.launch {
                launch {
                    val local = localSearch(query)
                    if (requestGeneration == generation) {
                        mutableState.update { it.copy(localResults = local) }
                    }
                }
                providers.forEach { provider ->
                    launch {
                        delay(remoteDebounceMs)
                        val result =
                            providerSearchOrFailures(query, provider.id, listOf(provider))
                                .singleOrNull { it.provider.id == provider.id }
                        if (
                            requestGeneration != generation ||
                                providerAttempts[provider.id.value] != null
                        )
                            return@launch
                        result?.let(::setProvider)
                            ?: setProviderContent(
                                provider.id,
                                R16ProviderSearchContent.Results(emptyList()),
                            )
                    }
                }
            }
    }

    /** Reissues exactly one failed provider without cancelling completed sibling sections. */
    fun retryProvider(providerId: ProviderId) {
        val query = mutableState.value.query
        if (query.isEmpty()) return
        val provider =
            mutableState.value.providers.singleOrNull { it.provider.id == providerId }?.provider
                ?: return
        val requestGeneration = generation
        val attempt = (providerAttempts[providerId.value] ?: 0L) + 1L
        providerAttempts[providerId.value] = attempt
        setProviderContent(providerId, R16ProviderSearchContent.Loading)
        retryJobs.remove(providerId)?.cancel()
        retryJobs[providerId] =
            scope.launch {
                delay(remoteDebounceMs)
                val result =
                    providerSearchOrFailures(query, providerId, listOf(provider)).singleOrNull {
                        it.provider.id == providerId
                    }
                if (
                    requestGeneration == generation && providerAttempts[providerId.value] == attempt
                ) {
                    result?.let(::setProvider)
                        ?: setProviderContent(
                            providerId,
                            R16ProviderSearchContent.Results(emptyList()),
                        )
                }
            }
    }

    fun selectProvider(
        observation: SourceTrackObservation,
        onResult: (R16SearchPlayResult) -> Unit,
    ) {
        val selectedKey = observation.sourceKey.encodedIdentity()
        scope.launch {
            mutableState.value = mutableState.value.copy(selectingSourceKey = selectedKey)
            try {
                onResult(persistAndPlay(observation))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onResult(
                    R16SearchPlayResult.Rejected(error.message ?: "Could not play provider result")
                )
            } finally {
                if (mutableState.value.selectingSourceKey == selectedKey) {
                    mutableState.value = mutableState.value.copy(selectingSourceKey = null)
                }
            }
        }
    }

    private fun setProvider(result: R16ProviderSearchResult) {
        setProviderContent(
            result.provider.id,
            result.failure?.let(R16ProviderSearchContent::Failure)
                ?: R16ProviderSearchContent.Results(
                    result.observations.take(MAX_RESULTS_PER_PROVIDER)
                ),
        )
    }

    private suspend fun providerSearchOrFailures(
        query: String,
        providerId: ProviderId?,
        providers: List<R16SearchProvider>,
    ): List<R16ProviderSearchResult> =
        try {
            providerSearch(query, providerId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            providers.map {
                R16ProviderSearchResult(
                    provider = it,
                    observations = emptyList(),
                    failure =
                        SourceDiscoveryFailure(
                            SourceDiscoveryFailureKind.UNAVAILABLE,
                            retryable = true,
                            message = error.message,
                        ),
                )
            }
        }

    private fun setProviderContent(providerId: ProviderId, content: R16ProviderSearchContent) {
        mutableState.update { state ->
            state.copy(
                providers =
                    state.providers.map {
                        if (it.provider.id == providerId) it.copy(content = content) else it
                    }
            )
        }
    }

    companion object {
        const val REMOTE_DEBOUNCE_MS = 200L
        const val MAX_RESULTS_PER_PROVIDER = 20
    }
}

private fun app.shippy.core.source.SourceKey.encodedIdentity() =
    "${providerId.value}:${itemType.name}:${sourceItemId}"
