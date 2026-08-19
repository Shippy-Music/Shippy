/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackSourcePreparer.kt is part of Auxio.
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

import app.shippy.core.identity.RecordingId
import app.shippy.core.playback.PlaybackError
import app.shippy.core.playback.PlaybackRequestTag
import app.shippy.core.playback.PlaybackSourceHandle

data class PlaybackPreparationRequest(
    val tag: PlaybackRequestTag,
    val recordingId: RecordingId,
    val attempt: Int = 1,
) {
    init {
        require(attempt > 0) { "Playback source preparation attempt must be positive" }
    }
}

sealed interface PlaybackPreparationResult {
    data class Ready(val source: PlaybackSourceHandle) : PlaybackPreparationResult

    data class Unavailable(val error: PlaybackError) : PlaybackPreparationResult
}

interface PlaybackSourcePreparer {
    suspend fun prepare(request: PlaybackPreparationRequest): PlaybackPreparationResult
}
