/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackTransitionTest.kt is part of Auxio.
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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTransitionTest {
    @Test
    fun equalPowerEnvelopeHasCorrectEndpointsAndMidpoint() {
        val start = equalPowerCrossfade(0f)
        val middle = equalPowerCrossfade(0.5f)
        val end = equalPowerCrossfade(1f)

        assertEquals(1f, start.outgoing, 0.0001f)
        assertEquals(0f, start.incoming, 0.0001f)
        assertEquals(0.7071f, middle.outgoing, 0.001f)
        assertEquals(0.7071f, middle.incoming, 0.001f)
        assertEquals(0f, end.outgoing, 0.0001f)
        assertEquals(1f, end.incoming, 0.0001f)
    }

    @Test
    fun equalPowerEnvelopePreservesPower() {
        listOf(0f, 0.2f, 0.5f, 0.8f, 1f).forEach { progress ->
            val envelope = equalPowerCrossfade(progress)
            assertEquals(
                1f,
                envelope.outgoing * envelope.outgoing + envelope.incoming * envelope.incoming,
                0.0001f,
            )
        }
    }

    @Test
    fun eligibilityRequiresEverySafetyGate() {
        val valid =
            CrossfadeEligibility(
                TransitionMode.CROSSFADE,
                false,
                true,
                false,
                240_000L,
                5_000L,
                nextItemExists = true,
                nextLocatorValid = true,
                standbyReady = true,
            )
        assertTrue(valid.allowed)
        assertFalse(valid.copy(crewActive = true).allowed)
        assertFalse(valid.copy(repeatOne = true).allowed)
        assertFalse(valid.copy(durationMs = 5_000L).allowed)
        assertFalse(valid.copy(standbyReady = false).allowed)
        assertFalse(valid.copy(mode = TransitionMode.GAPLESS).allowed)
    }

    @Test
    fun durationIsBounded() {
        assertEquals(
            PlaybackTransition.MIN_CROSSFADE_DURATION_MS,
            PlaybackTransition.boundedDurationMs(1L),
        )
        assertEquals(
            PlaybackTransition.MAX_CROSSFADE_DURATION_MS,
            PlaybackTransition.boundedDurationMs(Long.MAX_VALUE),
        )
    }
}
