/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackResolutionCoordinator.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.domain

import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import org.oxycblt.auxio.shippy.crew.cache.CrewTemporaryMediaIndex
import org.oxycblt.auxio.shippy.download.withVerifiedDownloadCandidate
import org.oxycblt.auxio.shippy.media.MediaObjectKey
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobRepository
import org.oxycblt.auxio.shippy.persistence.download.PersistedDownload
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
private constructor(
    private val playbackResolver: PlaybackResolver,
    private val providerRegistry: ProviderRegistry,
    private val latestDownloadForTrack: suspend (TrackId) -> PersistedDownload?,
    private val augmentActiveCrewTemporary: (QueueItem) -> QueueItem?,
) {
    @Inject
    constructor(
        playbackResolver: PlaybackResolver,
        providerRegistry: ProviderRegistry,
        downloadJobs: DownloadJobRepository,
        crewTemporaryMediaIndex: CrewTemporaryMediaIndex,
    ) : this(
        playbackResolver,
        providerRegistry,
        downloadJobs::getLatestForTrack,
        crewTemporaryMediaIndex::augmentActive,
    )

    internal constructor(
        playbackResolver: PlaybackResolver,
        providerRegistry: ProviderRegistry,
    ) : this(playbackResolver, providerRegistry, { null }, { null })

    internal constructor(
        playbackResolver: PlaybackResolver,
        providerRegistry: ProviderRegistry,
        latestDownloadForTrack: suspend (TrackId) -> PersistedDownload?,
        @Suppress("UNUSED_PARAMETER") testSeam: Unit = Unit,
    ) : this(playbackResolver, providerRegistry, latestDownloadForTrack, { null })

    internal constructor(
        playbackResolver: PlaybackResolver,
        providerRegistry: ProviderRegistry,
        latestDownloadForTrack: suspend (TrackId) -> PersistedDownload?,
        augmentActiveCrewTemporary: (QueueItem) -> QueueItem?,
        @Suppress("UNUSED_PARAMETER") testSeam: Boolean = true,
    ) : this(playbackResolver, providerRegistry, latestDownloadForTrack, augmentActiveCrewTemporary)

    suspend fun prepare(
        item: QueueItem,
        policy: ResolutionPolicy,
        constraints: StreamConstraints = StreamConstraints(),
    ): PlaybackPreparation {
        val resolvedItem = item.withActiveCrewTemporary().withLatestVerifiedDownload()
        val candidate =
            when (val selection = playbackResolver.resolve(resolvedItem.track, policy)) {
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
            prepareProvider(resolvedItem, candidate, constraints)
        } else {
            prepareDirect(resolvedItem, candidate)
        }
    }

    private suspend fun QueueItem.withLatestVerifiedDownload(): QueueItem =
        try {
            withVerifiedDownloadCandidate(latestDownloadForTrack(track.id))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            this
        }

    private fun QueueItem.withActiveCrewTemporary(): QueueItem =
        try {
            augmentActiveCrewTemporary(this) ?: this
        } catch (_: Exception) {
            this
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
                                    mediaObjectKey =
                                        MediaObjectKey.from(
                                            candidate,
                                            mimeType = result.value.mimeType,
                                            bitrateBps =
                                                result.value.bitrateBps
                                                    ?: constraints.preferredBitrateBps,
                                        ),
                                    cacheEligible = true,
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

    private fun prepareDirect(item: QueueItem, candidate: TrackCandidate): PlaybackPreparation {
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
                        mediaObjectKey = MediaObjectKey.from(candidate),
                    ),
            )
        )
    }
}
