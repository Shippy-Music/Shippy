/*
 * Copyright (c) 2026 Auxio Project
 * IdentityModels.kt is part of Auxio.
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
package app.shippy.core.identitymatch

import app.shippy.core.identity.RecordingId
import app.shippy.core.music.Explicitness
import app.shippy.core.music.RecordingVersion
import app.shippy.core.source.SourceKey

enum class MatchEvidenceKind {
    EXACT_SOURCE_KEY,
    VERIFIED_CHECKSUM,
    AUDIO_FINGERPRINT,
    MUSICBRAINZ_RECORDING,
    ISRC,
    SOURCE_FAMILY,
    NORMALIZED_TITLE,
    PRIMARY_ARTIST,
    ARTIST_OVERLAP,
    RELEASE,
    DURATION,
    EXPLICITNESS,
    VERSION,
    USER_REJECTION,
    VERSION_CONTRADICTION,
    DURATION_CONTRADICTION,
}

data class MatchEvidence(val kind: MatchEvidenceKind, val score: Double, val detail: String) {
    init {
        require(score in -1.0..1.0) { "Evidence score must be normalized" }
        require(detail.isNotBlank()) { "Evidence detail cannot be blank" }
    }
}

enum class MatchDecision {
    AUTO_LINK,
    REVIEW_PROBABLE,
    REVIEW_POSSIBLE,
    KEEP_SEPARATE,
}

data class MatchAssessment(
    val decision: MatchDecision,
    val score: Double,
    val evidence: List<MatchEvidence>,
    val vetoed: Boolean,
)

data class MatchingFeatures(
    val normalizedTitle: String?,
    val normalizedPrimaryArtist: String?,
    val normalizedArtistSet: Set<String>,
    val normalizedRelease: String?,
    val durationMs: Long?,
    val version: RecordingVersion,
    val explicitness: Explicitness,
    val isrcs: Set<String> = emptySet(),
    val musicBrainzRecordingIds: Set<String> = emptySet(),
    val acoustIds: Set<String> = emptySet(),
    val sourceKeys: Set<SourceKey> = emptySet(),
    val fingerprintHashes: Set<String> = emptySet(),
)

data class RecordingDraft(
    val title: String,
    val primaryArtist: String,
    val durationMs: Long?,
    val version: RecordingVersion,
)

data class MatchCandidate(val recordingId: RecordingId, val assessment: MatchAssessment)

data class IdentityRejection(val code: String) {
    init {
        require(code.isNotBlank()) { "Identity rejection code cannot be blank" }
    }
}

sealed interface IdentityOutcome {
    data class Existing(val recordingId: RecordingId, val evidence: MatchAssessment) :
        IdentityOutcome

    data class New(val draft: RecordingDraft) : IdentityOutcome

    data class Ambiguous(val candidates: List<MatchCandidate>) : IdentityOutcome

    data class Rejected(val reason: IdentityRejection) : IdentityOutcome
}
