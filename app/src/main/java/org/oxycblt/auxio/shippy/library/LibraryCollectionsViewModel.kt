/*
 * Copyright (c) 2026 Shippy contributors
 * LibraryCollectionsViewModel.kt is part of Shippy.
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
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.domain.LibraryCollection
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.SystemCollectionKind
import org.oxycblt.auxio.shippy.persistence.library.LibraryRelationshipRepository
import org.oxycblt.auxio.shippy.persistence.library.SavedProviderEntity
import org.oxycblt.auxio.shippy.persistence.library.SavedProviderEntityRepository

/**
 * Read-only projection of Shippy-owned library relationships.
 *
 * Local music remains owned by Auxio/Musikr and is supplied by the Library fragment. Track
 * relationships stay metadata-neutral; saved provider browse targets carry only the exact
 * provider identity and presentation metadata needed to reopen their detail route.
 */
@HiltViewModel
class LibraryCollectionsViewModel
@Inject
constructor(
    private val repository: LibraryRelationshipRepository,
    savedProviderEntities: SavedProviderEntityRepository,
) : ViewModel() {

    val state: StateFlow<LibraryCollectionsState> =
        combine(
                repository.observeLikedTrackIds(),
                repository.observeDownloadedTrackIds(),
                repository.observeUserPlaylists(),
                savedProviderEntities.observeAll(),
            ) { liked, downloaded, userPlaylists, savedEntities ->
                LibraryCollectionsState(
                    likedCount = liked.size,
                    downloadedCount = downloaded.size,
                    userPlaylists = userPlaylists,
                    savedProviderEntities = savedEntities,
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                LibraryCollectionsState(),
            )

    /**
     * Creates a persisted Shippy playlist when [rawName] has meaningful content.
     *
     * The UUID-backed ID deliberately belongs to Shippy's Room library rather than Auxio's
     * device-playlist store. Returning false lets the UI keep the name dialog open without
     * creating an invalid playlist.
     */
    fun createPlaylist(rawName: CharSequence): Boolean {
        val playlist = newShippyPlaylistOrNull(rawName) ?: return false
        viewModelScope.launch { repository.createPlaylist(playlist) }
        return true
    }

    /**
     * Stores an adapter-produced playlist order only when it is still a complete, current
     * projection. A concurrent create, delete, or pin change therefore rejects the drag instead
     * of allowing a stale partial order to reach persistence.
     */
    fun reorderUserPlaylists(reorderedPlaylists: List<LibraryCollection.Playlist>): Boolean {
        val playlistIds = reorderUserPlaylistIds(state.value.userPlaylists, reorderedPlaylists)
            ?: return false
        viewModelScope.launch { repository.replacePlaylistOrder(playlistIds) }
        return true
    }
}

internal fun newShippyPlaylistOrNull(
    rawName: CharSequence,
    idGenerator: () -> UUID = UUID::randomUUID,
): LibraryCollection.Playlist? {
    val name = rawName.toString().trim()
    if (name.isEmpty()) return null

    return LibraryCollection.Playlist(
        id = LibraryCollectionId("playlist:${idGenerator()}"),
        displayName = name,
        isPinned = false,
    )
}

/** Returns a complete safe order, or null when a drag no longer matches the current projection. */
internal fun reorderUserPlaylistIds(
    currentPlaylists: List<LibraryCollection.Playlist>,
    reorderedPlaylists: List<LibraryCollection.Playlist>,
): List<LibraryCollectionId>? {
    val currentIds = currentPlaylists.map(LibraryCollection.Playlist::id)
    val reorderedIds = reorderedPlaylists.map(LibraryCollection.Playlist::id)
    if (
        currentIds.size != currentIds.distinct().size ||
            reorderedIds.size != reorderedIds.distinct().size ||
            currentIds.toSet() != reorderedIds.toSet()
    ) {
        return null
    }

    val pinnedById = currentPlaylists.associate { it.id to it.isPinned }
    if (reorderedPlaylists.any { pinnedById.getValue(it.id) != it.isPinned }) return null
    if (reorderedIds.zipWithNext().any { (before, after) ->
            !pinnedById.getValue(before) && pinnedById.getValue(after)
        }) {
        return null
    }

    return reorderedIds.takeIf { it != currentIds }
}

internal data class LibraryCollectionsState(
    val likedCount: Int = 0,
    val downloadedCount: Int = 0,
    val userPlaylists: List<LibraryCollection.Playlist> = emptyList(),
    val savedProviderEntities: List<SavedProviderEntity> = emptyList(),
)

internal data class LibrarySystemCollectionRow(
    val kind: SystemCollectionKind,
    val itemCount: Int,
    val isLoading: Boolean = false,
)

internal fun LibraryCollectionsState.systemRows(
    localSongCount: Int,
    isLocalIndexing: Boolean,
): List<LibrarySystemCollectionRow> =
    listOf(
        LibrarySystemCollectionRow(SystemCollectionKind.LIKED, likedCount),
        LibrarySystemCollectionRow(SystemCollectionKind.DOWNLOADS, downloadedCount),
        LibrarySystemCollectionRow(
            SystemCollectionKind.LOCAL,
            localSongCount,
            isLoading = isLocalIndexing,
        ),
    )

internal fun LibraryCollectionsState.shouldShowOnboarding(
    localSongCount: Int,
    devicePlaylistCount: Int,
    isLocalIndexing: Boolean,
): Boolean =
    !isLocalIndexing &&
        localSongCount == 0 &&
        devicePlaylistCount == 0 &&
        likedCount == 0 &&
        downloadedCount == 0 &&
        userPlaylists.isEmpty() &&
        savedProviderEntities.isEmpty()
