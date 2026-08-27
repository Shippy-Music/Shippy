/*
 * Copyright (c) 2026 Auxio Project
 * DurableIntegrationDaoTest.kt is part of Auxio.
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
import app.shippy.data.db.entity.DownloadJobEntity
import app.shippy.data.db.entity.LastFmScrobbleOutboxEntity
import app.shippy.data.db.entity.LyricsCacheEntity
import app.shippy.data.db.entity.MediaAssetEntity
import app.shippy.data.db.entity.MigrationAuditEntity
import app.shippy.data.db.entity.RecordingArtistCreditEntity
import app.shippy.data.db.entity.RecordingEntity
import app.shippy.data.db.entity.SavedSourceEntity
import app.shippy.data.db.transaction.CanonicalWriteTransactions
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
class DurableIntegrationDaoTest {
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
    fun `verified download publication writes asset and job atomically`() = runBlocking {
        insertRecording()
        database.downloadDao().save(downloadJob())

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                database
                    .downloadDao()
                    .publishVerified("job-1", downloadAsset().copy(assetKind = "LOCAL_FILE"), 3)
            }
        }
        assertNull(database.assetDao().get("asset-1"))
        assertEquals("TRANSFERRING", database.downloadDao().get("job-1")?.state)

        val published = database.downloadDao().publishVerified("job-1", downloadAsset(), 3)
        assertEquals("AVAILABLE", published.state)
        assertEquals("asset-1", published.publishedAssetId)
        assertEquals(1_000L, published.bytesTransferred)
        assertEquals(downloadAsset(), database.assetDao().get("asset-1"))
        Unit
    }

    @Test
    fun `integration caches are exact and LastFm outbox is FIFO idempotent`() = runBlocking {
        insertRecording()
        assertTrue(database.lastFmOutboxDao().enqueue(scrobble("outbox-1", "session-1", 1)) > 0)
        assertTrue(database.lastFmOutboxDao().enqueue(scrobble("outbox-2", "session-2", 2)) > 0)
        assertEquals(-1L, database.lastFmOutboxDao().enqueue(scrobble("duplicate", "session-1", 3)))
        assertEquals(
            listOf("outbox-1", "outbox-2"),
            database.lastFmOutboxDao().oldest(50).map { it.outboxId },
        )
        assertEquals(1, database.lastFmOutboxDao().recordAttempt("outbox-1", 4))
        assertEquals(1, database.lastFmOutboxDao().oldest(1).single().attemptCount)
        assertEquals(1, database.lastFmOutboxDao().deleteAccepted(setOf("outbox-1")))
        assertEquals(1, database.lastFmOutboxDao().count())

        val lyrics =
            LyricsCacheEntity(
                recordingId = "recording-1",
                metadataFingerprint = "fingerprint-1",
                providerId = "lrclib",
                providerRecordId = "lyrics-1",
                plainLyrics = "Words",
                synchronizedLyrics = null,
                instrumental = false,
                cachedAtEpochMs = 5,
                expiresAtEpochMs = null,
            )
        database.lyricsDao().upsert(lyrics)
        assertEquals(lyrics, database.lyricsDao().exact("recording-1", "fingerprint-1", "lrclib"))
        assertNull(database.lyricsDao().exact("recording-1", "changed", "lrclib"))

        val saved = savedSource("Original")
        database.savedSourceDao().upsert(saved)
        database.savedSourceDao().upsert(savedSource("Refreshed"))
        assertEquals("Refreshed", database.savedSourceDao().library(10).single().title)
    }

    @Test
    fun `migration audit supports resumable progress and one completion`() = runBlocking {
        val audit =
            MigrationAuditEntity(
                migrationId = "migration-1",
                sourceVersion = 10,
                targetVersion = ShippyR16Database.SCHEMA_VERSION,
                startedAtEpochMs = 1,
                completedAtEpochMs = null,
                sourceCountsJson = "{source}",
                targetCountsJson = null,
                warningsJson = "[]",
                checksum = null,
                status = "IMPORTING",
            )
        database.migrationAuditDao().start(audit)
        assertEquals(
            "migration-1",
            database
                .migrationAuditDao()
                .latestIncomplete(10, ShippyR16Database.SCHEMA_VERSION)
                ?.migrationId,
        )

        database
            .migrationAuditDao()
            .updateProgress("migration-1", "{partial}", "[warning]", "VERIFYING")
        database
            .migrationAuditDao()
            .complete("migration-1", 5, "{complete}", "[]", "checksum", "READY_TO_SWITCH")

        val completed = checkNotNull(database.migrationAuditDao().latest())
        assertEquals(5L, completed.completedAtEpochMs)
        assertEquals("checksum", completed.checksum)
        assertEquals("READY_TO_SWITCH", completed.status)
        assertNull(database.migrationAuditDao().latestIncomplete(10, 1))
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                database.migrationAuditDao().updateProgress("migration-1", null, "[]", "IMPORTING")
            }
        }
        Unit
    }

    private suspend fun insertRecording() {
        val artist =
            ArtistEntity(
                artistId = "artist-1",
                canonicalName = "Artist",
                sortName = null,
                disambiguation = null,
                createdAtEpochMs = 1,
                updatedAtEpochMs = 1,
            )
        CanonicalWriteTransactions(database)
            .upsertRecordingGraph(
                recording =
                    RecordingEntity(
                        recordingId = "recording-1",
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
                    ),
                artists = listOf(artist),
                credits =
                    listOf(
                        RecordingArtistCreditEntity(
                            recordingId = "recording-1",
                            position = 0,
                            artistId = "artist-1",
                            creditedName = "Artist",
                            joinPhrase = "",
                        )
                    ),
            )
    }

    private fun downloadJob() =
        DownloadJobEntity(
            jobId = "job-1",
            recordingId = "recording-1",
            requestedSourceReferenceId = null,
            publishedAssetId = null,
            state = "TRANSFERRING",
            bytesTransferred = 500,
            expectedBytes = 1_000,
            failureKind = null,
            retryAfterEpochMs = null,
            pendingLocation = "content://pending/job-1",
            displayFallbackJson = "{}",
            createdAtEpochMs = 1,
            updatedAtEpochMs = 2,
        )

    private fun downloadAsset() =
        MediaAssetEntity(
            assetId = "asset-1",
            recordingId = "recording-1",
            sourceReferenceId = null,
            assetKind = "SHIPPY_DOWNLOAD",
            assetState = "AVAILABLE",
            locationType = "CONTENT_URI",
            location = "content://downloads/asset-1",
            documentId = "asset-1",
            mediaStoreId = null,
            displayName = "Track.flac",
            mimeType = "audio/flac",
            container = "flac",
            codec = "flac",
            bitrateBps = null,
            sampleRateHz = 48_000,
            channelCount = 2,
            contentLength = 1_000,
            contentChecksum = "sha256",
            fingerprintId = null,
            createdAtEpochMs = 2,
            updatedAtEpochMs = 3,
            lastVerifiedAtEpochMs = 3,
        )

    private fun scrobble(outboxId: String, listeningSessionId: String, queuedAt: Long) =
        LastFmScrobbleOutboxEntity(
            outboxId = outboxId,
            accountId = "test_account",
            listeningSessionId = listeningSessionId,
            recordingId = "recording-1",
            artist = "Artist",
            track = "Track",
            album = null,
            durationSeconds = 120,
            startedAtEpochSeconds = 1,
            chosenByUser = true,
            queuedAtEpochMs = queuedAt,
            attemptCount = 0,
            lastAttemptAtEpochMs = null,
        )

    private fun savedSource(title: String) =
        SavedSourceEntity(
            providerId = "provider",
            entityType = "ALBUM",
            sourceItemId = "album-1",
            title = title,
            subtitle = "Artist",
            artworkUrl = "https://example.com/art.jpg",
            originalUrl = "https://example.com/album",
            pinned = true,
            savedAtEpochMs = 1,
            updatedAtEpochMs = 2,
        )
}
