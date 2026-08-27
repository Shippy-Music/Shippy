/*
 * Copyright (c) 2026 Auxio Project
 * R16DownloadSourceResolverTest.kt is part of Auxio.
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

import app.shippy.core.identity.ProviderId as CoreProviderId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.source.AvailabilitySnapshot
import app.shippy.core.source.AvailabilityState
import app.shippy.core.source.IdentityStatus
import app.shippy.core.source.SourceItemType
import app.shippy.core.source.SourceKey
import app.shippy.core.source.SourceKind
import app.shippy.core.source.SourceReference
import app.shippy.data.source.R16SourceStateRepository
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.provider.MusicProvider
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderDescriptor
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderHealth
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.ResolvedStream
import org.oxycblt.auxio.shippy.provider.SearchPage
import org.oxycblt.auxio.shippy.provider.StreamConstraints

class R16DownloadSourceResolverTest {
    @Test
    fun `resolves exact provider source with download constraints and preserves stream`() =
        runBlocking {
            val recordingId = RecordingId(idValue(1))
            val source = source(recordingId, SourceKind.YOUTUBE, 2, "youtube")
            val stream =
                ResolvedStream(
                    candidateId = CandidateId("r16-download:${source.id.value}"),
                    uri = "https://audio.example.invalid/download",
                    mimeType = "audio/webm",
                    bitrateBps = 192_000,
                    contentLength = 9_876L,
                    expiresAtEpochMs = 123_456L,
                    headers = mapOf("Authorization" to "opaque-token"),
                )
            val provider =
                FakeProvider(
                    "youtube",
                    ProviderCapability.DOWNLOAD,
                    result = ProviderResult.Success(stream),
                )
            val resolver =
                R16DownloadSourceResolver(
                    FakeSourceStateRepository(source),
                    ProviderRegistry(setOf(provider)),
                )

            val result =
                resolver.resolve(
                    R16DownloadSourceRequest(
                        recordingId = recordingId,
                        sourceReferenceId = source.id,
                        requestedMediaVariant = "variant:lossless-ish",
                    )
                )

            val ready = (result as R16DownloadSourceResolution.Ready).value
            assertEquals(recordingId, ready.recordingId)
            assertEquals(source.id, ready.sourceReferenceId)
            assertEquals("variant:lossless-ish", ready.requestedMediaVariant)
            assertEquals(stream, ready.stream)
            assertEquals(provider.lastCandidate?.id, ready.stream.candidateId)
            assertEquals(source.source.sourceItemId, provider.lastCandidate?.sourceItemId)
            assertEquals(recordingId.value, provider.lastCandidate?.trackId?.value)
            assertEquals(true, provider.lastConstraints?.forDownload)
            assertEquals(1, provider.resolveCount)
        }

    @Test
    fun `provider stream for a different candidate fails closed`() = runBlocking {
        val recordingId = RecordingId(idValue(1))
        val source = source(recordingId, SourceKind.YOUTUBE, 2, "youtube")
        val provider =
            FakeProvider(
                "youtube",
                ProviderCapability.DOWNLOAD,
                result =
                    ProviderResult.Success(
                        ResolvedStream(
                            CandidateId("different-candidate"),
                            "https://audio.example.invalid/wrong-download",
                        )
                    ),
            )

        val result =
            R16DownloadSourceResolver(
                    FakeSourceStateRepository(source),
                    ProviderRegistry(setOf(provider)),
                )
                .resolve(request(recordingId, source))

        val failed = result as R16DownloadSourceResolution.Failed
        assertEquals(ProviderFailureKind.MALFORMED_RESPONSE, failed.failure.kind)
        assertEquals(false, failed.failure.retryable)
        assertEquals(CandidateId("r16-download:${source.id.value}"), provider.lastCandidate?.id)
        assertEquals(1, provider.resolveCount)
    }

    @Test
    fun `missing or mismatched source is rejected before provider resolution`() = runBlocking {
        val recordingId = RecordingId(idValue(1))
        val source = source(recordingId, SourceKind.YOUTUBE, 2, "youtube")
        val provider = FakeProvider("youtube", ProviderCapability.DOWNLOAD)
        val repository = FakeSourceStateRepository(source)
        val resolver = R16DownloadSourceResolver(repository, ProviderRegistry(setOf(provider)))

        val missing =
            resolver.resolve(
                R16DownloadSourceRequest(
                    recordingId = recordingId,
                    sourceReferenceId = SourceReferenceId(idValue(3)),
                    requestedMediaVariant = "opaque",
                )
            )
        val mismatch =
            resolver.resolve(
                R16DownloadSourceRequest(
                    recordingId = RecordingId(idValue(4)),
                    sourceReferenceId = source.id,
                    requestedMediaVariant = "opaque",
                )
            )

        assertEquals(
            R16DownloadSourceRejection.SOURCE_NOT_FOUND,
            (missing as R16DownloadSourceResolution.Rejected).reason,
        )
        assertEquals(
            R16DownloadSourceRejection.RECORDING_MISMATCH,
            (mismatch as R16DownloadSourceResolution.Rejected).reason,
        )
        assertEquals(0, provider.resolveCount)
    }

    @Test
    fun `non provider or non downloadable provider is rejected without fallback`() = runBlocking {
        val recordingId = RecordingId(idValue(1))
        val localSource = source(recordingId, SourceKind.LOCAL_FILE, 2, "youtube")
        val localProvider = FakeProvider("youtube", ProviderCapability.DOWNLOAD)
        val localResult =
            R16DownloadSourceResolver(
                    FakeSourceStateRepository(localSource),
                    ProviderRegistry(setOf(localProvider)),
                )
                .resolve(request(recordingId, localSource))

        val unsupportedSource = source(recordingId, SourceKind.YOUTUBE, 3, "youtube")
        val unsupportedProvider = FakeProvider("youtube", ProviderCapability.STREAM)
        val unsupportedResult =
            R16DownloadSourceResolver(
                    FakeSourceStateRepository(unsupportedSource),
                    ProviderRegistry(setOf(unsupportedProvider)),
                )
                .resolve(request(recordingId, unsupportedSource))

        val disabledSource = source(recordingId, SourceKind.YOUTUBE, 4, "youtube")
        val disabledProvider =
            FakeProvider(
                "youtube",
                ProviderCapability.DOWNLOAD,
                providerHealth = ProviderHealth.DISABLED,
            )
        val disabledResult =
            R16DownloadSourceResolver(
                    FakeSourceStateRepository(disabledSource),
                    ProviderRegistry(setOf(disabledProvider)),
                )
                .resolve(request(recordingId, disabledSource))

        assertEquals(
            R16DownloadSourceRejection.NOT_PROVIDER_SOURCE,
            (localResult as R16DownloadSourceResolution.Rejected).reason,
        )
        assertEquals(
            R16DownloadSourceRejection.DOWNLOAD_UNSUPPORTED,
            (unsupportedResult as R16DownloadSourceResolution.Rejected).reason,
        )
        assertEquals(
            R16DownloadSourceRejection.PROVIDER_DISABLED,
            (disabledResult as R16DownloadSourceResolution.Rejected).reason,
        )
        assertEquals(0, localProvider.resolveCount)
        assertEquals(0, unsupportedProvider.resolveCount)
        assertEquals(0, disabledProvider.resolveCount)
    }

    @Test
    fun `unresolved or rejected identity is rejected before provider resolution`() = runBlocking {
        val recordingId = RecordingId(idValue(1))
        val unresolvedSource =
            source(
                recordingId,
                SourceKind.YOUTUBE,
                5,
                "youtube",
                identityStatus = IdentityStatus.UNRESOLVED,
            )
        val rejectedSource =
            source(
                recordingId,
                SourceKind.YOUTUBE,
                6,
                "youtube",
                identityStatus = IdentityStatus.REJECTED,
            )
        val unresolvedProvider = FakeProvider("youtube", ProviderCapability.DOWNLOAD)
        val rejectedProvider = FakeProvider("youtube", ProviderCapability.DOWNLOAD)

        val unresolvedResult =
            R16DownloadSourceResolver(
                    FakeSourceStateRepository(unresolvedSource),
                    ProviderRegistry(setOf(unresolvedProvider)),
                )
                .resolve(request(recordingId, unresolvedSource))
        val rejectedResult =
            R16DownloadSourceResolver(
                    FakeSourceStateRepository(rejectedSource),
                    ProviderRegistry(setOf(rejectedProvider)),
                )
                .resolve(request(recordingId, rejectedSource))

        assertEquals(
            R16DownloadSourceRejection.IDENTITY_NOT_LINKED,
            (unresolvedResult as R16DownloadSourceResolution.Rejected).reason,
        )
        assertEquals(
            R16DownloadSourceRejection.IDENTITY_NOT_LINKED,
            (rejectedResult as R16DownloadSourceResolution.Rejected).reason,
        )
        assertEquals(0, unresolvedProvider.resolveCount)
        assertEquals(0, rejectedProvider.resolveCount)
    }

    @Test
    fun `provider failure is returned and cancellation is not converted`() = runBlocking {
        val recordingId = RecordingId(idValue(1))
        val source = source(recordingId, SourceKind.YOUTUBE, 2, "youtube")
        val failureProvider =
            FakeProvider(
                id = "youtube",
                capability = ProviderCapability.DOWNLOAD,
                result =
                    ProviderResult.Failure(
                        kind = ProviderFailureKind.RATE_LIMITED,
                        retryable = true,
                        message = "slow down",
                    ),
            )
        val alternateProvider = FakeProvider("jiosaavn", ProviderCapability.DOWNLOAD)
        val failed =
            R16DownloadSourceResolver(
                    FakeSourceStateRepository(source),
                    ProviderRegistry(setOf(alternateProvider, failureProvider)),
                )
                .resolve(request(recordingId, source))
        assertTrue(failed is R16DownloadSourceResolution.Failed)
        val failedResult = failed as R16DownloadSourceResolution.Failed
        assertEquals(ProviderFailureKind.RATE_LIMITED, failedResult.failure.kind)
        assertEquals(true, failedResult.failure.retryable)
        assertEquals(0, alternateProvider.resolveCount)

        val throwingProvider =
            FakeProvider(
                id = "youtube",
                capability = ProviderCapability.DOWNLOAD,
                error = IllegalStateException("provider exploded"),
            )
        val thrownFailure =
            R16DownloadSourceResolver(
                    FakeSourceStateRepository(source),
                    ProviderRegistry(setOf(throwingProvider)),
                )
                .resolve(request(recordingId, source))
        assertEquals(
            ProviderFailureKind.UNAVAILABLE,
            (thrownFailure as R16DownloadSourceResolution.Failed).failure.kind,
        )
        val thrownFailureResult = thrownFailure as R16DownloadSourceResolution.Failed
        assertEquals(false, thrownFailureResult.failure.retryable)
        assertEquals("provider exploded", thrownFailureResult.failure.message)

        val cancellationProvider =
            FakeProvider(
                id = "youtube",
                capability = ProviderCapability.DOWNLOAD,
                error = CancellationException("cancelled"),
            )
        try {
            R16DownloadSourceResolver(
                    FakeSourceStateRepository(source),
                    ProviderRegistry(setOf(cancellationProvider)),
                )
                .resolve(request(recordingId, source))
            throw AssertionError("Expected cancellation to propagate")
        } catch (cancelled: CancellationException) {
            assertEquals("cancelled", cancelled.message)
        }
    }

    private class FakeSourceStateRepository(private val source: SourceReference?) :
        R16SourceStateRepository {
        override fun observe(recordingId: RecordingId): Flow<List<SourceReference>> = emptyFlow()

        override suspend fun get(sourceReferenceId: SourceReferenceId): SourceReference? =
            source?.takeIf { it.id == sourceReferenceId }

        override suspend fun exact(key: SourceKey): SourceReference? = null

        override suspend fun updateAvailability(
            key: SourceKey,
            availability: AvailabilitySnapshot,
            updatedAt: Instant,
        ) = Unit
    }

    private class FakeProvider(
        id: String,
        capability: ProviderCapability,
        private val result: ProviderResult<ResolvedStream> =
            ProviderResult.Success(
                ResolvedStream(CandidateId("provider-stream"), "https://audio.example.invalid")
            ),
        private val error: Throwable? = null,
        private val providerHealth: ProviderHealth = ProviderHealth.AVAILABLE,
    ) : MusicProvider {
        override val descriptor = ProviderDescriptor(ProviderId(id), id, setOf(capability))
        var resolveCount = 0
        var lastCandidate: TrackCandidate? = null
        var lastConstraints: StreamConstraints? = null

        override fun health() = providerHealth

        override suspend fun search(query: String, continuation: String?) =
            ProviderResult.Success(SearchPage(emptyList()))

        override suspend fun resolve(
            candidate: TrackCandidate,
            constraints: StreamConstraints,
        ): ProviderResult<ResolvedStream> {
            resolveCount += 1
            lastCandidate = candidate
            lastConstraints = constraints
            error?.let { throw it }
            return result
        }
    }

    private fun request(recordingId: RecordingId, source: SourceReference) =
        R16DownloadSourceRequest(
            recordingId = recordingId,
            sourceReferenceId = source.id,
            requestedMediaVariant = "opaque",
        )

    private fun source(
        recordingId: RecordingId,
        kind: SourceKind,
        sourceReferenceValue: Int,
        providerId: String,
        identityStatus: IdentityStatus = IdentityStatus.AUTOMATICALLY_LINKED,
    ) =
        SourceReference(
            id = SourceReferenceId(idValue(sourceReferenceValue)),
            recordingId = recordingId,
            source =
                SourceKey(
                    CoreProviderId(providerId),
                    SourceItemType.VIDEO,
                    "item-$sourceReferenceValue",
                ),
            kind = kind,
            originalUrl = "https://provider.example.invalid/item-$sourceReferenceValue",
            availability = AvailabilitySnapshot(AvailabilityState.RESOLVABLE, null, null, null),
            rawMetadataId = "observation-$sourceReferenceValue",
            identityStatus = identityStatus,
        )

    private fun idValue(value: Int) =
        "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}
