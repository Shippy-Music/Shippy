/*
 * Copyright (c) 2026 Auxio Project
 * CanonicalTrackMetadataRepositoryTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.persistence.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm

class CanonicalTrackMetadataRepositoryTest {
    @Test
    fun `scoped observation chunks IDs and excludes unrelated catalogue rows`() = runBlocking {
        val requested = (0..900).map { TrackId("track-$it") } + TrackId("track-0")
        val dao = FakeDao(listOf(stored("track-0"), stored("track-900"), stored("unrelated")))

        val result = RoomCanonicalTrackMetadataRepository(dao).observeByIds(requested).first()

        assertEquals(2, dao.observedQueries.size)
        assertTrue(dao.observedQueries.all { it.size <= 900 })
        assertEquals(
            requested.distinct().map { it.value }.toSet(),
            dao.observedQueries.flatten().toSet(),
        )
        assertEquals(setOf(TrackId("track-0"), TrackId("track-900")), result.map { it.id }.toSet())
        assertFalse(result.any { it.id == TrackId("unrelated") })
    }

    private fun stored(id: String) =
        StoredCanonicalTrack(
            track =
                CanonicalTrackEntity(
                    trackId = id,
                    realm = TrackRealm.PROVIDER.name,
                    title = id,
                    artists = "6:Artist",
                    album = null,
                    durationMs = null,
                    versionLabel = null,
                    explicit = null,
                    live = false,
                    remix = false,
                    artwork = null,
                ),
            candidates = emptyList(),
        )

    private class FakeDao(private val records: List<StoredCanonicalTrack>) :
        CanonicalTrackMetadataDao() {
        val observedQueries = mutableListOf<List<String>>()

        override fun observeAll(): Flow<List<StoredCanonicalTrack>> = flowOf(records)

        override fun observeByIds(trackIds: List<String>): Flow<List<StoredCanonicalTrack>> {
            observedQueries += trackIds
            return flowOf(records.filter { it.track.trackId in trackIds })
        }

        override suspend fun getByIds(trackIds: List<String>): List<StoredCanonicalTrack> =
            records.filter { it.track.trackId in trackIds }

        override suspend fun insertTrack(track: CanonicalTrackEntity) = Unit

        override suspend fun deleteCandidates(trackId: String) = Unit

        override suspend fun insertCandidates(candidates: List<CanonicalTrackCandidateEntity>) =
            Unit
    }
}
