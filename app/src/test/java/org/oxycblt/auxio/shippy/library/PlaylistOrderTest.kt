/*
 * Copyright (c) 2026 Shippy contributors
 * PlaylistOrderTest.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.LibraryCollection
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId

class PlaylistOrderTest {
    @Test
    fun `reorders complete playlists within their pinned groups`() {
        val current = playlists("pinned-one" to true, "pinned-two" to true, "one" to false, "two" to false)

        assertEquals(
            listOf("pinned-two", "pinned-one", "two", "one"),
            reorderUserPlaylistIds(
                    current,
                    playlists("pinned-two" to true, "pinned-one" to true, "two" to false, "one" to false),
                )
                ?.map(LibraryCollectionId::value),
        )
    }

    @Test
    fun `rejects stale partial duplicate and cross group orders`() {
        val current = playlists("pinned" to true, "one" to false, "two" to false)

        assertNull(reorderUserPlaylistIds(current, playlists("pinned" to true, "two" to false)))
        assertNull(reorderUserPlaylistIds(current, playlists("pinned" to true, "one" to false, "one" to false)))
        assertNull(reorderUserPlaylistIds(current, playlists("one" to false, "pinned" to true, "two" to false)))
        assertNull(reorderUserPlaylistIds(current, playlists("pinned" to false, "one" to false, "two" to false)))
    }

    private fun playlists(vararg values: Pair<String, Boolean>) =
        values.map { (id, pinned) ->
            LibraryCollection.Playlist(
                id = LibraryCollectionId(id),
                displayName = id,
                isPinned = pinned,
            )
        }
}
