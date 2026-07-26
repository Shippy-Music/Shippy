/*
 * Copyright (c) 2026 Shippy contributors
 * HomeContinuationViewModel.kt is part of Shippy.
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
                recentlyPlayed = recent.take(HOME_RECENT_LIMIT),
                recentDownloads =
                    available
                        .sortedByDescending {
                            it.job.artifact?.verifiedAtEpochMs ?: it.updatedAtEpochMs
                        }
                        .take(HOME_RECENT_LIMIT),
            )
        }.stateIn(
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
        const val HOME_RECENT_LIMIT = 5
    }
}
