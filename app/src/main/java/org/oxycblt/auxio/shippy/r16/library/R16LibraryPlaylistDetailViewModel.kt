/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryPlaylistDetailViewModel.kt is part of Auxio.
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
import app.shippy.core.identity.PlaylistEntryId
import app.shippy.core.identity.PlaylistId
import app.shippy.core.library.PlaylistSort
import app.shippy.data.browser.R16MediaBrowserId
import app.shippy.data.browser.R16MediaBrowserIdCodec
import app.shippy.data.browser.R16MediaBrowserItem
import app.shippy.data.browser.R16MediaBrowserPlaylistPlaybackContext
import app.shippy.data.browser.R16MediaBrowserPlaylistSummary
import app.shippy.data.db.view.PlaylistEntryRowView
import app.shippy.data.library.R16LibraryPlaylistQuery
import app.shippy.data.library.R16PlaylistEntryMoveDirection
import app.shippy.data.library.R16PlaylistEntryMoveResult
import app.shippy.data.library.R16PlaylistLifecycleResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.r16.authority.R16ActiveDataRuntimeOwner

/** Loads one canonical playlist and its occurrence-preserving entry pages. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class R16LibraryPlaylistDetailViewModel
@Inject
constructor(activeRuntimeOwner: R16ActiveDataRuntimeOwner) : ViewModel() {
    private val runtime = activeRuntimeOwner.activeRuntimeOrNull()
    private val browser = runtime?.mediaBrowser
    private val readModels = runtime?.libraryReadModels
    private val mutations = runtime?.libraryMutations
    private val query = MutableStateFlow(R16LibraryPlaylistQuery())
    private var searchJob: Job? = null
    private val pages = mutableMapOf<PlaylistId, Flow<PagingData<PlaylistEntryRowView>>>()
    private val summaryRefresh = MutableStateFlow(0)
    private val pagingConfig = PagingConfig(pageSize = 50, enablePlaceholders = false)
    private val _lifecycleInFlight = MutableStateFlow(false)
    private val _lifecycleCompletion = MutableStateFlow<R16PlaylistLifecycleCompletion?>(null)
    private val _entryRemovalsInFlight = MutableStateFlow<Set<PlaylistEntryId>>(emptySet())
    private val _entryRemovalCompletions =
        MutableStateFlow<Map<Long, R16PlaylistEntryRemovalCompletion>>(emptyMap())
    private val _selectedEntryIds = MutableStateFlow<Set<PlaylistEntryId>>(emptySet())
    private val _editOrderMode = MutableStateFlow(false)
    private val _entryMoveInFlight = MutableStateFlow<PlaylistEntryId?>(null)
    private val _entryMoveCompletions =
        MutableStateFlow<Map<Long, R16PlaylistEntryMoveCompletion>>(emptyMap())
    private var nextLifecycleOperationId = 0L
    private var nextEntryRemovalOperationId = 0L
    private var nextEntryMoveOperationId = 0L
    private var sortInitialized = false

    internal val isFiltering: Flow<Boolean> = query.map { it.search != null }.distinctUntilChanged()
    internal val lifecycleInFlight = _lifecycleInFlight.asStateFlow()
    internal val lifecycleCompletion = _lifecycleCompletion.asStateFlow()
    internal val entryRemovalsInFlight = _entryRemovalsInFlight.asStateFlow()
    internal val entryRemovalCompletions = _entryRemovalCompletions.asStateFlow()
    internal val selectedEntryIds = _selectedEntryIds.asStateFlow()
    internal val editOrderMode = _editOrderMode.asStateFlow()
    internal val entryMoveInFlight = _entryMoveInFlight.asStateFlow()
    internal val entryMoveCompletions = _entryMoveCompletions.asStateFlow()
    internal val sort: Flow<PlaylistSort> = query.map { it.sort }.distinctUntilChanged()

    internal fun toggleSelect(id: PlaylistEntryId) {
        _selectedEntryIds.value =
            if (id in _selectedEntryIds.value) _selectedEntryIds.value - id
            else _selectedEntryIds.value + id
    }

    internal fun clearSelection() {
        _selectedEntryIds.value = emptySet()
    }

    internal fun summary(playlistId: PlaylistId): Flow<R16MediaBrowserPlaylistSummary?> =
        summaryRefresh.flatMapLatest {
            flow {
                    val mediaId =
                        R16MediaBrowserIdCodec.encode(R16MediaBrowserId.Playlist(playlistId))
                    emit((browser?.item(mediaId) as? R16MediaBrowserItem.Playlist)?.summary)
                }
                .onEach { summary ->
                    if (summary != null && !sortInitialized) {
                        sortInitialized = true
                        query.value = query.value.copy(sort = summary.displaySort.normalized())
                    }
                }
                .catch { emit(null) }
        }

    internal fun entries(playlistId: PlaylistId): Flow<PagingData<PlaylistEntryRowView>> =
        pages.getOrPut(playlistId) {
            readModels?.let { repository ->
                query
                    .flatMapLatest { search ->
                        Pager(pagingConfig) { repository.playlistEntries(playlistId.value, search) }
                            .flow
                    }
                    .cachedIn(viewModelScope)
            } ?: flowOf(PagingData.empty())
        }

    internal fun updateSearchQuery(rawQuery: CharSequence?) {
        val next = rawQuery?.toString()?.trim()?.takeIf(String::isNotEmpty)
        if (next != null) _editOrderMode.value = false
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                if (next != null) delay(200)
                query.value = query.value.copy(search = next)
            }
    }

    internal fun playbackContext(): R16MediaBrowserPlaylistPlaybackContext =
        R16MediaBrowserPlaylistPlaybackContext(
            search = query.value.search,
            sort = query.value.sort.normalized(),
        )

    internal fun setPlaylistSort(playlistId: PlaylistId, sort: PlaylistSort) {
        val normalizedSort = sort.normalized()
        if (_editOrderMode.value || normalizedSort == query.value.sort.normalized()) return
        viewModelScope.launch {
            val saved =
                runCatching { mutations?.setPlaylistDisplaySort(playlistId, normalizedSort) }
                    .getOrNull()
            if (saved == true) {
                query.value = query.value.copy(sort = normalizedSort)
                summaryRefresh.value += 1
            }
        }
    }

    internal fun setEditOrderMode(enabled: Boolean) {
        if (enabled && query.value.search != null) return
        if (enabled && _entryRemovalsInFlight.value.isNotEmpty()) return
        if (_entryMoveInFlight.value != null) return
        _editOrderMode.value = enabled
    }

    internal fun refreshSummary() {
        summaryRefresh.value += 1
    }

    internal fun renamePlaylist(playlistId: PlaylistId, rawName: String) {
        if (_lifecycleInFlight.value) return
        val operationId = ++nextLifecycleOperationId
        _lifecycleInFlight.value = true
        viewModelScope.launch {
            try {
                val result =
                    runCatching { mutations?.renamePlaylist(playlistId, rawName) }.getOrNull()
                        ?: R16PlaylistLifecycleResult.Failed
                _lifecycleCompletion.value =
                    R16PlaylistLifecycleCompletion(operationId, result.toLifecycleEffect())
            } finally {
                _lifecycleInFlight.value = false
            }
        }
    }

    internal fun deletePlaylist(playlistId: PlaylistId) {
        if (_lifecycleInFlight.value) return
        val operationId = ++nextLifecycleOperationId
        _lifecycleInFlight.value = true
        viewModelScope.launch {
            try {
                val result =
                    runCatching { mutations?.deletePlaylist(playlistId) }.getOrNull()
                        ?: R16PlaylistLifecycleResult.Failed
                _lifecycleCompletion.value =
                    R16PlaylistLifecycleCompletion(operationId, result.toLifecycleEffect())
            } finally {
                _lifecycleInFlight.value = false
            }
        }
    }

    internal fun removePlaylistEntry(playlistId: PlaylistId, playlistEntryId: PlaylistEntryId) {
        if (_editOrderMode.value) return
        if (playlistEntryId in _entryRemovalsInFlight.value) return
        val operationId = ++nextEntryRemovalOperationId
        _entryRemovalsInFlight.value += playlistEntryId
        viewModelScope.launch {
            try {
                val removed =
                    runCatching { mutations?.removePlaylistEntry(playlistId, playlistEntryId) }
                        .getOrNull() == true
                _entryRemovalCompletions.value =
                    _entryRemovalCompletions.value +
                        (operationId to
                            R16PlaylistEntryRemovalCompletion(
                                operationId = operationId,
                                playlistEntryId = playlistEntryId,
                                removed = removed,
                            ))
            } finally {
                _entryRemovalsInFlight.value -= playlistEntryId
            }
        }
    }

    internal fun removeSelectedEntries(playlistId: PlaylistId) {
        val selected = _selectedEntryIds.value
        if (selected.isEmpty()) return
        val operationId = ++nextEntryRemovalOperationId
        _entryRemovalsInFlight.value = _entryRemovalsInFlight.value + selected
        _selectedEntryIds.value = emptySet()
        viewModelScope.launch {
            try {
                val removedCount =
                    runCatching { mutations?.removePlaylistEntries(playlistId, selected) }
                        .getOrNull() ?: 0
                _entryRemovalCompletions.value =
                    _entryRemovalCompletions.value +
                        (operationId to
                            R16PlaylistEntryRemovalCompletion(
                                operationId = operationId,
                                playlistEntryId = selected.first(),
                                removed = removedCount > 0,
                            ))
            } finally {
                _entryRemovalsInFlight.value = _entryRemovalsInFlight.value - selected
            }
        }
    }

    internal fun movePlaylistEntry(
        playlistId: PlaylistId,
        playlistEntryId: PlaylistEntryId,
        direction: R16PlaylistEntryMoveDirection,
    ) {
        if (!_editOrderMode.value || query.value.search != null) return
        if (_entryRemovalsInFlight.value.isNotEmpty()) return
        if (_entryMoveInFlight.value != null) return
        val operationId = ++nextEntryMoveOperationId
        _entryMoveInFlight.value = playlistEntryId
        viewModelScope.launch {
            try {
                val result =
                    runCatching {
                            mutations?.movePlaylistEntry(playlistId, playlistEntryId, direction)
                        }
                        .getOrNull() ?: R16PlaylistEntryMoveResult.Failed
                if (result == R16PlaylistEntryMoveResult.NotCustomOrder) {
                    _editOrderMode.value = false
                }
                _entryMoveCompletions.value =
                    _entryMoveCompletions.value +
                        (operationId to
                            R16PlaylistEntryMoveCompletion(
                                operationId = operationId,
                                playlistEntryId = playlistEntryId,
                                direction = direction,
                                result = result,
                            ))
            } finally {
                if (_entryMoveInFlight.value == playlistEntryId) {
                    _entryMoveInFlight.value = null
                }
            }
        }
    }

    internal fun moveSelectedEntries(
        playlistId: PlaylistId,
        targetAnchorEntryId: PlaylistEntryId?,
        moveBefore: Boolean,
        selectedIdsInOrder: List<PlaylistEntryId>,
    ) {
        val selected = _selectedEntryIds.value
        if (selected.isEmpty()) return
        if (!canMoveSelectedPlaylistEntries(_editOrderMode.value, query.value.search != null))
            return
        if (_entryRemovalsInFlight.value.isNotEmpty()) return
        if (_entryMoveInFlight.value != null) return

        val entryIdsToMove = selectedPlaylistEntryIdsInOrder(selected, selectedIdsInOrder)
        if (entryIdsToMove.isEmpty()) return

        val operationId = ++nextEntryMoveOperationId
        _entryMoveInFlight.value = entryIdsToMove.first()
        viewModelScope.launch {
            try {
                val success =
                    runCatching {
                            mutations?.movePlaylistEntries(
                                playlistId = playlistId,
                                entryIdsToMove = entryIdsToMove,
                                targetAnchorEntryId = targetAnchorEntryId,
                                moveBefore = moveBefore,
                            )
                        }
                        .getOrNull() == true
                val result =
                    if (success) R16PlaylistEntryMoveResult.Moved
                    else R16PlaylistEntryMoveResult.Failed
                _entryMoveCompletions.value =
                    _entryMoveCompletions.value +
                        (operationId to
                            R16PlaylistEntryMoveCompletion(
                                operationId = operationId,
                                playlistEntryId = entryIdsToMove.first(),
                                direction = R16PlaylistEntryMoveDirection.TOWARD_START,
                                result = result,
                            ))
            } finally {
                if (_entryMoveInFlight.value == entryIdsToMove.first()) {
                    _entryMoveInFlight.value = null
                }
            }
        }
    }

    internal fun acknowledgeLifecycleCompletion(operationId: Long) {
        if (_lifecycleCompletion.value?.operationId == operationId) {
            _lifecycleCompletion.value = null
        }
    }

    internal fun acknowledgeEntryRemovalCompletion(operationId: Long) {
        _entryRemovalCompletions.value = _entryRemovalCompletions.value - operationId
    }

    internal fun acknowledgeEntryMoveCompletion(operationId: Long) {
        _entryMoveCompletions.value = _entryMoveCompletions.value - operationId
    }
}

/** Retained by operation token so a STOPPED/config-changed detail screen cannot lose the result. */
internal data class R16PlaylistEntryRemovalCompletion(
    val operationId: Long,
    val playlistEntryId: PlaylistEntryId,
    val removed: Boolean,
)

/** Retained by operation token so adjacent-move results survive STOP/config changes. */
internal data class R16PlaylistEntryMoveCompletion(
    val operationId: Long,
    val playlistEntryId: PlaylistEntryId,
    val direction: R16PlaylistEntryMoveDirection,
    val result: R16PlaylistEntryMoveResult,
)

internal fun playlistQuery(
    rawQuery: CharSequence?,
    sort: PlaylistSort = PlaylistSort(),
): R16LibraryPlaylistQuery =
    R16LibraryPlaylistQuery(rawQuery?.toString()?.trim()?.takeIf(String::isNotEmpty), sort)

/** Batch reordering is safe only against the canonical, unfiltered playlist order. */
internal fun canMoveSelectedPlaylistEntries(editOrderMode: Boolean, isFiltering: Boolean): Boolean =
    editOrderMode && !isFiltering

/**
 * Keeps duplicate recording occurrences distinct by ordering their PlaylistEntryIds from the loaded
 * list.
 */
internal fun selectedPlaylistEntryIdsInOrder(
    selectedEntryIds: Set<PlaylistEntryId>,
    loadedEntryIdsInOrder: List<PlaylistEntryId>,
): List<PlaylistEntryId> = loadedEntryIdsInOrder.filter { it in selectedEntryIds }
