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
