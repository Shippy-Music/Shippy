package org.oxycblt.auxio.shippy.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalPlaybackRestoreCoordinatorTest {
    @Test
    fun `unshuffled restore compacts heap and keeps selected item`() {
        assertEquals(
            RestoredQueueShape(emptyList(), 1, true),
            remapSurvivingQueue(
                originalSize = 4,
                selectedHeapIndex = 2,
                mapping = emptyList(),
                survivingOldIndices = listOf(0, 2, 3),
            ),
        )
    }

    @Test
    fun `shuffled restore falls back to previous playable item and compacts mapping`() {
        assertEquals(
            RestoredQueueShape(listOf(2, 0, 1), 0, false),
            remapSurvivingQueue(
                originalSize = 4,
                selectedHeapIndex = 3,
                mapping = listOf(2, 0, 3, 1),
                survivingOldIndices = listOf(0, 1, 2),
            ),
        )
    }

    @Test
    fun `restore rejects malformed mapping and empty survivors`() {
        assertNull(remapSurvivingQueue(3, 0, listOf(0, 0, 2), listOf(0, 2)))
        assertNull(remapSurvivingQueue(3, 0, emptyList(), emptyList()))
    }
}
