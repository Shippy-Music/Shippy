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
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.LibraryCollection
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.SystemCollectionKind
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.download.DownloadJobId
import org.oxycblt.auxio.shippy.download.DownloadState
import org.oxycblt.auxio.shippy.download.DownloadWorkCoordinator
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobRepository
import org.oxycblt.auxio.shippy.persistence.download.PersistedDownload
import org.oxycblt.auxio.shippy.persistence.library.CanonicalTrackMetadataRepository
import org.oxycblt.auxio.shippy.persistence.library.LibraryRelationshipRepository
import org.oxycblt.auxio.shippy.playback.ShippyPlaybackController
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderRegistry

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
    private val downloadCoordinator: DownloadWorkCoordinator,
    private val providerRegistry: ProviderRegistry,
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
                            val rows =
                                trackIds.resolveRows(
                                    tracks.toTrackDownloads(storedDownloads, downloadableProviderIds()),
                                )
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
            val rows =
                ids.resolveRows(tracks.toTrackDownloads(storedDownloads, downloadableProviderIds()))
            ShippyCollectionDetailState.System(title, rows.rows, rows.unresolvedCount)
        }

    fun play(
        collectionId: LibraryCollectionId,
        rows: List<ShippyCollectionTrackRow>,
        row: ShippyCollectionTrackRow,
    ) {
        val selectedIndex = rows.indexOf(row)
        if (selectedIndex < 0) return
        viewModelScope.launch {
            playback.playQueue(
                tracks = rows.map(ShippyCollectionTrackRow::track),
                selectedIndex = selectedIndex,
                contextId = collectionId.value,
            )
        }
    }

    fun performDownloadAction(row: ShippyCollectionTrackRow) {
        viewModelScope.launch {
            when (val action = row.download) {
                is CollectionRowDownloadPresentation.Ready ->
                    downloadCoordinator.request(row.track, action.candidateId)
                is CollectionRowDownloadPresentation.Paused -> downloadCoordinator.resume(action.jobId)
                is CollectionRowDownloadPresentation.Retry -> downloadCoordinator.retry(action.jobId)
                is CollectionRowDownloadPresentation.Available ->
                    downloadCoordinator.remove(action.jobId)
                CollectionRowDownloadPresentation.Hidden,
                is CollectionRowDownloadPresentation.Working -> Unit
            }
        }
    }

    private fun downloadableProviderIds(): Set<ProviderId> =
        providerRegistry
            .supporting(ProviderCapability.DOWNLOAD)
            .mapTo(mutableSetOf()) { it.descriptor.id }

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

/** A durable track row from the canonical catalog with its direct download action state. */
internal data class ShippyCollectionTrackRow(
    val track: Track,
    val download: CollectionRowDownloadPresentation,
)

internal sealed interface CollectionRowDownloadPresentation {
    data object Hidden : CollectionRowDownloadPresentation

    data class Ready(val candidateId: CandidateId) : CollectionRowDownloadPresentation

    data class Working(
        val jobId: DownloadJobId,
        val state: DownloadState,
    ) : CollectionRowDownloadPresentation

    data class Paused(val jobId: DownloadJobId) : CollectionRowDownloadPresentation

    data class Retry(val jobId: DownloadJobId) : CollectionRowDownloadPresentation

    data class Available(val jobId: DownloadJobId) : CollectionRowDownloadPresentation
}

internal data class ResolvedRows(
    val rows: List<ShippyCollectionTrackRow>,
    val unresolvedCount: Int,
)

internal fun List<TrackId>.resolveRows(
    persistedTracks: List<Pair<Track, CollectionRowDownloadPresentation>>,
): ResolvedRows {
    val latest = persistedTracks.associateBy({ it.first.id }, { it })
    val rows =
        mapNotNull { id ->
            latest[id]?.let { (track, download) -> ShippyCollectionTrackRow(track, download) }
        }
    return ResolvedRows(rows, size - rows.size)
}

internal fun collectionRowDownloadPresentation(
    track: Track,
    download: PersistedDownload?,
    downloadableProviderIds: Set<ProviderId>,
): CollectionRowDownloadPresentation {
    if (track.realm == TrackRealm.LOCAL) return CollectionRowDownloadPresentation.Hidden
    val candidate =
        track.candidates.firstOrNull {
            it.kind == CandidateKind.PROVIDER &&
                it.providerId in downloadableProviderIds &&
                it.availability != CandidateAvailability.UNAVAILABLE
        } ?: return CollectionRowDownloadPresentation.Hidden
    val job = download?.job ?: return CollectionRowDownloadPresentation.Ready(candidate.id)
    return when (job.state) {
        DownloadState.AVAILABLE -> CollectionRowDownloadPresentation.Available(job.id)
        DownloadState.PAUSED -> CollectionRowDownloadPresentation.Paused(job.id)
        DownloadState.FAILED_RETRYABLE -> CollectionRowDownloadPresentation.Retry(job.id)
        DownloadState.REQUESTED,
        DownloadState.RESOLVING,
        DownloadState.QUEUED,
        DownloadState.TRANSFERRING,
        DownloadState.VERIFYING,
        DownloadState.FINALIZING -> CollectionRowDownloadPresentation.Working(job.id, job.state)
        DownloadState.FAILED_FINAL,
        DownloadState.CANCELLED,
        DownloadState.REMOVED -> CollectionRowDownloadPresentation.Ready(candidate.id)
    }
}

private fun List<Track>.toTrackDownloads(
    downloads: List<PersistedDownload>,
    downloadableProviderIds: Set<ProviderId>,
): List<Pair<Track, CollectionRowDownloadPresentation>> =
    map { track ->
        track to collectionRowDownloadPresentation(
            track,
            downloads.latestFor(track.id),
            downloadableProviderIds,
        )
    }

private fun List<PersistedDownload>.latestFor(trackId: TrackId): PersistedDownload? =
    asSequence().filter { it.track.id == trackId }.maxByOrNull(PersistedDownload::updatedAtEpochMs)
