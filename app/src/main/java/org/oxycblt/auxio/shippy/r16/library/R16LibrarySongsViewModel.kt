/*
 * Copyright (c) 2026 Auxio Project
 * R16LibrarySongsViewModel.kt is part of Auxio.
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
import app.shippy.data.db.view.LibrarySongRowView
import app.shippy.data.library.R16LibrarySongQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.shippy.r16.authority.R16ActiveDataRuntimeOwner

/**
 * Inactive canonical Songs screen state.
 *
 * This ViewModel cannot activate R16 itself: an absent authority exposes an empty paging stream.
 * The future host creates this screen only after selecting R16. Every query then creates a fresh
 * Room PagingSource and `flatMapLatest` cancels the previous stream before its rows can reach the
 * adapter.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class R16LibrarySongsViewModel
@Inject
constructor(
    activation: R16LibraryReadModelsActivation,
    private val activeRuntimeOwner: R16ActiveDataRuntimeOwner,
) : ViewModel() {
    private val query = MutableStateFlow(R16LibrarySongQuery())
    private val readModels = activation.readModelsOrNull()

    internal val songs: Flow<PagingData<LibrarySongRowView>> =
        readModels?.let { repository ->
            query
                .flatMapLatest { search ->
                    Pager(R16_LIBRARY_SONGS_PAGING_CONFIG) { repository.songs(search) }.flow
                }
                .cachedIn(viewModelScope)
        } ?: flowOf(PagingData.empty())

    internal val currentSearchQuery: String?
        get() = query.value.search

    internal fun updateSearchQuery(rawQuery: CharSequence?) {
        query.value = librarySongsQuery(rawQuery)
    }

    /**
     * Durable Undo is intentionally hosted by the persistent Library owner, not a dismissed sheet.
     */
    internal fun undoIdentification(sourceReferenceId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                activeRuntimeOwner
                    .activeRuntimeOrNull()
                    ?.libraryMutations
                    ?.undoLatestIdentifyCandidate(sourceReferenceId)
            }
        }
    }
}

internal val R16_LIBRARY_SONGS_PAGING_CONFIG =
    PagingConfig(pageSize = 50, enablePlaceholders = false)

internal fun librarySongsQuery(rawQuery: CharSequence?): R16LibrarySongQuery =
    R16LibrarySongQuery(rawQuery?.toString()?.trim()?.takeIf(String::isNotEmpty))
