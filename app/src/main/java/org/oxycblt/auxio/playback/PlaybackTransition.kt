/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackTransition.kt is part of Auxio.
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
package org.oxycblt.auxio.playback

import kotlin.math.sqrt

/** The transition policy between adjacent tracks. GAPLESS leaves Media3's native queue intact. */
enum class TransitionMode(val intCode: Int) {
    GAPLESS(0),
    CROSSFADE(1);

    companion object {
        fun fromIntCode(value: Int) = entries.firstOrNull { it.intCode == value }
    }
}

/**
 * Settings are deliberately bounded so a badly restored preference cannot create a long overlap.
 */
object PlaybackTransition {
    const val DEFAULT_CROSSFADE_DURATION_MS = 5_000L
    const val MIN_CROSSFADE_DURATION_MS = 1_000L
    const val MAX_CROSSFADE_DURATION_MS = 12_000L

    fun boundedDurationMs(value: Long) =
        value.coerceIn(MIN_CROSSFADE_DURATION_MS, MAX_CROSSFADE_DURATION_MS)
}

/** Equal-power envelope: at every interior point, outgoing^2 + incoming^2 is approximately one. */
data class CrossfadeEnvelope(val outgoing: Float, val incoming: Float)

internal fun equalPowerCrossfade(progress: Float): CrossfadeEnvelope {
    val bounded = progress.coerceIn(0f, 1f)
    return CrossfadeEnvelope(sqrt(1f - bounded), sqrt(bounded))
}

internal data class CrossfadeEligibility(
    val mode: TransitionMode,
    val crewActive: Boolean,
    val isPlaying: Boolean,
    val repeatOne: Boolean,
    val durationMs: Long?,
    val fadeDurationMs: Long,
    val nextItemExists: Boolean,
    val nextLocatorValid: Boolean,
    val standbyReady: Boolean,
) {
    val allowed: Boolean
        get() =
            mode == TransitionMode.CROSSFADE &&
                !crewActive &&
                isPlaying &&
                !repeatOne &&
                durationMs != null &&
                durationMs > fadeDurationMs &&
                nextItemExists &&
                nextLocatorValid &&
                standbyReady
}
