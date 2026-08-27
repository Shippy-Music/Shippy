/*
 * Copyright (c) 2026 Auxio Project
 * R16MetadataEditorViewModel.kt is part of Auxio.
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
import app.shippy.core.source.SourceKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class MetadataEditorUiState(
    val recordingId: String = "",
    val title: String = "",
    val artist: String = "",
    val release: String = "",
    val canonicalTitle: String = "",
    val canonicalArtist: String = "",
    val canonicalRelease: String = "",
    val isTitleOverridden: Boolean = false,
    val isArtistOverridden: Boolean = false,
    val isReleaseOverridden: Boolean = false,
    val isSaved: Boolean = false,
    val canUnlinkIdentification: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class R16MetadataEditorViewModel
@Inject
constructor(
    private val activeRuntimeOwner: org.oxycblt.auxio.shippy.r16.authority.R16ActiveDataRuntimeOwner
) : ViewModel() {

    private val _state = MutableStateFlow(MetadataEditorUiState())
    val state: StateFlow<MetadataEditorUiState> = _state.asStateFlow()

    private fun mutations() = activeRuntimeOwner.activeRuntimeOrNull()?.libraryMutations

    fun loadRecording(recordingId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val mutationRepo = mutations()
                    val song = mutationRepo?.getSong(recordingId)
                    val overrides = mutationRepo?.getMetadataOverrides(recordingId).orEmpty()

                    val titleOverride = overrides["TITLE"]?.let { parseStringJson(it) }
                    val artistOverride = overrides["ARTIST"]?.let { parseStringJson(it) }
                    val releaseOverride = overrides["RELEASE"]?.let { parseStringJson(it) }

                    val canonicalTitle = song?.title.orEmpty()
                    val canonicalArtist = song?.artist.orEmpty()
                    val canonicalRelease = song?.album.orEmpty()
                    val canUnlink =
                        activeRuntimeOwner
                            .activeRuntimeOrNull()
                            ?.sources
                            ?.observe(RecordingId(recordingId))
                            ?.first()
                            ?.let { sources ->
                                var reversible = false
                                for (source in sources) {
                                    if (
                                        source.kind == SourceKind.LOCAL_FILE &&
                                            mutationRepo?.latestIdentifyUndo(source.id.value) !=
                                                null
                                    ) {
                                        reversible = true
                                        break
                                    }
                                }
                                reversible
                            } == true

                    _state.value =
                        MetadataEditorUiState(
                            recordingId = recordingId,
                            title = titleOverride ?: canonicalTitle,
                            artist = artistOverride ?: canonicalArtist,
                            release = releaseOverride ?: canonicalRelease,
                            canonicalTitle = canonicalTitle,
                            canonicalArtist = canonicalArtist,
                            canonicalRelease = canonicalRelease,
                            isTitleOverridden = titleOverride != null,
                            isArtistOverridden = artistOverride != null,
                            isReleaseOverridden = releaseOverride != null,
                            canUnlinkIdentification = canUnlink,
                        )
                }
            } catch (e: Exception) {
                _state.value =
                    _state.value.copy(errorMessage = e.message ?: "Failed to load metadata")
            }
        }
    }

    fun saveOverrides(newTitle: String, newArtist: String, newRelease: String) {
        val current = _state.value
        val recordingId = current.recordingId
        if (recordingId.isBlank()) return

        viewModelScope.launch {
            try {
                val mutationRepo = mutations()
                if (mutationRepo == null) {
                    _state.value = _state.value.copy(errorMessage = "R16 database is not active")
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    if (newTitle != current.canonicalTitle && newTitle.isNotBlank()) {
                        val json = JSONObject().put("value", newTitle).toString()
                        mutationRepo.updateMetadataOverride(recordingId, "TITLE", json)
                    } else {
                        mutationRepo.revertMetadataOverride(recordingId, "TITLE")
                    }

                    if (newArtist != current.canonicalArtist && newArtist.isNotBlank()) {
                        val json = JSONObject().put("value", newArtist).toString()
                        mutationRepo.updateMetadataOverride(recordingId, "ARTIST", json)
                    } else {
                        mutationRepo.revertMetadataOverride(recordingId, "ARTIST")
                    }

                    if (newRelease != current.canonicalRelease && newRelease.isNotBlank()) {
                        val json = JSONObject().put("value", newRelease).toString()
                        mutationRepo.updateMetadataOverride(recordingId, "RELEASE", json)
                    } else {
                        mutationRepo.revertMetadataOverride(recordingId, "RELEASE")
                    }
                }
                _state.value = _state.value.copy(isSaved = true)
            } catch (e: Exception) {
                _state.value =
                    _state.value.copy(errorMessage = e.message ?: "Failed to save metadata")
            }
        }
    }

    fun revertField(fieldName: String) {
        val recordingId = _state.value.recordingId
        if (recordingId.isBlank()) return

        viewModelScope.launch {
            val mutationRepo = mutations()
            if (mutationRepo == null) {
                _state.value = _state.value.copy(errorMessage = "R16 database is not active")
                return@launch
            }
            withContext(Dispatchers.IO) {
                mutationRepo.revertMetadataOverride(recordingId, fieldName)
            }
            loadRecording(recordingId)
        }
    }

    fun unmerge() {
        val recordingId = _state.value.recordingId
        if (recordingId.isBlank()) return

        viewModelScope.launch {
            try {
                val mutationRepo = mutations()
                if (mutationRepo == null) {
                    _state.value = _state.value.copy(errorMessage = "R16 database is not active")
                    return@launch
                }
                withContext(Dispatchers.IO) { mutationRepo.unmergeRecording(recordingId) }
                _state.value = _state.value.copy(isSaved = true)
            } catch (e: Exception) {
                _state.value =
                    _state.value.copy(errorMessage = e.message ?: "Failed to unmerge track")
            }
        }
    }

    fun unlinkIdentification() {
        val recordingId = _state.value.recordingId
        if (recordingId.isBlank()) return
        viewModelScope.launch {
            try {
                val success =
                    withContext(Dispatchers.IO) {
                        val runtime =
                            activeRuntimeOwner.activeRuntimeOrNull() ?: return@withContext false
                        for (source in runtime.sources.observe(RecordingId(recordingId)).first()) {
                            if (
                                source.kind == SourceKind.LOCAL_FILE &&
                                    runtime.libraryMutations.undoLatestIdentifyCandidate(
                                        source.id.value
                                    )
                            ) {
                                return@withContext true
                            }
                        }
                        false
                    }
                if (success) loadRecording(recordingId)
                else
                    _state.value =
                        _state.value.copy(errorMessage = "No reversible identification was found")
            } catch (e: Exception) {
                _state.value =
                    _state.value.copy(errorMessage = e.message ?: "Failed to unlink identification")
            }
        }
    }

    private fun parseStringJson(json: String): String? =
        try {
            JSONObject(json).optString("value")
        } catch (_: Exception) {
            null
        }
}
