/*
 * Copyright (c) 2026 Auxio Project
 * LibraryCollectionsViewModel.kt is part of Auxio.
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
 * relationships stay metadata-neutral; saved provider browse targets carry only the exact provider
 * identity and presentation metadata needed to reopen their detail route.
 */
@HiltViewModel
class LibraryCollectionsViewModel
@Inject
constructor(
    private val repository: LibraryRelationshipRepository,
    savedProviderEntities: SavedProviderEntityRepository,
    private val layoutStore: LibraryCollectionLayoutStore,
) : ViewModel() {

    private val baseState =
        combine(
            repository.observeLikedTrackIds(),
            repository.observeDownloadedTrackIds(),
            repository.observeUserPlaylists(),
            savedProviderEntities.observeAll(),
            repository.observePlaylistArtwork(),
        ) { liked, downloaded, userPlaylists, savedEntities, playlistArtwork ->
            LibraryCollectionsState(
                likedCount = liked.size,
                downloadedCount = downloaded.size,
                userPlaylists = userPlaylists,
                savedProviderEntities = savedEntities,
                playlistArtwork = playlistArtwork,
            )
        }

    internal val state: StateFlow<LibraryCollectionsState> =
        combine(baseState, layoutStore.entries) { state, layout ->
                state.copy(collectionLayout = layout)
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
     * device-playlist store. Returning false lets the UI keep the name dialog open without creating
     * an invalid playlist.
     */
    internal fun createPlaylist(rawName: CharSequence): Boolean {
        val playlist = newShippyPlaylistOrNull(rawName) ?: return false
        viewModelScope.launch { repository.createPlaylist(playlist) }
        return true
    }

    /**
     * Stores an adapter-produced playlist order only when it is still a complete, current
     * projection. A concurrent create, delete, or pin change therefore rejects the drag instead of
     * allowing a stale partial order to reach persistence.
     */
    internal fun reorderUserPlaylists(
        reorderedPlaylists: List<LibraryCollection.Playlist>
    ): Boolean {
        val playlistIds =
            reorderUserPlaylistIds(state.value.userPlaylists, reorderedPlaylists) ?: return false
        viewModelScope.launch { repository.replacePlaylistOrder(playlistIds) }
        return true
    }

    internal fun reorderCollections(reorderedRows: List<LibraryCollectionListRow>): Boolean {
        val expectedIds = buildSet {
            SystemCollectionKind.entries.forEach { add(LibraryCollection.System(it).id) }
            state.value.userPlaylists.forEach { add(it.id) }
        }
        if (
            reorderedRows.map { it.id }.distinct().size != reorderedRows.size ||
                reorderedRows.mapTo(mutableSetOf()) { it.id } != expectedIds
        ) {
            return false
        }
        layoutStore.replace(reorderedRows.map { LibraryCollectionLayoutEntry(it.id, it.isPinned) })
        val userPlaylists =
            reorderedRows.mapNotNull { row ->
                (row as? LibraryCollectionListRow.Playlist)?.playlist?.copy(isPinned = row.isPinned)
            }
        viewModelScope.launch { repository.replacePlaylistLayout(userPlaylists) }
        return true
    }

    internal fun setPinned(collectionId: LibraryCollectionId, pinned: Boolean) {
        layoutStore.setPinned(collectionId, pinned)
        if (!collectionId.isSystem) {
            viewModelScope.launch { repository.setPlaylistPinned(collectionId, pinned) }
        }
    }

    internal fun renamePlaylist(collectionId: LibraryCollectionId, name: String) {
        if (collectionId.isSystem || name.isBlank()) return
        viewModelScope.launch { repository.renamePlaylist(collectionId, name.trim()) }
    }

    internal fun setPlaylistArtwork(collectionId: LibraryCollectionId, artworkUri: String?) {
        if (collectionId.isSystem) return
        viewModelScope.launch { repository.setPlaylistArtwork(collectionId, artworkUri) }
    }

    internal fun deletePlaylist(collectionId: LibraryCollectionId) {
        if (collectionId.isSystem) return
        viewModelScope.launch { repository.deletePlaylist(collectionId) }
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
    if (
        reorderedIds.zipWithNext().any { (before, after) ->
            !pinnedById.getValue(before) && pinnedById.getValue(after)
        }
    ) {
        return null
    }

    return reorderedIds.takeIf { it != currentIds }
}

internal data class LibraryCollectionsState(
    val likedCount: Int = 0,
    val downloadedCount: Int = 0,
    val userPlaylists: List<LibraryCollection.Playlist> = emptyList(),
    val savedProviderEntities: List<SavedProviderEntity> = emptyList(),
    val playlistArtwork: Map<LibraryCollectionId, String> = emptyMap(),
    val collectionLayout: List<LibraryCollectionLayoutEntry> = emptyList(),
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

internal sealed interface LibraryCollectionListRow {
    val id: LibraryCollectionId
    val isPinned: Boolean

    fun withPinned(pinned: Boolean): LibraryCollectionListRow

    data class System(val collection: LibrarySystemCollectionRow, override val isPinned: Boolean) :
        LibraryCollectionListRow {
        override val id = LibraryCollection.System(collection.kind).id

        override fun withPinned(pinned: Boolean) = copy(isPinned = pinned)
    }

    data class Playlist(
        val playlist: LibraryCollection.Playlist,
        val artwork: String?,
        override val isPinned: Boolean,
    ) : LibraryCollectionListRow {
        override val id = playlist.id

        override fun withPinned(pinned: Boolean) = copy(isPinned = pinned)
    }
}

internal fun LibraryCollectionsState.collectionRows(
    localSongCount: Int,
    isLocalIndexing: Boolean,
): List<LibraryCollectionListRow> {
    val defaults = buildList {
        systemRows(localSongCount, isLocalIndexing).forEach { system ->
            add(LibraryCollectionListRow.System(system, isPinned = true))
        }
        userPlaylists.forEach { playlist ->
            add(
                LibraryCollectionListRow.Playlist(
                    playlist = playlist,
                    artwork = playlist.artworkUri ?: playlistArtwork[playlist.id],
                    isPinned = playlist.isPinned,
                )
            )
        }
    }
    val defaultsById = defaults.associateBy(LibraryCollectionListRow::id)
    val storedById = collectionLayout.associateBy(LibraryCollectionLayoutEntry::id)
    val order =
        buildList {
                collectionLayout.mapTo(this) { it.id }
                defaults.mapTo(this) { it.id }
            }
            .distinct()
            .filter(defaultsById::containsKey)
    val orderIndex = order.withIndex().associate { (index, id) -> id to index }
    return order
        .mapNotNull { id ->
            defaultsById[id]?.withPinned(
                storedById[id]?.pinned ?: defaultsById.getValue(id).isPinned
            )
        }
        .sortedWith(
            compareByDescending<LibraryCollectionListRow> { it.isPinned }
                .thenBy { orderIndex.getValue(it.id) }
        )
}

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
