/*
 * Copyright (c) 2026 Auxio Project
 * PlaylistSortTest.kt is part of Auxio.
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
package app.shippy.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaylistSortTest {
    @Test
    fun `custom is canonical ascending and legacy manual is accepted`() {
        val sort = PlaylistSort.fromWire("MANUAL", "DESCENDING")

        assertEquals(PlaylistSortMode.CUSTOM, sort.mode)
        assertEquals(PlaylistSortDirection.ASCENDING, sort.direction)
        assertEquals(sort, PlaylistSort(PlaylistSortMode.CUSTOM).normalized())
    }

    @Test
    fun `recently and oldest added are distinct typed modes`() {
        assertNotEquals(PlaylistSortMode.RECENTLY_ADDED, PlaylistSortMode.OLDEST_ADDED)
        assertEquals(PlaylistSortMode.RECENTLY_ADDED, PlaylistSortMode.fromWire("RECENTLY_ADDED"))
        assertEquals(PlaylistSortMode.OLDEST_ADDED, PlaylistSortMode.fromWire("OLDEST_ADDED"))
        assertEquals(
            PlaylistSort(PlaylistSortMode.RECENTLY_ADDED, PlaylistSortDirection.DESCENDING),
            PlaylistSort(PlaylistSortMode.OLDEST_ADDED).normalized(),
        )
    }
}
