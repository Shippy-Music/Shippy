/*
 * Copyright (c) 2026 Shippy contributors
 * LibraryRelationshipMappingTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.persistence.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.TrackId

class LibraryRelationshipMappingTest {
    @Test
    fun `stored relationship maps flags and user playlist memberships`() {
        val stored =
            StoredLibraryRelationship(
                relationship =
                    LibraryRelationshipEntity(
                        trackId = "provider:track",
                        liked = true,
                        downloaded = false,
                    ),
                playlistMemberships =
                    listOf(
                        PlaylistMembershipEntity("provider:track", "playlist:road-trip", 0),
                        PlaylistMembershipEntity("provider:track", "playlist:favorites", 1),
                    ),
            )

        val relationship = stored.toDomain()

        assertEquals(TrackId("provider:track"), relationship.trackId)
        assertTrue(relationship.liked)
        assertFalse(relationship.downloaded)
        assertEquals(
            setOf(
                LibraryCollectionId("playlist:road-trip"),
                LibraryCollectionId("playlist:favorites"),
            ),
            relationship.playlistIds,
        )
    }

    @Test
    fun `mapping rejects persisted system collection as playlist membership`() {
        val stored =
            StoredLibraryRelationship(
                relationship = LibraryRelationshipEntity(trackId = "provider:track"),
                playlistMemberships =
                    listOf(PlaylistMembershipEntity("provider:track", "system:local", 0)),
            )

        assertThrows(IllegalArgumentException::class.java) {
            stored.toDomain()
        }
    }

    @Test
    fun `system namespace detection does not reject user playlist ids`() {
        assertTrue(LibraryCollectionId("system:liked").isSystem)
        assertFalse(LibraryCollectionId("playlist:system:liked").isSystem)
    }

    @Test
    fun `user playlist metadata maps without exposing persistence position`() {
        val playlist =
            UserPlaylistEntity(
                    playlistId = "playlist:road-trip",
                    name = "Road trip",
                    pinned = true,
                    position = 7,
                )
                .toDomain()

        assertEquals(LibraryCollectionId("playlist:road-trip"), playlist.id)
        assertEquals("Road trip", playlist.displayName)
        assertTrue(playlist.isPinned)
    }

    @Test
    fun `ordered track projection preserves dao order`() {
        assertEquals(
            listOf(TrackId("track:second"), TrackId("track:first")),
            toTrackIds(listOf("track:second", "track:first")),
        )
    }

    @Test
    fun `system collection ids cannot enter user playlist operations`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireUserPlaylistId(LibraryCollectionId("system:downloads"))
        }
    }
}
