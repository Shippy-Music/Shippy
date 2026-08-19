/*
 * Copyright (c) 2026 Auxio Project
 * Media3TransactionProjectorTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback

import androidx.media3.common.Player
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.playback.PlaybackRequestTag
import app.shippy.core.playback.PlaybackSourceHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3TransactionProjectorTest {
    @Test
    fun `projection tags every bounded item before selecting the current occurrence`() {
        val entries = (1..3).map(::preparedItem)
        val transaction =
            PlayerTransaction(
                tag = PlaybackRequestTag(7, 11, entries[1].queueEntryId),
                expectedCurrentEntryId = entries[1].queueEntryId,
                window = entries,
                startPositionMs = 42_000,
                playWhenReady = true,
            )

        val projection = Media3TransactionProjector.project(transaction)

        assertEquals(1, projection.currentIndex)
        assertEquals(42_000L, projection.startPositionMs)
        assertTrue(projection.playWhenReady)
        assertEquals(
            entries.map(PreparedEngineItem::queueEntryId),
            projection.items.map { it.requestTag.queueEntryId },
        )
        assertTrue(projection.items.all { it.requestTag.generation == 7L })
    }

    @Test
    fun `callback reason distinguishes transaction commit from automatic advance`() {
        val transaction =
            PlayerTransaction(
                tag = PlaybackRequestTag(3, 5, queueEntryId(1)),
                expectedCurrentEntryId = queueEntryId(1),
                window = listOf(preparedItem(1), preparedItem(2)),
                startPositionMs = 0,
                playWhenReady = false,
            )
        val projection = Media3TransactionProjector.project(transaction)

        val transactionCommit =
            Media3TransactionProjector.committedObservation(
                projection.items[0].requestTag,
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
            )
        val automatic =
            Media3TransactionProjector.committedObservation(
                projection.items[1].requestTag,
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            )

        assertFalse(transactionCommit.automaticTransition)
        assertTrue(automatic.automaticTransition)
        assertEquals(queueEntryId(2), automatic.tag.queueEntryId)
    }

    private fun preparedItem(value: Int) =
        PreparedEngineItem(
            queueEntryId(value),
            PlaybackSourceHandle(
                stableKey = "source-$value",
                sourceReferenceId = SourceReferenceId(idValue(value + 10)),
                mediaAssetId = null,
            ),
        )

    private fun queueEntryId(value: Int) = QueueEntryId(idValue(value))

    private fun idValue(value: Int) =
        "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}
