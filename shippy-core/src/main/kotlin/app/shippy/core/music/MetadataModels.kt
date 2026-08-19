/*
 * Copyright (c) 2026 Auxio Project
 * MetadataModels.kt is part of Auxio.
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
package app.shippy.core.music

import app.shippy.core.identity.MetadataObservationId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import java.time.Instant

enum class MetadataSourceType {
    USER_OVERRIDE,
    USER_CONFIRMED,
    STRONG_IDENTIFIER,
    MUSIC_PROVIDER,
    EMBEDDED_FILE,
    GENERIC_PROVIDER,
    FILENAME,
}

enum class MetadataSubjectKind {
    RECORDING,
    RELEASE,
    ARTIST,
    SOURCE,
    ASSET,
}

data class MetadataSubject(val kind: MetadataSubjectKind, val id: String) {
    init {
        require(id.isNotBlank()) { "Metadata subject ID cannot be blank" }
    }
}

enum class ExternalIdentifierKind {
    ISRC,
    MUSICBRAINZ_RECORDING,
    MUSICBRAINZ_RELEASE,
    MUSICBRAINZ_ARTIST,
    ACOUST_ID,
}

data class ExternalIdentifier(val kind: ExternalIdentifierKind, val value: String) {
    init {
        require(value.isNotBlank()) { "External identifier cannot be blank" }
    }
}

data class MetadataObservation(
    val id: MetadataObservationId,
    val subject: MetadataSubject,
    val sourceType: MetadataSourceType,
    val sourceReferenceId: SourceReferenceId?,
    val title: String?,
    val artistNames: List<String>,
    val releaseTitle: String?,
    val durationMs: Long?,
    val artwork: List<ArtworkReference>,
    val year: Int?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val genres: Set<String>,
    val externalIdentifiers: Set<ExternalIdentifier>,
    val versionHints: Set<String>,
    val capturedAt: Instant,
) {
    init {
        require(title == null || title.isNotBlank()) { "Observed title cannot be blank" }
        require(artistNames.all(String::isNotBlank)) { "Observed artist names cannot be blank" }
        require(durationMs == null || durationMs >= 0) { "Observed duration cannot be negative" }
        require(year == null || year > 0) { "Observed year must be positive" }
        require(trackNumber == null || trackNumber > 0) { "Track number must be positive" }
        require(discNumber == null || discNumber > 0) { "Disc number must be positive" }
    }
}

enum class MetadataField {
    TITLE,
    ARTIST_CREDIT,
    DURATION,
    RELEASE,
    ARTWORK,
    EXPLICITNESS,
    VERSION,
}

data class MetadataProvenance(
    val field: MetadataField,
    val observationId: MetadataObservationId?,
    val sourceType: MetadataSourceType,
    val selectedAt: Instant,
    val userOverride: Boolean,
)

data class MetadataCandidate<T>(
    val value: T,
    val sourceType: MetadataSourceType,
    val observationId: MetadataObservationId?,
    val capturedAt: Instant,
)

data class CanonicalMetadataValue<T>(val value: T, val provenance: MetadataProvenance)

/** Field-level precedence; freshness breaks ties but cannot beat a higher-trust source. */
object CanonicalMetadataResolver {
    fun <T> select(
        field: MetadataField,
        candidates: Collection<MetadataCandidate<T>>,
        selectedAt: Instant,
    ): CanonicalMetadataValue<T>? {
        val selected =
            candidates.maxWithOrNull(
                compareBy<MetadataCandidate<T>> { it.sourceType.precedence }
                    .thenBy { it.capturedAt }
                    .thenBy { it.observationId?.value ?: "" }
            ) ?: return null
        return CanonicalMetadataValue(
            value = selected.value,
            provenance =
                MetadataProvenance(
                    field = field,
                    observationId = selected.observationId,
                    sourceType = selected.sourceType,
                    selectedAt = selectedAt,
                    userOverride = selected.sourceType == MetadataSourceType.USER_OVERRIDE,
                ),
        )
    }

    private val MetadataSourceType.precedence: Int
        get() =
            when (this) {
                MetadataSourceType.FILENAME -> 0
                MetadataSourceType.GENERIC_PROVIDER -> 1
                MetadataSourceType.EMBEDDED_FILE -> 2
                MetadataSourceType.MUSIC_PROVIDER -> 3
                MetadataSourceType.STRONG_IDENTIFIER -> 4
                MetadataSourceType.USER_CONFIRMED -> 5
                MetadataSourceType.USER_OVERRIDE -> 6
            }
}

data class UserMetadataOverride<T>(
    val recordingId: RecordingId,
    val field: MetadataField,
    val value: T,
    val updatedAt: Instant,
)
