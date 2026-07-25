/*
 * Copyright (c) 2026 Shippy contributors
 * PlaybackResolutionCoordinator.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.domain

import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.StreamConstraints

sealed interface PlaybackPreparation {
    data class Ready(val value: ResolvedQueueItem) : PlaybackPreparation

    data class Failed(
        val trackId: TrackId,
        val kind: ProviderFailureKind,
        val retryable: Boolean,
        val message: String? = null,
    ) : PlaybackPreparation
}

class PlaybackResolutionCoordinator
@Inject
constructor(
    private val playbackResolver: PlaybackResolver,
    private val providerRegistry: ProviderRegistry,
) {
    suspend fun prepare(
        item: QueueItem,
        policy: ResolutionPolicy,
        constraints: StreamConstraints = StreamConstraints(),
    ): PlaybackPreparation {
        val candidate =
            when (val selection = playbackResolver.resolve(item.track, policy)) {
                is ResolutionResult.Selected -> selection.candidate
                is ResolutionResult.Unavailable ->
                    return PlaybackPreparation.Failed(
                        trackId = selection.trackId,
                        kind = ProviderFailureKind.UNAVAILABLE,
                        retryable = true,
                        message = "No playable source is currently available",
                    )
            }

        return if (candidate.kind == CandidateKind.PROVIDER) {
            prepareProvider(item, candidate, constraints)
        } else {
            prepareDirect(item, candidate)
        }
    }

    private suspend fun prepareProvider(
        item: QueueItem,
        candidate: TrackCandidate,
        constraints: StreamConstraints,
    ): PlaybackPreparation {
        val provider =
            candidate.providerId?.let(providerRegistry::get)
                ?: return PlaybackPreparation.Failed(
                    trackId = item.track.id,
                    kind = ProviderFailureKind.UNAVAILABLE,
                    retryable = false,
                    message = "The selected provider is not enabled",
                )
        return try {
            when (val result = provider.resolve(candidate, constraints)) {
                is ProviderResult.Success ->
                    PlaybackPreparation.Ready(
                        ResolvedQueueItem(
                            item = item,
                            playback =
                                ResolvedPlayback(
                                    queueItemId = item.id,
                                    candidateId = result.value.candidateId,
                                    uri = result.value.uri,
                                    mimeType = result.value.mimeType,
                                    headers = result.value.headers,
                                    bitrateBps = result.value.bitrateBps,
                                    contentLength = result.value.contentLength,
                                    expiresAtEpochMs = result.value.expiresAtEpochMs,
                                ),
                        )
                    )
                is ProviderResult.Failure ->
                    PlaybackPreparation.Failed(
                        trackId = item.track.id,
                        kind = result.kind,
                        retryable = result.retryable,
                        message = result.message,
                    )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PlaybackPreparation.Failed(
                trackId = item.track.id,
                kind = ProviderFailureKind.UNAVAILABLE,
                retryable = true,
                message = error.message,
            )
        }
    }

    private fun prepareDirect(
        item: QueueItem,
        candidate: TrackCandidate,
    ): PlaybackPreparation {
        val uri =
            candidate.locator?.takeIf(String::isNotBlank)
                ?: return PlaybackPreparation.Failed(
                    trackId = item.track.id,
                    kind = ProviderFailureKind.UNAVAILABLE,
                    retryable = true,
                    message = "The selected source has no playable location",
                )
        return PlaybackPreparation.Ready(
            ResolvedQueueItem(
                item = item,
                playback =
                    ResolvedPlayback(
                        queueItemId = item.id,
                        candidateId = candidate.id,
                        uri = uri,
                        mimeType = candidate.media?.mimeType,
                        bitrateBps = candidate.media?.bitrateBps,
                        contentLength = candidate.media?.contentLength,
                    ),
            )
        )
    }
}
