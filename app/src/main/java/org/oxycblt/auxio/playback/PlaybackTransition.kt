/*
 * Copyright (c) 2026 Shippy contributors
 * PlaybackTransition.kt is part of Shippy.
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

/** Settings are deliberately bounded so a badly restored preference cannot create a long overlap. */
object PlaybackTransition {
    const val DEFAULT_CROSSFADE_DURATION_MS = 5_000L
    const val MIN_CROSSFADE_DURATION_MS = 1_000L
    const val MAX_CROSSFADE_DURATION_MS = 12_000L

    fun boundedDurationMs(value: Long) = value.coerceIn(MIN_CROSSFADE_DURATION_MS, MAX_CROSSFADE_DURATION_MS)
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
