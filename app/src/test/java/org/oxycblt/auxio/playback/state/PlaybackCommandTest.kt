/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackCommandTest.kt is part of Auxio.
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
import org.junit.Assert.assertThrows
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

class PlaybackCommandTest {
    @Test
    fun `same track keeps distinct queue identities and local context`() {
        val first = resolvedItem("queue-1", "album-parent")
        val second = resolvedItem("queue-2", "album-parent")

        val command =
            PlaybackCommandFactoryImpl.PlaybackCommandImpl(
                selectedItemId = second.item.id,
                queue = listOf(first, second),
                parent = null,
                shuffled = false,
            )

        assertEquals(first.item.track.id, second.item.track.id)
        assertEquals(2, command.queue.map { it.item.id }.distinct().size)
        assertEquals("album-parent", command.queue[0].item.contextId)
        assertEquals(second.item.id, command.selectedItemId)
    }

    @Test
    fun `duplicate queue item identity is rejected`() {
        val item = resolvedItem("queue-1", null)

        assertThrows(IllegalArgumentException::class.java) {
            PlaybackCommandFactoryImpl.PlaybackCommandImpl(
                selectedItemId = item.item.id,
                queue = listOf(item, item),
                parent = null,
                shuffled = false,
            )
        }
    }

    @Test
    fun `selected identity must exist in queue`() {
        val item = resolvedItem("queue-1", null)

        assertThrows(IllegalArgumentException::class.java) {
            PlaybackCommandFactoryImpl.PlaybackCommandImpl(
                selectedItemId = QueueItemId("missing"),
                queue = listOf(item),
                parent = null,
                shuffled = false,
            )
        }
    }

    private fun resolvedItem(queueItemId: String, contextId: String?): ResolvedQueueItem {
        val trackId = TrackId("local:track")
        val candidateId = CandidateId("local:track")
        val candidate =
            TrackCandidate(
                id = candidateId,
                trackId = trackId,
                kind = CandidateKind.LOCAL,
                sourceId = "device-local",
                sourceItemId = "track",
                availability = CandidateAvailability.AVAILABLE,
                locator = "content://music/track",
            )
        val item =
            QueueItem(
                id = QueueItemId(queueItemId),
                track =
                    Track(
                        id = trackId,
                        realm = TrackRealm.LOCAL,
                        title = "Track",
                        artists = listOf("Artist"),
                        candidates = listOf(candidate),
                    ),
                contextId = contextId,
            )
        return ResolvedQueueItem(
            item = item,
            playback =
                ResolvedPlayback(
                    queueItemId = item.id,
                    candidateId = candidateId,
                    uri = "content://music/track",
                ),
        )
    }
}
