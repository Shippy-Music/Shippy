/*
 * Copyright (c) 2026 Shippy contributors
 * CollectionDetailPresentationTest.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.LibraryCollection
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.SystemCollectionKind

class CollectionDetailPresentationTest {
    @Test
    fun `only Local uses the existing Auxio local-library surface`() {
        assertTrue(LibraryCollection.System(SystemCollectionKind.LOCAL).id.usesAuxioLocalSurface())
        assertFalse(LibraryCollection.System(SystemCollectionKind.LIKED).id.usesAuxioLocalSurface())
        assertFalse(LibraryCollectionId("playlist:road-trip").usesAuxioLocalSurface())
    }

    @Test
    fun `unresolved relationship IDs never become fake song rows`() {
        assertEquals(
            CollectionDetailMessage.METADATA_PENDING,
            ShippyCollectionDetailState.System("Liked", unresolvedTrackCount = 3).messageKind(),
        )
        assertEquals(
            CollectionDetailMessage.EMPTY,
            ShippyCollectionDetailState.System("Downloads", unresolvedTrackCount = 0).messageKind(),
        )
        assertEquals(CollectionDetailMessage.DELETED, ShippyCollectionDetailState.Missing.messageKind())
    }

    @Test
    fun `playlist state keeps persisted management metadata separate from tracks`() {
        val playlist =
            LibraryCollection.Playlist(
                id = LibraryCollectionId("playlist:road-trip"),
                displayName = "Road trip",
                isPinned = true,
            )

        val state = ShippyCollectionDetailState.Playlist(playlist, unresolvedTrackCount = 2)

        assertEquals("Road trip", state.title)
        assertTrue(state.playlist.isPinned)
        assertEquals(CollectionDetailMessage.METADATA_PENDING, state.messageKind())
    }
}
