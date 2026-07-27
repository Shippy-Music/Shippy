/*
 * Copyright (c) 2026 Auxio Project
 * PlaylistTrackOrderTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.library

import org.junit.Assert.assertEquals
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.TrackId

class PlaylistTrackOrderTest {
    @Test
    fun `reorders visible tracks while retaining unresolved track slots`() {
        val order = ids("one", "unresolved", "two", "three")

        assertEquals(
            ids("three", "unresolved", "two", "one"),
            reorderPlaylistTrackIds(order, ids("three", "two", "one")),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects duplicate visible track ids`() {
        reorderPlaylistTrackIds(ids("one", "two"), ids("two", "two"))
    }

    private fun ids(vararg values: String): List<TrackId> = values.map(::TrackId)
}
