/*
 * Copyright (c) 2026 Shippy contributors
 * MusicProvider.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
        require(contentLength == null || contentLength >= 0) {
            "Content length cannot be negative"
        }
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

    suspend fun search(query: String, continuation: String? = null): ProviderResult<SearchPage>

    suspend fun resolve(
        candidate: TrackCandidate,
        constraints: StreamConstraints,
    ): ProviderResult<ResolvedStream>
}
