/*
 * Copyright (c) 2026 Auxio Project
 * R16LivePlaybackQueueTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.maintenance

import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.playback.PlaybackSnapshot
import app.shippy.core.queue.QueueEntry
import app.shippy.core.queue.QueueState
import app.shippy.core.queue.ShuffleState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class R16LivePlaybackQueueTest {
    @Test
    fun `live queue reflects the current snapshot and clears on release`() {
        val first = RecordingId("00000000-0000-0000-0000-000000000001")
        val second = RecordingId("00000000-0000-0000-0000-000000000002")
        val entries = listOf(entry(1, first), entry(2, second))
        val queue = R16LivePlaybackQueue()

        queue.update(
            PlaybackSnapshot.Empty.copy(
                queue =
                    QueueState(entries, entries.map { it.id }, entries.first().id, ShuffleState.Off)
            )
        )

        assertEquals(linkedSetOf(first, second), queue.activeRecordingIdsOrNull())
        queue.clear()
        assertNull(queue.activeRecordingIdsOrNull())
    }

    private fun entry(index: Int, recordingId: RecordingId): QueueEntry {
        val id = QueueEntryId("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}")
        return QueueEntry(id, recordingId, null, null, null, Instant.EPOCH)
    }
}
