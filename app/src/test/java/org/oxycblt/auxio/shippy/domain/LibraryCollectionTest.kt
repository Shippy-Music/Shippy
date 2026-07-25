/*
 * Copyright (c) 2026 Shippy contributors
 * LibraryCollectionTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCollectionTest {
    @Test
    fun `system collections are stable and cannot be renamed or deleted`() {
        val collections = SystemCollectionKind.entries.map { LibraryCollection.System(it) }

        assertEquals(
            listOf("system:liked", "system:downloads", "system:local"),
            collections.map { it.id.value },
        )
        assertTrue(collections.all { !it.canRename && !it.canDelete })
    }

    @Test
    fun `user playlist remains editable and deletable`() {
        val playlist =
            LibraryCollection.Playlist(
                id = LibraryCollectionId("playlist:road-trip"),
                displayName = "Road trip",
                isPinned = true,
            )

        assertTrue(playlist.canRename)
        assertTrue(playlist.canDelete)
        assertTrue(playlist.isPinned)
    }

    @Test
    fun `download relationship does not imply local realm`() {
        val relationship = LibraryRelationship(TrackId("provider:track"), downloaded = true)

        assertTrue(relationship.downloaded)
        assertFalse(relationship.liked)
        assertTrue(relationship.playlistIds.isEmpty())
    }
}
