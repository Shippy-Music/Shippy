/*
 * Copyright (c) 2026 Auxio Project
 * ShippyCollectionDetailViewModel.kt is part of Auxio.
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
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.oxycblt.auxio.image.CoverProvider
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.LibraryCollection
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.LocalTrackCandidateMapper
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
 * canonical metadata cache can resolve each ID, the UI must not pretend those IDs are playable song
 * rows. The state therefore reports a count and an honest unresolved status instead.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
    private val musicRepository: MusicRepository,
    private val localTrackMapper: LocalTrackCandidateMapper,
    private val layoutStore: LibraryCollectionLayoutStore,
) : ViewModel() {
    private val playbackStarting = MutableStateFlow(false)
    private val selectedTrackIdsMutable = MutableStateFlow<Set<TrackId>>(emptySet())
    internal val selectedTrackIds = selectedTrackIdsMutable.asStateFlow()

    internal fun observe(collectionId: LibraryCollectionId): Flow<ShippyCollectionDetailState> =
        combine(collectionState(collectionId), playbackStarting, layoutStore.entries) {
            state,
            starting,
            layout ->
            state
                .withPlaybackStarting(starting)
                .withPinned(
                    layout.firstOrNull { it.id == collectionId }?.pinned ?: state.defaultPinned()
                )
        }

    private fun collectionState(
        collectionId: LibraryCollectionId
    ): Flow<ShippyCollectionDetailState> =
        when (collectionId.value) {
            "system:${SystemCollectionKind.LIKED.id}" ->
                collectionState(
                    SystemCollectionKind.LIKED.displayName,
                    repository.observeLikedTrackIds(),
                )
            "system:${SystemCollectionKind.DOWNLOADS.id}" ->
                collectionState(
                    SystemCollectionKind.DOWNLOADS.displayName,
                    repository.observeDownloadedTrackIds(),
                )
            "system:${SystemCollectionKind.LOCAL.id}" -> localCollectionState()
            else ->
                if (collectionId.isSystem) {
                    flowOf(ShippyCollectionDetailState.Missing)
                } else {
                    combine(
                            repository.observeUserPlaylist(collectionId),
                            repository.observePlaylistTrackIds(collectionId),
                            ::Pair,
                        )
                        .flatMapLatest { (playlist, trackIds) ->
                            playlist?.let {
                                scopedRows(trackIds).map { rows ->
                                    ShippyCollectionDetailState.Playlist(
                                        playlist = it,
                                        trackIds = trackIds,
                                        rows = rows.rows,
                                        unresolvedTrackCount = rows.unresolvedCount,
                                    )
                                }
                            } ?: flowOf(ShippyCollectionDetailState.Missing)
                        }
                }
        }

    private fun localCollectionState(): Flow<ShippyCollectionDetailState> =
        observeLocalTracks().map { tracks ->
            ShippyCollectionDetailState.System(
                title = SystemCollectionKind.LOCAL.displayName,
                rows =
                    tracks.map { track ->
                        ShippyCollectionTrackRow(track, CollectionRowDownloadPresentation.Hidden)
                    },
                unresolvedTrackCount = 0,
            )
        }

    private fun observeLocalTracks(): Flow<List<Track>> =
        callbackFlow {
                val listener =
                    object : MusicRepository.UpdateListener {
                        override fun onMusicChanges(changes: MusicRepository.Changes) {
                            if (!changes.deviceLibrary) return
                            trySend(musicRepository.library?.songs.orEmpty().toList())
                        }
                    }
                musicRepository.addUpdateListener(listener)
                awaitClose { musicRepository.removeUpdateListener(listener) }
            }
            .map { songs ->
                songs.map { song ->
                    localTrackMapper
                        .map(song)
                        .copy(
                            artwork =
                                song.cover?.id?.let { id ->
                                    CoverProvider.CONTENT_URI.buildUpon()
                                        .appendPath(id)
                                        .build()
                                        .toString()
                                }
                        )
                }
            }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()

    private fun collectionState(
        title: String,
        trackIds: Flow<List<TrackId>>,
    ): Flow<ShippyCollectionDetailState> =
        trackIds.flatMapLatest { ids ->
            scopedRows(ids).map { rows ->
                ShippyCollectionDetailState.System(title, rows.rows, rows.unresolvedCount)
            }
        }

    private fun scopedRows(trackIds: List<TrackId>): Flow<ResolvedRows> =
        combine(metadata.observeByIds(trackIds), downloads.observeForTracks(trackIds)) {
            tracks,
            storedDownloads ->
            trackIds.resolveRows(
                tracks.toTrackDownloads(storedDownloads, downloadableProviderIds())
            )
        }

    internal fun play(
        collectionId: LibraryCollectionId,
        rows: List<ShippyCollectionTrackRow>,
        row: ShippyCollectionTrackRow,
    ) {
        if (playbackStarting.value) return
        val selectedIndex = rows.indexOf(row)
        if (selectedIndex < 0) return
        playbackStarting.value = true
        viewModelScope.launch {
            try {
                playback.playQueue(
                    tracks = rows.map(ShippyCollectionTrackRow::track),
                    selectedIndex = selectedIndex,
                    contextId = collectionId.value,
                )
            } finally {
                playbackStarting.value = false
            }
        }
    }

    internal fun playAll(
        collectionId: LibraryCollectionId,
        rows: List<ShippyCollectionTrackRow>,
        shuffled: Boolean,
    ) {
        if (playbackStarting.value || rows.isEmpty()) return
        playbackStarting.value = true
        viewModelScope.launch {
            try {
                playback.playQueue(
                    tracks = rows.map(ShippyCollectionTrackRow::track),
                    selectedIndex = 0,
                    contextId = collectionId.value,
                    shuffled = shuffled,
                )
            } finally {
                playbackStarting.value = false
            }
        }
    }

    internal fun downloadAvailable(rows: List<ShippyCollectionTrackRow>) {
        viewModelScope.launch {
            rows.forEach { row ->
                when (val action = row.download) {
                    is CollectionRowDownloadPresentation.Ready ->
                        downloadCoordinator.request(row.track, action.candidateId)
                    is CollectionRowDownloadPresentation.Paused ->
                        downloadCoordinator.resume(action.jobId)
                    is CollectionRowDownloadPresentation.Retry ->
                        downloadCoordinator.retry(action.jobId)
                    CollectionRowDownloadPresentation.Hidden,
                    is CollectionRowDownloadPresentation.Working,
                    is CollectionRowDownloadPresentation.Available -> Unit
                }
            }
        }
    }

    internal fun performDownloadAction(row: ShippyCollectionTrackRow) {
        viewModelScope.launch {
            when (val action = row.download) {
                is CollectionRowDownloadPresentation.Ready ->
                    downloadCoordinator.request(row.track, action.candidateId)
                is CollectionRowDownloadPresentation.Paused ->
                    downloadCoordinator.resume(action.jobId)
                is CollectionRowDownloadPresentation.Retry ->
                    downloadCoordinator.retry(action.jobId)
                is CollectionRowDownloadPresentation.Available ->
                    downloadCoordinator.remove(action.jobId)
                CollectionRowDownloadPresentation.Hidden,
                is CollectionRowDownloadPresentation.Working -> Unit
            }
        }
    }

    private fun downloadableProviderIds(): Set<ProviderId> =
        providerRegistry.supporting(ProviderCapability.DOWNLOAD).mapTo(mutableSetOf()) {
            it.descriptor.id
        }

    internal fun rename(playlistId: LibraryCollectionId, name: String) {
        if (playlistId.isSystem || name.isBlank()) return
        viewModelScope.launch { repository.renamePlaylist(playlistId, name.trim()) }
    }

    internal fun setPinned(playlistId: LibraryCollectionId, pinned: Boolean) {
        layoutStore.setPinned(playlistId, pinned)
        if (!playlistId.isSystem) {
            viewModelScope.launch { repository.setPlaylistPinned(playlistId, pinned) }
        }
    }

    internal fun setArtwork(playlistId: LibraryCollectionId, artworkUri: String?) {
        if (playlistId.isSystem) return
        viewModelScope.launch { repository.setPlaylistArtwork(playlistId, artworkUri) }
    }

    internal fun delete(playlistId: LibraryCollectionId) {
        if (playlistId.isSystem) return
        viewModelScope.launch { repository.deletePlaylist(playlistId) }
    }

    internal fun reorderPlaylistTracks(
        playlistId: LibraryCollectionId,
        trackIds: List<TrackId>,
        reorderedRows: List<ShippyCollectionTrackRow>,
    ) {
        if (playlistId.isSystem) return
        val reorderedTrackIds = reorderPlaylistTrackIds(trackIds, reorderedRows.map { it.track.id })
        if (reorderedTrackIds == trackIds) return
        viewModelScope.launch { repository.replacePlaylistTracks(playlistId, reorderedTrackIds) }
    }

    internal fun toggleTrackSelection(trackId: TrackId) {
        selectedTrackIdsMutable.value =
            selectedTrackIdsMutable.value.toMutableSet().apply {
                if (!add(trackId)) remove(trackId)
            }
    }

    internal fun clearTrackSelection() {
        selectedTrackIdsMutable.value = emptySet()
    }

    internal fun removeSelectedFromPlaylist(
        playlistId: LibraryCollectionId,
        trackIds: List<TrackId>,
    ) {
        if (playlistId.isSystem) return
        val selected = selectedTrackIdsMutable.value
        if (selected.isEmpty()) return
        val remaining = trackIds.filterNot(selected::contains)
        viewModelScope.launch {
            repository.replacePlaylistTracks(playlistId, remaining)
            clearTrackSelection()
        }
    }

    internal fun removeSelectedFromLiked() {
        val selected = selectedTrackIdsMutable.value
        if (selected.isEmpty()) return
        viewModelScope.launch {
            repository.clearLiked(selected)
            clearTrackSelection()
        }
    }
}

internal sealed interface ShippyCollectionDetailState {
    val title: String
    val unresolvedTrackCount: Int
    val rows: List<ShippyCollectionTrackRow>
    val playbackStarting: Boolean
    val isPinned: Boolean

    data class System(
        override val title: String,
        override val rows: List<ShippyCollectionTrackRow>,
        override val unresolvedTrackCount: Int,
        override val playbackStarting: Boolean = false,
        override val isPinned: Boolean = true,
    ) : ShippyCollectionDetailState

    data class Playlist(
        val playlist: LibraryCollection.Playlist,
        val trackIds: List<TrackId>,
        override val rows: List<ShippyCollectionTrackRow>,
        override val unresolvedTrackCount: Int,
        override val playbackStarting: Boolean = false,
        override val isPinned: Boolean = playlist.isPinned,
    ) : ShippyCollectionDetailState {
        override val title: String = playlist.displayName
    }

    data object Missing : ShippyCollectionDetailState {
        override val title: String = "Playlist unavailable"
        override val unresolvedTrackCount: Int = 0
        override val rows: List<ShippyCollectionTrackRow> = emptyList()
        override val playbackStarting: Boolean = false
        override val isPinned: Boolean = false
    }
}

private fun ShippyCollectionDetailState.withPlaybackStarting(
    playbackStarting: Boolean
): ShippyCollectionDetailState =
    when (this) {
        is ShippyCollectionDetailState.System -> copy(playbackStarting = playbackStarting)
        is ShippyCollectionDetailState.Playlist -> copy(playbackStarting = playbackStarting)
        ShippyCollectionDetailState.Missing -> this
    }

private fun ShippyCollectionDetailState.withPinned(pinned: Boolean): ShippyCollectionDetailState =
    when (this) {
        is ShippyCollectionDetailState.System -> copy(isPinned = pinned)
        is ShippyCollectionDetailState.Playlist ->
            copy(playlist = playlist.copy(isPinned = pinned), isPinned = pinned)
        ShippyCollectionDetailState.Missing -> this
    }

private fun ShippyCollectionDetailState.defaultPinned() =
    when (this) {
        is ShippyCollectionDetailState.System -> true
        is ShippyCollectionDetailState.Playlist -> playlist.isPinned
        ShippyCollectionDetailState.Missing -> false
    }

/** A durable track row from the canonical catalog with its direct download action state. */
internal data class ShippyCollectionTrackRow(
    val track: Track,
    val download: CollectionRowDownloadPresentation,
)

internal sealed interface CollectionRowDownloadPresentation {
    data object Hidden : CollectionRowDownloadPresentation

    data class Ready(val candidateId: CandidateId) : CollectionRowDownloadPresentation

    data class Working(val jobId: DownloadJobId, val state: DownloadState) :
        CollectionRowDownloadPresentation

    data class Paused(val jobId: DownloadJobId) : CollectionRowDownloadPresentation

    data class Retry(val jobId: DownloadJobId) : CollectionRowDownloadPresentation

    data class Available(val jobId: DownloadJobId) : CollectionRowDownloadPresentation
}

internal data class ResolvedRows(val rows: List<ShippyCollectionTrackRow>, val unresolvedCount: Int)

internal fun List<TrackId>.resolveRows(
    persistedTracks: List<Pair<Track, CollectionRowDownloadPresentation>>
): ResolvedRows {
    val latest = persistedTracks.associateBy({ it.first.id }, { it })
    val rows = mapNotNull { id ->
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
): List<Pair<Track, CollectionRowDownloadPresentation>> = map { track ->
    track to
        collectionRowDownloadPresentation(
            track,
            downloads.latestFor(track.id),
            downloadableProviderIds,
        )
}

private fun List<PersistedDownload>.latestFor(trackId: TrackId): PersistedDownload? =
    asSequence().filter { it.track.id == trackId }.maxByOrNull(PersistedDownload::updatedAtEpochMs)
