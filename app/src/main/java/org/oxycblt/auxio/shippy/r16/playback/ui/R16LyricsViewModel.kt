/*
 * Copyright (c) 2026 Auxio Project
 * R16LyricsViewModel.kt is part of Auxio.
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
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.oxycblt.auxio.playback.activeLyricIndex
import org.oxycblt.auxio.shippy.lyrics.LyricsRepository
import org.oxycblt.auxio.shippy.lyrics.R16LyricsCoordinator
import org.oxycblt.auxio.shippy.lyrics.R16LyricsState

@HiltViewModel
class R16LyricsViewModel @Inject constructor(lyricsRepository: LyricsRepository) : ViewModel() {
    private val coordinator =
        R16LyricsCoordinator(
            scope = viewModelScope,
            lyricsRepository = lyricsRepository,
            presentations = null,
        )

    val lyricsState: StateFlow<R16LyricsState> = coordinator.state

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _activeLineIndex = MutableStateFlow(-1)
    val activeLineIndex: StateFlow<Int> = _activeLineIndex.asStateFlow()

    private val _autoFollow = MutableStateFlow(true)
    val autoFollow: StateFlow<Boolean> = _autoFollow.asStateFlow()

    fun loadTrack(
        recordingId: RecordingId,
        queueEntryId: QueueEntryId,
        generation: Long = 0L,
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long,
    ) {
        coordinator.loadFor(
            recordingId = recordingId,
            queueEntryId = queueEntryId,
            generation = generation,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
        )
    }

    fun updateProgress(positionMs: Long) {
        _currentPositionMs.value = positionMs
        val state = lyricsState.value
        if (state is R16LyricsState.Synced) {
            _activeLineIndex.value = activeLyricIndex(state.lyrics, positionMs)
        } else {
            _activeLineIndex.value = -1
        }
    }

    fun setAutoFollow(enabled: Boolean) {
        _autoFollow.value = enabled
    }

    fun toggleAutoFollow() {
        _autoFollow.value = !_autoFollow.value
    }

    fun retry() {
        coordinator.retry()
    }

    fun refresh() {
        coordinator.refresh()
    }
}
