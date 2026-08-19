/*
 * Copyright (c) 2026 Auxio Project
 * ListeningSessionTrackerTest.kt is part of Auxio.
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

import app.shippy.core.identity.ListeningSessionId
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.listening.ListeningSessionTracker
import app.shippy.core.listening.ListeningTrackerEvent
import app.shippy.core.listening.ListeningTrackerState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListeningSessionTrackerTest {
    @Test
    fun `buffer pause and seek count only elapsed audible time`() {
        val tracker = ListeningSessionTracker()
        val queueEntryId = QueueEntryId(idValue(1))
        val committed =
            tracker.reduce(
                ListeningTrackerState(),
                ListeningTrackerEvent.Commit(
                    sessionId = ListeningSessionId(idValue(2)),
                    queueEntryId = queueEntryId,
                    recordingId = RecordingId(idValue(3)),
                    sourceReferenceId = null,
                    startedAtWallClock = Instant.EPOCH,
                    elapsedRealtimeMs = 1_000,
                    chosenByUser = true,
                ),
            )
        val playing =
            tracker.reduce(
                committed.state,
                ListeningTrackerEvent.AudibleChanged(queueEntryId, true, 1_000, 1.0),
            )
        val afterSeek =
            tracker.reduce(
                tracker.reduce(playing.state, ListeningTrackerEvent.Tick(6_000)).state,
                ListeningTrackerEvent.Seeked(queueEntryId, 6_000, 1.0),
            )
        val buffered =
            tracker.reduce(
                tracker.reduce(afterSeek.state, ListeningTrackerEvent.Tick(8_000)).state,
                ListeningTrackerEvent.AudibleChanged(queueEntryId, false, 9_000, 1.0),
            )
        val whileBuffered = tracker.reduce(buffered.state, ListeningTrackerEvent.Tick(20_000))
        val finished =
            tracker.reduce(whileBuffered.state, ListeningTrackerEvent.Finish(queueEntryId, 20_000))

        assertEquals(8_000L, finished.finalized?.audibleTime?.accumulatedAudibleMs)
        assertNull(finished.state.active)
    }

    private fun idValue(index: Int) =
        "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"
}
