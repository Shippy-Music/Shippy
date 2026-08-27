/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackCachePolicyTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.media.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackCachePolicyTest {
    @Test
    fun `policy accepts listed values and safely defaults malformed values`() {
        assertEquals(
            256L * PlaybackCachePolicy.MEBIBYTE,
            PlaybackCachePolicy.maximumBytes("268435456"),
        )
        assertEquals(
            PlaybackCachePolicy.DEFAULT_MAXIMUM_BYTES,
            PlaybackCachePolicy.maximumBytes("not-a-size"),
        )
        assertEquals(
            7L * PlaybackCachePolicy.DAY_MS,
            PlaybackCachePolicy.unusedMaxAgeMs("604800000"),
        )
        assertEquals(
            PlaybackCachePolicy.DEFAULT_UNUSED_MAX_AGE_MS,
            PlaybackCachePolicy.unusedMaxAgeMs("123"),
        )
        assertNull(PlaybackCachePolicy.unusedMaxAgeMs(PlaybackCachePolicy.SIZE_ONLY_VALUE))
    }
}
