/*
 * Copyright (c) 2026 Auxio Project
 * R16IngestionRepository.kt is part of Auxio.
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
package app.shippy.data.ingest

import app.shippy.core.asset.AssetLocation
import app.shippy.core.asset.AudioTechnicalMetadata
import app.shippy.core.asset.ContentChecksum
import app.shippy.core.asset.MediaAssetKind
import app.shippy.core.identity.MediaAssetId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identitymatch.MatchAssessment
import app.shippy.core.identitymatch.MatchingFeatures
import app.shippy.core.identitymatch.RecordingDraft
import app.shippy.core.music.ArtworkReference
import app.shippy.core.music.Explicitness
import app.shippy.core.music.ExternalIdentifier
import app.shippy.core.music.RecordingVersion
import app.shippy.core.source.SourceKey
import app.shippy.core.source.SourceKind
import java.time.Instant

data class R16ObservedAsset(
    val kind: MediaAssetKind,
    val location: AssetLocation,
    val locationType: String,
    val documentId: String?,
    val mediaStoreId: Long?,
    val normalizedPathToken: String?,
    val downloadJobId: String?,
    val lastModifiedEpochMs: Long?,
    val technical: AudioTechnicalMetadata,
    val checksum: ContentChecksum?,
    val fingerprintId: String?,
    val verifiedAt: Instant?,
)

data class R16SourceObservation(
    val sourceKey: SourceKey,
    val sourceKind: SourceKind,
    val title: String?,
    val artistNames: List<String>,
    val releaseTitle: String?,
    val durationMs: Long?,
    val version: RecordingVersion,
    val explicitness: Explicitness,
    val artwork: List<ArtworkReference>,
    val externalIdentifiers: Set<ExternalIdentifier>,
    val originalUrl: String?,
    val asset: R16ObservedAsset?,
    val capturedAt: Instant,
)

data class R16ExistingSource(val sourceKey: SourceKey, val recordingId: RecordingId?)

data class R16ManagedAsset(
    val assetId: MediaAssetId,
    val recordingId: RecordingId,
    val evidence: R16ObservedAsset,
    val verified: Boolean,
)

data class R16IdentityCandidate(
    val recordingId: RecordingId,
    val features: MatchingFeatures,
    val explicitlyRejected: Boolean,
)

enum class R16AssetWriteMode {
    NONE,
    REGISTER,
    REUSE_EXACT,
}

data class R16IngestionCommand(
    val observation: R16SourceObservation,
    val recordingId: RecordingId?,
    val newRecording: RecordingDraft?,
    val resolution: String,
    val identityEvidence: MatchAssessment?,
    val assetWriteMode: R16AssetWriteMode,
    val exactManagedAssetId: MediaAssetId?,
    val reviewCandidateIds: Set<RecordingId>,
) {
    init {
        require(
            (assetWriteMode == R16AssetWriteMode.REUSE_EXACT) == (exactManagedAssetId != null)
        ) {
            "Only exact managed-asset reuse may carry an existing asset ID"
        }
    }
}

interface R16IngestionSession {
    suspend fun exactSource(sourceKey: SourceKey): R16ExistingSource?

    suspend fun managedAssetCandidates(asset: R16ObservedAsset): List<R16ManagedAsset>

    suspend fun identityCandidates(
        observation: R16SourceObservation,
        features: MatchingFeatures,
    ): List<R16IdentityCandidate>

    suspend fun persist(command: R16IngestionCommand)
}

interface R16IngestionRepository {
    suspend fun <T> transaction(block: suspend R16IngestionSession.() -> T): T
}
