/*
 * Copyright (c) 2026 Auxio Project
 * PlaylistOrderTest.kt is part of Auxio.
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
import org.junit.Assert.assertNull
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.LibraryCollection
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId

class PlaylistOrderTest {
    @Test
    fun `reorders complete playlists within their pinned groups`() {
        val current =
            playlists("pinned-one" to true, "pinned-two" to true, "one" to false, "two" to false)

        assertEquals(
            listOf("pinned-two", "pinned-one", "two", "one"),
            reorderUserPlaylistIds(
                    current,
                    playlists(
                        "pinned-two" to true,
                        "pinned-one" to true,
                        "two" to false,
                        "one" to false,
                    ),
                )
                ?.map(LibraryCollectionId::value),
        )
    }

    @Test
    fun `rejects stale partial duplicate and cross group orders`() {
        val current = playlists("pinned" to true, "one" to false, "two" to false)

        assertNull(reorderUserPlaylistIds(current, playlists("pinned" to true, "two" to false)))
        assertNull(
            reorderUserPlaylistIds(
                current,
                playlists("pinned" to true, "one" to false, "one" to false),
            )
        )
        assertNull(
            reorderUserPlaylistIds(
                current,
                playlists("one" to false, "pinned" to true, "two" to false),
            )
        )
        assertNull(
            reorderUserPlaylistIds(
                current,
                playlists("pinned" to false, "one" to false, "two" to false),
            )
        )
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
