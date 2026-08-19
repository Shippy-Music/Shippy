/*
 * Copyright (c) 2026 Auxio Project
 * RecordingIngestor.kt is part of Auxio.
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
package app.shippy.sources.ingest

import app.shippy.core.identity.RecordingId
import app.shippy.core.identitymatch.MatchAssessment
import app.shippy.core.identitymatch.MatchCandidate
import app.shippy.core.identitymatch.MatchDecision
import app.shippy.core.identitymatch.MatchingFeatures
import app.shippy.core.identitymatch.MatchingPolicy
import app.shippy.core.identitymatch.MetadataNormalizer
import app.shippy.core.identitymatch.RecordingDraft
import app.shippy.core.music.ExternalIdentifierKind
import app.shippy.core.source.SourceKey
import app.shippy.sources.asset.ManagedAssetEvidence
import app.shippy.sources.asset.ManagedAssetMatch
import app.shippy.sources.observation.ObservedMediaAsset
import app.shippy.sources.observation.SourceTrackObservation
import java.util.UUID

fun interface RecordingIdFactory {
    fun create(observation: SourceTrackObservation): RecordingId
}

data class ExistingSourceLink(val sourceKey: SourceKey, val recordingId: RecordingId?)

data class IngestionIdentityCandidate(
    val recordingId: RecordingId,
    val features: MatchingFeatures,
    val explicitlyRejected: Boolean = false,
)

enum class IngestionResolution {
    EXACT_SOURCE,
    MANAGED_ASSET,
    VERIFIED_IDENTITY,
    NEW_RECORDING,
    UNRESOLVED_METADATA,
}

data class RecordingIngestionWrite(
    val observation: SourceTrackObservation,
    val recordingId: RecordingId?,
    val newRecording: RecordingDraft?,
    val resolution: IngestionResolution,
    val identityEvidence: MatchAssessment?,
    val managedAssetMatch: ManagedAssetMatch?,
    val reviewCandidateIds: Set<RecordingId>,
) {
    init {
        require((resolution == IngestionResolution.UNRESOLVED_METADATA) == (recordingId == null)) {
            "Only unresolved metadata may omit recording identity"
        }
        require((resolution == IngestionResolution.NEW_RECORDING) == (newRecording != null)) {
            "Only a new-recording write may carry a recording draft"
        }
        require(newRecording == null || recordingId != null) {
            "A recording draft requires its new identity"
        }
    }
}

data class RecordingIngestionResult(
    val recordingId: RecordingId?,
    val resolution: IngestionResolution,
    val reviewCandidateIds: Set<RecordingId>,
)

interface RecordingIngestionTransaction {
    suspend fun exactSource(sourceKey: SourceKey): ExistingSourceLink?

    suspend fun classifyManagedAsset(asset: ManagedAssetEvidence): ManagedAssetMatch

    suspend fun identityCandidates(features: MatchingFeatures): List<IngestionIdentityCandidate>

    suspend fun persist(write: RecordingIngestionWrite)
}

interface RecordingIngestionStore {
    suspend fun <T> transaction(block: suspend RecordingIngestionTransaction.() -> T): T
}

/**
 * Chooses one recording identity and persists the source observation in the same store transaction.
 * Metadata-only ambiguity never silently merges recordings.
 */
class RecordingIngestor(
    private val store: RecordingIngestionStore,
    private val matchingPolicy: MatchingPolicy = MatchingPolicy(),
    private val recordingIdFactory: RecordingIdFactory = RecordingIdFactory {
        RecordingId(UUID.randomUUID().toString())
    },
) {
    suspend fun ingest(observation: SourceTrackObservation): RecordingIngestionResult =
        store.transaction {
            val exactSource = exactSource(observation.sourceKey)
            exactSource?.recordingId?.let { recordingId ->
                return@transaction persistAndReturn(
                    RecordingIngestionWrite(
                        observation = observation,
                        recordingId = recordingId,
                        newRecording = null,
                        resolution = IngestionResolution.EXACT_SOURCE,
                        identityEvidence = null,
                        managedAssetMatch = null,
                        reviewCandidateIds = emptySet(),
                    )
                )
            }

            val managedMatch =
                observation.asset?.let { classifyManagedAsset(it.toManagedEvidence()) }
                    ?: ManagedAssetMatch.None
            if (managedMatch is ManagedAssetMatch.Exact) {
                return@transaction persistAndReturn(
                    RecordingIngestionWrite(
                        observation = observation,
                        recordingId = managedMatch.record.recordingId,
                        newRecording = null,
                        resolution = IngestionResolution.MANAGED_ASSET,
                        identityEvidence = null,
                        managedAssetMatch = managedMatch,
                        reviewCandidateIds = emptySet(),
                    )
                )
            }

            val features = observation.toMatchingFeatures()
            val assessed =
                identityCandidates(features)
                    .distinctBy(IngestionIdentityCandidate::recordingId)
                    .sortedBy { it.recordingId.value }
                    .map { candidate ->
                        MatchCandidate(
                            recordingId = candidate.recordingId,
                            assessment =
                                matchingPolicy.assess(
                                    incoming = features,
                                    candidate = candidate.features,
                                    explicitlyRejected = candidate.explicitlyRejected,
                                ),
                        )
                    }
            val automatic =
                assessed.filter {
                    it.assessment.decision == MatchDecision.AUTO_LINK && !it.assessment.vetoed
                }
            val selected = automatic.singleOrNull()
            val reviewIds = buildSet {
                if (managedMatch is ManagedAssetMatch.Probable) {
                    addAll(managedMatch.candidates.map { it.recordingId })
                }
                assessed
                    .filter {
                        it.assessment.decision == MatchDecision.REVIEW_PROBABLE ||
                            it.assessment.decision == MatchDecision.REVIEW_POSSIBLE ||
                            automatic.size > 1 && it in automatic
                    }
                    .mapTo(this) { it.recordingId }
                selected?.let { remove(it.recordingId) }
            }
            if (selected != null) {
                return@transaction persistAndReturn(
                    RecordingIngestionWrite(
                        observation = observation,
                        recordingId = selected.recordingId,
                        newRecording = null,
                        resolution = IngestionResolution.VERIFIED_IDENTITY,
                        identityEvidence = selected.assessment,
                        managedAssetMatch = managedMatch,
                        reviewCandidateIds = reviewIds,
                    )
                )
            }

            val draft = observation.toRecordingDraft()
            val recordingId = draft?.let { recordingIdFactory.create(observation) }
            persistAndReturn(
                RecordingIngestionWrite(
                    observation = observation,
                    recordingId = recordingId,
                    newRecording = draft,
                    resolution =
                        if (draft == null) {
                            IngestionResolution.UNRESOLVED_METADATA
                        } else {
                            IngestionResolution.NEW_RECORDING
                        },
                    identityEvidence = null,
                    managedAssetMatch = managedMatch,
                    reviewCandidateIds = reviewIds,
                )
            )
        }
}

private suspend fun RecordingIngestionTransaction.persistAndReturn(
    write: RecordingIngestionWrite
): RecordingIngestionResult {
    persist(write)
    return RecordingIngestionResult(
        recordingId = write.recordingId,
        resolution = write.resolution,
        reviewCandidateIds = write.reviewCandidateIds,
    )
}

private fun SourceTrackObservation.toRecordingDraft(): RecordingDraft? {
    val canonicalTitle = title?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val primaryArtist = artistNames.firstOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return RecordingDraft(canonicalTitle, primaryArtist, durationMs, version)
}

private fun SourceTrackObservation.toMatchingFeatures(): MatchingFeatures {
    val identifiers = externalIdentifiers.groupBy { it.kind }
    return MatchingFeatures(
        normalizedTitle = MetadataNormalizer.comparisonKey(title),
        normalizedPrimaryArtist = MetadataNormalizer.comparisonKey(artistNames.firstOrNull()),
        normalizedArtistSet =
            artistNames.mapNotNullTo(linkedSetOf(), MetadataNormalizer::comparisonKey),
        normalizedRelease = MetadataNormalizer.comparisonKey(releaseTitle),
        durationMs = durationMs,
        version = version,
        explicitness = explicitness,
        isrcs = identifiers[ExternalIdentifierKind.ISRC].values(),
        musicBrainzRecordingIds =
            identifiers[ExternalIdentifierKind.MUSICBRAINZ_RECORDING].values(),
        acoustIds = identifiers[ExternalIdentifierKind.ACOUST_ID].values(),
        sourceKeys = setOf(sourceKey),
        fingerprintHashes = setOfNotNull(asset?.fingerprint),
    )
}

private fun List<app.shippy.core.music.ExternalIdentifier>?.values(): Set<String> =
    orEmpty().mapTo(linkedSetOf()) { it.value }

private fun ObservedMediaAsset.toManagedEvidence() =
    ManagedAssetEvidence(
        locationType = locationType,
        location = location,
        documentId = documentId,
        mediaStoreId = mediaStoreId,
        normalizedPathToken = normalizedPathToken,
        contentLength = technical.contentLength,
        lastModifiedEpochMs = lastModifiedEpochMs,
        checksum = checksum,
        fingerprint = fingerprint,
        downloadJobId = downloadJobId,
    )
