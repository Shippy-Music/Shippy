/*
 * Copyright (c) 2026 Auxio Project
 * LastFmHomeViewModel.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.lastfm.LastFmCredentialRepository
import org.oxycblt.auxio.shippy.lastfm.LastFmOverview
import org.oxycblt.auxio.shippy.lastfm.LastFmOverviewCache
import org.oxycblt.auxio.shippy.lastfm.LastFmOverviewClient
import org.oxycblt.auxio.shippy.lastfm.LastFmOverviewResult

@HiltViewModel
class LastFmHomeViewModel
@Inject
constructor(
    private val credentials: LastFmCredentialRepository,
    private val client: LastFmOverviewClient,
    private val cache: LastFmOverviewCache,
) : ViewModel() {
    private val mutableState = MutableStateFlow<LastFmHomeState>(LastFmHomeState.Loading)
    val state: StateFlow<LastFmHomeState> = mutableState.asStateFlow()

    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                val auth =
                    try {
                        credentials.load()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        mutableState.value = LastFmHomeState.Error
                        return@launch
                    }
                if (auth == null) {
                    runCatching { cache.clear() }
                        .onFailure { if (it is CancellationException) throw it }
                    mutableState.value = LastFmHomeState.Hidden
                    return@launch
                }

                var cached =
                    try {
                        cache.load()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        null
                    }
                if (cached != null && cached.username != auth.username) {
                    runCatching { cache.clear() }
                        .onFailure { if (it is CancellationException) throw it }
                    cached = null
                }
                mutableState.value =
                    cached?.let { LastFmHomeState.Content(it, stale = true) }
                        ?: LastFmHomeState.Loading

                when (val result = client.load(auth)) {
                    is LastFmOverviewResult.Success -> {
                        try {
                            cache.save(result.overview)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                        }
                        mutableState.value = LastFmHomeState.Content(result.overview, stale = false)
                    }
                    is LastFmOverviewResult.Failure -> {
                        if (cached == null) mutableState.value = LastFmHomeState.Error
                    }
                }
            }
    }
}

sealed interface LastFmHomeState {
    data object Hidden : LastFmHomeState

    data object Loading : LastFmHomeState

    data class Content(val overview: LastFmOverview, val stale: Boolean) : LastFmHomeState

    data object Error : LastFmHomeState
}
