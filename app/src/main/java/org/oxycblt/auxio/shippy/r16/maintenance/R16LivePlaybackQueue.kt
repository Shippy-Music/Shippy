/*
 * Copyright (c) 2026 Auxio Project
 * R16LivePlaybackQueue.kt is part of Auxio.
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

import app.shippy.core.identity.RecordingId
import app.shippy.core.playback.PlaybackSnapshot
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-local projection of the one active R16 playback queue for maintenance protection. Durable
 * checkpoint/reference rules remain data-layer authority; this only closes the gap before a delayed
 * checkpoint is written.
 */
@Singleton
class R16LivePlaybackQueue @Inject constructor() {
    private val recordingIds = AtomicReference<Set<RecordingId>?>(null)

    fun update(snapshot: PlaybackSnapshot) {
        recordingIds.set(snapshot.queue.baseQueue.mapTo(linkedSetOf()) { it.recordingId })
    }

    fun activeRecordingIdsOrNull(): Set<RecordingId>? = recordingIds.get()

    fun clear() {
        recordingIds.set(null)
    }
}
