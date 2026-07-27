/*
 * Copyright (c) 2026 Auxio Project
 * MusicProvider.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.provider

import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate

enum class ProviderCapability {
    SEARCH,
    TRACK,
    ALBUM,
    ARTIST,
    PLAYLIST,
    RELATED,
    STREAM,
    DOWNLOAD,
}

enum class ProviderHealth {
    AVAILABLE,
    DEGRADED,
    UNAVAILABLE,
    DISABLED,
}

enum class ProviderEntityType {
    ALBUM,
    ARTIST,
    PLAYLIST,
}

/**
 * Metadata-only provider browse target. [sourceItemId] is an opaque provider token, never media
 * data.
 */
data class ProviderEntity(
    val providerId: ProviderId,
    val sourceItemId: String,
    val type: ProviderEntityType,
    val title: String,
    val subtitle: String? = null,
    val artwork: String? = null,
    val originalUrl: String? = null,
) {
    init {
        require(sourceItemId.isNotBlank() && sourceItemId.length <= 512) {
            "Provider entity source item ID must be a bounded nonblank token"
        }
        require(!sourceItemId.contains("://")) { "Provider entity source item ID cannot be a URL" }
        require(title.isNotBlank() && title.length <= 512) {
            "Provider entity title must be bounded and nonblank"
        }
        require(subtitle == null || (subtitle.isNotBlank() && subtitle.length <= 512)) {
            "Provider entity subtitle must be bounded and nonblank when present"
        }
        require(artwork == null || (artwork.isNotBlank() && artwork.length <= 2_048)) {
            "Provider entity artwork must be bounded and nonblank when present"
        }
        require(
            originalUrl == null ||
                (originalUrl.startsWith("https://") && originalUrl.length <= 2_048)
        ) {
            "Provider entity original URL must be a bounded HTTPS URL when present"
        }
    }
}

data class ProviderDescriptor(
    val id: ProviderId,
    val displayName: String,
    val capabilities: Set<ProviderCapability>,
) {
    init {
        require(displayName.isNotBlank()) { "Provider display name cannot be blank" }
    }
}

data class SearchPage(
    val tracks: List<Track>,
    val continuation: String? = null,
    val entities: List<ProviderEntity> = emptyList(),
)

data class ProviderBrowsePage(
    val entity: ProviderEntity,
    val tracks: List<Track>,
    val continuation: String? = null,
)

data class StreamConstraints(
    val preferredBitrateBps: Int? = null,
    val allowMetered: Boolean = true,
    val forDownload: Boolean = false,
) {
    init {
        require(preferredBitrateBps == null || preferredBitrateBps > 0) {
            "Preferred bitrate must be positive"
        }
    }
}

data class ResolvedStream(
    val candidateId: CandidateId,
    val uri: String,
    val mimeType: String? = null,
    val bitrateBps: Int? = null,
    val contentLength: Long? = null,
    val expiresAtEpochMs: Long? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    init {
        require(uri.isNotBlank()) { "Resolved stream URI cannot be blank" }
        require(bitrateBps == null || bitrateBps > 0) { "Bitrate must be positive" }
        require(contentLength == null || contentLength >= 0) { "Content length cannot be negative" }
    }
}

sealed interface ProviderResult<out T> {
    data class Success<T>(val value: T) : ProviderResult<T>

    data class Failure(
        val kind: ProviderFailureKind,
        val retryable: Boolean,
        val message: String? = null,
    ) : ProviderResult<Nothing>
}

enum class ProviderFailureKind {
    NETWORK,
    RATE_LIMITED,
    AUTHENTICATION,
    REGION,
    UNAVAILABLE,
    MALFORMED_RESPONSE,
    UNSUPPORTED,
}

interface MusicProvider {
    val descriptor: ProviderDescriptor

    fun health(): ProviderHealth

    /** Performs one bounded, metadata-only availability check. */
    suspend fun probeHealth(): ProviderHealth = health()

    suspend fun search(query: String, continuation: String? = null): ProviderResult<SearchPage>

    suspend fun browse(
        entity: ProviderEntity,
        continuation: String? = null,
    ): ProviderResult<ProviderBrowsePage> =
        ProviderResult.Failure(
            kind = ProviderFailureKind.UNSUPPORTED,
            retryable = false,
            message = "Provider browsing is unsupported",
        )

    suspend fun resolve(
        candidate: TrackCandidate,
        constraints: StreamConstraints,
    ): ProviderResult<ResolvedStream>
}
