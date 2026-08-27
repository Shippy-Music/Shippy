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
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Paging-only display state for a rule-derived Library collection. */
@HiltViewModel
internal class R16SystemCollectionDetailViewModel
@Inject
constructor(activation: R16LibraryReadModelsActivation) : ViewModel() {
    private val readModels = activation.readModelsOrNull()

    internal fun songs(collection: R16SystemCollection): Flow<PagingData<LibrarySongRowView>> =
        readModels?.let { repository ->
            Pager(R16_LIBRARY_SONGS_PAGING_CONFIG) { repository.systemCollection(collection) }
                .flow
                .cachedIn(viewModelScope)
        } ?: flowOf(PagingData.empty())
}
