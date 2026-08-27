/*
 * Copyright (c) 2026 Auxio Project
 * R16IdentifyTrackViewModel.kt is part of Auxio.
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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.source.SourceKind
import app.shippy.sources.observation.SourceTrackObservation
import app.shippy.sources.provider.SourceDiscoveryRepository
import app.shippy.sources.provider.SourceDiscoverySnapshot
import app.shippy.sources.provider.SourceProviderDescriptor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.shippy.r16.authority.R16ActiveDataRuntimeOwner
import org.oxycblt.auxio.shippy.r16.source.DataSourceRepository
import org.oxycblt.auxio.shippy.r16.source.R16ProviderObservationRepository
import org.oxycblt.auxio.shippy.search.UnifiedSearchRepository

enum class IdentifyConfidenceKind {
    AUTHORITATIVE,
    PROBABLE,
    DURATION_MATCH,
}

data class IdentifyCandidate(
    val recordingId: String?,
    val stableId: String = "recording:${recordingId.orEmpty()}",
    val title: String,
    val artist: String,
    val album: String?,
    val artworkLocation: String?,
    val confidence: IdentifyConfidenceKind,
    val provenance: String? = null,
    internal val sourceObservation: SourceTrackObservation? = null,
) {
    companion object {}
}

data class IdentifyUndoUi(val sourceReferenceId: String, val previousRecordingId: String)

data class IdentifyTrackUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val candidates: List<IdentifyCandidate> = emptyList(),
    val confirmedRecordingId: String? = null,
    val undo: IdentifyUndoUi? = null,
    val errorMessage: String? = null,
)

@HiltViewModel
class R16IdentifyTrackViewModel
private constructor(
    private val activeRuntimeOwner: R16ActiveDataRuntimeOwner,
    providerDiscovery: SourceDiscoveryRepository,
) : ViewModel() {
    @Inject
    constructor(
        activeRuntimeOwner: R16ActiveDataRuntimeOwner,
        unifiedSearch: UnifiedSearchRepository,
    ) : this(activeRuntimeOwner, R16ProviderObservationRepository(unifiedSearch))

    /** Narrow test constructor; production uses the provider-observation boundary above. */
    internal constructor(
        activeRuntimeOwner: R16ActiveDataRuntimeOwner
    ) : this(activeRuntimeOwner, EmptySourceDiscoveryRepository)

    private val _state = MutableStateFlow(IdentifyTrackUiState())
    val state: StateFlow<IdentifyTrackUiState> = _state.asStateFlow()
    private val discovery =
        R16IdentifyDiscoveryCoordinator(
            scope = viewModelScope,
            discovery = providerDiscovery,
            localSearch = ::searchLocalCandidates,
        )

    init {
        viewModelScope.launch {
            discovery.state.collect { result ->
                _state.value =
                    _state.value.copy(
                        query = result.query,
                        isLoading = result.isLoading,
                        candidates = result.candidates,
                        errorMessage = null,
                    )
            }
        }
    }

    fun searchCandidates(query: String) = discovery.submit(query)

    fun confirmCandidate(
        sourceReferenceId: String?,
        subjectRecordingId: String?,
        candidate: IdentifyCandidate,
    ) {
        viewModelScope.launch {
            try {
                val targetRecordingId =
                    withContext(Dispatchers.IO) { candidate.resolveTargetRecordingId() }
                val sourceId =
                    withContext(Dispatchers.IO) {
                        sourceReferenceId
                            ?: if (subjectRecordingId != null) {
                                sourceForRecording(subjectRecordingId)
                            } else {
                                null
                            }
                    }
                val runtime = activeRuntimeOwner.activeRuntimeOrNull()
                val previousRecordingId =
                    withContext(Dispatchers.IO) {
                        sourceId?.let {
                            runtime?.sources?.get(SourceReferenceId(it))?.recordingId?.value
                        }
                    }
                val success =
                    withContext(Dispatchers.IO) {
                        targetRecordingId != null &&
                            runtime
                                ?.libraryMutations
                                ?.confirmIdentifyCandidate(sourceId, targetRecordingId) == true
                    }
                if (success) {
                    _state.value =
                        _state.value.copy(
                            confirmedRecordingId = targetRecordingId,
                            undo =
                                if (sourceId != null && previousRecordingId != null) {
                                    IdentifyUndoUi(sourceId, previousRecordingId)
                                } else null,
                        )
                } else {
                    _state.value =
                        _state.value.copy(errorMessage = "Failed to confirm candidate match")
                }
            } catch (error: Exception) {
                _state.value =
                    _state.value.copy(errorMessage = error.message ?: "Failed to confirm match")
            }
        }
    }

    internal fun confirmCandidate(sourceReferenceId: String?, candidate: IdentifyCandidate) {
        confirmCandidate(sourceReferenceId, null, candidate)
    }

    fun undoCandidate(sourceReferenceId: String, previousRecordingId: String) {
        viewModelScope.launch {
            val success =
                withContext(Dispatchers.IO) {
                    activeRuntimeOwner
                        .activeRuntimeOrNull()
                        ?.libraryMutations
                        ?.undoLatestIdentifyCandidate(sourceReferenceId) == true
                }
            if (success) _state.value = _state.value.copy(confirmedRecordingId = null, undo = null)
            else _state.value = _state.value.copy(errorMessage = "Failed to undo candidate match")
        }
    }

    private suspend fun searchLocalCandidates(query: String): List<IdentifyCandidate> {
        val mutations =
            activeRuntimeOwner.activeRuntimeOrNull()?.libraryMutations ?: return emptyList()
        return withContext(Dispatchers.IO) {
            mutations.searchIdentifyCandidates(query).map { candidate ->
                IdentifyCandidate(
                    stableId = "recording:${candidate.recordingId}",
                    recordingId = candidate.recordingId,
                    title = candidate.title,
                    artist = candidate.artist,
                    album = candidate.album,
                    artworkLocation = candidate.artworkLocation,
                    confidence =
                        if (candidate.isExact) IdentifyConfidenceKind.AUTHORITATIVE
                        else IdentifyConfidenceKind.PROBABLE,
                )
            }
        }
    }

    private suspend fun IdentifyCandidate.resolveTargetRecordingId(): String? =
        recordingId
            ?: sourceObservation?.let { observation ->
                val runtime = activeRuntimeOwner.activeRuntimeOrNull() ?: return null
                val sources = DataSourceRepository(runtime.sources, runtime.ingestion)
                sources.upsertExactProvider(observation)
                sources.exact(observation.sourceKey)?.recordingId?.value
            }

    private suspend fun sourceForRecording(recordingId: String): String? =
        activeRuntimeOwner
            .activeRuntimeOrNull()
            ?.sources
            ?.observe(RecordingId(recordingId))
            ?.first()
            ?.firstOrNull { it.kind == SourceKind.LOCAL_FILE }
            ?.id
            ?.value

    private object EmptySourceDiscoveryRepository : SourceDiscoveryRepository {
        override fun providers(): List<SourceProviderDescriptor> = emptyList()

        override suspend fun search(
            query: String,
            providerId: app.shippy.core.identity.ProviderId?,
        ): SourceDiscoverySnapshot = SourceDiscoverySnapshot.EMPTY
    }
}
