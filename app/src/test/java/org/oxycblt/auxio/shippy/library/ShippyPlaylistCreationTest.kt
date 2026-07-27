/*
 * Copyright (c) 2026 Auxio Project
 * ShippyPlaylistCreationTest.kt is part of Auxio.
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

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShippyPlaylistCreationTest {
    @Test
    fun `playlist creation trims names and starts unpinned`() {
        val playlist =
            requireNotNull(
                newShippyPlaylistOrNull("  Night drive  ") {
                    UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
                }
            )

        assertEquals("Night drive", playlist.displayName)
        assertEquals("playlist:123e4567-e89b-12d3-a456-426614174000", playlist.id.value)
        assertFalse(playlist.isPinned)
    }

    @Test
    fun `blank names are rejected before persistence`() {
        assertNull(newShippyPlaylistOrNull("   "))
    }

    @Test
    fun `each generated playlist uses a Shippy playlist UUID namespace`() {
        val first = requireNotNull(newShippyPlaylistOrNull("First") { UUID(1, 1) })
        val second = requireNotNull(newShippyPlaylistOrNull("Second") { UUID(2, 2) })

        assertTrue(first.id.value.startsWith("playlist:"))
        assertTrue(second.id.value.startsWith("playlist:"))
        assertNotEquals(first.id, second.id)
    }
}
