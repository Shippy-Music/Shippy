/*
 * Copyright (c) 2026 Auxio Project
 * Ids.kt is part of Auxio.
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
package app.shippy.core.identity

import java.util.UUID

private fun requireCanonicalUuid(value: String, label: String) {
    val parsed = runCatching { UUID.fromString(value) }.getOrNull()
    require(parsed != null && parsed.toString() == value) { "$label must be a canonical UUID" }
}

@JvmInline
value class RecordingId(val value: String) {
    init {
        requireCanonicalUuid(value, "RecordingId")
    }
}

@JvmInline
value class ArtistId(val value: String) {
    init {
        requireCanonicalUuid(value, "ArtistId")
    }
}

@JvmInline
value class ReleaseId(val value: String) {
    init {
        requireCanonicalUuid(value, "ReleaseId")
    }
}

@JvmInline
value class SourceReferenceId(val value: String) {
    init {
        requireCanonicalUuid(value, "SourceReferenceId")
    }
}

@JvmInline
value class MediaAssetId(val value: String) {
    init {
        requireCanonicalUuid(value, "MediaAssetId")
    }
}

@JvmInline
value class PlaylistId(val value: String) {
    init {
        requireCanonicalUuid(value, "PlaylistId")
    }
}

@JvmInline
value class PlaylistEntryId(val value: String) {
    init {
        requireCanonicalUuid(value, "PlaylistEntryId")
    }
}

@JvmInline
value class QueueEntryId(val value: String) {
    init {
        requireCanonicalUuid(value, "QueueEntryId")
    }
}

@JvmInline
value class ListeningSessionId(val value: String) {
    init {
        requireCanonicalUuid(value, "ListeningSessionId")
    }
}

@JvmInline
value class PortableRecordingId(val value: String) {
    init {
        requireCanonicalUuid(value, "PortableRecordingId")
    }
}

@JvmInline
value class IdentityDecisionId(val value: String) {
    init {
        requireCanonicalUuid(value, "IdentityDecisionId")
    }
}

@JvmInline
value class MetadataObservationId(val value: String) {
    init {
        requireCanonicalUuid(value, "MetadataObservationId")
    }
}

@JvmInline
value class ContributorId(val value: String) {
    init {
        require(value.isNotBlank()) { "ContributorId cannot be blank" }
    }
}

@JvmInline
value class ProviderId(val value: String) {
    init {
        require(value.isNotBlank() && value == value.trim()) {
            "ProviderId must be non-blank and trimmed"
        }
    }
}
