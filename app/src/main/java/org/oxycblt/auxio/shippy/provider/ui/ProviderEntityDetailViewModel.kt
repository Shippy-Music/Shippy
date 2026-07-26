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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.domain.PlaybackPreparation
import org.oxycblt.auxio.shippy.playback.PlaybackStartResult
import org.oxycblt.auxio.shippy.playback.ShippyPlaybackController
import org.oxycblt.auxio.shippy.provider.ProviderBrowsePage
import org.oxycblt.auxio.shippy.provider.ProviderEntity
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.util.Event
import org.oxycblt.auxio.util.MutableEvent

sealed interface ProviderEntityDetailState {
    val entity: ProviderEntity

    data class Loading(override val entity: ProviderEntity) : ProviderEntityDetailState

    data class Content(
        val page: ProviderBrowsePage,
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
) : ViewModel() {
    private val _state = MutableStateFlow<ProviderEntityDetailState?>(null)
    val state: StateFlow<ProviderEntityDetailState?> = _state

    private val _playbackFailure = MutableEvent<PlaybackPreparation.Failed>()
    val playbackFailure: Event<PlaybackPreparation.Failed> = _playbackFailure

    private var loadJob: Job? = null
    private var playbackJob: Job? = null
    private var requestedEntity: ProviderEntity? = null

    fun load(entity: ProviderEntity, force: Boolean = false) {
        if (!force && requestedEntity == entity && _state.value != null) return
        requestedEntity = entity
        loadJob?.cancel()
        _state.value = ProviderEntityDetailState.Loading(entity)
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
                _state.value =
                    when (result) {
                        is ProviderResult.Success ->
                            ProviderEntityDetailState.Content(result.value)
                        is ProviderResult.Failure ->
                            ProviderEntityDetailState.Error(entity, result.retryable)
                    }
            }
    }

    fun retry() {
        requestedEntity?.let { load(it, force = true) }
    }

    fun loadMore() {
        val current = _state.value as? ProviderEntityDetailState.Content ?: return
        val continuation = current.page.continuation ?: return
        if (current.loadingMore) return
        val entity = requestedEntity ?: return
        loadJob?.cancel()
        _state.value = current.copy(loadingMore = true, pagingFailed = false)
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
                _state.value =
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
        val page = (_state.value as? ProviderEntityDetailState.Content)?.page ?: return
        if (index !in page.tracks.indices) return
        playbackJob?.cancel()
        playbackJob =
            viewModelScope.launch {
                when (
                    val result =
                        playback.playQueue(
                            tracks = page.tracks,
                            selectedIndex = index,
                            contextId =
                                "provider:${page.entity.providerId.value}:" +
                                    "${page.entity.type.name.lowercase()}:" +
                                    page.entity.sourceItemId,
                            shuffled = shuffled,
                        )
                ) {
                    is PlaybackStartResult.Started -> Unit
                    is PlaybackStartResult.Failed -> _playbackFailure.put(result.failure)
                }
            }
    }
}
