/*
 * Copyright (c) 2026 Auxio Project
 * CoreModelTest.kt is part of Auxio.
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

import app.shippy.core.identity.PlaylistEntryId
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.queue.QueueEntry
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoreModelTest {
    @Test
    fun `canonical IDs reject embedded or malformed values`() {
        assertThrows(IllegalArgumentException::class.java) { RecordingId("provider:123") }
        assertThrows(IllegalArgumentException::class.java) { QueueEntryId("  ") }
        assertEquals(
            "00000000-0000-0000-0000-000000000001",
            RecordingId("00000000-0000-0000-0000-000000000001").value,
        )
    }

    @Test
    fun `duplicate recordings retain distinct playlist and queue occurrences`() {
        val recordingId = RecordingId("00000000-0000-0000-0000-000000000001")
        val first =
            QueueEntry(
                id = QueueEntryId("00000000-0000-0000-0000-000000000002"),
                recordingId = recordingId,
                origin = null,
                playlistEntryId = PlaylistEntryId("00000000-0000-0000-0000-000000000003"),
                contributor = null,
                addedAt = Instant.EPOCH,
            )
        val second =
            first.copy(
                id = QueueEntryId("00000000-0000-0000-0000-000000000004"),
                playlistEntryId = PlaylistEntryId("00000000-0000-0000-0000-000000000005"),
            )

        assertEquals(first.recordingId, second.recordingId)
        assertNotEquals(first.id, second.id)
        assertNotEquals(first.playlistEntryId, second.playlistEntryId)
    }
}
