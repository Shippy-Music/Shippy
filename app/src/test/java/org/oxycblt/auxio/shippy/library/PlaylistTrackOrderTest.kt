/*
 * Copyright (c) 2026 Shippy contributors
 * PlaylistTrackOrderTest.kt is part of Shippy.
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
