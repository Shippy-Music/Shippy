/*
 * Copyright (c) 2026 Auxio Project
 * MetadataIdentityPolicyTest.kt is part of Auxio.
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
package app.shippy.core

import app.shippy.core.identity.MetadataObservationId
import app.shippy.core.identitymatch.MatchDecision
import app.shippy.core.identitymatch.MatchingFeatures
import app.shippy.core.identitymatch.MatchingPolicy
import app.shippy.core.identitymatch.MetadataNormalizer
import app.shippy.core.identitymatch.RecordingVersionParser
import app.shippy.core.music.CanonicalMetadataResolver
import app.shippy.core.music.Explicitness
import app.shippy.core.music.MetadataCandidate
import app.shippy.core.music.MetadataField
import app.shippy.core.music.MetadataSourceType
import app.shippy.core.music.RecordingVersion
import app.shippy.core.music.VersionKind
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataIdentityPolicyTest {
    @Test
    fun `user override wins over newer provider metadata`() {
        val provider =
            MetadataCandidate(
                value = "Provider title",
                sourceType = MetadataSourceType.MUSIC_PROVIDER,
                observationId = id(1),
                capturedAt = Instant.ofEpochSecond(20),
            )
        val override =
            MetadataCandidate(
                value = "My title",
                sourceType = MetadataSourceType.USER_OVERRIDE,
                observationId = id(2),
                capturedAt = Instant.ofEpochSecond(10),
            )

        val selected =
            CanonicalMetadataResolver.select(
                MetadataField.TITLE,
                listOf(provider, override),
                selectedAt = Instant.ofEpochSecond(30),
            )

        assertEquals("My title", selected?.value)
        assertTrue(selected?.provenance?.userOverride == true)
    }

    @Test
    fun `normalization removes featured credit but preserves version words`() {
        assertEquals(
            "see you again live",
            MetadataNormalizer.comparisonKey(" See You Again (feat. Charlie Puth) — Live "),
        )
    }

    @Test
    fun `materially different version vetoes metadata match`() {
        val original = features(RecordingVersion(VersionKind.ORIGINAL))
        val live = features(RecordingVersionParser.parse("Live at Wembley"))

        val assessment = MatchingPolicy().assess(original, live)

        assertEquals(MatchDecision.KEEP_SEPARATE, assessment.decision)
        assertTrue(assessment.vetoed)
    }

    @Test
    fun `strong identifier auto links without fuzzy catalogue scan`() {
        val first =
            features(RecordingVersion(VersionKind.ORIGINAL)).copy(isrcs = setOf("USRC17607839"))
        val second = features(RecordingVersion(VersionKind.ORIGINAL)).copy(isrcs = first.isrcs)

        val assessment = MatchingPolicy().assess(first, second)

        assertEquals(MatchDecision.AUTO_LINK, assessment.decision)
    }

    @Test
    fun `local file with wrong unverified isrc does not veto correct provider candidate`() {
        val local =
            features(RecordingVersion(VersionKind.ORIGINAL))
                .copy(isrcs = setOf("WRONG_LOCAL_ISRC"), verifiedIsrcs = emptySet())
        val provider =
            features(RecordingVersion(VersionKind.ORIGINAL))
                .copy(isrcs = setOf("CORRECT_ISRC"), verifiedIsrcs = setOf("CORRECT_ISRC"))

        val assessment = MatchingPolicy().assess(local, provider)

        org.junit.Assert.assertFalse(assessment.vetoed)
        assertTrue(assessment.score > 0.0)
    }

    @Test
    fun `verified provider isrc contradiction vetoes match`() {
        val providerA =
            features(RecordingVersion(VersionKind.ORIGINAL))
                .copy(isrcs = setOf("USRC17607839"), verifiedIsrcs = setOf("USRC17607839"))
        val providerB =
            features(RecordingVersion(VersionKind.ORIGINAL))
                .copy(isrcs = setOf("GBAYE0601477"), verifiedIsrcs = setOf("GBAYE0601477"))

        val assessment = MatchingPolicy().assess(providerA, providerB)

        assertTrue(assessment.vetoed)
        assertEquals(MatchDecision.KEEP_SEPARATE, assessment.decision)
    }

    @Test
    fun `verified musicbrainz recording id contradiction vetoes match`() {
        val mbidA =
            features(RecordingVersion(VersionKind.ORIGINAL))
                .copy(
                    musicBrainzRecordingIds = setOf("00000000-0000-0000-0000-000000000001"),
                    verifiedMusicBrainzRecordingIds = setOf("00000000-0000-0000-0000-000000000001"),
                )
        val mbidB =
            features(RecordingVersion(VersionKind.ORIGINAL))
                .copy(
                    musicBrainzRecordingIds = setOf("00000000-0000-0000-0000-000000000002"),
                    verifiedMusicBrainzRecordingIds = setOf("00000000-0000-0000-0000-000000000002"),
                )

        val assessment = MatchingPolicy().assess(mbidA, mbidB)

        assertTrue(assessment.vetoed)
        assertEquals(MatchDecision.KEEP_SEPARATE, assessment.decision)
    }

    @Test
    fun `unverified matching identifiers contribute positive evidence`() {
        val unverifiedA =
            features(RecordingVersion(VersionKind.ORIGINAL))
                .copy(isrcs = setOf("USRC17607839"), verifiedIsrcs = emptySet())
        val unverifiedB =
            features(RecordingVersion(VersionKind.ORIGINAL))
                .copy(isrcs = setOf("USRC17607839"), verifiedIsrcs = emptySet())

        val assessment = MatchingPolicy().assess(unverifiedA, unverifiedB)

        assertEquals(MatchDecision.AUTO_LINK, assessment.decision)
        assertTrue(
            assessment.evidence.any {
                it.kind == app.shippy.core.identitymatch.MatchEvidenceKind.ISRC
            }
        )
    }

    @Test
    fun `identifier matching is case insensitive and whitespace tolerant`() {
        val lower =
            features(RecordingVersion(VersionKind.ORIGINAL))
                .copy(isrcs = setOf("  usrc17607839  "), verifiedIsrcs = setOf("  usrc17607839  "))
        val upper =
            features(RecordingVersion(VersionKind.ORIGINAL))
                .copy(isrcs = setOf("USRC17607839"), verifiedIsrcs = setOf("USRC17607839"))

        val assessment = MatchingPolicy().assess(lower, upper)

        assertEquals(MatchDecision.AUTO_LINK, assessment.decision)
    }

    @Test
    fun `recording merge and unmerge plan produces deterministic survivor and reversible graph`() {
        val rec1 = app.shippy.core.identity.RecordingId("00000000-0000-0000-0000-000000000001")
        val rec2 = app.shippy.core.identity.RecordingId("00000000-0000-0000-0000-000000000002")
        val decisionId =
            app.shippy.core.identity.IdentityDecisionId("00000000-0000-0000-0000-000000000009")
        val move =
            app.shippy.core.identitymatch.RecordingReferenceMove(
                kind = app.shippy.core.identitymatch.RecordingReferenceKind.SOURCE_REFERENCE,
                stableId = "source-1",
            )

        val plan =
            app.shippy.core.identitymatch.RecordingMergePlanner.plan(
                firstId = rec1,
                secondId = rec2,
                decisionId = decisionId,
                movedReferences = setOf(move),
                createdAt = Instant.ofEpochSecond(100),
            )

        assertEquals(rec1, plan.survivorId)
        assertEquals(rec2, plan.retiredId)

        var graph = app.shippy.core.identitymatch.RecordingRedirectGraph()
        val mutation = graph.add(plan.retiredId, plan.survivorId)
        assertTrue(mutation is app.shippy.core.identitymatch.RedirectMutation.Added)
        graph = mutation.graph

        assertEquals(rec1, graph.resolve(rec2))
        assertEquals(rec1, graph.resolve(rec1))

        val unmerge = plan.reverse(graph)
        assertEquals(rec2, unmerge.restoredRecordingId)
        assertEquals(rec2, unmerge.redirectGraph.resolve(rec2))
    }

    private fun features(version: RecordingVersion) =
        MatchingFeatures(
            normalizedTitle = "song",
            normalizedPrimaryArtist = "artist",
            normalizedArtistSet = setOf("artist"),
            normalizedRelease = "album",
            durationMs = 180_000,
            version = version,
            explicitness = Explicitness.UNKNOWN,
        )

    private fun id(value: Int) =
        MetadataObservationId("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")
}
