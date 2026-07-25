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
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.domain.LibraryCollection
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.SystemCollectionKind
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.download.DownloadState
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobRepository
import org.oxycblt.auxio.shippy.persistence.library.LibraryRelationshipRepository
import org.oxycblt.auxio.shippy.persistence.library.CanonicalTrackMetadataRepository
import org.oxycblt.auxio.shippy.playback.ShippyPlaybackController

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
constructor(
    private val repository: LibraryRelationshipRepository,
    private val metadata: CanonicalTrackMetadataRepository,
    private val downloads: DownloadJobRepository,
    private val playback: ShippyPlaybackController,
) : ViewModel() {
    fun observe(collectionId: LibraryCollectionId): Flow<ShippyCollectionDetailState> =
        when (collectionId.value) {
            "system:${SystemCollectionKind.LIKED.id}" ->
                collectionState(SystemCollectionKind.LIKED.displayName, repository.observeLikedTrackIds())
            "system:${SystemCollectionKind.DOWNLOADS.id}" ->
                collectionState(SystemCollectionKind.DOWNLOADS.displayName, repository.observeDownloadedTrackIds())
            else ->
                if (collectionId.isSystem) {
                    flowOf(ShippyCollectionDetailState.Missing)
                } else {
                    combine(
                        repository.observeUserPlaylist(collectionId),
                        repository.observePlaylistTrackIds(collectionId),
                        metadata.observeAll(),
                        downloads.observeAll(),
                    ) { playlist, trackIds, tracks, storedDownloads ->
                        playlist?.let {
                            val rows = trackIds.resolveRows(tracks.toTrackStates(storedDownloads))
                            ShippyCollectionDetailState.Playlist(it, rows.rows, rows.unresolvedCount)
                        }
                            ?: ShippyCollectionDetailState.Missing
                    }
                }
        }

    private fun collectionState(
        title: String,
        trackIds: Flow<List<TrackId>>,
    ): Flow<ShippyCollectionDetailState> =
        combine(trackIds, metadata.observeAll(), downloads.observeAll()) { ids, tracks, storedDownloads ->
            val rows = ids.resolveRows(tracks.toTrackStates(storedDownloads))
            ShippyCollectionDetailState.System(title, rows.rows, rows.unresolvedCount)
        }

    fun play(
        collectionId: LibraryCollectionId,
        row: ShippyCollectionTrackRow,
    ) {
        viewModelScope.launch { playback.play(row.track, contextId = collectionId.value) }
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
    val rows: List<ShippyCollectionTrackRow>

    data class System(
        override val title: String,
        override val rows: List<ShippyCollectionTrackRow>,
        override val unresolvedTrackCount: Int,
    ) : ShippyCollectionDetailState

    data class Playlist(
        val playlist: LibraryCollection.Playlist,
        override val rows: List<ShippyCollectionTrackRow>,
        override val unresolvedTrackCount: Int,
    ) : ShippyCollectionDetailState {
        override val title: String = playlist.displayName
    }

    data object Missing : ShippyCollectionDetailState {
        override val title: String = "Playlist unavailable"
        override val unresolvedTrackCount: Int = 0
        override val rows: List<ShippyCollectionTrackRow> = emptyList()
    }
}

/** A durable track row from the canonical catalog, optionally enriched with download state. */
internal data class ShippyCollectionTrackRow(
    val track: Track,
    val downloadState: DownloadState?,
)

internal data class ResolvedRows(
    val rows: List<ShippyCollectionTrackRow>,
    val unresolvedCount: Int,
)

internal fun List<TrackId>.resolveRows(
    persistedTracks: List<Pair<Track, DownloadState?>>,
): ResolvedRows {
    val latest = persistedTracks.associateBy({ it.first.id }, { it })
    val rows = mapNotNull { id -> latest[id]?.let { (track, state) -> ShippyCollectionTrackRow(track, state) } }
    return ResolvedRows(rows, size - rows.size)
}

private fun List<Track>.toTrackStates(
    downloads: List<org.oxycblt.auxio.shippy.persistence.download.PersistedDownload>,
): List<Pair<Track, DownloadState?>> =
    map { it to null } + downloads.sortedBy { it.updatedAtEpochMs }.map { it.track to it.job.state }
