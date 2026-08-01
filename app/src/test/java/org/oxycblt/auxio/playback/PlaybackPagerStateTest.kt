/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackPagerStateTest.kt is part of Auxio.
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
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.QueueItemId

class PlaybackPagerStateTest {
    @Test
    fun `stable current item wins when captured index is stale`() {
        val ids = listOf("first", "inserted", "current").map(::QueueItemId)

        val index = resolvePagerIndex(ids, QueueItemId("current"), fallbackIndex = 1)

        assertEquals(2, index)
    }

    @Test
    fun `missing identity falls back to a safe index`() {
        val ids = listOf("first", "second").map(::QueueItemId)

        assertEquals(1, resolvePagerIndex(ids, QueueItemId("gone"), fallbackIndex = 8))
        assertEquals(0, resolvePagerIndex(emptyList(), null, fallbackIndex = 4))
    }
}
