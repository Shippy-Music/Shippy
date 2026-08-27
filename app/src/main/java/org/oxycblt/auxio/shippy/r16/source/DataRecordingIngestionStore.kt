/*
 * Copyright (c) 2026 Auxio Project
 * DataRecordingIngestionStore.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.source

import app.shippy.data.ingest.R16AssetWriteMode
import app.shippy.data.ingest.R16IdentityCandidate
import app.shippy.data.ingest.R16IngestionCommand
import app.shippy.data.ingest.R16IngestionRepository
import app.shippy.data.ingest.R16IngestionSession
import app.shippy.data.ingest.R16ManagedAsset
import app.shippy.data.ingest.R16ObservedAsset
import app.shippy.data.ingest.R16SourceObservation
import app.shippy.sources.asset.ManagedAssetEvidence
import app.shippy.sources.asset.ManagedAssetMatch
import app.shippy.sources.asset.ManagedAssetRecord
import app.shippy.sources.asset.ManagedAssetRegistry
import app.shippy.sources.ingest.ExistingSourceLink
import app.shippy.sources.ingest.IngestionIdentityCandidate
import app.shippy.sources.ingest.RecordingIngestionStore
import app.shippy.sources.ingest.RecordingIngestionTransaction
import app.shippy.sources.ingest.RecordingIngestionWrite
import app.shippy.sources.observation.ObservedMediaAsset
import app.shippy.sources.observation.SourceTrackObservation

class DataRecordingIngestionStore(
    private val repository: R16IngestionRepository,
    private val identityMatchingEnabled: Boolean = true,
) : RecordingIngestionStore {
    override suspend fun <T> transaction(block: suspend RecordingIngestionTransaction.() -> T): T =
        repository.transaction {
            block(DataRecordingIngestionTransaction(this, identityMatchingEnabled))
        }
}

private class DataRecordingIngestionTransaction(
    private val session: R16IngestionSession,
    private val identityMatchingEnabled: Boolean,
) : RecordingIngestionTransaction {
    override suspend fun exactSource(
        sourceKey: app.shippy.core.source.SourceKey
    ): ExistingSourceLink? =
        session.exactSource(sourceKey)?.let { ExistingSourceLink(it.sourceKey, it.recordingId) }

    override suspend fun classifyManagedAsset(asset: ManagedAssetEvidence): ManagedAssetMatch {
        val observed = asset.toDataAsset()
        val candidates = session.managedAssetCandidates(observed).map(R16ManagedAsset::toRecord)
        return ManagedAssetRegistry(candidates).classify(asset)
    }

    override suspend fun identityCandidates(
        observation: SourceTrackObservation,
        features: app.shippy.core.identitymatch.MatchingFeatures,
    ): List<IngestionIdentityCandidate> =
        if (!identityMatchingEnabled) {
            emptyList()
        } else {
            session
                .identityCandidates(observation.toDataObservation(), features)
                .map(R16IdentityCandidate::toCandidate)
        }

    override suspend fun persist(write: RecordingIngestionWrite) {
        session.persist(
            R16IngestionCommand(
                observation = write.observation.toDataObservation(),
                recordingId = write.recordingId,
                newRecording = write.newRecording,
                resolution = write.resolution.name,
                identityEvidence = write.identityEvidence,
                assetWriteMode = write.assetWriteMode(),
                exactManagedAssetId =
                    (write.managedAssetMatch as? ManagedAssetMatch.Exact)?.record?.assetId,
                reviewCandidateIds = write.reviewCandidateIds,
            )
        )
    }
}

private fun RecordingIngestionWrite.assetWriteMode(): R16AssetWriteMode =
    when {
        observation.asset == null -> R16AssetWriteMode.NONE
        managedAssetMatch is ManagedAssetMatch.Exact -> R16AssetWriteMode.REUSE_EXACT
        managedAssetMatch is ManagedAssetMatch.Probable -> R16AssetWriteMode.NONE
        else -> R16AssetWriteMode.REGISTER
    }

private fun R16IdentityCandidate.toCandidate() =
    IngestionIdentityCandidate(recordingId, features, explicitlyRejected)

private fun R16ManagedAsset.toRecord() =
    ManagedAssetRecord(
        assetId = assetId,
        recordingId = recordingId,
        evidence = evidence.toManagedEvidence(),
        verified = verified,
    )

private fun R16ObservedAsset.toManagedEvidence() =
    ManagedAssetEvidence(
        locationType = locationType,
        location = location,
        documentId = documentId,
        mediaStoreId = mediaStoreId,
        normalizedPathToken = normalizedPathToken,
        contentLength = technical.contentLength,
        lastModifiedEpochMs = lastModifiedEpochMs,
        checksum = checksum,
        fingerprint = fingerprintId,
        downloadJobId = downloadJobId,
    )

private fun ManagedAssetEvidence.toDataAsset() =
    R16ObservedAsset(
        kind = app.shippy.core.asset.MediaAssetKind.LOCAL_FILE,
        location = location,
        locationType = locationType,
        documentId = documentId,
        mediaStoreId = mediaStoreId,
        normalizedPathToken = normalizedPathToken,
        downloadJobId = downloadJobId,
        lastModifiedEpochMs = lastModifiedEpochMs,
        technical =
            app.shippy.core.asset.AudioTechnicalMetadata(
                mimeType = null,
                codec = null,
                bitrateBps = null,
                sampleRateHz = null,
                channelCount = null,
                contentLength = contentLength,
            ),
        checksum = checksum,
        fingerprintId = fingerprint,
        verifiedAt = null,
    )

private fun SourceTrackObservation.toDataObservation() =
    R16SourceObservation(
        sourceKey = sourceKey,
        sourceKind = sourceKind,
        title = title,
        artistNames = artistNames,
        releaseTitle = releaseTitle,
        durationMs = durationMs,
        version = version,
        explicitness = explicitness,
        artwork = artwork,
        externalIdentifiers = externalIdentifiers,
        originalUrl = originalUrl,
        asset = asset?.toDataAsset(),
        capturedAt = capturedAt,
    )

private fun ObservedMediaAsset.toDataAsset() =
    R16ObservedAsset(
        kind = kind,
        location = location,
        locationType = locationType,
        documentId = documentId,
        mediaStoreId = mediaStoreId,
        normalizedPathToken = normalizedPathToken,
        downloadJobId = downloadJobId,
        lastModifiedEpochMs = lastModifiedEpochMs,
        technical = technical,
        checksum = checksum,
        fingerprintId = fingerprint,
        verifiedAt = verifiedAt,
    )
