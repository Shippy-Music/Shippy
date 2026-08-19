/*
 * Copyright (c) 2026 Auxio Project
 * MatchingPolicy.kt is part of Auxio.
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

import app.shippy.core.music.Explicitness
import app.shippy.core.music.RecordingVersion
import app.shippy.core.music.VersionKind
import app.shippy.core.music.VersionTrait
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

object MetadataNormalizer {
    private val featuredCredit =
        Regex("[\\[(]\\s*(feat\\.?|ft\\.?|featuring)\\b[^\\])]*[\\])]", RegexOption.IGNORE_CASE)
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    private val whitespace = Regex("\\s+")

    fun comparisonKey(value: String?): String? {
        val normalized =
            value
                ?.takeIf(String::isNotBlank)
                ?.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
                ?.lowercase(Locale.ROOT)
                ?.replace(featuredCredit, " ")
                ?.replace("&", " and ")
                ?.replace(punctuation, " ")
                ?.replace(whitespace, " ")
                ?.trim()
        return normalized?.takeIf(String::isNotEmpty)
    }
}

object RecordingVersionParser {
    fun parse(label: String?): RecordingVersion {
        val normalized = MetadataNormalizer.comparisonKey(label)
        if (normalized == null) return RecordingVersion(VersionKind.ORIGINAL)
        val traits = buildSet {
            if (normalized.hasPhrase("live")) add(VersionTrait.LIVE)
            if (normalized.hasPhrase("remix") || normalized.hasPhrase("mix"))
                add(VersionTrait.REMIX)
            if (normalized.hasPhrase("acoustic")) add(VersionTrait.ACOUSTIC)
            if (normalized.hasPhrase("instrumental")) add(VersionTrait.INSTRUMENTAL)
            if (normalized.hasPhrase("radio edit")) add(VersionTrait.RADIO_EDIT)
            if (normalized.contains("remaster")) add(VersionTrait.REMASTERED)
            if (normalized.hasPhrase("cover")) add(VersionTrait.COVER)
            if (normalized.hasPhrase("karaoke")) add(VersionTrait.KARAOKE)
            if (normalized.hasPhrase("sped up")) add(VersionTrait.SPED_UP)
            if (normalized.hasPhrase("slowed")) add(VersionTrait.SLOWED)
            if (normalized.hasPhrase("reverb")) add(VersionTrait.REVERB)
        }
        val kind =
            when {
                VersionTrait.LIVE in traits -> VersionKind.LIVE
                VersionTrait.REMIX in traits -> VersionKind.REMIX
                VersionTrait.ACOUSTIC in traits -> VersionKind.ACOUSTIC
                VersionTrait.INSTRUMENTAL in traits -> VersionKind.INSTRUMENTAL
                VersionTrait.RADIO_EDIT in traits -> VersionKind.RADIO_EDIT
                VersionTrait.REMASTERED in traits -> VersionKind.REMASTER
                VersionTrait.COVER in traits -> VersionKind.COVER
                VersionTrait.KARAOKE in traits -> VersionKind.KARAOKE
                VersionTrait.SPED_UP in traits -> VersionKind.SPED_UP
                VersionTrait.SLOWED in traits -> VersionKind.SLOWED
                else -> VersionKind.OTHER
            }
        return RecordingVersion(kind = kind, label = requireNotNull(label).trim(), traits = traits)
    }

    private fun String.hasPhrase(phrase: String) =
        this == phrase || startsWith("$phrase ") || endsWith(" $phrase") || contains(" $phrase ")
}

class MatchingPolicy {
    fun assess(
        incoming: MatchingFeatures,
        candidate: MatchingFeatures,
        explicitlyRejected: Boolean = false,
    ): MatchAssessment {
        if (explicitlyRejected) {
            return veto(MatchEvidenceKind.USER_REJECTION, "User rejected this identity")
        }

        val exactSource = incoming.sourceKeys.intersect(candidate.sourceKeys).isNotEmpty()
        if (exactSource) {
            return terminal(MatchEvidenceKind.EXACT_SOURCE_KEY, "Exact source key")
        }
        val exactChecksum = incoming.fingerprintHashes.intersect(candidate.fingerprintHashes)
        if (exactChecksum.isNotEmpty()) {
            return terminal(MatchEvidenceKind.AUDIO_FINGERPRINT, "Exact fingerprint")
        }

        versionVeto(incoming.version, candidate.version)?.let {
            return it
        }
        durationVeto(incoming.durationMs, candidate.durationMs)?.let {
            return it
        }

        val evidence = mutableListOf<MatchEvidence>()
        if (
            incoming.musicBrainzRecordingIds
                .intersect(candidate.musicBrainzRecordingIds)
                .isNotEmpty()
        ) {
            evidence += evidence(MatchEvidenceKind.MUSICBRAINZ_RECORDING, 0.97)
        }
        if (incoming.isrcs.intersect(candidate.isrcs).isNotEmpty()) {
            evidence += evidence(MatchEvidenceKind.ISRC, 0.92)
        }
        if (
            incoming.normalizedTitle != null &&
                incoming.normalizedTitle == candidate.normalizedTitle
        ) {
            evidence += evidence(MatchEvidenceKind.NORMALIZED_TITLE, 0.18)
        }
        if (
            incoming.normalizedPrimaryArtist != null &&
                incoming.normalizedPrimaryArtist == candidate.normalizedPrimaryArtist
        ) {
            evidence += evidence(MatchEvidenceKind.PRIMARY_ARTIST, 0.18)
        }
        if (
            incoming.normalizedArtistSet.isNotEmpty() &&
                incoming.normalizedArtistSet.intersect(candidate.normalizedArtistSet).isNotEmpty()
        ) {
            evidence += evidence(MatchEvidenceKind.ARTIST_OVERLAP, 0.12)
        }
        if (
            incoming.normalizedRelease != null &&
                incoming.normalizedRelease == candidate.normalizedRelease
        ) {
            evidence += evidence(MatchEvidenceKind.RELEASE, 0.08)
        }
        durationScore(incoming.durationMs, candidate.durationMs)?.let { score ->
            evidence += evidence(MatchEvidenceKind.DURATION, score)
        }
        if (
            incoming.explicitness != Explicitness.UNKNOWN &&
                incoming.explicitness == candidate.explicitness
        ) {
            evidence += evidence(MatchEvidenceKind.EXPLICITNESS, 0.02)
        }
        if (incoming.version == candidate.version) {
            evidence += evidence(MatchEvidenceKind.VERSION, 0.08)
        }

        val score = evidence.sumOf(MatchEvidence::score).coerceIn(0.0, 1.0)
        val hasStrongIdentifier =
            evidence.any {
                it.kind == MatchEvidenceKind.MUSICBRAINZ_RECORDING ||
                    it.kind == MatchEvidenceKind.ISRC
            }
        val completeMetadata =
            evidence.any { it.kind == MatchEvidenceKind.NORMALIZED_TITLE } &&
                evidence.any { it.kind == MatchEvidenceKind.PRIMARY_ARTIST } &&
                evidence.any { it.kind == MatchEvidenceKind.DURATION }
        val decision =
            when {
                score >= 0.90 && (hasStrongIdentifier || completeMetadata) ->
                    MatchDecision.AUTO_LINK
                score >= 0.75 -> MatchDecision.REVIEW_PROBABLE
                score >= 0.55 -> MatchDecision.REVIEW_POSSIBLE
                else -> MatchDecision.KEEP_SEPARATE
            }
        return MatchAssessment(decision, score, evidence, vetoed = false)
    }

    private fun versionVeto(first: RecordingVersion, second: RecordingVersion): MatchAssessment? {
        val materiallyDifferent =
            setOf(
                VersionKind.LIVE,
                VersionKind.REMIX,
                VersionKind.ACOUSTIC,
                VersionKind.INSTRUMENTAL,
                VersionKind.COVER,
                VersionKind.KARAOKE,
                VersionKind.SPED_UP,
                VersionKind.SLOWED,
            )
        return if (
            first.kind != second.kind &&
                (first.kind in materiallyDifferent || second.kind in materiallyDifferent)
        ) {
            veto(MatchEvidenceKind.VERSION_CONTRADICTION, "Material recording-version mismatch")
        } else {
            null
        }
    }

    private fun durationVeto(first: Long?, second: Long?): MatchAssessment? {
        if (first == null || second == null || maxOf(first, second) == 0L) return null
        val differenceRatio = abs(first - second).toDouble() / maxOf(first, second)
        return if (differenceRatio > 0.08) {
            veto(
                MatchEvidenceKind.DURATION_CONTRADICTION,
                "Duration differs by more than 8 percent",
            )
        } else {
            null
        }
    }

    private fun durationScore(first: Long?, second: Long?): Double? {
        if (first == null || second == null) return null
        val difference = abs(first - second)
        return when {
            difference <= 1_000 -> 0.16
            difference <= 3_000 -> 0.12
            difference <= maxOf(first, second) * 0.08 -> 0.06
            else -> null
        }
    }

    private fun evidence(kind: MatchEvidenceKind, score: Double) =
        MatchEvidence(kind, score, kind.name.lowercase(Locale.ROOT))

    private fun terminal(kind: MatchEvidenceKind, detail: String) =
        MatchAssessment(
            decision = MatchDecision.AUTO_LINK,
            score = 1.0,
            evidence = listOf(MatchEvidence(kind, 1.0, detail)),
            vetoed = false,
        )

    private fun veto(kind: MatchEvidenceKind, detail: String) =
        MatchAssessment(
            decision = MatchDecision.KEEP_SEPARATE,
            score = 0.0,
            evidence = listOf(MatchEvidence(kind, -1.0, detail)),
            vetoed = true,
        )
}
