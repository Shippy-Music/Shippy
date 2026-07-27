/*
 * Copyright (c) 2026 Auxio Project
 * CrewClockSync.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.sync

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One NTP-style monotonic clock exchange.
 *
 * Client and coordinator values are each monotonic only inside their own process. The four
 * timestamps are enough to estimate their offset without trusting wall-clock time.
 */
data class CrewClockProbe(
    val clientSentMs: Long,
    val coordinatorReceivedMs: Long,
    val coordinatorSentMs: Long,
    val clientReceivedMs: Long,
) {
    init {
        require(clientSentMs >= 0 && coordinatorReceivedMs >= 0) {
            "Clock probe timestamps cannot be negative"
        }
        require(coordinatorSentMs >= coordinatorReceivedMs) {
            "Coordinator send must not precede receive"
        }
        require(clientReceivedMs >= clientSentMs) { "Client receive must not precede send" }
    }

    fun sample(): CrewClockSample? {
        val clientElapsed = clientReceivedMs - clientSentMs
        val coordinatorElapsed = coordinatorSentMs - coordinatorReceivedMs
        val roundTripMs = clientElapsed - coordinatorElapsed
        if (roundTripMs < 0) return null

        val offsetMs =
            ((coordinatorReceivedMs.toDouble() - clientSentMs.toDouble()) +
                (coordinatorSentMs.toDouble() - clientReceivedMs.toDouble())) / 2.0
        return CrewClockSample(offsetMs, roundTripMs)
    }
}

data class CrewClockSample(
    /** Coordinator monotonic time minus client monotonic time. */
    val offsetMs: Double,
    val roundTripMs: Long,
) {
    init {
        require(offsetMs.isFinite()) { "Clock offset must be finite" }
        require(roundTripMs >= 0) { "Clock round trip cannot be negative" }
    }
}

data class CrewClockEstimate(
    val coordinatorMinusClientMs: Double,
    val uncertaintyMs: Long,
    val sampleCount: Int,
) {
    init {
        require(coordinatorMinusClientMs.isFinite()) { "Clock offset must be finite" }
        require(uncertaintyMs >= 0) { "Clock uncertainty cannot be negative" }
        require(sampleCount > 0) { "Clock estimate must contain a sample" }
    }

    fun coordinatorToClient(coordinatorMonotonicMs: Long): Long =
        (coordinatorMonotonicMs - coordinatorMinusClientMs).roundToLong()
}

/**
 * Deterministically favors the lowest-latency probes, then takes their median offset so a single
 * delayed response cannot shift the session clock.
 */
object CrewClockEstimator {
    fun estimate(probes: List<CrewClockProbe>, bestSampleLimit: Int = 3): CrewClockEstimate? {
        require(bestSampleLimit > 0) { "Best-sample limit must be positive" }
        val best =
            probes
                .mapNotNull(CrewClockProbe::sample)
                .sortedWith(compareBy(CrewClockSample::roundTripMs, CrewClockSample::offsetMs))
                .take(bestSampleLimit)
        if (best.isEmpty()) return null

        val offsets = best.map(CrewClockSample::offsetMs).sorted()
        val median =
            if (offsets.size % 2 == 1) {
                offsets[offsets.size / 2]
            } else {
                (offsets[offsets.size / 2 - 1] + offsets[offsets.size / 2]) / 2.0
            }
        return CrewClockEstimate(
            coordinatorMinusClientMs = median,
            uncertaintyMs = best.maxOf(CrewClockSample::roundTripMs) / 2,
            sampleCount = best.size,
        )
    }
}

sealed interface ScheduledPlaybackDecision {
    data class Wait(val delayMs: Long) : ScheduledPlaybackDecision

    data class Start(val positionMs: Long) : ScheduledPlaybackDecision
}

/** Converts a coordinator target epoch into a local wait or late-join position. */
fun schedulePlayback(
    coordinatorTargetMs: Long,
    basePositionMs: Long,
    clientNowMs: Long,
    clock: CrewClockEstimate,
): ScheduledPlaybackDecision {
    require(coordinatorTargetMs >= 0 && basePositionMs >= 0 && clientNowMs >= 0) {
        "Playback schedule values cannot be negative"
    }
    val localTargetMs = clock.coordinatorToClient(coordinatorTargetMs)
    val delayMs = localTargetMs - clientNowMs
    return if (delayMs > 0) {
        ScheduledPlaybackDecision.Wait(delayMs)
    } else {
        ScheduledPlaybackDecision.Start(Math.addExact(basePositionMs, -delayMs))
    }
}

data class CrewDriftPolicy(
    val ignoredDriftMs: Long,
    val speedCorrectionLimitMs: Long,
    val speedCorrectionFraction: Double,
) {
    init {
        require(ignoredDriftMs >= 0) { "Ignored drift cannot be negative" }
        require(speedCorrectionLimitMs >= ignoredDriftMs) {
            "Speed correction limit cannot be below ignored drift"
        }
        require(speedCorrectionFraction > 0.0 && speedCorrectionFraction < 0.1) {
            "Speed correction must be small and inaudible"
        }
    }
}

sealed interface CrewDriftDecision {
    data object InSync : CrewDriftDecision

    data class CorrectSpeed(val playbackRate: Float) : CrewDriftDecision

    data class Seek(val positionMs: Long) : CrewDriftDecision
}

/**
 * Produces a player action without hard-coding product thresholds. Device testing supplies the
 * policy values; this function only guarantees deterministic and bounded decisions.
 */
fun correctPlaybackDrift(
    expectedPositionMs: Long,
    actualPositionMs: Long,
    supportsSpeedCorrection: Boolean,
    policy: CrewDriftPolicy,
): CrewDriftDecision {
    require(expectedPositionMs >= 0 && actualPositionMs >= 0) {
        "Playback positions cannot be negative"
    }
    val driftMs = actualPositionMs - expectedPositionMs
    val absoluteDriftMs = abs(driftMs)
    if (absoluteDriftMs <= policy.ignoredDriftMs) return CrewDriftDecision.InSync
    if (supportsSpeedCorrection && absoluteDriftMs <= policy.speedCorrectionLimitMs) {
        val rate =
            if (driftMs < 0) {
                1.0 + policy.speedCorrectionFraction
            } else {
                1.0 - policy.speedCorrectionFraction
            }
        return CrewDriftDecision.CorrectSpeed(rate.toFloat())
    }
    return CrewDriftDecision.Seek(expectedPositionMs)
}
