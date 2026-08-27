/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaylistDestinationPickerViewModel.kt is part of Auxio.
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

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.shippy.core.identity.PlaylistId
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.data.browser.R16MediaBrowserPlaylistSummary
import app.shippy.data.library.R16PlaylistEntryAddResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.r16.authority.R16ActiveDataRuntimeOwner
import org.oxycblt.auxio.shippy.r16.library.R16MediaBrowserPagingSource

private val R16_PLAYLIST_DESTINATION_PAGING_CONFIG =
    PagingConfig(pageSize = 50, enablePlaceholders = false)

/** One-shot destination picker for the exact occurrence captured by Now Playing. */
@HiltViewModel
internal class R16PlaylistDestinationPickerViewModel
@Inject
constructor(activeRuntimeOwner: R16ActiveDataRuntimeOwner, savedStateHandle: SavedStateHandle) :
    ViewModel() {
    private val runtime = activeRuntimeOwner.activeRuntimeOrNull()
    private val browser = runtime?.mediaBrowser
    private val mutations = runtime?.libraryMutations
    private val recordingId = savedStateHandle.get<String>(ARG_RECORDING_ID)?.let(::RecordingId)
    internal val queueEntryId =
        savedStateHandle.get<String>(ARG_QUEUE_ENTRY_ID)?.let(::QueueEntryId)
    internal val capturedTitle = savedStateHandle.get<String>(ARG_TITLE).orEmpty()
    internal val capturedArtist = savedStateHandle.get<String>(ARG_ARTIST).orEmpty()
    private val _inFlight = MutableStateFlow<Set<PlaylistId>>(emptySet())
    private val _completion = MutableStateFlow<R16PlaylistDestinationCompletion?>(null)
    private var nextOperationId = 0L

    internal val inFlight = _inFlight.asStateFlow()
    internal val completion = _completion.asStateFlow()

    internal val liked: Flow<Boolean> =
        recordingId?.let { id -> mutations?.observeLike(id) } ?: flowOf(false)

    internal val memberships: Flow<Map<PlaylistId, Int>> =
        recordingId?.let { id -> mutations?.observePlaylistMemberships(id) } ?: flowOf(emptyMap())

    internal val playlists: Flow<PagingData<R16MediaBrowserPlaylistSummary>> =
        browser?.let { repository ->
            Pager(R16_PLAYLIST_DESTINATION_PAGING_CONFIG) {
                    R16MediaBrowserPagingSource(repository::playlistSummaries)
                }
                .flow
                .cachedIn(viewModelScope)
        } ?: flowOf(PagingData.empty())

    internal fun toggleLiked() {
        val capturedRecordingId = recordingId ?: return
        viewModelScope.launch {
            try {
                mutations?.toggleLiked(capturedRecordingId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {}
        }
    }

    internal fun togglePlaylistMembership(playlistId: PlaylistId, isCurrentlyMember: Boolean) {
        val capturedRecordingId = recordingId ?: return
        if (_inFlight.value.isNotEmpty()) return
        val operationId = ++nextOperationId
        _inFlight.update { it + playlistId }
        viewModelScope.launch {
            try {
                if (isCurrentlyMember) {
                    val removed =
                        try {
                            mutations?.removeRecordingFromPlaylist(
                                playlistId,
                                capturedRecordingId,
                            ) == true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            false
                        }
                    _completion.value =
                        R16PlaylistDestinationCompletion(
                            operationId = operationId,
                            playlistId = playlistId,
                            result = null,
                            isRemoval = true,
                            success = removed,
                        )
                } else {
                    val result =
                        try {
                            mutations?.addPlaylistEntry(playlistId, capturedRecordingId)
                                ?: R16PlaylistEntryAddResult.Failed
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            R16PlaylistEntryAddResult.Failed
                        }
                    _completion.value =
                        R16PlaylistDestinationCompletion(
                            operationId = operationId,
                            playlistId = playlistId,
                            result = result,
                            isRemoval = false,
                            success = result is R16PlaylistEntryAddResult.Added,
                        )
                }
            } finally {
                _inFlight.update { it - playlistId }
            }
        }
    }

    internal fun addTo(playlistId: PlaylistId) {
        togglePlaylistMembership(playlistId, isCurrentlyMember = false)
    }

    internal fun removeFrom(playlistId: PlaylistId) {
        togglePlaylistMembership(playlistId, isCurrentlyMember = true)
    }

    internal fun hasCapturedIdentity(): Boolean = recordingId != null && queueEntryId != null

    internal fun acknowledgeCompletion(operationId: Long) {
        if (_completion.value?.operationId == operationId) _completion.value = null
    }

    internal companion object {
        const val ARG_RECORDING_ID = "recording_id"
        const val ARG_QUEUE_ENTRY_ID = "queue_entry_id"
        const val ARG_TITLE = "title"
        const val ARG_ARTIST = "artist"
    }
}

/** Retained across view recreation until this exact destination picker consumes the outcome. */
internal data class R16PlaylistDestinationCompletion(
    val operationId: Long,
    val playlistId: PlaylistId,
    val result: R16PlaylistEntryAddResult?,
    val isRemoval: Boolean = false,
    val success: Boolean = false,
)
