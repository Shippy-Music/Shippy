/*
 * Copyright (c) 2026 Shippy contributors
 * ProviderEntityDetailViewModel.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.provider.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.domain.PlaybackPreparation
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.download.DownloadWorkCoordinator
import org.oxycblt.auxio.shippy.library.CollectionRowDownloadPresentation
import org.oxycblt.auxio.shippy.library.ShippyCollectionTrackRow
import org.oxycblt.auxio.shippy.library.collectionRowDownloadPresentation
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobRepository
import org.oxycblt.auxio.shippy.persistence.library.SavedProviderEntity
import org.oxycblt.auxio.shippy.persistence.library.SavedProviderEntityRepository
import org.oxycblt.auxio.shippy.playback.PlaybackStartResult
import org.oxycblt.auxio.shippy.playback.ShippyPlaybackController
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderBrowsePage
import org.oxycblt.auxio.shippy.provider.ProviderEntity
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.util.Event
import org.oxycblt.auxio.util.MutableEvent

enum class ProviderCollectionQueueAction {
    PLAY_NEXT,
    ADD_TO_QUEUE,
}

sealed interface ProviderEntityDetailState {
    val entity: ProviderEntity

    data class Loading(override val entity: ProviderEntity) : ProviderEntityDetailState

    data class Content(
        val page: ProviderBrowsePage,
        val rows: List<ShippyCollectionTrackRow> = emptyList(),
        val loadingMore: Boolean = false,
        val pagingFailed: Boolean = false,
    ) : ProviderEntityDetailState {
        override val entity: ProviderEntity
            get() = page.entity
    }

    data class Error(
        override val entity: ProviderEntity,
        val retryable: Boolean,
    ) : ProviderEntityDetailState
}

/** One lifecycle-owned catalogue detail flow; provider state never leaks into the Fragment. */
@HiltViewModel
class ProviderEntityDetailViewModel
@Inject
constructor(
    private val providers: ProviderRegistry,
    private val playback: ShippyPlaybackController,
    private val downloads: DownloadJobRepository,
    private val downloadCoordinator: DownloadWorkCoordinator,
    private val savedProviderEntities: SavedProviderEntityRepository,
) : ViewModel() {
    private val sourceState = MutableStateFlow<ProviderEntityDetailState?>(null)
    val state: StateFlow<ProviderEntityDetailState?> =
        combine(sourceState, downloads.observeAll()) { state, storedDownloads ->
            when (state) {
                is ProviderEntityDetailState.Content ->
                    state.copy(
                        rows =
                            state.page.tracks.map { track ->
                                ShippyCollectionTrackRow(
                                    track = track,
                                    download =
                                        collectionRowDownloadPresentation(
                                            track,
                                            storedDownloads.latestFor(track.id),
                                            downloadableProviderIds(),
                                        ),
                                )
                            }
                    )
                else -> state
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _playbackFailure = MutableEvent<PlaybackPreparation.Failed>()
    val playbackFailure: Event<PlaybackPreparation.Failed> = _playbackFailure

    private val _queueActionCompleted = MutableEvent<ProviderCollectionQueueAction>()
    val queueActionCompleted: Event<ProviderCollectionQueueAction> = _queueActionCompleted

    private val _savedEntity = MutableStateFlow<SavedProviderEntity?>(null)
    val savedEntity: StateFlow<SavedProviderEntity?> = _savedEntity

    private var loadJob: Job? = null
    private var playbackJob: Job? = null
    private var savedObservationJob: Job? = null
    private var requestedEntity: ProviderEntity? = null

    fun load(entity: ProviderEntity, force: Boolean = false) {
        if (!force && requestedEntity == entity && sourceState.value != null) return
        requestedEntity = entity
        savedObservationJob?.cancel()
        savedObservationJob =
            viewModelScope.launch {
                savedProviderEntities.observe(entity).collect { saved -> _savedEntity.value = saved }
            }
        loadJob?.cancel()
        sourceState.value = ProviderEntityDetailState.Loading(entity)
        loadJob =
            viewModelScope.launch {
                val provider = providers.get(entity.providerId)
                val result =
                    if (provider == null) {
                        ProviderResult.Failure(
                            ProviderFailureKind.UNAVAILABLE,
                            retryable = false,
                        )
                    } else {
                        try {
                            provider.browse(entity)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            ProviderResult.Failure(
                                ProviderFailureKind.UNAVAILABLE,
                                retryable = true,
                            )
                        }
                    }
                if (requestedEntity != entity) return@launch
                sourceState.value =
                    when (result) {
                        is ProviderResult.Success ->
                            ProviderEntityDetailState.Content(result.value).also {
                                savedProviderEntities.refreshIfSaved(result.value.entity)
                            }
                        is ProviderResult.Failure ->
                            ProviderEntityDetailState.Error(entity, result.retryable)
                    }
            }
    }

    fun retry() {
        requestedEntity?.let { load(it, force = true) }
    }

    fun loadMore() {
        val current = sourceState.value as? ProviderEntityDetailState.Content ?: return
        val continuation = current.page.continuation ?: return
        if (current.loadingMore) return
        val entity = requestedEntity ?: return
        loadJob?.cancel()
        sourceState.value = current.copy(loadingMore = true, pagingFailed = false)
        loadJob =
            viewModelScope.launch {
                val provider = providers.get(entity.providerId)
                val result =
                    if (provider == null) {
                        ProviderResult.Failure(
                            ProviderFailureKind.UNAVAILABLE,
                            retryable = false,
                        )
                    } else {
                        try {
                            provider.browse(entity, continuation)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            ProviderResult.Failure(
                                ProviderFailureKind.UNAVAILABLE,
                                retryable = true,
                            )
                        }
                    }
                if (requestedEntity != entity) return@launch
                sourceState.value =
                    when (result) {
                        is ProviderResult.Success ->
                            ProviderEntityDetailState.Content(
                                page =
                                    result.value.copy(
                                        tracks =
                                            (current.page.tracks + result.value.tracks)
                                                .distinctBy { it.id }
                                    )
                            )
                        is ProviderResult.Failure ->
                            current.copy(
                                page =
                                    current.page.copy(
                                        continuation =
                                            current.page.continuation.takeIf {
                                                result.retryable
                                            }
                                    ),
                                loadingMore = false,
                                pagingFailed = result.retryable,
                            )
                    }
            }
    }

    fun play(index: Int, shuffled: Boolean) {
        val page = (sourceState.value as? ProviderEntityDetailState.Content)?.page ?: return
        if (index !in page.tracks.indices) return
        playbackJob?.cancel()
        playbackJob =
            viewModelScope.launch {
                when (
                    val result =
                        playback.playQueue(
                            tracks = page.tracks,
                            selectedIndex = index,
                            contextId = page.contextId(),
                            shuffled = shuffled,
                        )
                ) {
                    is PlaybackStartResult.Started -> Unit
                    is PlaybackStartResult.Failed -> _playbackFailure.put(result.failure)
                }
            }
    }

    fun playNext() {
        mutateQueue(ProviderCollectionQueueAction.PLAY_NEXT)
    }

    fun addToQueue() {
        mutateQueue(ProviderCollectionQueueAction.ADD_TO_QUEUE)
    }

    fun toggleSaved() {
        val entity =
            (sourceState.value as? ProviderEntityDetailState.Content)?.page?.entity
                ?: requestedEntity
                ?: return
        viewModelScope.launch {
            if (_savedEntity.value == null) {
                savedProviderEntities.save(entity)
            } else {
                savedProviderEntities.remove(entity)
            }
        }
    }

    fun togglePinned() {
        val saved = _savedEntity.value ?: return
        viewModelScope.launch {
            savedProviderEntities.setPinned(saved.entity, !saved.isPinned)
        }
    }

    private fun mutateQueue(action: ProviderCollectionQueueAction) {
        val page = (sourceState.value as? ProviderEntityDetailState.Content)?.page ?: return
        if (page.tracks.isEmpty()) return
        playbackJob?.cancel()
        playbackJob =
            viewModelScope.launch {
                val result =
                    when (action) {
                        ProviderCollectionQueueAction.PLAY_NEXT ->
                            playback.playNext(page.tracks, contextId = page.contextId())
                        ProviderCollectionQueueAction.ADD_TO_QUEUE ->
                            playback.addToQueue(page.tracks, contextId = page.contextId())
                    }
                when (result) {
                    is PlaybackStartResult.Started -> _queueActionCompleted.put(action)
                    is PlaybackStartResult.Failed -> _playbackFailure.put(result.failure)
                }
            }
    }

    fun performDownloadAction(row: ShippyCollectionTrackRow) {
        viewModelScope.launch {
            when (val action = row.download) {
                is CollectionRowDownloadPresentation.Ready ->
                    downloadCoordinator.request(row.track, action.candidateId)
                is CollectionRowDownloadPresentation.Paused -> downloadCoordinator.resume(action.jobId)
                is CollectionRowDownloadPresentation.Retry -> downloadCoordinator.retry(action.jobId)
                is CollectionRowDownloadPresentation.Available -> downloadCoordinator.remove(action.jobId)
                CollectionRowDownloadPresentation.Hidden,
                is CollectionRowDownloadPresentation.Working -> Unit
            }
        }
    }

    private fun downloadableProviderIds(): Set<ProviderId> =
        providers
            .supporting(ProviderCapability.DOWNLOAD)
            .mapTo(mutableSetOf()) { it.descriptor.id }

    private fun List<org.oxycblt.auxio.shippy.persistence.download.PersistedDownload>.latestFor(
        trackId: org.oxycblt.auxio.shippy.domain.TrackId,
    ) = asSequence().filter { it.track.id == trackId }.maxByOrNull { it.updatedAtEpochMs }

    private fun ProviderBrowsePage.contextId() =
        "provider:${entity.providerId.value}:${entity.type.name.lowercase()}:${entity.sourceItemId}"
}
