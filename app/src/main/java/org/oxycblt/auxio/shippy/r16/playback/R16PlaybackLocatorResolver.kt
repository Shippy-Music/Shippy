/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaybackLocatorResolver.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback

import app.shippy.core.asset.MediaAssetKind
import app.shippy.core.identity.ProviderId as CoreProviderId
import app.shippy.core.playback.PlaybackError
import app.shippy.core.source.AvailabilityState
import app.shippy.core.source.IdentityStatus
import app.shippy.core.source.PlaybackSourceCandidate
import app.shippy.core.source.ResolutionCandidateKind
import app.shippy.core.source.SourceSelectionPolicy
import app.shippy.core.source.SourceSelectionResult
import app.shippy.data.playback.R16PlayableAsset
import app.shippy.data.playback.R16PlaybackSourceRepository
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.media.MediaObjectKey
import org.oxycblt.auxio.shippy.provider.MusicProvider
import org.oxycblt.auxio.shippy.provider.ProviderHealth
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.StreamConstraints

/**
 * Resolves R16 durable source/asset identity into one process-private locator. Retry one
 * re-resolves the preferred provider; the final coordinator retry may fall back through remaining
 * candidates.
 */
class R16PlaybackLocatorResolver(
    private val repository: R16PlaybackSourceRepository,
    private val providers: ProviderRegistry,
    private val selectionPolicy: SourceSelectionPolicy = SourceSelectionPolicy(),
    private val onMeteredNetwork: () -> Boolean = { false },
    private val allowProviderOnMetered: () -> Boolean = { true },
    private val preferredBitrateBps: () -> Int? = { null },
    private val providerPriority: (String) -> Int = ::defaultProviderPriority,
) : PlaybackLocatorResolver {
    override suspend fun resolve(request: PlaybackPreparationRequest): PlaybackLocatorResolution {
        val options = repository.options(request.recordingId)
        val materializations =
            buildMap<String, Materialization> {
                options.assets.forEach { asset ->
                    put(asset.stableKey(), Materialization.Asset(asset, asset.toCandidate()))
                }
                options.sources.forEach { source ->
                    val provider = providers.get(ProviderId(source.source.providerId.value))
                    if (provider != null) {
                        put(
                            source.stableKey(),
                            Materialization.Provider(source, provider, source.toCandidate(provider)),
                        )
                    }
                }
            }
        val remaining =
            materializations.values
                .map(Materialization::candidate)
                .filterNot { it.stableKey in request.excludedStableKeys }
                .toMutableList()
        var lastError: PlaybackError? = null
        while (remaining.isNotEmpty()) {
            val selected = selectionPolicy.select(remaining, onMeteredNetwork())
            if (selected !is SourceSelectionResult.Selected) break
            val candidate = selected.candidate
            val materialization = checkNotNull(materializations[candidate.stableKey])
            when (materialization) {
                is Materialization.Asset ->
                    return PlaybackLocatorResolution.Ready(materialization.asset.toLocator())
                is Materialization.Provider -> {
                    val result = materialization.resolve(request)
                    if (result is PlaybackLocatorResolution.Ready) return result
                    val failure = result as PlaybackLocatorResolution.Unavailable
                    lastError = failure.error
                    if (request.attempt == 1 && failure.error.retryable) return failure
                    remaining.removeAll { it.stableKey == candidate.stableKey }
                }
            }
        }
        return PlaybackLocatorResolution.Unavailable(
            lastError ?: PlaybackError("SOURCE_UNAVAILABLE", retryable = false)
        )
    }

    private sealed interface Materialization {
        val candidate: PlaybackSourceCandidate

        data class Asset(
            val asset: R16PlayableAsset,
            override val candidate: PlaybackSourceCandidate,
        ) : Materialization

        data class Provider(
            val source: app.shippy.core.source.SourceReference,
            val provider: MusicProvider,
            override val candidate: PlaybackSourceCandidate,
        ) : Materialization
    }

    private suspend fun Materialization.Provider.resolve(
        request: PlaybackPreparationRequest
    ): PlaybackLocatorResolution {
        val sourceCandidate = source.toLegacyCandidate(request)
        return try {
            when (
                val result =
                    provider.resolve(
                        sourceCandidate,
                        StreamConstraints(
                            preferredBitrateBps = preferredBitrateBps(),
                            allowMetered = !onMeteredNetwork() || allowProviderOnMetered(),
                        ),
                    )
            ) {
                is ProviderResult.Success -> {
                    val stream = result.value
                    PlaybackLocatorResolution.Ready(
                        PlaybackLocator(
                            stableKey = source.stableKey(),
                            sourceReferenceId = source.id,
                            mediaAssetId = null,
                            uri = stream.uri,
                            mimeType = stream.mimeType,
                            headers = stream.headers,
                            cacheKey =
                                MediaObjectKey.fromSourceReference(
                                        source.id,
                                        listOfNotNull(
                                                stream.mimeType,
                                                stream.bitrateBps?.toString(),
                                            )
                                            .joinToString("-")
                                            .ifEmpty { "DEFAULT" },
                                    )
                                    .value,
                            expiresAtEpochMs = stream.expiresAtEpochMs,
                        )
                    )
                }
                is ProviderResult.Failure -> PlaybackLocatorResolution.Unavailable(result.toError())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PlaybackLocatorResolution.Unavailable(
                PlaybackError("SOURCE_PROVIDER_EXCEPTION", retryable = true)
            )
        }
    }

    private fun app.shippy.core.source.SourceReference.toLegacyCandidate(
        request: PlaybackPreparationRequest
    ) =
        TrackCandidate(
            id = CandidateId("r16:${id.value}"),
            trackId = TrackId(request.recordingId.value),
            kind = CandidateKind.PROVIDER,
            sourceId = source.providerId.value,
            sourceItemId = source.sourceItemId,
            availability = CandidateAvailability.RESOLVABLE,
            // originalUrl is canonical provenance, never a resolved media locator.
            locator = null,
            providerId = ProviderId(source.providerId.value),
        )

    private fun R16PlayableAsset.toLocator() =
        PlaybackLocator(
            stableKey = stableKey(),
            sourceReferenceId = sourceReferenceId,
            mediaAssetId = id,
            uri = location,
            mimeType = technical.mimeType,
        )

    private fun R16PlayableAsset.toCandidate() =
        PlaybackSourceCandidate(
            stableKey = stableKey(),
            kind =
                when (kind) {
                    MediaAssetKind.LOCAL_FILE -> ResolutionCandidateKind.LINKED_LOCAL_ASSET
                    MediaAssetKind.SHIPPY_DOWNLOAD -> ResolutionCandidateKind.PERMANENT_DOWNLOAD
                    MediaAssetKind.COMPLETE_CACHE -> ResolutionCandidateKind.COMPLETE_CACHE
                    MediaAssetKind.CREW_TEMPORARY -> ResolutionCandidateKind.CREW_REQUIRED_ASSET
                },
            sourceReferenceId = sourceReferenceId,
            mediaAssetId = id,
            providerId = null,
            availability = AvailabilityState.AVAILABLE,
            identityVerified = true,
            assetVerified = true,
            enabled = true,
            meteredAllowed = true,
            userPreferred = false,
            lossless = technical.isLossless(),
            providerPriority = 0,
            startupCost = 0,
        )

    private fun app.shippy.core.source.SourceReference.toCandidate(provider: MusicProvider) =
        PlaybackSourceCandidate(
            stableKey = stableKey(),
            kind = ResolutionCandidateKind.PROVIDER,
            sourceReferenceId = id,
            mediaAssetId = null,
            providerId = CoreProviderId(source.providerId.value),
            availability = availability.state,
            identityVerified =
                recordingId != null &&
                    identityStatus != IdentityStatus.UNRESOLVED &&
                    identityStatus != IdentityStatus.REJECTED,
            assetVerified = false,
            enabled =
                provider.health() != ProviderHealth.DISABLED &&
                    provider.health() != ProviderHealth.UNAVAILABLE,
            meteredAllowed = allowProviderOnMetered(),
            userPreferred = false,
            lossless = false,
            providerPriority = max(0, providerPriority(source.providerId.value)),
            startupCost = PROVIDER_STARTUP_COST,
        )

    private fun R16PlayableAsset.stableKey() = "asset:${id.value}"

    private fun app.shippy.core.source.SourceReference.stableKey() = "source:${id.value}"

    private fun app.shippy.core.asset.AudioTechnicalMetadata.isLossless(): Boolean {
        val description = listOfNotNull(mimeType, codec).joinToString(" ").lowercase()
        return LOSSLESS_MARKERS.any(description::contains)
    }

    private fun ProviderResult.Failure.toError() = PlaybackError("SOURCE_${kind.name}", retryable)

    private companion object {
        const val PROVIDER_STARTUP_COST = 10
        val LOSSLESS_MARKERS = listOf("flac", "alac", "wav", "pcm")
    }
}

private fun defaultProviderPriority(providerId: String): Int =
    when (providerId) {
        "youtube_music" -> 0
        "jiosaavn" -> 1
        "youtube" -> 2
        else -> 100
    }
