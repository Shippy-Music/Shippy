/*
 * Copyright (c) 2026 Auxio Project
 * SourceModels.kt is part of Auxio.
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
package app.shippy.core.source

import app.shippy.core.identity.ProviderId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import java.time.Instant

enum class SourceKind {
    LOCAL_FILE,
    SHIPPY_DOWNLOAD,
    JIOSAAVN,
    YOUTUBE_MUSIC,
    YOUTUBE,
    LASTFM_HINT,
    MUSICBRAINZ,
    IMPORTED_LINK,
    CREW_PEER,
}

enum class SourceItemType {
    RECORDING,
    RELEASE_TRACK,
    VIDEO,
    LOCAL_FILE,
    IMPORTED_LINK,
}

data class SourceKey(
    val providerId: ProviderId,
    val itemType: SourceItemType,
    val sourceItemId: String,
) {
    init {
        require(sourceItemId.isNotBlank()) { "Source item ID cannot be blank" }
    }
}

enum class AvailabilityState {
    AVAILABLE,
    RESOLVABLE,
    DEGRADED,
    UNAVAILABLE,
    UNKNOWN,
}

data class AvailabilityFailure(val code: String, val retryable: Boolean) {
    init {
        require(code.isNotBlank()) { "Availability failure code cannot be blank" }
    }
}

data class AvailabilitySnapshot(
    val state: AvailabilityState,
    val checkedAt: Instant?,
    val expiresAt: Instant?,
    val failure: AvailabilityFailure?,
) {
    init {
        require(checkedAt == null || expiresAt == null || expiresAt >= checkedAt) {
            "Availability expiry cannot precede its check"
        }
        require(state != AvailabilityState.AVAILABLE || failure == null) {
            "Available source cannot carry a failure"
        }
    }
}

enum class IdentityStatus {
    UNRESOLVED,
    AUTOMATICALLY_LINKED,
    USER_CONFIRMED,
    REJECTED,
}

data class SourceReference(
    val id: SourceReferenceId,
    val recordingId: RecordingId?,
    val source: SourceKey,
    val kind: SourceKind,
    val originalUrl: String?,
    val availability: AvailabilitySnapshot,
    val rawMetadataId: String,
    val identityStatus: IdentityStatus,
) {
    init {
        require(originalUrl == null || originalUrl.isNotBlank()) { "Original URL cannot be blank" }
        require(rawMetadataId.isNotBlank()) { "Raw metadata ID cannot be blank" }
        require(recordingId != null || identityStatus == IdentityStatus.UNRESOLVED) {
            "Only unresolved sources may omit recording identity"
        }
    }
}
