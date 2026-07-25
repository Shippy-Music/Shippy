/*
 * Copyright (c) 2026 Shippy contributors
 * ShippyCollectionDetailViewModel.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.domain.LibraryCollection
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.SystemCollectionKind
import org.oxycblt.auxio.shippy.persistence.library.LibraryRelationshipRepository

/**
 * Read-only detail projection for relationship-backed collections.
 *
 * Relationship storage intentionally knows IDs, not track presentation metadata. Until the
 * canonical metadata cache can resolve each ID, the UI must not pretend those IDs are playable
 * song rows. The state therefore reports a count and an honest unresolved status instead.
 */
@HiltViewModel
class ShippyCollectionDetailViewModel
@Inject
constructor(private val repository: LibraryRelationshipRepository) : ViewModel() {
    fun observe(collectionId: LibraryCollectionId): Flow<ShippyCollectionDetailState> =
        when (collectionId.value) {
            "system:${SystemCollectionKind.LIKED.id}" ->
                repository.observeLikedTrackIds().map { ids ->
                    ShippyCollectionDetailState.System(SystemCollectionKind.LIKED.displayName, ids.size)
                }
            "system:${SystemCollectionKind.DOWNLOADS.id}" ->
                repository.observeDownloadedTrackIds().map { ids ->
                    ShippyCollectionDetailState.System(
                        SystemCollectionKind.DOWNLOADS.displayName,
                        ids.size,
                    )
                }
            else ->
                if (collectionId.isSystem) {
                    flowOf(ShippyCollectionDetailState.Missing)
                } else {
                    combine(
                        repository.observeUserPlaylist(collectionId),
                        repository.observePlaylistTrackIds(collectionId),
                    ) { playlist, trackIds ->
                        playlist?.let { ShippyCollectionDetailState.Playlist(it, trackIds.size) }
                            ?: ShippyCollectionDetailState.Missing
                    }
                }
        }

    fun rename(playlistId: LibraryCollectionId, name: String) {
        if (playlistId.isSystem || name.isBlank()) return
        viewModelScope.launch { repository.renamePlaylist(playlistId, name.trim()) }
    }

    fun setPinned(playlistId: LibraryCollectionId, pinned: Boolean) {
        if (playlistId.isSystem) return
        viewModelScope.launch { repository.setPlaylistPinned(playlistId, pinned) }
    }

    fun delete(playlistId: LibraryCollectionId) {
        if (playlistId.isSystem) return
        viewModelScope.launch { repository.deletePlaylist(playlistId) }
    }
}

internal sealed interface ShippyCollectionDetailState {
    val title: String
    val unresolvedTrackCount: Int

    data class System(
        override val title: String,
        override val unresolvedTrackCount: Int,
    ) : ShippyCollectionDetailState

    data class Playlist(
        val playlist: LibraryCollection.Playlist,
        override val unresolvedTrackCount: Int,
    ) : ShippyCollectionDetailState {
        override val title: String = playlist.displayName
    }

    data object Missing : ShippyCollectionDetailState {
        override val title: String = "Playlist unavailable"
        override val unresolvedTrackCount: Int = 0
    }
}
