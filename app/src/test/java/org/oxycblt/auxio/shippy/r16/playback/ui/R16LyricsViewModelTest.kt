/*
 * Copyright (c) 2026 Auxio Project
 * R16LyricsViewModelTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback.ui

import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.shippy.lyrics.LyricsLookupResult
import org.oxycblt.auxio.shippy.lyrics.LyricsRecord
import org.oxycblt.auxio.shippy.lyrics.LyricsRepository
import org.oxycblt.auxio.shippy.lyrics.LyricsRequest
import org.oxycblt.auxio.shippy.lyrics.R16LyricsState
import org.oxycblt.auxio.shippy.lyrics.SyncedLyricLine
import org.oxycblt.auxio.shippy.lyrics.SyncedLyrics
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class R16LyricsViewModelTest {
    private val recId = RecordingId("00000000-0000-0000-0000-000000000001")
    private val qeId = QueueEntryId("00000000-0000-0000-0000-000000000002")

    private class FakeLyricsRepository : LyricsRepository {
        var handler: (suspend (LyricsRequest) -> LyricsLookupResult)? = null

        override suspend fun lookup(request: LyricsRequest): LyricsLookupResult =
            handler?.invoke(request) ?: LyricsLookupResult.NotFound
    }

    @Test
    fun loadTrack_andProgressUpdate_tracksActiveLineIndex() = runBlocking {
        val repository = FakeLyricsRepository()
        val synced =
            SyncedLyrics(
                lines =
                    listOf(
                        SyncedLyricLine(1000L, "First line"),
                        SyncedLyricLine(5000L, "Second line"),
                        SyncedLyricLine(10000L, "Third line"),
                    ),
                plainText = "First line\nSecond line\nThird line",
            )
        val record =
            LyricsRecord(
                id = 1L,
                trackName = "Song",
                artistName = "Artist",
                albumName = null,
                durationSeconds = 60.0,
                instrumental = false,
                plainLyrics = null,
                syncedLyrics = "...",
            )
        repository.handler = { LyricsLookupResult.Found(record, synced) }

        val viewModel = R16LyricsViewModel(repository)
        viewModel.loadTrack(
            recordingId = recId,
            queueEntryId = qeId,
            title = "Song",
            artist = "Artist",
            album = null,
            durationMs = 60_000L,
        )

        for (i in 0 until 50) {
            ShadowLooper.idleMainLooper()
            if (viewModel.lyricsState.value is R16LyricsState.Synced) break
            delay(10)
        }
        assertTrue(
            "Expected Synced state but was ${viewModel.lyricsState.value}",
            viewModel.lyricsState.value is R16LyricsState.Synced,
        )

        // Before first line
        viewModel.updateProgress(500L)
        assertEquals(-1, viewModel.activeLineIndex.value)

        // At first line
        viewModel.updateProgress(1500L)
        assertEquals(0, viewModel.activeLineIndex.value)

        // At second line
        viewModel.updateProgress(6000L)
        assertEquals(1, viewModel.activeLineIndex.value)

        // At third line
        viewModel.updateProgress(12000L)
        assertEquals(2, viewModel.activeLineIndex.value)
    }

    @Test
    fun autoFollow_toggleAndSet_updatesState() {
        val repository = FakeLyricsRepository()
        val viewModel = R16LyricsViewModel(repository)

        assertTrue(viewModel.autoFollow.value)

        viewModel.toggleAutoFollow()
        assertFalse(viewModel.autoFollow.value)

        viewModel.setAutoFollow(true)
        assertTrue(viewModel.autoFollow.value)
    }

    @Test
    fun loadTrack_withNewGeneration_reloadsForNewGenerationToken() = runBlocking {
        var lookupCount = 0
        val repository = FakeLyricsRepository()
        repository.handler = {
            lookupCount++
            LyricsLookupResult.NotFound
        }

        val viewModel = R16LyricsViewModel(repository)
        viewModel.loadTrack(
            recordingId = recId,
            queueEntryId = qeId,
            generation = 1L,
            title = "Song",
            artist = "Artist",
            album = null,
            durationMs = 60_000L,
        )

        for (i in 0 until 50) {
            ShadowLooper.idleMainLooper()
            if (viewModel.lyricsState.value is R16LyricsState.Failed) break
            delay(10)
        }
        assertEquals(1, lookupCount)

        // Same token call on render does NOT retry failed state
        viewModel.loadTrack(
            recordingId = recId,
            queueEntryId = qeId,
            generation = 1L,
            title = "Song",
            artist = "Artist",
            album = null,
            durationMs = 60_000L,
        )
        ShadowLooper.idleMainLooper()
        assertEquals(1, lookupCount)

        // New playback generation for the same track DOES reload
        viewModel.loadTrack(
            recordingId = recId,
            queueEntryId = qeId,
            generation = 2L,
            title = "Song",
            artist = "Artist",
            album = null,
            durationMs = 60_000L,
        )
        for (i in 0 until 50) {
            ShadowLooper.idleMainLooper()
            if (lookupCount == 2) break
            delay(10)
        }
        assertEquals(2, lookupCount)
    }
}
