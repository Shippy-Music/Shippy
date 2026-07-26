/*
 * Copyright (c) 2026 Shippy contributors
 * PlayerActionsViewModel.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.LibraryCollection
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.download.DownloadJobId
import org.oxycblt.auxio.shippy.download.DownloadState
import org.oxycblt.auxio.shippy.download.DownloadWorkCoordinator
import org.oxycblt.auxio.shippy.persistence.download.PersistedDownload
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobRepository
import org.oxycblt.auxio.shippy.persistence.library.LibraryRelationshipRepository
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderRegistry

/**
 * Presentation-only action state for the current player item.
 *
 * The source repositories remain authoritative: this class neither invents a second download
 * record nor infers availability from metadata.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerActionsViewModel
@Inject
constructor(
    private val relationships: LibraryRelationshipRepository,
    private val downloads: DownloadJobRepository,
    private val downloadCoordinator: DownloadWorkCoordinator,
    private val providerRegistry: ProviderRegistry,
) : ViewModel() {
    private val displayItem = MutableStateFlow<PlaybackDisplayItem?>(null)

    val state: StateFlow<PlayerActionsState> =
        displayItem
            .flatMapLatest { item ->
                val track = item?.queueItem?.track ?: return@flatMapLatest flowOf(PlayerActionsState())
                combine(
                    relationships.observe(track.id),
                    downloads.observeAll(),
                    relationships.observeUserPlaylists(),
                ) { relationship, jobs, playlists ->
                    PlayerActionsState(
                        track = track,
                        liked = relationship.liked,
                        playlistIds = relationship.playlistIds,
                        playlists = playlists,
                        download =
                            downloadPresentation(
                                track,
                                item.resolvedCandidateId,
                                jobs.latestFor(track.id),
                                providerRegistry
                                    .supporting(ProviderCapability.DOWNLOAD)
                                    .mapTo(mutableSetOf()) { it.descriptor.id },
                            ),
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerActionsState())

    fun setDisplayItem(item: PlaybackDisplayItem?) {
        displayItem.value = item
    }

    fun toggleLiked() {
        val actionState = state.value
        val track = actionState.track ?: return
        if (actionState.liked) return
        viewModelScope.launch { relationships.setLiked(track, true) }
    }

    fun updateSavedDestinations(
        liked: Boolean,
        playlistIds: Set<LibraryCollectionId>,
    ) {
        val track = state.value.track ?: return
        viewModelScope.launch {
            relationships.setLiked(track, liked)
            relationships.replacePlaylistMemberships(track, playlistIds)
        }
    }

    fun removeDownload(jobId: DownloadJobId) {
        viewModelScope.launch { downloadCoordinator.remove(jobId) }
    }

    fun performDownloadAction() {
        val actionState = state.value
        val track = actionState.track ?: return
        viewModelScope.launch {
            when (val action = actionState.download) {
                is PlayerDownloadPresentation.Ready ->
                    downloadCoordinator.request(track, action.candidateId)
                is PlayerDownloadPresentation.Paused -> downloadCoordinator.resume(action.jobId)
                is PlayerDownloadPresentation.Retry -> downloadCoordinator.retry(action.jobId)
                is PlayerDownloadPresentation.Available,
                PlayerDownloadPresentation.Hidden,
                is PlayerDownloadPresentation.Working -> Unit
            }
        }
    }
}

internal data class PlayerActionsState(
    val track: Track? = null,
    val liked: Boolean = false,
    val playlistIds: Set<LibraryCollectionId> = emptySet(),
    val playlists: List<LibraryCollection.Playlist> = emptyList(),
    val download: PlayerDownloadPresentation = PlayerDownloadPresentation.Hidden,
)

internal sealed interface PlayerDownloadPresentation {
    data object Hidden : PlayerDownloadPresentation

    data class Ready(val candidateId: CandidateId) : PlayerDownloadPresentation

    data class Working(val state: DownloadState) : PlayerDownloadPresentation

    data class Paused(val jobId: DownloadJobId) : PlayerDownloadPresentation

    data class Retry(val jobId: DownloadJobId) : PlayerDownloadPresentation

    data class Available(val jobId: DownloadJobId) : PlayerDownloadPresentation
}

internal fun downloadPresentation(
    track: Track,
    resolvedCandidateId: CandidateId,
    download: PersistedDownload?,
    downloadableProviderIds: Set<ProviderId>,
): PlayerDownloadPresentation {
    val candidate =
        track.candidates.firstOrNull {
            it.id == resolvedCandidateId &&
                it.availability != CandidateAvailability.UNAVAILABLE &&
                (
                    it.kind == CandidateKind.CREW_TEMPORARY ||
                        (track.realm != TrackRealm.LOCAL &&
                            it.kind == CandidateKind.PROVIDER &&
                            it.providerId in downloadableProviderIds)
                )
        } ?: return PlayerDownloadPresentation.Hidden
    val job = download?.job ?: return PlayerDownloadPresentation.Ready(candidate.id)
    return when (job.state) {
        DownloadState.AVAILABLE -> PlayerDownloadPresentation.Available(job.id)
        DownloadState.PAUSED -> PlayerDownloadPresentation.Paused(job.id)
        DownloadState.FAILED_RETRYABLE -> PlayerDownloadPresentation.Retry(job.id)
        DownloadState.REQUESTED,
        DownloadState.RESOLVING,
        DownloadState.QUEUED,
        DownloadState.TRANSFERRING,
        DownloadState.VERIFYING,
        DownloadState.FINALIZING -> PlayerDownloadPresentation.Working(job.state)
        DownloadState.FAILED_FINAL,
        DownloadState.CANCELLED,
        DownloadState.REMOVED -> PlayerDownloadPresentation.Ready(candidate.id)
    }
}

private fun List<PersistedDownload>.latestFor(trackId: TrackId): PersistedDownload? =
    asSequence().filter { it.track.id == trackId }.maxByOrNull(PersistedDownload::updatedAtEpochMs)
