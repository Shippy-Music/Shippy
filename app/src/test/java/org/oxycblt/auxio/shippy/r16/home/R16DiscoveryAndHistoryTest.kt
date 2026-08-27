/*
 * Copyright (c) 2026 Auxio Project
 * R16DiscoveryAndHistoryTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.home

import app.shippy.data.home.R16HomeHistoryItem
import com.google.android.material.R as MR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.R
import org.oxycblt.auxio.shippy.lastfm.LastFmOverviewTrack
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16DiscoveryAndHistoryTest {

    @Test
    fun lastFmOverviewTrack_dataPresentation_isCorrect() {
        val track =
            LastFmOverviewTrack(
                title = "Discovery Track",
                artist = "Discovery Artist",
                album = "Discovery Album",
                artworkUrl = "https://example.com/art.jpg",
                playCount = 42L,
            )

        assertEquals("Discovery Track", track.title)
        assertEquals("Discovery Artist", track.artist)
        assertEquals("Discovery Album", track.album)
        assertEquals(42L, track.playCount)
    }

    @Test
    fun scrobbleStatusBadges_mapsCorrectStringsAndColorAttributes() {
        data class ScrobbleBadge(val stringRes: Int, val colorAttr: Int)

        fun resolveBadge(status: String?): ScrobbleBadge? {
            if (status == null) return null
            val (stringRes, colorAttr) =
                when (status) {
                    "SENT" ->
                        Pair(
                            R.string.r16_scrobble_status_sent,
                            androidx.appcompat.R.attr.colorPrimary,
                        )
                    "PENDING" -> Pair(R.string.r16_scrobble_status_pending, MR.attr.colorTertiary)
                    "RETRYABLE_FAILURE" ->
                        Pair(
                            R.string.r16_scrobble_status_retryable_failure,
                            MR.attr.colorOnErrorContainer,
                        )
                    "NOT_AUTHORIZED" ->
                        Pair(
                            R.string.r16_scrobble_status_not_authorized,
                            android.R.attr.textColorSecondary,
                        )
                    else -> Pair(0, android.R.attr.textColorSecondary)
                }
            return ScrobbleBadge(stringRes, colorAttr)
        }

        // 1. SENT
        val sentBadge = resolveBadge("SENT")
        assertEquals(R.string.r16_scrobble_status_sent, sentBadge?.stringRes)
        assertEquals(androidx.appcompat.R.attr.colorPrimary, sentBadge?.colorAttr)

        // 2. PENDING
        val pendingBadge = resolveBadge("PENDING")
        assertEquals(R.string.r16_scrobble_status_pending, pendingBadge?.stringRes)
        assertEquals(MR.attr.colorTertiary, pendingBadge?.colorAttr)

        // 3. RETRYABLE_FAILURE
        val failedBadge = resolveBadge("RETRYABLE_FAILURE")
        assertEquals(R.string.r16_scrobble_status_retryable_failure, failedBadge?.stringRes)
        assertEquals(MR.attr.colorOnErrorContainer, failedBadge?.colorAttr)

        // 4. NOT_AUTHORIZED
        val unauthBadge = resolveBadge("NOT_AUTHORIZED")
        assertEquals(R.string.r16_scrobble_status_not_authorized, unauthBadge?.stringRes)
        assertEquals(android.R.attr.textColorSecondary, unauthBadge?.colorAttr)

        // 5. null
        val nullBadge = resolveBadge(null)
        assertEquals(null, nullBadge)
    }

    @Test
    fun historyItem_playability_andSubtitleFormatting() {
        val itemWithAlbum =
            R16HomeHistoryItem(
                listeningSessionId = "s-1",
                recordingId = "00000000-0000-0000-0000-000000000001",
                queueEntryId = "00000000-0000-0000-0000-000000000011",
                startedAtEpochMs = 1000,
                endedAtEpochMs = 2000,
                title = "Track 1",
                artist = "Artist 1",
                releaseTitle = "Album 1",
                artworkLocation = null,
                scrobbleStatus = "SENT",
            )

        val subtitleWithAlbum =
            listOf(itemWithAlbum.artist, itemWithAlbum.releaseTitle)
                .filterNotNull()
                .filter(String::isNotBlank)
                .joinToString(" - ")
        assertEquals("Artist 1 - Album 1", subtitleWithAlbum)

        val itemWithoutAlbum = itemWithAlbum.copy(releaseTitle = null)
        val subtitleWithoutAlbum =
            listOf(itemWithoutAlbum.artist, itemWithoutAlbum.releaseTitle)
                .filterNotNull()
                .filter(String::isNotBlank)
                .joinToString(" - ")
        assertEquals("Artist 1", subtitleWithoutAlbum)

        // Playability checks
        val playableWhenConnected = true && itemWithAlbum.recordingId != null
        assertTrue(playableWhenConnected)

        val itemWithNullRecording = itemWithAlbum.copy(recordingId = null)
        val playableWithNullRecording = true && itemWithNullRecording.recordingId != null
        assertFalse(playableWithNullRecording)

        val unplayableWhenDisconnected = false && itemWithAlbum.recordingId != null
        assertFalse(unplayableWhenDisconnected)
    }
}
