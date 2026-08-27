/*
 * Copyright (c) 2026 Auxio Project
 * R16HomeHistoryViewModel.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.shippy.data.home.R16HomeHistoryItem
import app.shippy.data.home.R16HomePinnedPlaylistShortcut
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.r16.authority.R16ActiveDataRuntimeOwner

/** Small ACTIVE-only read model: bounded Home summaries plus raw paged sessions. */
@HiltViewModel
internal class R16HomeHistoryViewModel
@Inject
constructor(activeRuntimeOwner: R16ActiveDataRuntimeOwner) : ViewModel() {
    private val repository = activeRuntimeOwner.activeRuntimeOrNull()?.home
    private val _recentlyPlayed = MutableStateFlow<List<R16HomeHistoryItem>>(emptyList())
    val recentlyPlayed = _recentlyPlayed.asStateFlow()

    val pinnedPlaylistShortcuts: Flow<List<R16HomePinnedPlaylistShortcut>> =
        repository?.pinnedPlaylistShortcuts() ?: flowOf(emptyList())

    val history: Flow<PagingData<R16HomeHistoryItem>> =
        repository?.let { source ->
            Pager(PagingConfig(pageSize = 50, enablePlaceholders = false)) { source.history() }
                .flow
                .cachedIn(viewModelScope)
        } ?: flowOf(PagingData.empty())

    fun refreshRecentlyPlayed() {
        val source = repository ?: return
        viewModelScope.launch { _recentlyPlayed.value = source.recentlyPlayed() }
    }
}
