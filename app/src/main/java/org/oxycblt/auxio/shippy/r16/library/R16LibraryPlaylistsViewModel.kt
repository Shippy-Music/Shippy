/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryPlaylistsViewModel.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.shippy.core.identity.PlaylistId
import app.shippy.data.browser.R16MediaBrowserPlaylistSummary
import app.shippy.data.library.R16PlaylistLifecycleResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.r16.authority.R16ActiveDataRuntimeOwner

private val R16_LIBRARY_PLAYLIST_PAGING_CONFIG =
    PagingConfig(pageSize = 50, enablePlaceholders = false)

/** Reads canonical playlist summaries; Room owns their pinned/order projection. */
@HiltViewModel
internal class R16LibraryPlaylistsViewModel
@Inject
constructor(activeRuntimeOwner: R16ActiveDataRuntimeOwner) : ViewModel() {
    private val runtime = activeRuntimeOwner.activeRuntimeOrNull()
    private val browser = runtime?.mediaBrowser
    private val layoutMutations = runtime?.libraryMutations
    private val _pinTogglesInFlight = MutableStateFlow<Set<PlaylistId>>(emptySet())
    private val _pinToggleCompletions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _playlistLifecycleInFlight = MutableStateFlow(false)
    private val _playlistLifecycleCompletion =
        MutableStateFlow<R16PlaylistLifecycleCompletion?>(null)
    private var nextPlaylistLifecycleOperationId = 0L

    internal val pinTogglesInFlight = _pinTogglesInFlight.asStateFlow()
    internal val pinToggleCompletions = _pinToggleCompletions.asSharedFlow()
    internal val playlistLifecycleInFlight = _playlistLifecycleInFlight.asStateFlow()
    internal val playlistLifecycleCompletion = _playlistLifecycleCompletion.asStateFlow()

    internal val playlists: Flow<PagingData<R16MediaBrowserPlaylistSummary>> =
        browser?.let { repository ->
            Pager(R16_LIBRARY_PLAYLIST_PAGING_CONFIG) {
                    R16MediaBrowserPagingSource(repository::playlistSummaries)
                }
                .flow
                .cachedIn(viewModelScope)
        } ?: flowOf(PagingData.empty())

    internal fun togglePinned(summary: R16MediaBrowserPlaylistSummary) {
        val playlistId = summary.playlistId
        if (playlistId in _pinTogglesInFlight.value) return
        _pinTogglesInFlight.update { it + playlistId }
        viewModelScope.launch {
            try {
                val changed =
                    runCatching {
                            layoutMutations?.setPlaylistPinned(playlistId, !summary.pinned) == true
                        }
                        .getOrDefault(false)
                if (changed) {
                    _pinToggleCompletions.emit(Unit)
                }
            } finally {
                _pinTogglesInFlight.update { it - playlistId }
            }
        }
    }

    internal fun createPlaylist(rawName: String) {
        if (_playlistLifecycleInFlight.value) return
        val operationId = ++nextPlaylistLifecycleOperationId
        _playlistLifecycleInFlight.value = true
        viewModelScope.launch {
            try {
                val result =
                    runCatching { layoutMutations?.createUserPlaylist(rawName) }.getOrNull()
                        ?: R16PlaylistLifecycleResult.Failed
                _playlistLifecycleCompletion.value =
                    R16PlaylistLifecycleCompletion(operationId, result.toLifecycleEffect())
            } finally {
                _playlistLifecycleInFlight.value = false
            }
        }
    }

    internal fun acknowledgePlaylistLifecycleCompletion(operationId: Long) {
        if (_playlistLifecycleCompletion.value?.operationId == operationId) {
            _playlistLifecycleCompletion.value = null
        }
    }
}

/** Retained until the active Fragment consumes this exact operation completion. */
internal data class R16PlaylistLifecycleCompletion(
    val operationId: Long,
    val effect: R16PlaylistLifecycleEffect,
)

internal sealed interface R16PlaylistLifecycleEffect {
    data class Created(val playlistId: PlaylistId) : R16PlaylistLifecycleEffect

    data object Updated : R16PlaylistLifecycleEffect

    data object Deleted : R16PlaylistLifecycleEffect

    data class Failure(val reason: R16PlaylistLifecycleFailure) : R16PlaylistLifecycleEffect
}

internal enum class R16PlaylistLifecycleFailure {
    INVALID_NAME,
    NOT_FOUND,
    FAILED,
}

internal fun R16PlaylistLifecycleResult.toLifecycleEffect(): R16PlaylistLifecycleEffect =
    when (this) {
        is R16PlaylistLifecycleResult.Created -> R16PlaylistLifecycleEffect.Created(playlistId)
        R16PlaylistLifecycleResult.Updated -> R16PlaylistLifecycleEffect.Updated
        R16PlaylistLifecycleResult.Deleted -> R16PlaylistLifecycleEffect.Deleted
        R16PlaylistLifecycleResult.InvalidName ->
            R16PlaylistLifecycleEffect.Failure(R16PlaylistLifecycleFailure.INVALID_NAME)
        R16PlaylistLifecycleResult.NotFound ->
            R16PlaylistLifecycleEffect.Failure(R16PlaylistLifecycleFailure.NOT_FOUND)
        R16PlaylistLifecycleResult.Failed ->
            R16PlaylistLifecycleEffect.Failure(R16PlaylistLifecycleFailure.FAILED)
    }
