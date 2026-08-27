/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryArtistDetailViewModel.kt is part of Auxio.
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
import app.shippy.core.identity.ArtistId
import app.shippy.data.db.view.LibrarySongRowView
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Paged current-Library recordings for one canonical artist. */
@HiltViewModel
internal class R16LibraryArtistDetailViewModel
@Inject
constructor(activation: R16LibraryReadModelsActivation) : ViewModel() {
    private val readModels = activation.readModelsOrNull()

    internal fun songs(artistId: ArtistId): Flow<PagingData<LibrarySongRowView>> =
        readModels?.let { repository ->
            Pager(R16_LIBRARY_ARTIST_SONGS_PAGING_CONFIG) { repository.artistSongs(artistId.value) }
                .flow
                .cachedIn(viewModelScope)
        } ?: flowOf(PagingData.empty())
}

private val R16_LIBRARY_ARTIST_SONGS_PAGING_CONFIG =
    PagingConfig(pageSize = 50, enablePlaceholders = false)
