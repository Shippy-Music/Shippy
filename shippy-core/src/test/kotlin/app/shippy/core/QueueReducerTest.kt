/*
 * Copyright (c) 2026 Auxio Project
 * QueueReducerTest.kt is part of Auxio.
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

import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.queue.QueueAnchor
import app.shippy.core.queue.QueueEntry
import app.shippy.core.queue.QueueMutationRejection
import app.shippy.core.queue.QueueMutationResult
import app.shippy.core.queue.QueueReducer
import app.shippy.core.queue.ShuffleState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueReducerTest {
    private val reducer = QueueReducer()

    @Test
    fun `shuffle is reproducible and keeps duplicate recordings as distinct entries`() {
        val entries = listOf(entry(1, 9), entry(2, 9), entry(3, 3), entry(4, 4))

        val first = reducer.replace(entries, entries[1].id, shuffleSeed = 42)
        val restored = reducer.replace(entries, entries[1].id, shuffleSeed = 42)
        val reshuffled = reducer.reshuffle(first, seed = 99)

        assertEquals(first.traversalOrder, restored.traversalOrder)
        assertEquals(entries.map(QueueEntry::id).toSet(), first.traversalOrder.toSet())
        assertEquals(entries.size, first.traversalOrder.size)
        assertNotEquals(first.traversalOrder, reshuffled.traversalOrder)
    }

    @Test
    fun `shuffle toggle preserves current identity and off restores base order`() {
        val entries = (1..6).map { entry(it, it) }
        val initial = reducer.replace(entries, entries[3].id)

        val shuffled = reducer.setShuffle(initial, enabled = true, seed = 7)
        val restored = reducer.setShuffle(shuffled, enabled = false, seed = 7)

        assertEquals(entries[3].id, shuffled.currentQueueEntryId)
        assertEquals(entries[3].id, restored.currentQueueEntryId)
        assertEquals(entries.map(QueueEntry::id), restored.traversalOrder)
        assertEquals(ShuffleState.Off, restored.shuffle)
    }

    @Test
    fun `add and remove preserve occurrences and choose the next traversal entry`() {
        val entries = (1..5).map { entry(it, it) }
        val shuffled = reducer.replace(entries, entries[2].id, shuffleSeed = 23)
        val currentIndex = shuffled.traversalOrder.indexOf(shuffled.currentQueueEntryId)
        val expectedNext =
            shuffled.traversalOrder.drop(currentIndex + 1).firstOrNull()
                ?: shuffled.traversalOrder.take(currentIndex).last()
        val added = reducer.addNext(shuffled, listOf(entry(6, 6)))
        val removed = reducer.remove(added, setOf(entries[2].id, QueueEntryId(idValue(6))))

        assertEquals(
            QueueEntryId(idValue(6)),
            added.traversalOrder[added.traversalOrder.indexOf(entries[2].id) + 1],
        )
        assertEquals(6, added.traversalOrder.toSet().size)
        assertEquals(
            entries.map(QueueEntry::id).toSet() - entries[2].id,
            removed.traversalOrder.toSet(),
        )
        assertEquals(expectedNext, removed.currentQueueEntryId)
        assertEquals(removed.baseQueue.size, removed.traversalOrder.size)
    }

    @Test
    fun `move uses neighboring identities and rejects a stale anchor`() {
        val entries = (1..4).map { entry(it, it) }
        val initial = reducer.replace(entries, entries.first().id)

        val moved =
            reducer.move(
                initial,
                entries[3].id,
                QueueAnchor(before = entries[0].id, after = entries[1].id),
            ) as QueueMutationResult.Applied
        val rejected =
            reducer.move(
                moved.state,
                entries[3].id,
                QueueAnchor(before = entries[0].id, after = entries[2].id),
            ) as QueueMutationResult.Rejected

        assertEquals(
            listOf(entries[0].id, entries[3].id, entries[1].id, entries[2].id),
            moved.state.traversalOrder,
        )
        assertEquals(QueueMutationRejection.STALE_ANCHOR, rejected.reason)
        assertTrue(rejected.state === moved.state)
    }

    private fun entry(queueValue: Int, recordingValue: Int) =
        QueueEntry(
            id = QueueEntryId(idValue(queueValue)),
            recordingId = RecordingId(idValue(recordingValue)),
            origin = null,
            playlistEntryId = null,
            contributor = null,
            addedAt = Instant.EPOCH,
        )

    private fun idValue(value: Int) =
        "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}
