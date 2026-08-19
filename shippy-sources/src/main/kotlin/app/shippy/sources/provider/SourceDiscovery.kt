/*
 * Copyright (c) 2026 Auxio Project
 * SourceDiscovery.kt is part of Auxio.
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
package app.shippy.sources.provider

import app.shippy.core.identity.ProviderId
import app.shippy.core.music.ArtworkReference
import app.shippy.core.source.SourceKind
import app.shippy.sources.observation.SourceTrackObservation
import java.net.URI

data class SourceProviderDescriptor(val id: ProviderId, val displayName: String) {
    init {
        require(displayName.isNotBlank()) { "Source provider display name cannot be blank" }
    }
}

enum class SourceDiscoveryFailureKind {
    NETWORK,
    RATE_LIMITED,
    AUTHENTICATION,
    REGION,
    UNAVAILABLE,
    MALFORMED_RESPONSE,
    UNSUPPORTED,
}

data class SourceDiscoveryFailure(
    val kind: SourceDiscoveryFailureKind,
    val retryable: Boolean,
    val message: String? = null,
)

enum class SourceEntityType {
    ALBUM,
    ARTIST,
    PLAYLIST,
}

data class SourceEntityKey(
    val providerId: ProviderId,
    val type: SourceEntityType,
    val sourceItemId: String,
) {
    init {
        require(sourceItemId.isNotBlank() && sourceItemId.length <= 512) {
            "Source entity item ID must be a bounded nonblank token"
        }
        require(!sourceItemId.contains("://")) { "Source entity item ID cannot be a URL" }
    }
}

data class SourceEntityObservation(
    val key: SourceEntityKey,
    val sourceKind: SourceKind,
    val title: String,
    val subtitle: String?,
    val artwork: ArtworkReference?,
    val originalUrl: String?,
) {
    init {
        require(title.isNotBlank() && title.length <= 512) {
            "Source entity title must be bounded and nonblank"
        }
        require(subtitle == null || (subtitle.isNotBlank() && subtitle.length <= 512)) {
            "Source entity subtitle must be bounded and nonblank when present"
        }
        require(artwork == null || artwork.value.isPublicHttps()) {
            "Provider entity artwork must be public HTTPS"
        }
        require(originalUrl == null || originalUrl.isPublicHttps()) {
            "Provider entity original URL must be public HTTPS"
        }
    }
}

data class SourceDiscoverySection(
    val provider: SourceProviderDescriptor,
    val tracks: List<SourceTrackObservation>,
    val entities: List<SourceEntityObservation> = emptyList(),
    val continuation: String? = null,
    val failure: SourceDiscoveryFailure? = null,
    val discardedTrackCount: Int = 0,
) {
    init {
        require(failure == null || (tracks.isEmpty() && entities.isEmpty())) {
            "A failed source section cannot also contain fresh results"
        }
        require(continuation == null || continuation.isNotBlank()) {
            "Source continuation cannot be blank"
        }
        require(discardedTrackCount >= 0) { "Discarded track count cannot be negative" }
    }
}

data class SourceDiscoverySnapshot(val query: String, val sections: List<SourceDiscoverySection>) {
    companion object {
        val EMPTY = SourceDiscoverySnapshot("", emptyList())
    }
}

/** Metadata discovery only. Recording identity is assigned later by the ingestion boundary. */
interface SourceDiscoveryRepository {
    fun providers(): List<SourceProviderDescriptor>

    suspend fun search(query: String, providerId: ProviderId? = null): SourceDiscoverySnapshot
}

private fun String.isPublicHttps(): Boolean =
    length <= 2_048 &&
        runCatching { URI(this) }
            .getOrNull()
            ?.let { uri ->
                uri.scheme.equals("https", ignoreCase = true) &&
                    !uri.host.isNullOrBlank() &&
                    uri.userInfo == null
            } == true
