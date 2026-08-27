/*
 * Copyright (c) 2026 Auxio Project
 * R16LibrarySearchViewModel.kt is part of Auxio.
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
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import app.shippy.data.db.view.ArtistLibrarySummaryRow
import app.shippy.data.db.view.LibrarySongRowView
import app.shippy.data.db.view.PlaylistSummaryRowView
import app.shippy.data.library.R16LibrarySearchQuery
import app.shippy.data.library.R16LibrarySongQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Provider-free, paged result state for the dedicated Library Search route. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class R16LibrarySearchViewModel
@Inject
constructor(activation: R16LibraryReadModelsActivation) : ViewModel() {
    private val readModels = activation.readModelsOrNull()
    private val mutableQuery = MutableStateFlow("")

    internal val query: StateFlow<String> = mutableQuery

    internal val songs: Flow<PagingData<LibrarySongRowView>> = paged { repository, text ->
        repository.songs(R16LibrarySongQuery(text))
    }

    internal val playlists: Flow<PagingData<PlaylistSummaryRowView>> = paged { repository, text ->
        repository.searchPlaylists(R16LibrarySearchQuery(text))
    }

    internal val artists: Flow<PagingData<ArtistLibrarySummaryRow>> = paged { repository, text ->
        repository.searchArtists(R16LibrarySearchQuery(text))
    }

    internal fun updateQuery(rawQuery: CharSequence?) {
        mutableQuery.value = rawQuery?.toString()?.trim().orEmpty()
    }

    private fun <Item : Any> paged(
        source:
            (app.shippy.data.library.R16LibraryReadRepository, String) -> PagingSource<Int, Item>
    ): Flow<PagingData<Item>> =
        readModels?.let { repository ->
            mutableQuery
                .flatMapLatest { text ->
                    if (text.isBlank()) flowOf(PagingData.empty())
                    else Pager(R16_LIBRARY_SONGS_PAGING_CONFIG) { source(repository, text) }.flow
                }
                .cachedIn(viewModelScope)
        } ?: flowOf(PagingData.empty())
}
