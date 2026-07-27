/*
 * Copyright (c) 2026 Auxio Project
 * CanonicalPlaybackRestoreCoordinatorTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalPlaybackRestoreCoordinatorTest {
    @Test
    fun `unshuffled restore compacts heap and keeps selected item`() {
        assertEquals(
            RestoredQueueShape(emptyList(), 1, true),
            remapSurvivingQueue(
                originalSize = 4,
                selectedHeapIndex = 2,
                mapping = emptyList(),
                survivingOldIndices = listOf(0, 2, 3),
            ),
        )
    }

    @Test
    fun `shuffled restore falls back to previous playable item and compacts mapping`() {
        assertEquals(
            RestoredQueueShape(listOf(2, 0, 1), 0, false),
            remapSurvivingQueue(
                originalSize = 4,
                selectedHeapIndex = 3,
                mapping = listOf(2, 0, 3, 1),
                survivingOldIndices = listOf(0, 1, 2),
            ),
        )
    }

    @Test
    fun `restore rejects malformed mapping and empty survivors`() {
        assertNull(remapSurvivingQueue(3, 0, listOf(0, 0, 2), listOf(0, 2)))
        assertNull(remapSurvivingQueue(3, 0, emptyList(), emptyList()))
    }
}
