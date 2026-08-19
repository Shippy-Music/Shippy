/*
 * Copyright (c) 2026 Auxio Project
 * AssetModels.kt is part of Auxio.
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
package app.shippy.core.asset

import app.shippy.core.identity.MediaAssetId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import java.time.Instant

enum class MediaAssetKind {
    LOCAL_FILE,
    SHIPPY_DOWNLOAD,
    COMPLETE_CACHE,
    CREW_TEMPORARY,
}

enum class AssetState {
    AVAILABLE,
    VERIFYING,
    MISSING,
    CORRUPT,
    PERMISSION_REQUIRED,
}

@JvmInline
value class AssetLocation(val opaqueHandle: String) {
    init {
        require(opaqueHandle.isNotBlank()) { "Asset location cannot be blank" }
    }
}

data class AudioTechnicalMetadata(
    val mimeType: String?,
    val codec: String?,
    val bitrateBps: Int?,
    val sampleRateHz: Int?,
    val channelCount: Int?,
    val contentLength: Long?,
) {
    init {
        require(bitrateBps == null || bitrateBps > 0) { "Bitrate must be positive" }
        require(sampleRateHz == null || sampleRateHz > 0) { "Sample rate must be positive" }
        require(channelCount == null || channelCount > 0) { "Channel count must be positive" }
        require(contentLength == null || contentLength >= 0) { "Content length cannot be negative" }
    }
}

data class ContentChecksum(val algorithm: String, val value: String) {
    init {
        require(algorithm.isNotBlank() && value.isNotBlank()) { "Checksum must be complete" }
    }
}

@JvmInline
value class FingerprintReference(val value: String) {
    init {
        require(value.isNotBlank()) { "Fingerprint reference cannot be blank" }
    }
}

data class MediaAsset(
    val id: MediaAssetId,
    val recordingId: RecordingId,
    val sourceReferenceId: SourceReferenceId?,
    val kind: MediaAssetKind,
    val location: AssetLocation,
    val state: AssetState,
    val technical: AudioTechnicalMetadata,
    val checksum: ContentChecksum?,
    val fingerprint: FingerprintReference?,
    val lastVerifiedAt: Instant?,
)
