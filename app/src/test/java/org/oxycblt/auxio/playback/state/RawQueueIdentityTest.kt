/*
 * Copyright (c) 2026 Auxio Project
 * RawQueueIdentityTest.kt is part of Auxio.
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
package org.oxycblt.auxio.playback.state

import org.junit.Assert.assertEquals
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.ResolvedPlayback
import org.oxycblt.auxio.shippy.domain.ResolvedQueueItem
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm

class RawQueueIdentityTest {
    @Test
    fun `shuffled projection keeps current metadata aligned with physical audio item`() {
        val heap = listOf(item("a"), item("b"), item("c"), item("d"))
        val raw = RawQueue(heap = heap, shuffledMapping = listOf(2, 0, 3, 1), heapIndex = 3)

        val projected = raw.resolveItems()
        val projectedIndex = raw.resolveIndex()

        assertEquals(2, projectedIndex)
        assertEquals(heap[raw.heapIndex].item.id, projected[projectedIndex].item.id)
        assertEquals(QueueItemId("queue-d"), projected[projectedIndex].item.id)
    }

    @Test
    fun `changing shuffle projection never changes current physical identity`() {
        val heap = listOf(item("a"), item("b"), item("c"), item("d"))
        val projections = listOf(listOf(0, 1, 2, 3), listOf(2, 0, 3, 1), listOf(3, 2, 1, 0))

        projections.forEach { mapping ->
            val raw = RawQueue(heap = heap, shuffledMapping = mapping, heapIndex = 1)
            assertEquals(QueueItemId("queue-b"), raw.resolveItems()[raw.resolveIndex()].item.id)
        }
    }

    @Test
    fun `index transition republishes changed shuffle projection with its matching index`() {
        val heap = listOf(item("a"), item("b"), item("c"), item("d"))
        val oldQueue = listOf(heap[0], heap[1], heap[2], heap[3])
        val raw = RawQueue(heap = heap, shuffledMapping = listOf(2, 0, 3, 1), heapIndex = 1)

        val transition = synchronizeIndexTransition(oldQueue, raw)

        assertEquals(true, transition.queueProjectionChanged)
        assertEquals(3, transition.index)
        assertEquals(heap[raw.heapIndex].item.id, transition.queue[transition.index].item.id)
    }

    private fun item(suffix: String): ResolvedQueueItem {
        val trackId = TrackId("local:$suffix")
        val candidateId = CandidateId("local:$suffix")
        val candidate =
            TrackCandidate(
                id = candidateId,
                trackId = trackId,
                kind = CandidateKind.LOCAL,
                sourceId = "device-local",
                sourceItemId = suffix,
                availability = CandidateAvailability.AVAILABLE,
                locator = "content://music/$suffix",
            )
        val queueItem =
            QueueItem(
                id = QueueItemId("queue-$suffix"),
                track =
                    Track(
                        id = trackId,
                        realm = TrackRealm.LOCAL,
                        title = "Track $suffix",
                        artists = listOf("Artist"),
                        candidates = listOf(candidate),
                    ),
            )
        return ResolvedQueueItem(
            item = queueItem,
            playback =
                ResolvedPlayback(
                    queueItemId = queueItem.id,
                    candidateId = candidateId,
                    uri = "content://music/$suffix",
                ),
        )
    }
}
