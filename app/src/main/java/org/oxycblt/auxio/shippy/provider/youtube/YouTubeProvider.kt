/*
 * Copyright (c) 2026 Auxio Project
 * YouTubeProvider.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.provider.youtube

import java.io.IOException
import java.net.URI
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.provider.MusicProvider
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderDescriptor
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderHealth
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.ResolvedStream
import org.oxycblt.auxio.shippy.provider.SearchPage
import org.oxycblt.auxio.shippy.provider.StreamConstraints

class YouTubeProvider @Inject constructor(gateway: YouTubeExtractionGateway) :
    BaseYouTubeProvider(
        id = ProviderId("youtube"),
        displayName = "YouTube",
        searchFilter = YouTubeSearchFilter.VIDEOS,
        gateway = gateway,
    )

open class BaseYouTubeProvider(
    private val id: ProviderId,
    displayName: String,
    private val searchFilter: YouTubeSearchFilter,
    private val gateway: YouTubeExtractionGateway,
) : MusicProvider {
    override val descriptor =
        ProviderDescriptor(
            id = id,
            displayName = displayName,
            capabilities =
                setOf(
                    ProviderCapability.SEARCH,
                    ProviderCapability.TRACK,
                    ProviderCapability.STREAM,
                    ProviderCapability.DOWNLOAD,
                ),
        )

    override fun health() = ProviderHealth.AVAILABLE

    override suspend fun search(query: String, continuation: String?): ProviderResult<SearchPage> {
        if (query.isBlank()) return ProviderResult.Success(SearchPage(emptyList()))
        if (continuation != null)
            return unsupported("YouTube search continuation is not implemented")
        return try {
            ProviderResult.Success(
                SearchPage(gateway.search(query.trim(), searchFilter).map(::toTrack))
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            failure(ProviderFailureKind.NETWORK, retryable = true, error.message)
        } catch (error: Exception) {
            failure(ProviderFailureKind.UNAVAILABLE, retryable = true, error.message)
        }
    }

    override suspend fun resolve(
        candidate: TrackCandidate,
        constraints: StreamConstraints,
    ): ProviderResult<ResolvedStream> {
        if (candidate.providerId != id || candidate.kind != CandidateKind.PROVIDER) {
            return unsupported("Candidate does not belong to ${descriptor.displayName}")
        }
        return try {
            val selected =
                gateway.audioStreams(candidate.sourceItemId).select(constraints)
                    ?: return failure(
                        ProviderFailureKind.UNSUPPORTED,
                        retryable = false,
                        message = "No direct HTTPS audio stream is available",
                    )
            ProviderResult.Success(
                ResolvedStream(
                    candidateId = candidate.id,
                    uri = selected.url,
                    mimeType = selected.mimeType,
                    bitrateBps = selected.bitrateBps,
                    expiresAtEpochMs = selected.url.expiryEpochMs(),
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            failure(ProviderFailureKind.NETWORK, retryable = true, error.message)
        } catch (error: Exception) {
            failure(ProviderFailureKind.UNAVAILABLE, retryable = true, error.message)
        }
    }

    private fun toTrack(item: YouTubeExtractedTrack): Track {
        val trackId = TrackId("${id.value}:${item.videoId}")
        return Track(
            id = trackId,
            realm = TrackRealm.PROVIDER,
            title = item.title,
            artists = listOf(item.uploader),
            durationMs = item.durationMs,
            artwork = item.artwork,
            candidates =
                listOf(
                    TrackCandidate(
                        id = CandidateId("${id.value}:${item.videoId}"),
                        trackId = trackId,
                        kind = CandidateKind.PROVIDER,
                        sourceId = id.value,
                        sourceItemId = item.videoId,
                        availability = CandidateAvailability.RESOLVABLE,
                        locator = item.originalUrl,
                        providerId = id,
                    )
                ),
        )
    }

    private fun List<YouTubeExtractedAudio>.select(
        constraints: StreamConstraints
    ): YouTubeExtractedAudio? {
        val preferred = constraints.preferredBitrateBps
        return if (preferred == null) {
            maxByOrNull { it.bitrateBps ?: Int.MIN_VALUE }
        } else {
            minByOrNull { abs((it.bitrateBps ?: preferred) - preferred) }
        }
    }

    private fun unsupported(message: String): ProviderResult.Failure =
        failure(ProviderFailureKind.UNSUPPORTED, retryable = false, message = message)

    private fun failure(kind: ProviderFailureKind, retryable: Boolean, message: String?) =
        ProviderResult.Failure(kind, retryable, message)
}

private fun String.expiryEpochMs(): Long? =
    runCatching {
            URI(this)
                .rawQuery
                ?.split('&')
                ?.firstOrNull { it.substringBefore('=') == "expire" }
                ?.substringAfter('=', missingDelimiterValue = "")
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.times(1_000)
        }
        .getOrNull()
