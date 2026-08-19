/*
 * Copyright (c) 2026 Auxio Project
 * CoreDaoTest.kt is part of Auxio.
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
package app.shippy.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.data.db.entity.ArtistEntity
import app.shippy.data.db.entity.LibraryRecordingEntity
import app.shippy.data.db.entity.MediaAssetEntity
import app.shippy.data.db.entity.MetadataObservationEntity
import app.shippy.data.db.entity.PlaylistEntity
import app.shippy.data.db.entity.PlaylistEntryEntity
import app.shippy.data.db.entity.RecordingArtistCreditEntity
import app.shippy.data.db.entity.RecordingEntity
import app.shippy.data.db.entity.SourceReferenceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoreDaoTest {
    private lateinit var database: ShippyR16Database

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    ShippyR16Database::class.java,
                )
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `recording graph and exact source ingestion are scoped and idempotent`() = runBlocking {
        insertRecording("recording-1")
        val first = source("source-1", "observation-1")
        val repeated = source("source-2", "observation-2").copy(availabilityState = "DEGRADED")

        val inserted = database.sourceDao().ingestExact(observation("observation-1"), first)
        val reused = database.sourceDao().ingestExact(observation("observation-2"), repeated)

        assertEquals("source-1", inserted.sourceReferenceId)
        assertEquals("source-1", reused.sourceReferenceId)
        assertEquals("DEGRADED", reused.availabilityState)
        assertEquals(2, database.sourceDao().observationCount())
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                database.sourceDao().link("source-1", "recording-2", "USER_CONFIRMED", 3)
            }
        }
        Unit
    }

    @Test
    fun `asset and Library writes remain keyed to one recording`() = runBlocking {
        insertRecording("recording-1")
        val asset = asset("asset-1", "recording-1")

        database.assetDao().upsert(asset)
        database
            .libraryDao()
            .upsertRelationship(
                LibraryRecordingEntity(
                    recordingId = "recording-1",
                    liked = true,
                    explicitlySaved = false,
                    userEdited = false,
                    manuallyIdentified = false,
                    firstAddedAtEpochMs = 1,
                    updatedAtEpochMs = 1,
                )
            )

        assertEquals(asset, database.assetDao().verifiedPlayable("recording-1").single())
        assertEquals(true, database.libraryDao().relationship("recording-1")?.liked)
        assertNull(database.libraryDao().relationship("missing"))
    }

    @Test
    fun `playlist DAO preserves duplicate recordings and requires complete reorder`() =
        runBlocking {
            insertRecording("recording-1")
            val playlist = playlist("playlist-1")
            val first = playlistEntry("entry-1", 10)
            val second = playlistEntry("entry-2", 20)
            database.playlistDao().create(playlist, listOf(first, second))

            database
                .playlistDao()
                .replaceOrder(
                    playlist.playlistId,
                    listOf(first.copy(orderKey = 20), second.copy(orderKey = 10)),
                )

            assertEquals(
                listOf("entry-2", "entry-1"),
                database.playlistDao().entries(playlist.playlistId).map { it.playlistEntryId },
            )
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    database.playlistDao().replaceOrder(playlist.playlistId, listOf(first))
                }
            }
            assertEquals(1, database.playlistDao().deletePlaylist(playlist.playlistId))
            assertEquals(
                emptyList<PlaylistEntryEntity>(),
                database.playlistDao().entries(playlist.playlistId),
            )
        }

    @Test
    fun `read views and FTS project one canonical identity`() = runBlocking {
        insertRecording("recording-1")
        database.assetDao().upsert(asset("asset-1", "recording-1"))
        database
            .libraryDao()
            .upsertRelationship(
                LibraryRecordingEntity(
                    recordingId = "recording-1",
                    liked = true,
                    explicitlySaved = false,
                    userEdited = false,
                    manuallyIdentified = false,
                    firstAddedAtEpochMs = 1,
                    updatedAtEpochMs = 1,
                )
            )
        database.playlistDao().create(playlist("playlist-1"), listOf(playlistEntry("entry-1", 10)))

        val song = checkNotNull(database.readModelDao().librarySong("recording-1"))
        val playlistEntry = database.readModelDao().playlistEntries("playlist-1").single()
        database.searchDao().refresh("recording-1")

        assertEquals("Artist", song.artistDisplay)
        assertTrue(song.localAssetExists)
        assertTrue(song.liked)
        assertEquals("entry-1", playlistEntry.playlistEntryId)
        assertEquals("AVAILABLE", playlistEntry.availabilitySummary)
        assertEquals(listOf("recording-1"), database.searchDao().searchRecordingIds("Track*", 10))
    }

    private suspend fun insertRecording(recordingId: String) {
        val artist =
            ArtistEntity(
                artistId = "artist-1",
                canonicalName = "Artist",
                sortName = null,
                disambiguation = null,
                createdAtEpochMs = 1,
                updatedAtEpochMs = 1,
            )
        database
            .recordingDao()
            .upsertRecordingGraph(
                recording = recording(recordingId),
                artists = listOf(artist),
                credits =
                    listOf(
                        RecordingArtistCreditEntity(
                            recordingId = recordingId,
                            position = 0,
                            artistId = artist.artistId,
                            creditedName = artist.canonicalName,
                            joinPhrase = "",
                        )
                    ),
            )
    }

    private fun recording(id: String) =
        RecordingEntity(
            recordingId = id,
            canonicalTitle = "Track",
            durationMs = 120_000,
            versionKind = "ORIGINAL",
            versionLabel = null,
            explicitness = "UNKNOWN",
            preferredReleaseId = null,
            preferredArtworkId = null,
            retentionKind = "DURABLE",
            retainedUntilEpochMs = null,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
        )

    private fun observation(id: String) =
        MetadataObservationEntity(
            observationId = id,
            sourceType = "PROVIDER",
            sourceReferenceId = null,
            assetId = null,
            title = "Track",
            artistCreditJson = null,
            releaseTitle = null,
            releaseArtist = null,
            durationMs = 120_000,
            artworkJson = null,
            releaseYear = null,
            trackNumber = null,
            discNumber = null,
            genresJson = null,
            versionHintsJson = null,
            externalIdsJson = null,
            extrasJson = null,
            capturedAtEpochMs = 1,
        )

    private fun source(id: String, observationId: String) =
        SourceReferenceEntity(
            sourceReferenceId = id,
            recordingId = "recording-1",
            providerId = "provider",
            itemType = "RECORDING",
            sourceItemId = "source-item-1",
            originalUrl = null,
            availabilityState = "AVAILABLE",
            availabilityCheckedAtEpochMs = 1,
            availabilityExpiresAtEpochMs = null,
            failureKind = null,
            identityStatus = "USER_CONFIRMED",
            rawMetadataObservationId = observationId,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 2,
        )

    private fun asset(id: String, recordingId: String) =
        MediaAssetEntity(
            assetId = id,
            recordingId = recordingId,
            sourceReferenceId = null,
            assetKind = "LOCAL_FILE",
            assetState = "AVAILABLE",
            locationType = "CONTENT_URI",
            location = "content://music/$id",
            documentId = null,
            mediaStoreId = null,
            displayName = "Track.flac",
            mimeType = "audio/flac",
            container = "flac",
            codec = "flac",
            bitrateBps = null,
            sampleRateHz = 48_000,
            channelCount = 2,
            contentLength = 1_000,
            contentChecksum = null,
            fingerprintId = null,
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
            lastVerifiedAtEpochMs = 1,
        )

    private fun playlist(id: String) =
        PlaylistEntity(
            playlistId = id,
            name = "Mix",
            pinned = false,
            libraryOrderKey = 10,
            artworkOverride = null,
            displaySortMode = "CUSTOM",
            displaySortDirection = "ASC",
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
        )

    private fun playlistEntry(id: String, orderKey: Long) =
        PlaylistEntryEntity(
            playlistEntryId = id,
            playlistId = "playlist-1",
            recordingId = "recording-1",
            orderKey = orderKey,
            addedAtEpochMs = 1,
        )
}
