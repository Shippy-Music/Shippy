/*
 * Copyright (c) 2026 Shippy contributors
 * ShippyPlaybackController.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.playback

import javax.inject.Inject
import org.oxycblt.auxio.playback.state.PlaybackCommandFactoryImpl
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.shippy.domain.PlaybackPreparation
import org.oxycblt.auxio.shippy.domain.PlaybackResolutionCoordinator
import org.oxycblt.auxio.shippy.domain.QueueItemFactory
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.ResolutionPolicy
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderSettings

sealed interface PlaybackStartResult {
    data class Started(val queueItemId: QueueItemId) : PlaybackStartResult

    data class Failed(val failure: PlaybackPreparation.Failed) : PlaybackStartResult
}

/**
 * Async boundary between source-aware Shippy surfaces and Auxio's synchronous player authority.
 *
 * Provider resolution must complete before entering PlaybackStateManager's synchronized command
 * path. The manager remains the only queue/player authority; this class owns no playback state.
 */
class ShippyPlaybackController
@Inject
constructor(
    private val queueItemFactory: QueueItemFactory,
    private val resolutionCoordinator: PlaybackResolutionCoordinator,
    private val providerRegistry: ProviderRegistry,
    private val providerSettings: ProviderSettings,
    private val playbackManager: PlaybackStateManager,
) {
    suspend fun play(
        track: Track,
        contextId: String? = null,
        contributorId: String? = null,
        pushPullEnabled: Boolean = false,
    ): PlaybackStartResult {
        val item = queueItemFactory.fromTrack(track, contextId, contributorId)
        val policy =
            ResolutionPolicy(
                providerPriority =
                    providerSettings
                        .selection(
                            providerRegistry
                                .supporting(ProviderCapability.STREAM)
                                .map { it.descriptor.id }
                        )
                        .priority,
                pushPullEnabled = pushPullEnabled,
            )
        return when (val preparation = resolutionCoordinator.prepare(item, policy)) {
            is PlaybackPreparation.Ready -> {
                playbackManager.play(
                    PlaybackCommandFactoryImpl.PlaybackCommandImpl(
                        selectedItemId = item.id,
                        queue = listOf(preparation.value),
                        parent = null,
                        shuffled = false,
                    )
                )
                PlaybackStartResult.Started(item.id)
            }
            is PlaybackPreparation.Failed -> PlaybackStartResult.Failed(preparation)
        }
    }
}
