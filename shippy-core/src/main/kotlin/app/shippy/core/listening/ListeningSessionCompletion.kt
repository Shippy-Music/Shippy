/*
 * Copyright (c) 2026 Auxio Project
 * ListeningSessionCompletion.kt is part of Auxio.
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
package app.shippy.core.listening

import app.shippy.core.identity.ListeningSessionId
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import java.time.Instant

/** The small set of reasons that can close a playback listening session. */
enum class ListeningSessionCompletionReason {
    QUEUE_REPLACED,
    NATURAL_END,
    CLEARED,
    RELEASED,
}

/** Durable in-progress snapshot for a session that has not reached a completion boundary. */
data class ActiveListeningSessionCheckpoint(
    val sessionId: ListeningSessionId,
    val queueEntryId: QueueEntryId,
    val recordingId: RecordingId,
    val sourceReferenceId: SourceReferenceId?,
    val startedAtWallClock: Instant,
    val activeListenedMs: Long,
    val lastPositionMs: Long,
    val chosenByUser: Boolean,
    val scrobbleAuthorized: Boolean = false,
    val accountId: String? = null,
) {
    init {
        require(activeListenedMs >= 0) { "Active listening time cannot be negative" }
        require(lastPositionMs >= 0) { "Last position cannot be negative" }
    }
}

/** Immutable handoff from playback to history/scrobble persistence. */
data class FinalizedListeningSession(
    val sessionId: ListeningSessionId,
    val queueEntryId: QueueEntryId,
    val recordingId: RecordingId,
    val sourceReferenceId: SourceReferenceId?,
    val startedAtWallClock: Instant,
    val endedAtWallClock: Instant,
    val activeListenedMs: Long,
    val lastPositionMs: Long,
    val completionReason: ListeningSessionCompletionReason,
    val chosenByUser: Boolean,
    val scrobbleAuthorized: Boolean = false,
    val accountId: String? = null,
) {
    init {
        require(activeListenedMs >= 0) { "Active listening time cannot be negative" }
        require(lastPositionMs >= 0) { "Last position cannot be negative" }
        require(!endedAtWallClock.isBefore(startedAtWallClock)) {
            "Listening session cannot end before it starts"
        }
    }
}
