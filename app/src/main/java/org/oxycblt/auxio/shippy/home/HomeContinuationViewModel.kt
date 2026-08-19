/*
 * Copyright (c) 2026 Auxio Project
 * HomeContinuationViewModel.kt is part of Auxio.
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.domain.SystemCollectionKind
import org.oxycblt.auxio.shippy.history.RecentListeningEntry
import org.oxycblt.auxio.shippy.history.RecentListeningRepository
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobRepository
import org.oxycblt.auxio.shippy.persistence.download.PersistedDownload
import org.oxycblt.auxio.shippy.playback.ShippyPlaybackController

internal data class HomeContinuationState(
    val recentlyPlayed: List<RecentListeningEntry> = emptyList(),
    val recentDownloads: List<PersistedDownload> = emptyList(),
)

@HiltViewModel
internal class HomeContinuationViewModel
@Inject
constructor(
    recentListening: RecentListeningRepository,
    downloads: DownloadJobRepository,
    private val playback: ShippyPlaybackController,
) : ViewModel() {
    val state: StateFlow<HomeContinuationState> =
        combine(recentListening.observe(), downloads.observeAvailable()) { recent, available ->
                HomeContinuationState(
                    recentlyPlayed =
                        recent.distinctBy(RecentListeningEntry::trackId).take(HOME_RECENT_LIMIT),
                    recentDownloads =
                        available
                            .sortedByDescending {
                                it.job.artifact?.verifiedAtEpochMs ?: it.updatedAtEpochMs
                            }
                            .take(HOME_RECENT_LIMIT),
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                HomeContinuationState(),
            )

    fun playDownload(download: PersistedDownload) {
        viewModelScope.launch {
            playback.playQueue(
                tracks = listOf(download.track),
                selectedIndex = 0,
                contextId = "system:${SystemCollectionKind.DOWNLOADS.id}",
            )
        }
    }

    private companion object {
        const val HOME_RECENT_LIMIT = 6
    }
}
