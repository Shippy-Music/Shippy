/*
 * Copyright (c) 2026 Shippy contributors
 * PlaybackResolverTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResolverTest {
    private val preferred = ProviderId("jiosaavn")
    private val fallback = ProviderId("youtube_music")
    private val resolver = PlaybackResolver()

    @Test
    fun `crew temporary cache wins for provider track`() {
        val track =
            providerTrack(
                candidate(CandidateKind.PROVIDER, "preferred", preferred),
                candidate(CandidateKind.CREW_TEMPORARY, "crew-cache"),
                candidate(CandidateKind.DOWNLOAD, "download"),
            )

        assertSelected("crew-cache", resolve(track))
    }

    @Test
    fun `download wins before providers`() {
        val track =
            providerTrack(
                candidate(CandidateKind.PROVIDER, "preferred", preferred),
                candidate(CandidateKind.DOWNLOAD, "download"),
            )

        assertSelected("download", resolve(track))
    }

    @Test
    fun `preferred provider wins before fallback regardless of candidate order`() {
        val track =
            providerTrack(
                candidate(CandidateKind.PROVIDER, "fallback", fallback),
                candidate(CandidateKind.PROVIDER, "preferred", preferred),
            )

        assertSelected("preferred", resolve(track))
    }

    @Test
    fun `fallback provider is selected when preferred is unavailable`() {
        val track =
            providerTrack(
                candidate(
                    CandidateKind.PROVIDER,
                    "preferred",
                    preferred,
                    CandidateAvailability.UNAVAILABLE,
                ),
                candidate(CandidateKind.PROVIDER, "fallback", fallback),
            )

        assertSelected("fallback", resolve(track))
    }

    @Test
    fun `crew peer requires push and pull`() {
        val track = providerTrack(candidate(CandidateKind.CREW_PEER, "peer"))

        assertTrue(resolve(track, pushPullEnabled = false) is ResolutionResult.Unavailable)
        assertSelected("peer", resolve(track, pushPullEnabled = true))
    }

    @Test
    fun `exact local candidate wins only in local realm`() {
        val local = candidate(CandidateKind.LOCAL, "local-file")
        val localTrack = track(TrackRealm.LOCAL, local)
        val providerTrack = track(TrackRealm.PROVIDER, local)

        assertSelected("local-file", resolve(localTrack))
        assertTrue(resolve(providerTrack) is ResolutionResult.Unavailable)
    }

    @Test
    fun `disabled providers are not used`() {
        val disabled = ProviderId("disabled")
        val track = providerTrack(candidate(CandidateKind.PROVIDER, "disabled", disabled))

        assertTrue(resolve(track) is ResolutionResult.Unavailable)
    }

    @Test
    fun `same track can produce distinct queue items`() {
        val track = providerTrack(candidate(CandidateKind.PROVIDER, "preferred", preferred))

        val first = QueueItem(QueueItemId("queue-1"), track)
        val second = QueueItem(QueueItemId("queue-2"), track)

        assertEquals(first.track.id, second.track.id)
        assertTrue(first.id != second.id)
    }

    private fun resolve(
        track: Track,
        pushPullEnabled: Boolean = true,
    ) =
        resolver.resolve(
            track,
            ResolutionPolicy(
                providerPriority = listOf(preferred, fallback),
                pushPullEnabled = pushPullEnabled,
            ),
        )

    private fun assertSelected(expectedCandidateId: String, result: ResolutionResult) {
        assertTrue(result is ResolutionResult.Selected)
        assertEquals(
            CandidateId(expectedCandidateId),
            (result as ResolutionResult.Selected).candidate.id,
        )
    }

    private fun providerTrack(vararg candidates: TrackCandidate) =
        track(TrackRealm.PROVIDER, *candidates)

    private fun track(realm: TrackRealm, vararg candidates: TrackCandidate): Track {
        val id = TrackId("track")
        return Track(
            id = id,
            realm = realm,
            title = "Track",
            artists = listOf("Artist"),
            candidates = candidates.map { it.copy(trackId = id) },
        )
    }

    private fun candidate(
        kind: CandidateKind,
        id: String,
        providerId: ProviderId? = null,
        availability: CandidateAvailability = CandidateAvailability.AVAILABLE,
    ) =
        TrackCandidate(
            id = CandidateId(id),
            trackId = TrackId("unbound-track"),
            kind = kind,
            sourceId = providerId?.value ?: kind.name.lowercase(),
            sourceItemId = id,
            availability = availability,
            providerId = providerId,
        )
}
