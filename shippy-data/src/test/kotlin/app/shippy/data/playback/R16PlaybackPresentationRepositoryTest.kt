/*
 * Copyright (c) 2026 Auxio Project
 * R16PlaybackPresentationRepositoryTest.kt is part of Auxio.
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
import app.shippy.core.identity.RecordingId
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.ArtistEntity
import app.shippy.data.db.entity.RecordingArtistCreditEntity
import app.shippy.data.db.entity.RecordingEntity
import app.shippy.data.db.transaction.CanonicalWriteTransactions
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16PlaybackPresentationRepositoryTest {
    private lateinit var database: ShippyR16Database
    private lateinit var repository: R16PlaybackPresentationRepository

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    ShippyR16Database::class.java,
                )
                .allowMainThreadQueries()
                .build()
        repository = RoomR16PlaybackPresentationRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `requested recordings project canonical playback metadata by identity`() = runBlocking {
        insertRecording(RECORDING_ONE, "First", "Artist One", 123_000)
        insertRecording(RECORDING_TWO, "Second", "Artist Two", null)

        val presentations =
            repository
                .observe(setOf(RecordingId(RECORDING_TWO), RecordingId(MISSING_RECORDING)))
                .first()

        assertEquals(setOf(RecordingId(RECORDING_TWO)), presentations.keys)
        val second = presentations.getValue(RecordingId(RECORDING_TWO))
        assertEquals("Second", second.title)
        assertEquals("Artist Two", second.artist)
        assertNull(second.durationMs)
        assertEquals(
            emptyMap<RecordingId, R16RecordingPresentation>(),
            repository.observe(emptySet()).first(),
        )
    }

    @Test
    fun `large presentation lookup stays below SQLite bind limit`() = runBlocking {
        val recordingIds =
            (1..1_001).mapTo(mutableSetOf()) { value ->
                RecordingId(UUID(0, value.toLong()).toString())
            }

        assertTrue(repository.observe(recordingIds).first().isEmpty())
    }

    private suspend fun insertRecording(
        recordingId: String,
        title: String,
        artistName: String,
        durationMs: Long?,
    ) {
        val artistId = "artist-$recordingId"
        CanonicalWriteTransactions(database)
            .upsertRecordingGraph(
                recording =
                    RecordingEntity(
                        recordingId = recordingId,
                        canonicalTitle = title,
                        durationMs = durationMs,
                        versionKind = "ORIGINAL",
                        versionLabel = null,
                        explicitness = "UNKNOWN",
                        preferredReleaseId = null,
                        preferredArtworkId = null,
                        retentionKind = "DURABLE",
                        retainedUntilEpochMs = null,
                        createdAtEpochMs = 1,
                        updatedAtEpochMs = 1,
                    ),
                artists =
                    listOf(
                        ArtistEntity(
                            artistId = artistId,
                            canonicalName = artistName,
                            sortName = null,
                            disambiguation = null,
                            createdAtEpochMs = 1,
                            updatedAtEpochMs = 1,
                        )
                    ),
                credits =
                    listOf(
                        RecordingArtistCreditEntity(
                            recordingId = recordingId,
                            position = 0,
                            artistId = artistId,
                            creditedName = artistName,
                            joinPhrase = "",
                        )
                    ),
            )
    }

    private companion object {
        const val RECORDING_ONE = "00000000-0000-0000-0000-000000000001"
        const val RECORDING_TWO = "00000000-0000-0000-0000-000000000002"
        const val MISSING_RECORDING = "00000000-0000-0000-0000-000000000003"
    }
}
