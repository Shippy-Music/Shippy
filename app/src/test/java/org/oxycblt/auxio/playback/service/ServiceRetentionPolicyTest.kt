/*
 * Copyright (c) 2026 Auxio Project
 * ServiceRetentionPolicyTest.kt is part of Auxio.
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
package org.oxycblt.auxio.playback.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRetentionPolicyTest {
    @Test
    fun `active paused Crew survives task removal even when normal exit is enabled`() {
        assertTrue(
            ServiceRetentionPolicy.shouldRetainAfterTaskRemoval(
                isPlaying = false,
                exitOnTaskRemoval = true,
                hasActiveCrew = true,
            )
        )
    }

    @Test
    fun `ordinary paused player still ends on task removal`() {
        assertFalse(
            ServiceRetentionPolicy.shouldRetainAfterTaskRemoval(
                isPlaying = false,
                exitOnTaskRemoval = false,
                hasActiveCrew = false,
            )
        )
    }

    @Test
    fun `ordinary playing player respects exit preference`() {
        assertTrue(
            ServiceRetentionPolicy.shouldRetainAfterTaskRemoval(
                isPlaying = true,
                exitOnTaskRemoval = false,
                hasActiveCrew = false,
            )
        )
        assertFalse(
            ServiceRetentionPolicy.shouldRetainAfterTaskRemoval(
                isPlaying = true,
                exitOnTaskRemoval = true,
                hasActiveCrew = false,
            )
        )
    }
}
