/*
 * Copyright (c) 2026 Auxio Project
 * CorePolicyTest.kt is part of Auxio.
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

import app.shippy.core.crew.PortableArtistCredit
import app.shippy.core.crew.PortableArtworkHint
import app.shippy.core.crew.PortableRecordingDescriptor
import app.shippy.core.crew.PortableRecordingVersion
import app.shippy.core.crew.PortableSourceHint
import app.shippy.core.identity.IdentityDecisionId
import app.shippy.core.identity.ListeningSessionId
import app.shippy.core.identity.PortableRecordingId
import app.shippy.core.identity.ProviderId
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identitymatch.RecordingMergePlanner
import app.shippy.core.identitymatch.RecordingRedirectGraph
import app.shippy.core.identitymatch.RecordingReferenceKind
import app.shippy.core.identitymatch.RecordingReferenceMove
import app.shippy.core.identitymatch.RedirectMutation
import app.shippy.core.listening.ActiveListeningSession
import app.shippy.core.listening.AudibleTimeAccumulator
import app.shippy.core.listening.ListeningThresholdPolicy
import app.shippy.core.music.VersionKind
import app.shippy.core.source.SourceItemType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CorePolicyTest {
    @Test
    fun `redirect chains resolve without permitting cycles`() {
        val first = recordingId(1)
        val second = recordingId(2)
        val third = recordingId(3)
        val firstMerge = RecordingRedirectGraph().add(first, second) as RedirectMutation.Added
        val secondMerge = firstMerge.graph.add(second, third) as RedirectMutation.Added

        val cycle = secondMerge.graph.add(third, first)

        assertEquals(third, secondMerge.graph.resolve(first))
        assertTrue(cycle is RedirectMutation.RejectedCycle)
    }

    @Test
    fun `deterministic merge audit retains exact inverse reference moves`() {
        val moved =
            setOf(
                RecordingReferenceMove(RecordingReferenceKind.MEDIA_ASSET, "asset-7"),
                RecordingReferenceMove(RecordingReferenceKind.PLAYLIST_ENTRY, "entry-3"),
            )
        val plan =
            RecordingMergePlanner.plan(
                firstId = recordingId(8),
                secondId = recordingId(2),
                decisionId = decisionId(1),
                movedReferences = moved,
                createdAt = Instant.EPOCH,
            )
        val applied =
            RecordingRedirectGraph().add(plan.retiredId, plan.survivorId) as RedirectMutation.Added

        val inverse = plan.reverse(applied.graph)

        assertEquals(recordingId(2), plan.survivorId)
        assertEquals(moved, inverse.referencesToRestore)
        assertEquals(plan.retiredId, inverse.redirectGraph.resolve(plan.retiredId))
    }

    @Test
    fun `audible accumulator excludes pauses and scales monotonic time by speed`() {
        val original =
            ActiveListeningSession(
                id = listeningSessionId(1),
                queueEntryId = queueEntryId(1),
                recordingId = recordingId(1),
                sourceReferenceId = null,
                startedAtWallClock = Instant.EPOCH,
                audibleTime = AudibleTimeAccumulator(),
                chosenByUser = true,
            )

        val afterNormalSpeed = original.startAudible(1_000, 1.0).stopAudible(11_000)
        val afterPauseAndFastPlayback =
            afterNormalSpeed.startAudible(31_000, 2.0).tick(36_000).stopAudible(36_000)
        val restored = afterPauseAndFastPlayback.checkpointForRestore()

        assertEquals(20_000, afterPauseAndFastPlayback.audibleTime.accumulatedAudibleMs)
        assertNull(restored.audibleTime.anchorElapsedRealtimeMs)
    }

    @Test
    fun `scrobble threshold uses audible half duration capped at four minutes`() {
        assertNull(ListeningThresholdPolicy.scrobbleThresholdMs(30_000))
        assertEquals(15_500L, ListeningThresholdPolicy.scrobbleThresholdMs(31_000))
        assertEquals(240_000L, ListeningThresholdPolicy.scrobbleThresholdMs(900_000))
        assertFalse(ListeningThresholdPolicy.isScrobbleEligible(900_000, 239_999))
        assertTrue(ListeningThresholdPolicy.isScrobbleEligible(900_000, 240_000))
    }

    @Test
    fun `portable descriptor admits stable provider hints but rejects private locators`() {
        val descriptor =
            PortableRecordingDescriptor(
                portableId = PortableRecordingId(idValue(1)),
                title = "Track",
                artistCredit = listOf(PortableArtistCredit("Artist")),
                durationMs = 180_000,
                version = PortableRecordingVersion(VersionKind.ORIGINAL),
                externalIdentifiers = emptySet(),
                sourceHints =
                    listOf(
                        PortableSourceHint(
                            providerId = ProviderId("jiosaavn"),
                            itemType = SourceItemType.RECORDING,
                            stableItemId = "song-1",
                        )
                    ),
                artworkHint = PortableArtworkHint("https://img.example.test/art.jpg"),
            )

        assertEquals("song-1", descriptor.sourceHints.single().stableItemId)
        assertThrows(IllegalArgumentException::class.java) {
            PortableSourceHint(
                providerId = ProviderId("jiosaavn"),
                itemType = SourceItemType.RECORDING,
                stableItemId = "file:///music/private.flac",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortableArtworkHint("file:///music/cover.jpg")
        }
    }

    private fun recordingId(value: Int) = RecordingId(idValue(value))

    private fun queueEntryId(value: Int) = QueueEntryId(idValue(value))

    private fun listeningSessionId(value: Int) = ListeningSessionId(idValue(value))

    private fun decisionId(value: Int) = IdentityDecisionId(idValue(value))

    private fun idValue(value: Int) =
        "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}
