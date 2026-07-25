/*
 * Copyright (c) 2026 Shippy contributors
 * LibraryCollectionsProjectionTest.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.SystemCollectionKind

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
    }
}
