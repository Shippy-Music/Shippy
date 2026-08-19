/*
 * Copyright (c) 2026 Auxio Project
 * ListeningSession.kt is part of Auxio.
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
import kotlin.math.roundToLong

data class AudibleTimeAccumulator(
    val accumulatedAudibleMs: Long = 0,
    val anchorElapsedRealtimeMs: Long? = null,
    val playbackSpeed: Double = 1.0,
) {
    init {
        require(accumulatedAudibleMs >= 0) { "Audible time cannot be negative" }
        require(anchorElapsedRealtimeMs == null || anchorElapsedRealtimeMs >= 0) {
            "Monotonic anchor cannot be negative"
        }
        require(playbackSpeed.isFinite() && playbackSpeed > 0) {
            "Playback speed must be finite and positive"
        }
    }

    fun start(nowElapsedRealtimeMs: Long, speed: Double): AudibleTimeAccumulator {
        require(nowElapsedRealtimeMs >= 0) { "Monotonic time cannot be negative" }
        require(speed.isFinite() && speed > 0) { "Playback speed must be finite and positive" }
        val advanced = if (anchorElapsedRealtimeMs == null) this else tick(nowElapsedRealtimeMs)
        return advanced.copy(anchorElapsedRealtimeMs = nowElapsedRealtimeMs, playbackSpeed = speed)
    }

    fun tick(nowElapsedRealtimeMs: Long): AudibleTimeAccumulator {
        val anchor = anchorElapsedRealtimeMs ?: return this
        require(nowElapsedRealtimeMs >= anchor) { "Monotonic time cannot move backwards" }
        val added = ((nowElapsedRealtimeMs - anchor) * playbackSpeed).roundToLong()
        return copy(
            accumulatedAudibleMs = Math.addExact(accumulatedAudibleMs, added),
            anchorElapsedRealtimeMs = nowElapsedRealtimeMs,
        )
    }

    fun stop(nowElapsedRealtimeMs: Long): AudibleTimeAccumulator =
        if (anchorElapsedRealtimeMs == null) {
            this
        } else {
            tick(nowElapsedRealtimeMs).copy(anchorElapsedRealtimeMs = null)
        }

    fun checkpointForRestore(): AudibleTimeAccumulator = copy(anchorElapsedRealtimeMs = null)
}

data class ActiveListeningSession(
    val id: ListeningSessionId,
    val queueEntryId: QueueEntryId,
    val recordingId: RecordingId,
    val sourceReferenceId: SourceReferenceId?,
    val startedAtWallClock: Instant,
    val audibleTime: AudibleTimeAccumulator,
    val chosenByUser: Boolean,
) {
    fun startAudible(nowElapsedRealtimeMs: Long, playbackSpeed: Double): ActiveListeningSession =
        copy(audibleTime = audibleTime.start(nowElapsedRealtimeMs, playbackSpeed))

    fun tick(nowElapsedRealtimeMs: Long): ActiveListeningSession =
        copy(audibleTime = audibleTime.tick(nowElapsedRealtimeMs))

    fun stopAudible(nowElapsedRealtimeMs: Long): ActiveListeningSession =
        copy(audibleTime = audibleTime.stop(nowElapsedRealtimeMs))

    fun checkpointForRestore(): ActiveListeningSession =
        copy(audibleTime = audibleTime.checkpointForRestore())
}

object ListeningThresholdPolicy {
    private const val MINIMUM_DURATION_MS = 30_000L
    private const val MAXIMUM_THRESHOLD_MS = 240_000L

    fun scrobbleThresholdMs(durationMs: Long): Long? {
        require(durationMs >= 0) { "Recording duration cannot be negative" }
        if (durationMs <= MINIMUM_DURATION_MS) return null
        return minOf(durationMs / 2, MAXIMUM_THRESHOLD_MS)
    }

    fun isScrobbleEligible(durationMs: Long, accumulatedAudibleMs: Long): Boolean {
        require(accumulatedAudibleMs >= 0) { "Audible time cannot be negative" }
        val threshold = scrobbleThresholdMs(durationMs) ?: return false
        return accumulatedAudibleMs >= threshold
    }
}
