/*
 * Copyright (c) 2026 Auxio Project
 * ListeningSessionTracker.kt is part of Auxio.
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

data class ListeningTrackerState(val active: ActiveListeningSession? = null)

sealed interface ListeningTrackerEvent {
    data class Commit(
        val sessionId: ListeningSessionId,
        val queueEntryId: QueueEntryId,
        val recordingId: RecordingId,
        val sourceReferenceId: SourceReferenceId?,
        val startedAtWallClock: Instant,
        val elapsedRealtimeMs: Long,
        val chosenByUser: Boolean,
        val scrobbleAuthorized: Boolean = false,
        val accountId: String? = null,
    ) : ListeningTrackerEvent

    data class AudibleChanged(
        val queueEntryId: QueueEntryId,
        val audible: Boolean,
        val elapsedRealtimeMs: Long,
        val playbackSpeed: Double,
    ) : ListeningTrackerEvent

    data class Tick(val elapsedRealtimeMs: Long) : ListeningTrackerEvent

    data class Seeked(
        val queueEntryId: QueueEntryId,
        val elapsedRealtimeMs: Long,
        val playbackSpeed: Double,
    ) : ListeningTrackerEvent

    data class Finish(val queueEntryId: QueueEntryId, val elapsedRealtimeMs: Long) :
        ListeningTrackerEvent
}

data class ListeningTrackerTransition(
    val state: ListeningTrackerState,
    val finalized: ActiveListeningSession? = null,
)

class ListeningSessionTracker {
    fun reduce(
        state: ListeningTrackerState,
        event: ListeningTrackerEvent,
    ): ListeningTrackerTransition =
        when (event) {
            is ListeningTrackerEvent.Commit -> commit(state, event)
            is ListeningTrackerEvent.AudibleChanged -> audibleChanged(state, event)
            is ListeningTrackerEvent.Tick ->
                ListeningTrackerTransition(
                    state.copy(active = state.active?.tick(event.elapsedRealtimeMs))
                )
            is ListeningTrackerEvent.Seeked -> seeked(state, event)
            is ListeningTrackerEvent.Finish -> finish(state, event)
        }

    private fun commit(
        state: ListeningTrackerState,
        event: ListeningTrackerEvent.Commit,
    ): ListeningTrackerTransition {
        require(event.elapsedRealtimeMs >= 0) { "Monotonic time cannot be negative" }
        if (state.active?.queueEntryId == event.queueEntryId) {
            return ListeningTrackerTransition(state)
        }
        val finalized = state.active?.stopAudible(event.elapsedRealtimeMs)
        return ListeningTrackerTransition(
            state =
                ListeningTrackerState(
                    ActiveListeningSession(
                        id = event.sessionId,
                        queueEntryId = event.queueEntryId,
                        recordingId = event.recordingId,
                        sourceReferenceId = event.sourceReferenceId,
                        startedAtWallClock = event.startedAtWallClock,
                        audibleTime = AudibleTimeAccumulator(),
                        chosenByUser = event.chosenByUser,
                        scrobbleAuthorized = event.scrobbleAuthorized,
                        accountId = event.accountId,
                    )
                ),
            finalized = finalized,
        )
    }

    private fun audibleChanged(
        state: ListeningTrackerState,
        event: ListeningTrackerEvent.AudibleChanged,
    ): ListeningTrackerTransition {
        val active = state.active ?: return ListeningTrackerTransition(state)
        if (active.queueEntryId != event.queueEntryId) return ListeningTrackerTransition(state)
        val updated =
            if (event.audible) {
                active.startAudible(event.elapsedRealtimeMs, event.playbackSpeed)
            } else {
                active.stopAudible(event.elapsedRealtimeMs)
            }
        return ListeningTrackerTransition(state.copy(active = updated))
    }

    private fun seeked(
        state: ListeningTrackerState,
        event: ListeningTrackerEvent.Seeked,
    ): ListeningTrackerTransition {
        val active = state.active ?: return ListeningTrackerTransition(state)
        if (active.queueEntryId != event.queueEntryId) return ListeningTrackerTransition(state)
        val wasAudible = active.audibleTime.anchorElapsedRealtimeMs != null
        val stopped = active.stopAudible(event.elapsedRealtimeMs)
        val updated =
            if (wasAudible) {
                stopped.startAudible(event.elapsedRealtimeMs, event.playbackSpeed)
            } else {
                stopped
            }
        return ListeningTrackerTransition(state.copy(active = updated))
    }

    private fun finish(
        state: ListeningTrackerState,
        event: ListeningTrackerEvent.Finish,
    ): ListeningTrackerTransition {
        val active = state.active ?: return ListeningTrackerTransition(state)
        if (active.queueEntryId != event.queueEntryId) return ListeningTrackerTransition(state)
        val finalized = active.stopAudible(event.elapsedRealtimeMs)
        return ListeningTrackerTransition(ListeningTrackerState(), finalized)
    }
}
