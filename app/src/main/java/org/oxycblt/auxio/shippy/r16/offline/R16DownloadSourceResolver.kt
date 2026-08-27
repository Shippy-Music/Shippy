/*
 * Copyright (c) 2026 Auxio Project
 * R16DownloadSourceResolver.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.offline

import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.source.IdentityStatus
import app.shippy.core.source.SourceKind
import app.shippy.core.source.SourceReference
import app.shippy.data.source.R16SourceStateRepository
import kotlinx.coroutines.CancellationException
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderHealth
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.ResolvedStream
import org.oxycblt.auxio.shippy.provider.StreamConstraints

/** The one persisted source and recording identity a download request is allowed to use. */
data class R16DownloadSourceRequest(
    val recordingId: RecordingId,
    val sourceReferenceId: SourceReferenceId,
    /** Opaque persistence/display data. This resolver deliberately does not interpret it. */
    val requestedMediaVariant: String,
) {
    init {
        require(requestedMediaVariant.isNotBlank()) { "Requested media variant must be non-blank" }
    }
}

/** A provider stream resolved for one exact persisted source; the stream is not rewritten. */
data class R16DownloadSource(
    val recordingId: RecordingId,
    val sourceReferenceId: SourceReferenceId,
    val requestedMediaVariant: String,
    val stream: ResolvedStream,
)

sealed interface R16DownloadSourceResolution {
    data class Ready(val value: R16DownloadSource) : R16DownloadSourceResolution

    data class Rejected(val reason: R16DownloadSourceRejection) : R16DownloadSourceResolution

    data class Failed(val failure: ProviderResult.Failure) : R16DownloadSourceResolution
}

enum class R16DownloadSourceRejection {
    SOURCE_NOT_FOUND,
    RECORDING_MISMATCH,
    IDENTITY_NOT_LINKED,
    NOT_PROVIDER_SOURCE,
    PROVIDER_NOT_REGISTERED,
    PROVIDER_DISABLED,
    PROVIDER_UNAVAILABLE,
    DOWNLOAD_UNSUPPORTED,
}

/**
 * Resolves exactly the persisted provider source named by a download request.
 *
 * This is intentionally not a playback resolver: it does not rank, search, retry, or fall back to
 * another source. Durable source and recording identity are validated before the provider is
 * called, and provider stream metadata remains intact for the transfer/verification boundary.
 */
class R16DownloadSourceResolver(
    private val sources: R16SourceStateRepository,
    private val providers: ProviderRegistry,
) {
    suspend fun resolve(request: R16DownloadSourceRequest): R16DownloadSourceResolution {
        val source =
            sources.get(request.sourceReferenceId)
                ?: return R16DownloadSourceResolution.Rejected(
                    R16DownloadSourceRejection.SOURCE_NOT_FOUND
                )
        if (source.recordingId != request.recordingId) {
            return R16DownloadSourceResolution.Rejected(
                R16DownloadSourceRejection.RECORDING_MISMATCH
            )
        }
        when (source.identityStatus) {
            IdentityStatus.AUTOMATICALLY_LINKED,
            IdentityStatus.USER_CONFIRMED -> Unit
            IdentityStatus.UNRESOLVED,
            IdentityStatus.REJECTED ->
                return R16DownloadSourceResolution.Rejected(
                    R16DownloadSourceRejection.IDENTITY_NOT_LINKED
                )
        }
        if (!source.kind.isProviderSource()) {
            return R16DownloadSourceResolution.Rejected(
                R16DownloadSourceRejection.NOT_PROVIDER_SOURCE
            )
        }

        val providerId = ProviderId(source.source.providerId.value)
        val provider =
            providers.get(providerId)
                ?: return R16DownloadSourceResolution.Rejected(
                    R16DownloadSourceRejection.PROVIDER_NOT_REGISTERED
                )
        if (provider.health() == ProviderHealth.DISABLED) {
            return R16DownloadSourceResolution.Rejected(
                R16DownloadSourceRejection.PROVIDER_DISABLED
            )
        }
        if (provider.health() == ProviderHealth.UNAVAILABLE) {
            return R16DownloadSourceResolution.Rejected(
                R16DownloadSourceRejection.PROVIDER_UNAVAILABLE
            )
        }
        if (ProviderCapability.DOWNLOAD !in provider.descriptor.capabilities) {
            return R16DownloadSourceResolution.Rejected(
                R16DownloadSourceRejection.DOWNLOAD_UNSUPPORTED
            )
        }

        val candidate = source.toDownloadCandidate(request.recordingId)
        return try {
            when (val result = provider.resolve(candidate, StreamConstraints(forDownload = true))) {
                is ProviderResult.Success -> {
                    if (result.value.candidateId != candidate.id) {
                        R16DownloadSourceResolution.Failed(
                            ProviderResult.Failure(
                                kind = ProviderFailureKind.MALFORMED_RESPONSE,
                                retryable = false,
                                message = "Provider resolved a different source candidate",
                            )
                        )
                    } else {
                        R16DownloadSourceResolution.Ready(
                            R16DownloadSource(
                                recordingId = request.recordingId,
                                sourceReferenceId = request.sourceReferenceId,
                                requestedMediaVariant = request.requestedMediaVariant,
                                stream = result.value,
                            )
                        )
                    }
                }
                is ProviderResult.Failure -> R16DownloadSourceResolution.Failed(result)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            R16DownloadSourceResolution.Failed(
                ProviderResult.Failure(
                    kind = ProviderFailureKind.UNAVAILABLE,
                    retryable = false,
                    message = error.message,
                )
            )
        }
    }

    private fun SourceReference.toDownloadCandidate(recordingId: RecordingId) =
        TrackCandidate(
            id = CandidateId("r16-download:${id.value}"),
            trackId = TrackId(recordingId.value),
            kind = CandidateKind.PROVIDER,
            sourceId = source.providerId.value,
            sourceItemId = source.sourceItemId,
            availability = CandidateAvailability.RESOLVABLE,
            providerId = ProviderId(source.providerId.value),
        )

    private fun SourceKind.isProviderSource(): Boolean =
        this == SourceKind.JIOSAAVN ||
            this == SourceKind.YOUTUBE_MUSIC ||
            this == SourceKind.YOUTUBE
}
