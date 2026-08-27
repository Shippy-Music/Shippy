/*
 * Copyright (c) 2026 Auxio Project
 * R16LyricsCoordinatorTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.lyrics

import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class R16LyricsCoordinatorTest {
    private lateinit var scope: CoroutineScope

    private val recId1 = RecordingId("00000000-0000-0000-0000-000000000001")
    private val recId2 = RecordingId("00000000-0000-0000-0000-000000000002")
    private val recId3 = RecordingId("00000000-0000-0000-0000-000000000003")
    private val recId4 = RecordingId("00000000-0000-0000-0000-000000000004")

    private val qeId1 = QueueEntryId("00000000-0000-0000-0000-000000000011")
    private val qeId2 = QueueEntryId("00000000-0000-0000-0000-000000000012")
    private val qeId3 = QueueEntryId("00000000-0000-0000-0000-000000000013")
    private val qeId4 = QueueEntryId("00000000-0000-0000-0000-000000000014")

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Main)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class FakeLyricsRepository : LyricsRepository {
        var handler: (suspend (LyricsRequest) -> LyricsLookupResult)? = null

        override suspend fun lookup(request: LyricsRequest): LyricsLookupResult =
            handler?.invoke(request) ?: LyricsLookupResult.NotFound
    }

    @Test
    fun fetchLyrics_returnsSyncedLyrics_andEmitsSyncedState() = runBlocking {
        val repository = FakeLyricsRepository()
        val coordinator =
            R16LyricsCoordinator(scope = scope, lyricsRepository = repository, presentations = null)

        val synced =
            SyncedLyrics(
                lines =
                    listOf(
                        SyncedLyricLine(0L, "First line"),
                        SyncedLyricLine(5000L, "Second line"),
                    ),
                plainText = "First line\nSecond line",
            )
        val record =
            LyricsRecord(
                id = 101L,
                trackName = "Test Track",
                artistName = "Test Artist",
                albumName = "Test Album",
                durationSeconds = 180.0,
                instrumental = false,
                plainLyrics = "First line\nSecond line",
                syncedLyrics = "[00:00.00] First line\n[00:05.00] Second line",
                sourceId = "lrclib",
            )

        repository.handler = { LyricsLookupResult.Found(record, synced) }

        coordinator.loadFor(
            recordingId = recId1,
            queueEntryId = qeId1,
            generation = 1L,
            title = "Test Track",
            artist = "Test Artist",
            album = "Test Album",
            durationMs = 180_000L,
        )

        for (i in 0 until 50) {
            ShadowLooper.idleMainLooper()
            if (coordinator.state.value is R16LyricsState.Synced) break
            delay(10)
        }

        val state = coordinator.state.value
        assertTrue("Expected Synced state but got $state", state is R16LyricsState.Synced)
        val syncedState = state as R16LyricsState.Synced
        assertEquals(2, syncedState.lyrics.lines.size)
        assertEquals("First line", syncedState.lyrics.lines[0].text)
        assertEquals("lrclib", syncedState.sourceId)
    }

    @Test
    fun fetchLyrics_returnsPlainLyrics_andEmitsUnsyncedState() = runBlocking {
        val repository = FakeLyricsRepository()
        val coordinator =
            R16LyricsCoordinator(scope = scope, lyricsRepository = repository, presentations = null)

        val plain = PlainLyrics("Plain lyrics content")
        val record =
            LyricsRecord(
                id = 102L,
                trackName = "Plain Track",
                artistName = "Plain Artist",
                albumName = null,
                durationSeconds = 200.0,
                instrumental = false,
                plainLyrics = "Plain lyrics content",
                syncedLyrics = null,
                sourceId = "musixmatch",
            )

        repository.handler = { LyricsLookupResult.Found(record, plain) }

        coordinator.loadFor(
            recordingId = recId2,
            queueEntryId = qeId2,
            generation = 1L,
            title = "Plain Track",
            artist = "Plain Artist",
            album = null,
            durationMs = 200_000L,
        )

        for (i in 0 until 50) {
            ShadowLooper.idleMainLooper()
            if (coordinator.state.value is R16LyricsState.Unsynced) break
            delay(10)
        }

        val state = coordinator.state.value
        assertTrue("Expected Unsynced state but got $state", state is R16LyricsState.Unsynced)
        val unsyncedState = state as R16LyricsState.Unsynced
        assertEquals("Plain lyrics content", unsyncedState.lyrics.plainText)
        assertEquals("musixmatch", unsyncedState.sourceId)
    }

    @Test
    fun fetchLyrics_returnsNotFound_andEmitsUnavailableState() = runBlocking {
        val repository = FakeLyricsRepository()
        val coordinator =
            R16LyricsCoordinator(scope = scope, lyricsRepository = repository, presentations = null)

        repository.handler = { LyricsLookupResult.NotFound }

        coordinator.loadFor(
            recordingId = recId3,
            queueEntryId = qeId3,
            generation = 1L,
            title = "Unknown Track",
            artist = "Unknown Artist",
            album = null,
            durationMs = 120_000L,
        )

        for (i in 0 until 50) {
            ShadowLooper.idleMainLooper()
            if (coordinator.state.value is R16LyricsState.Unavailable) break
            delay(10)
        }

        val state = coordinator.state.value
        assertTrue("Expected Unavailable state but got $state", state is R16LyricsState.Unavailable)
    }

    @Test
    fun fetchLyrics_returnsFailure_andEmitsFailedState() = runBlocking {
        val repository = FakeLyricsRepository()
        val coordinator =
            R16LyricsCoordinator(scope = scope, lyricsRepository = repository, presentations = null)

        repository.handler = {
            LyricsLookupResult.Failure(
                kind = LyricsFailureKind.NETWORK,
                retryable = true,
                message = "Connection timeout",
            )
        }

        coordinator.loadFor(
            recordingId = recId4,
            queueEntryId = qeId4,
            generation = 1L,
            title = "Failing Track",
            artist = "Failing Artist",
            album = null,
            durationMs = 150_000L,
        )

        for (i in 0 until 50) {
            ShadowLooper.idleMainLooper()
            if (coordinator.state.value is R16LyricsState.Failed) break
            delay(10)
        }

        val state = coordinator.state.value
        assertTrue("Expected Failed state but got $state", state is R16LyricsState.Failed)
        val failedState = state as R16LyricsState.Failed
        assertEquals(LyricsFailureKind.NETWORK, failedState.kind)
        assertTrue(failedState.retryable)
        assertEquals("Connection timeout", failedState.message)
    }

    @Test
    fun rapidTrackSkip_slowInitialFetch_doesNotOverwriteNewTrackLyrics() = runBlocking {
        val repository = FakeLyricsRepository()
        val coordinator =
            R16LyricsCoordinator(scope = scope, lyricsRepository = repository, presentations = null)

        val track1Gate = CompletableDeferred<Unit>()
        val track1Record =
            LyricsRecord(
                id = 1L,
                trackName = "Track 1",
                artistName = "Artist 1",
                albumName = null,
                durationSeconds = 100.0,
                instrumental = false,
                plainLyrics = "Track 1 Lyrics",
                syncedLyrics = null,
            )
        val track2Record =
            LyricsRecord(
                id = 2L,
                trackName = "Track 2",
                artistName = "Artist 2",
                albumName = null,
                durationSeconds = 100.0,
                instrumental = false,
                plainLyrics = "Track 2 Lyrics",
                syncedLyrics = null,
            )

        repository.handler = { req ->
            if (req.title == "Track 1") {
                track1Gate.await()
                LyricsLookupResult.Found(track1Record, PlainLyrics("Track 1 Lyrics"))
            } else {
                LyricsLookupResult.Found(track2Record, PlainLyrics("Track 2 Lyrics"))
            }
        }

        // Start loading Track 1 (which hangs on track1Gate)
        coordinator.loadFor(
            recordingId = recId1,
            queueEntryId = qeId1,
            generation = 1L,
            title = "Track 1",
            artist = "Artist 1",
            album = null,
            durationMs = 100_000L,
        )
        ShadowLooper.idleMainLooper()

        // Rapid skip to Track 2
        coordinator.loadFor(
            recordingId = recId2,
            queueEntryId = qeId2,
            generation = 2L,
            title = "Track 2",
            artist = "Artist 2",
            album = null,
            durationMs = 100_000L,
        )

        for (i in 0 until 50) {
            ShadowLooper.idleMainLooper()
            if (coordinator.state.value is R16LyricsState.Unsynced) break
            delay(10)
        }

        // State should now be Track 2
        val stateAfterTrack2 = coordinator.state.value
        assertTrue(
            "Expected Unsynced state but got $stateAfterTrack2",
            stateAfterTrack2 is R16LyricsState.Unsynced,
        )
        val unsyncedTrack2 = stateAfterTrack2 as R16LyricsState.Unsynced
        assertEquals("Track 2 Lyrics", unsyncedTrack2.lyrics.plainText)

        // Release Track 1
        track1Gate.complete(Unit)
        for (i in 0 until 10) {
            ShadowLooper.idleMainLooper()
            delay(10)
        }

        // State must STILL be Track 2, not overwritten by stale Track 1
        val finalState = coordinator.state.value
        assertTrue(finalState is R16LyricsState.Unsynced)
        assertEquals("Track 2 Lyrics", (finalState as R16LyricsState.Unsynced).lyrics.plainText)
    }

    @Test
    fun failedLookup_suppressesRedundantRefetchUntilExplicitRetryOrRefresh() = runBlocking {
        val repository = FakeLyricsRepository()
        var fetchCount = 0
        repository.handler = {
            fetchCount++
            LyricsLookupResult.Failure(
                LyricsFailureKind.NETWORK,
                retryable = true,
                message = "Network down",
            )
        }

        val coordinator =
            R16LyricsCoordinator(scope = scope, lyricsRepository = repository, presentations = null)

        // First attempt fails
        coordinator.loadFor(
            recordingId = recId1,
            queueEntryId = qeId1,
            generation = 1L,
            title = "Track 1",
            artist = "Artist 1",
            album = null,
            durationMs = 100_000L,
        )
        ShadowLooper.idleMainLooper()
        for (i in 0 until 20) {
            ShadowLooper.idleMainLooper()
            if (coordinator.state.value is R16LyricsState.Failed) break
            delay(10)
        }
        assertTrue(coordinator.state.value is R16LyricsState.Failed)
        assertEquals(1, fetchCount)

        // Redundant loadFor with same token must be suppressed
        coordinator.loadFor(
            recordingId = recId1,
            queueEntryId = qeId1,
            generation = 1L,
            title = "Track 1",
            artist = "Artist 1",
            album = null,
            durationMs = 100_000L,
        )
        ShadowLooper.idleMainLooper()
        assertEquals(1, fetchCount)
        assertTrue(coordinator.state.value is R16LyricsState.Failed)

        // Explicit retry triggers a new fetch
        coordinator.retry()
        ShadowLooper.idleMainLooper()
        for (i in 0 until 20) {
            ShadowLooper.idleMainLooper()
            delay(10)
        }
        assertEquals(2, fetchCount)
    }
}
