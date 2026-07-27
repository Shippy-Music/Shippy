/*
 * Copyright (c) 2026 Auxio Project
 * LibraryCollectionsProjectionTest.kt is part of Auxio.
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.LibraryCollection
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.SystemCollectionKind
import org.oxycblt.auxio.shippy.persistence.library.SavedProviderEntity
import org.oxycblt.auxio.shippy.provider.ProviderEntity
import org.oxycblt.auxio.shippy.provider.ProviderEntityType

class LibraryCollectionsProjectionTest {
    @Test
    fun `system collections are permanent and ordered before playlists`() {
        val rows =
            LibraryCollectionsState(likedCount = 2, downloadedCount = 1)
                .systemRows(localSongCount = 4, isLocalIndexing = false)

        assertEquals(
            listOf(
                SystemCollectionKind.LIKED,
                SystemCollectionKind.DOWNLOADS,
                SystemCollectionKind.LOCAL,
            ),
            rows.map { it.kind },
        )
        assertEquals(listOf(2, 1, 4), rows.map { it.itemCount })
    }

    @Test
    fun `only local collection exposes active local indexing`() {
        val rows = LibraryCollectionsState().systemRows(localSongCount = 0, isLocalIndexing = true)

        assertFalse(rows[0].isLoading)
        assertFalse(rows[1].isLoading)
        assertTrue(rows[2].isLoading)
    }

    @Test
    fun `empty library retains an actionable folder onboarding row`() {
        assertTrue(
            LibraryCollectionsState()
                .shouldShowOnboarding(
                    localSongCount = 0,
                    devicePlaylistCount = 0,
                    isLocalIndexing = false,
                )
        )
        assertFalse(
            LibraryCollectionsState(likedCount = 1)
                .shouldShowOnboarding(
                    localSongCount = 0,
                    devicePlaylistCount = 0,
                    isLocalIndexing = false,
                )
        )
        assertFalse(
            LibraryCollectionsState()
                .shouldShowOnboarding(
                    localSongCount = 0,
                    devicePlaylistCount = 0,
                    isLocalIndexing = true,
                )
        )
        assertFalse(
            LibraryCollectionsState(
                    savedProviderEntities =
                        listOf(
                            SavedProviderEntity(
                                ProviderEntity(
                                    ProviderId("provider"),
                                    "album-1",
                                    ProviderEntityType.ALBUM,
                                    "Saved album",
                                ),
                                isPinned = false,
                                savedAtEpochMs = 1,
                            )
                        )
                )
                .shouldShowOnboarding(
                    localSongCount = 0,
                    devicePlaylistCount = 0,
                    isLocalIndexing = false,
                )
        )
    }

    @Test
    fun `custom playlist artwork wins over generated artwork`() {
        val id = LibraryCollectionId("playlist:custom")
        val rows =
            LibraryCollectionsState(
                    userPlaylists =
                        listOf(
                            LibraryCollection.Playlist(
                                id = id,
                                displayName = "Custom",
                                isPinned = false,
                                artworkUri = "content://artwork/custom",
                            )
                        ),
                    playlistArtwork = mapOf(id to "https://generated.example/cover.jpg"),
                )
                .collectionRows(localSongCount = 0, isLocalIndexing = false)

        val playlist = rows.filterIsInstance<LibraryCollectionListRow.Playlist>().single()
        assertEquals("content://artwork/custom", playlist.artwork)
    }

    @Test
    fun `stored layout can unpin a system collection and preserve cross-type order`() {
        val playlistId = LibraryCollectionId("playlist:one")
        val localId = LibraryCollection.System(SystemCollectionKind.LOCAL).id
        val rows =
            LibraryCollectionsState(
                    userPlaylists =
                        listOf(
                            LibraryCollection.Playlist(
                                id = playlistId,
                                displayName = "One",
                                isPinned = true,
                            )
                        ),
                    collectionLayout =
                        listOf(
                            LibraryCollectionLayoutEntry(playlistId, pinned = true),
                            LibraryCollectionLayoutEntry(localId, pinned = false),
                        ),
                )
                .collectionRows(localSongCount = 2, isLocalIndexing = false)

        assertEquals(playlistId, rows.first().id)
        assertFalse(rows.single { it.id == localId }.isPinned)
    }
}
