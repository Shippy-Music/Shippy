/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaybackCheckpointRepositoryTest.kt is part of Auxio.
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
package app.shippy.data.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.core.identity.ContributorId
import app.shippy.core.identity.PlaylistEntryId
import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.playback.PlaybackCheckpoint
import app.shippy.core.playback.RepeatMode
import app.shippy.core.queue.PlaybackOrigin
import app.shippy.core.queue.PlaybackOriginKind
import app.shippy.core.queue.QueueEntry
import app.shippy.core.queue.QueueState
import app.shippy.core.queue.ShuffleState
import app.shippy.data.db.ShippyR16Database
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16PlaybackCheckpointRepositoryTest {
    private lateinit var database: ShippyR16Database
    private lateinit var repository: R16PlaybackCheckpointRepository

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    ShippyR16Database::class.java,
                )
                .allowMainThreadQueries()
                .build()
        repository =
            RoomR16PlaybackCheckpointRepository(
                database,
                now = { Instant.parse("2026-08-19T12:00:00Z") },
                sessionIdFactory = { "session-r16" },
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `round trip preserves source-neutral queue occurrence intent`() = runBlocking {
        val recordingId = RecordingId(id(1))
        val first = entry(2, recordingId, PlaybackOriginKind.PLAYLIST)
        val second = entry(3, recordingId, PlaybackOriginKind.CREW)
        val queue =
            QueueState(
                baseQueue = listOf(first, second),
                traversalOrder = listOf(second.id, first.id),
                currentQueueEntryId = second.id,
                shuffle = ShuffleState.On(73),
            )
        val expected =
            PlaybackCheckpoint(
                queue = queue,
                positionMs = 42_000,
                playWhenReady = true,
                repeatMode = RepeatMode.ALL,
            )

        repository.save(expected)

        assertEquals(expected, repository.load())
    }

    @Test
    fun `checksum mismatch is rejected instead of restoring partial intent`() {
        runBlocking {
            val entry = entry(2, RecordingId(id(1)), PlaybackOriginKind.LOCAL_LIBRARY)
            repository.save(
                PlaybackCheckpoint(
                    queue = QueueState(listOf(entry), listOf(entry.id), entry.id, ShuffleState.Off),
                    positionMs = 0,
                    playWhenReady = false,
                    repeatMode = RepeatMode.OFF,
                )
            )
            val stored = checkNotNull(database.playbackCheckpointDao().load("active"))
            database
                .playbackCheckpointDao()
                .replace(stored.checkpoint.copy(checksum = "tampered"), stored.entries)

            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.load() } }
        }
    }

    private fun entry(value: Int, recordingId: RecordingId, originKind: PlaybackOriginKind) =
        QueueEntry(
            id = QueueEntryId(id(value)),
            recordingId = recordingId,
            origin = PlaybackOrigin(originKind, "origin-$value"),
            playlistEntryId = PlaylistEntryId(id(value + 100)),
            contributor = ContributorId("member-$value"),
            addedAt = Instant.ofEpochMilli(value.toLong() * 1_000),
        )

    private fun id(value: Int) = "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}
