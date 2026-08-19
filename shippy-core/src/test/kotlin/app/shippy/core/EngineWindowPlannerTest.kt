/*
 * Copyright (c) 2026 Auxio Project
 * EngineWindowPlannerTest.kt is part of Auxio.
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
import app.shippy.core.playback.EngineWindowPlanner
import app.shippy.core.playback.RepeatMode
import app.shippy.core.queue.QueueEntry
import app.shippy.core.queue.QueueReducer
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class EngineWindowPlannerTest {
    @Test
    fun `repeat wrap never duplicates a small queue occurrence in one window`() {
        val entries = listOf(entry(1), entry(2))
        val queue = QueueReducer().replace(entries, entries[1].id)

        val plan = EngineWindowPlanner().plan(queue, entries[1].id, RepeatMode.ALL)

        assertEquals(listOf(entries[1].id, entries[0].id), plan.orderedEntryIds)
    }

    private fun entry(value: Int) =
        QueueEntry(
            id = QueueEntryId(idValue(value)),
            recordingId = RecordingId(idValue(value + 10)),
            origin = null,
            playlistEntryId = null,
            contributor = null,
            addedAt = Instant.EPOCH,
        )

    private fun idValue(value: Int) =
        "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}
