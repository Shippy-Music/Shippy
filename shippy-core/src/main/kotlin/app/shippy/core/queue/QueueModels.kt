/*
 * Copyright (c) 2026 Auxio Project
 * QueueModels.kt is part of Auxio.
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
package app.shippy.core.queue

import app.shippy.core.identity.ContributorId
import app.shippy.core.identity.PlaylistEntryId
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import java.time.Instant

enum class PlaybackOriginKind {
    LOCAL_LIBRARY,
    SYSTEM_COLLECTION,
    PLAYLIST,
    RELEASE,
    ARTIST,
    SEARCH,
    CREW,
    EXTERNAL,
}

data class PlaybackOrigin(val kind: PlaybackOriginKind, val referenceId: String?) {
    init {
        require(referenceId == null || referenceId.isNotBlank()) {
            "Origin reference cannot be blank"
        }
    }
}

data class QueueEntry(
    val id: QueueEntryId,
    val recordingId: RecordingId,
    val origin: PlaybackOrigin?,
    val playlistEntryId: PlaylistEntryId?,
    val contributor: ContributorId?,
    val addedAt: Instant,
)

data class QueueAnchor(val before: QueueEntryId?, val after: QueueEntryId?) {
    init {
        require(before != after || before == null) {
            "Queue anchor cannot reference one entry twice"
        }
    }
}
