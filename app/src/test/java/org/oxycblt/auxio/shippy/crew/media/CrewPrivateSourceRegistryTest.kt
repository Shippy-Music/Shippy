/*
 * Copyright (c) 2026 Auxio Project
 * CrewPrivateSourceRegistryTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm

class CrewPrivateSourceRegistryTest {
    private val version = ProtocolVersion(1)
    private val member = CrewMemberId("local", version)
    private val firstSession = CrewSessionId("one", version)
    private val secondSession = CrewSessionId("two", version)

    @Test
    fun `public queue strips private candidates and every playable locator`() {
        val raw =
            item()
                .copy(
                    track =
                        item()
                            .track
                            .copy(
                                artwork = "https://art.example/cover.jpg",
                                candidates =
                                    item().track.candidates +
                                        providerCandidate("https://stream.example/audio") +
                                        temporaryCandidate(),
                            )
                )

        val public = publicizeCrewQueueItem(raw, member)

        assertEquals(member.value, public.contributorId)
        assertEquals("https://art.example/cover.jpg", public.track.artwork)
        assertEquals(
            listOf(CandidateKind.LOCAL, CandidateKind.PROVIDER),
            public.track.candidates.map { it.kind },
        )
        assertTrue(public.track.candidates.all { it.locator == null })
    }

    @Test
    fun `public queue rejects private artwork and preserves remote provenance`() {
        val raw =
            item()
                .copy(
                    contributorId = "remote",
                    track = item().track.copy(artwork = "content://art/cover"),
                )

        val public = publicizeCrewQueueItem(raw, member)

        assertEquals("remote", public.contributorId)
        assertNull(public.track.artwork)
    }

    @Test
    fun `provider realm is never stamped as a local contribution`() {
        val raw =
            item()
                .copy(
                    track =
                        item()
                            .track
                            .copy(
                                realm = TrackRealm.PROVIDER,
                                candidates =
                                    listOf(providerCandidate("https://stream.example/audio")),
                            )
                )

        assertNull(publicizeCrewQueueItem(raw, member).contributorId)
    }

    @Test
    fun `registry restores only the exact active member source`() {
        val registry = CrewPrivateSourceRegistry()
        registry.beginSession(firstSession)
        val public =
            registry.captureAndPublicize(
                firstSession,
                member,
                CrewAction.QueueReplaced(listOf(item())),
            ) as CrewAction.QueueReplaced
        val publicItem = public.items.single()

        assertEquals(
            "content://local/audio",
            registry.overlay(firstSession, member, publicItem).track.candidates.single().locator,
        )
        assertNull(
            registry
                .overlay(firstSession, CrewMemberId("other", version), publicItem)
                .track
                .candidates
                .single()
                .locator
        )
        assertNull(
            registry.overlay(secondSession, member, publicItem).track.candidates.single().locator
        )
    }

    @Test
    fun `same session begin preserves source and a new session isolates it`() {
        val registry = CrewPrivateSourceRegistry()
        registry.beginSession(firstSession)
        val public =
            registry.captureAndPublicize(
                firstSession,
                member,
                CrewAction.QueueItemInserted(item(), 0),
            ) as CrewAction.QueueItemInserted

        registry.beginSession(firstSession)
        assertEquals(
            "content://local/audio",
            registry.overlay(firstSession, member, public.item).track.candidates.single().locator,
        )

        registry.beginSession(secondSession)
        registry.beginSession(firstSession)
        assertNull(
            registry.overlay(firstSession, member, public.item).track.candidates.single().locator
        )
    }

    @Test
    fun `identity mismatch prune and end cannot restore stale locators`() {
        val registry = CrewPrivateSourceRegistry()
        registry.beginSession(firstSession)
        val public =
            registry.captureAndPublicize(
                firstSession,
                member,
                CrewAction.QueueReplaced(listOf(item())),
            ) as CrewAction.QueueReplaced
        val publicItem = public.items.single()
        val mismatched =
            publicItem.copy(
                track =
                    publicItem.track.copy(
                        candidates =
                            publicItem.track.candidates.map { it.copy(sourceItemId = "different") }
                    )
            )

        assertNull(
            registry.overlay(firstSession, member, mismatched).track.candidates.single().locator
        )

        registry.prune(firstSession, emptyList())
        assertNull(
            registry.overlay(firstSession, member, publicItem).track.candidates.single().locator
        )

        registry.captureAndPublicize(firstSession, member, CrewAction.QueueItemInserted(item(), 0))
        registry.endSession(firstSession)
        assertNull(
            registry.overlay(firstSession, member, publicItem).track.candidates.single().locator
        )
    }

    @Test
    fun `malformed and opaque content locators are ignored safely`() {
        val registry = CrewPrivateSourceRegistry()
        registry.beginSession(firstSession)

        listOf("content:not-hierarchical", "content://", "%%%").forEach { locator ->
            val raw =
                item()
                    .copy(
                        track =
                            item()
                                .track
                                .copy(
                                    candidates =
                                        item().track.candidates.map { it.copy(locator = locator) }
                                )
                    )
            val public =
                registry.captureAndPublicize(
                    firstSession,
                    member,
                    CrewAction.QueueReplaced(listOf(raw)),
                ) as CrewAction.QueueReplaced

            assertNull(
                registry
                    .overlay(firstSession, member, public.items.single())
                    .track
                    .candidates
                    .single()
                    .locator
            )
        }
    }

    private fun item(): QueueItem {
        val trackId = TrackId("track")
        return QueueItem(
            id = QueueItemId("queue"),
            track =
                Track(
                    id = trackId,
                    realm = TrackRealm.LOCAL,
                    title = "Track",
                    artists = listOf("Artist"),
                    candidates =
                        listOf(
                            TrackCandidate(
                                id = CandidateId("local"),
                                trackId = trackId,
                                kind = CandidateKind.LOCAL,
                                sourceId = "source",
                                sourceItemId = "item",
                                availability = CandidateAvailability.AVAILABLE,
                                locator = "content://local/audio",
                            ),
                            TrackCandidate(
                                id = CandidateId("download"),
                                trackId = trackId,
                                kind = CandidateKind.DOWNLOAD,
                                sourceId = "download",
                                sourceItemId = "item",
                                availability = CandidateAvailability.AVAILABLE,
                                locator = "content://download/audio",
                            ),
                        ),
                ),
        )
    }

    private fun providerCandidate(locator: String) =
        TrackCandidate(
            id = CandidateId("provider"),
            trackId = TrackId("track"),
            kind = CandidateKind.PROVIDER,
            sourceId = "provider",
            sourceItemId = "provider-item",
            availability = CandidateAvailability.RESOLVABLE,
            locator = locator,
            providerId = ProviderId("provider"),
        )

    private fun temporaryCandidate() =
        TrackCandidate(
            id = CandidateId("temporary"),
            trackId = TrackId("track"),
            kind = CandidateKind.CREW_TEMPORARY,
            sourceId = "crew",
            sourceItemId = "temporary-item",
            availability = CandidateAvailability.AVAILABLE,
            locator = "file:///private/cache/audio",
        )
}
