/*
 * Copyright (c) 2026 Auxio Project
 * R16SystemCollectionDetailViewModel.kt is part of Auxio.
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
import androidx.paging.cachedIn
import app.shippy.core.library.R16SystemCollection
import app.shippy.data.db.view.LibrarySongRowView
import app.shippy.data.library.R16LibrarySongQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** Paging-only display state for a rule-derived Library collection. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class R16SystemCollectionDetailViewModel
@Inject
constructor(activation: R16LibraryReadModelsActivation) : ViewModel() {
    private val readModels = activation.readModelsOrNull()
    private val search = MutableStateFlow<String?>(null)
    private var searchJob: Job? = null
    private val pages = mutableMapOf<R16SystemCollection, Flow<PagingData<LibrarySongRowView>>>()

    internal fun playbackQuery(): String? = search.value

    internal fun songs(collection: R16SystemCollection): Flow<PagingData<LibrarySongRowView>> =
        pages.getOrPut(collection) {
            readModels?.let { repository ->
                search
                    .flatMapLatest { query ->
                        Pager(R16_LIBRARY_SONGS_PAGING_CONFIG) {
                                repository.systemCollection(collection, R16LibrarySongQuery(query))
                            }
                            .flow
                    }
                    .cachedIn(viewModelScope)
            } ?: flowOf(PagingData.empty())
        }

    internal fun updateSearchQuery(rawQuery: CharSequence?) {
        val next = rawQuery?.toString()?.trim()?.takeIf(String::isNotEmpty)
        searchJob?.cancel()
        searchJob =
            viewModelScope.launch {
                if (next != null) delay(200)
                search.value = next
            }
    }
}
