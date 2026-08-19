/*
 * Copyright (c) 2026 Auxio Project
 * SourceTrackObservation.kt is part of Auxio.
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
package app.shippy.sources.observation

import app.shippy.core.asset.AssetLocation
import app.shippy.core.asset.AudioTechnicalMetadata
import app.shippy.core.asset.ContentChecksum
import app.shippy.core.asset.MediaAssetKind
import app.shippy.core.music.ArtworkReference
import app.shippy.core.music.Explicitness
import app.shippy.core.music.ExternalIdentifier
import app.shippy.core.music.RecordingVersion
import app.shippy.core.source.SourceKey
import app.shippy.core.source.SourceKind
import java.net.URI
import java.time.Instant

data class ObservedMediaAsset(
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
    val fingerprint: String?,
    val verifiedAt: Instant?,
) {
    init {
        require(locationType.isNotBlank()) { "Asset location type cannot be blank" }
        require(documentId == null || documentId.isNotBlank()) { "Document ID cannot be blank" }
        require(mediaStoreId == null || mediaStoreId >= 0) { "MediaStore ID cannot be negative" }
        require(normalizedPathToken == null || normalizedPathToken.isNotBlank()) {
            "Normalized path token cannot be blank"
        }
        require(downloadJobId == null || downloadJobId.isNotBlank()) {
            "Download job ID cannot be blank"
        }
        require(lastModifiedEpochMs == null || lastModifiedEpochMs >= 0) {
            "Last-modified time cannot be negative"
        }
        require(fingerprint == null || fingerprint.isNotBlank()) { "Fingerprint cannot be blank" }
    }
}

data class SourceTrackObservation(
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
    val asset: ObservedMediaAsset?,
    val capturedAt: Instant,
) {
    init {
        require(title == null || title.isNotBlank()) { "Observed title cannot be blank" }
        require(artistNames.all(String::isNotBlank)) { "Observed artists cannot be blank" }
        require(releaseTitle == null || releaseTitle.isNotBlank()) {
            "Observed release cannot be blank"
        }
        require(durationMs == null || durationMs >= 0) { "Observed duration cannot be negative" }
        require(originalUrl == null || originalUrl.isPublicHttps()) {
            "Original source URL must be public HTTPS"
        }
        require(
            sourceKind == SourceKind.LOCAL_FILE ||
                sourceKind == SourceKind.SHIPPY_DOWNLOAD ||
                artwork.all { it.value.isPublicHttps() }
        ) {
            "Provider artwork references must be public HTTPS"
        }
        require(
            sourceKind == SourceKind.LOCAL_FILE ||
                sourceKind == SourceKind.SHIPPY_DOWNLOAD ||
                asset == null
        ) {
            "Provider observations cannot embed durable device asset locations"
        }
    }
}

private fun String.isPublicHttps(): Boolean =
    runCatching { URI(this) }
        .getOrNull()
        ?.let { uri ->
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null
        } == true
