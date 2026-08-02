/*
 * Copyright (c) 2026 Auxio Project
 * ShippyPlaybackController.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.playback

import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.playback.state.PlaybackCommandFactoryImpl
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.shippy.domain.PlaybackPreparation
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemFactory
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.ResolutionPolicy
import org.oxycblt.auxio.shippy.domain.ResolvedQueueItem
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderSettings
import org.oxycblt.auxio.shippy.provider.StreamConstraints

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
    private val providerRegistry: ProviderRegistry,
    private val providerSettings: ProviderSettings,
    private val playbackManager: PlaybackStateManager,
    private val providerPlaybackLifecycle: ProviderPlaybackLifecycle,
) {
    suspend fun play(
        track: Track,
        contextId: String? = null,
        contributorId: String? = null,
        pushPullEnabled: Boolean = false,
    ): PlaybackStartResult =
        playQueue(
            tracks = listOf(track),
            selectedIndex = 0,
            contextId = contextId,
            contributorId = contributorId,
            pushPullEnabled = pushPullEnabled,
        )

    /** Replaces playback with one already-ordered collection context through the single player. */
    suspend fun playQueue(
        tracks: List<Track>,
        selectedIndex: Int,
        contextId: String? = null,
        contributorId: String? = null,
        pushPullEnabled: Boolean = false,
        shuffled: Boolean = false,
    ): PlaybackStartResult {
        val prepared =
            withContext(Dispatchers.Default) {
                prepareQueue(tracks, selectedIndex, contextId, contributorId, pushPullEnabled)
            }
        when (prepared) {
            is PreparedPlaybackQueue.Failed -> return PlaybackStartResult.Failed(prepared.failure)
            is PreparedPlaybackQueue.Ready -> {
                withContext(Dispatchers.Main.immediate) {
                    playbackManager.play(
                        PlaybackCommandFactoryImpl.PlaybackCommandImpl(
                            selectedItemId = prepared.plan.selectedItemId,
                            queue = prepared.items,
                            parent = null,
                            shuffled = shuffled,
                        )
                    )
                }
                return PlaybackStartResult.Started(prepared.plan.selectedItemId)
            }
        }
    }

    /** Resolves atomically, then inserts source-aware tracks immediately after the current item. */
    suspend fun playNext(
        tracks: List<Track>,
        contextId: String? = null,
        contributorId: String? = null,
        pushPullEnabled: Boolean = false,
    ): PlaybackStartResult =
        mutateQueue(tracks, contextId, contributorId, pushPullEnabled) {
            playbackManager.playNextResolved(it)
        }

    /** Resolves atomically, then appends source-aware tracks to the canonical queue. */
    suspend fun addToQueue(
        tracks: List<Track>,
        contextId: String? = null,
        contributorId: String? = null,
        pushPullEnabled: Boolean = false,
    ): PlaybackStartResult =
        mutateQueue(tracks, contextId, contributorId, pushPullEnabled) {
            playbackManager.addResolvedToQueue(it)
        }

    private suspend fun mutateQueue(
        tracks: List<Track>,
        contextId: String?,
        contributorId: String?,
        pushPullEnabled: Boolean,
        mutation: (List<ResolvedQueueItem>) -> Unit,
    ): PlaybackStartResult {
        if (playbackManager.currentQueueItem == null) {
            return playQueue(
                tracks = tracks,
                selectedIndex = 0,
                contextId = contextId,
                contributorId = contributorId,
                pushPullEnabled = pushPullEnabled,
            )
        }
        val plan = queuePlaybackPlan(queueItemFactory, tracks, 0, contextId, contributorId)
        val policy = resolutionPolicy(pushPullEnabled)
        val constraints =
            StreamConstraints(preferredBitrateBps = providerSettings.streamingBitrateBps())
        val prepared = providerPlaybackLifecycle.prepareAdditional(plan.items, policy, constraints)
        mutation(prepared)
        providerPlaybackLifecycle.updateQueue(
            items = playbackManager.queueItems,
            policy = policy,
            constraints = constraints,
            updates = playbackManager.resolvedQueue + prepared,
        )
        return PlaybackStartResult.Started(plan.selectedItemId)
    }

    private suspend fun prepareQueue(
        tracks: List<Track>,
        selectedIndex: Int,
        contextId: String?,
        contributorId: String?,
        pushPullEnabled: Boolean,
    ): PreparedPlaybackQueue {
        val plan =
            queuePlaybackPlan(queueItemFactory, tracks, selectedIndex, contextId, contributorId)
        val policy = resolutionPolicy(pushPullEnabled)
        val constraints =
            StreamConstraints(preferredBitrateBps = providerSettings.streamingBitrateBps())
        return when (
            val prepared =
                providerPlaybackLifecycle.prepareInitial(
                    plan.items,
                    selectedIndex,
                    policy,
                    constraints,
                )
        ) {
            is ProviderPlaybackLifecycle.InitialPreparation.Ready ->
                PreparedPlaybackQueue.Ready(plan, prepared.items)
            is ProviderPlaybackLifecycle.InitialPreparation.Failed ->
                PreparedPlaybackQueue.Failed(prepared.failure)
        }
    }

    private fun resolutionPolicy(pushPullEnabled: Boolean) =
        ResolutionPolicy(
            providerPriority =
                providerSettings
                    .selection(
                        providerRegistry.supporting(ProviderCapability.STREAM).map {
                            it.descriptor.id
                        }
                    )
                    .priority,
            pushPullEnabled = pushPullEnabled,
        )
}

private sealed interface PreparedPlaybackQueue {
    data class Ready(val plan: QueuePlaybackPlan, val items: List<ResolvedQueueItem>) :
        PreparedPlaybackQueue

    data class Failed(val failure: PlaybackPreparation.Failed) : PreparedPlaybackQueue
}

internal data class QueuePlaybackPlan(val items: List<QueueItem>, val selectedItemId: QueueItemId)

internal fun queuePlaybackPlan(
    queueItemFactory: QueueItemFactory,
    tracks: List<Track>,
    selectedIndex: Int,
    contextId: String?,
    contributorId: String?,
): QueuePlaybackPlan {
    require(tracks.isNotEmpty()) { "Playback queue cannot be empty" }
    require(selectedIndex in tracks.indices) { "Selected queue index is out of bounds" }
    val items = tracks.map { queueItemFactory.fromTrack(it, contextId, contributorId) }
    return QueuePlaybackPlan(items, items[selectedIndex].id)
}
