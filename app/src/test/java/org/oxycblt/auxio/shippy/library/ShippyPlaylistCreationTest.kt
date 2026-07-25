/*
 * Copyright (c) 2026 Shippy contributors
 * ShippyPlaylistCreationTest.kt is part of Shippy.
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
                newShippyPlaylistOrNull("  Night drive  ") { UUID.fromString("123e4567-e89b-12d3-a456-426614174000") }
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
