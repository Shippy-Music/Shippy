/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackTrace.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback

import app.shippy.core.identity.QueueEntryId

enum class PlaybackTraceKind {
    COMMAND,
    RESTORE,
    CORE_EVENT,
    EFFECT,
}

data class PlaybackTraceEvent(
    val sequence: Long,
    val generation: Long,
    val queueRevision: Long,
    val kind: PlaybackTraceKind,
    val detail: String,
    val queueEntryId: QueueEntryId?,
    val ignored: Boolean,
)

/** Implementations must be fast and non-throwing; playback never waits for trace persistence. */
fun interface PlaybackTraceSink {
    fun record(event: PlaybackTraceEvent)
}

object NoOpPlaybackTraceSink : PlaybackTraceSink {
    override fun record(event: PlaybackTraceEvent) = Unit
}

class BoundedPlaybackTraceRecorder(private val capacity: Int = 256) : PlaybackTraceSink {
    private val events = ArrayDeque<PlaybackTraceEvent>(capacity)

    init {
        require(capacity > 0) { "Playback trace capacity must be positive" }
    }

    override fun record(event: PlaybackTraceEvent) {
        synchronized(events) {
            if (events.size == capacity) events.removeFirst()
            events.addLast(event)
        }
    }

    fun snapshot(): List<PlaybackTraceEvent> = synchronized(events) { events.toList() }

    /** Deterministic, locator-free diagnostics suitable for an owner-visible export surface. */
    fun exportText(): String =
        snapshot().joinToString(separator = "\n") { event ->
            listOf(
                    event.sequence,
                    event.generation,
                    event.queueRevision,
                    event.kind,
                    event.detail,
                    event.queueEntryId?.value.orEmpty(),
                    event.ignored,
                )
                .joinToString(separator = "|")
        }
}
