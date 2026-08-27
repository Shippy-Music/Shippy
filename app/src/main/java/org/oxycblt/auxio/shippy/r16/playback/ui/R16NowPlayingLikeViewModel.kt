/*
 * Copyright (c) 2026 Auxio Project
 * R16NowPlayingLikeViewModel.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shippy.core.identity.RecordingId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.r16.authority.R16ActiveDataRuntimeOwner

internal data class R16NowPlayingLikeUiState(
    val recordingId: String? = null,
    val liked: Boolean = false,
    val saving: Boolean = false,
) {
    val canSave: Boolean
        get() = recordingId != null && !liked && !saving
}

/** Owns one-way Like state for the exact recording currently projected by the MediaSession. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class R16NowPlayingLikeViewModel
@Inject
constructor(activeRuntimeOwner: R16ActiveDataRuntimeOwner) : ViewModel() {
    private val repository = activeRuntimeOwner.activeRuntimeOrNull()?.libraryMutations
    private val currentRecordingId = MutableStateFlow<String?>(null)
    private val savingRecordingId = MutableStateFlow<String?>(null)
    private val currentLike =
        currentRecordingId.flatMapLatest { recordingId ->
            if (recordingId == null || repository == null) {
                flowOf(R16NowPlayingLikeUiState())
            } else {
                repository.observeLike(RecordingId(recordingId)).combine(savingRecordingId) {
                    liked,
                    saving ->
                    R16NowPlayingLikeUiState(
                        recordingId = recordingId,
                        liked = liked,
                        saving = saving == recordingId,
                    )
                }
            }
        }

    val uiState =
        currentLike.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            R16NowPlayingLikeUiState(),
        )

    fun setCurrentRecordingId(recordingId: String?) {
        if (currentRecordingId.value != recordingId) currentRecordingId.value = recordingId
    }

    fun saveCurrentToLiked() {
        val recordingId = currentRecordingId.value ?: return
        if (repository == null || savingRecordingId.value != null) return

        savingRecordingId.value = recordingId
        viewModelScope.launch {
            try {
                repository.saveToLiked(RecordingId(recordingId))
            } finally {
                if (savingRecordingId.value == recordingId) savingRecordingId.value = null
            }
        }
    }
}
