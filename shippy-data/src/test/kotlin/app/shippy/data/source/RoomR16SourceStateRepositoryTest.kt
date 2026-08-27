/*
 * Copyright (c) 2026 Auxio Project
 * RoomR16SourceStateRepositoryTest.kt is part of Auxio.
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
package app.shippy.data.source

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.core.asset.AssetLocation
import app.shippy.core.asset.AudioTechnicalMetadata
import app.shippy.core.asset.MediaAssetKind
import app.shippy.core.identity.ProviderId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.identitymatch.RecordingDraft
import app.shippy.core.music.Explicitness
import app.shippy.core.music.RecordingVersion
import app.shippy.core.music.VersionKind
import app.shippy.core.source.AvailabilityFailure
import app.shippy.core.source.AvailabilitySnapshot
import app.shippy.core.source.AvailabilityState
import app.shippy.core.source.SourceItemType
import app.shippy.core.source.SourceKey
import app.shippy.core.source.SourceKind
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.ingest.R16AssetWriteMode
import app.shippy.data.ingest.R16IngestionCommand
import app.shippy.data.ingest.R16IngestionRepository
import app.shippy.data.ingest.R16ObservedAsset
import app.shippy.data.ingest.R16SourceObservation
import app.shippy.data.ingest.RoomR16IngestionRepository
import app.shippy.data.playback.R16PlaybackSourceRepository
import app.shippy.data.playback.RoomR16PlaybackSourceRepository
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomR16SourceStateRepositoryTest {
    private lateinit var database: ShippyR16Database
    private lateinit var ingestion: R16IngestionRepository
    private lateinit var sources: R16SourceStateRepository
    private lateinit var playbackSources: R16PlaybackSourceRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, ShippyR16Database::class.java)
                .allowMainThreadQueries()
                .build()
        ingestion = RoomR16IngestionRepository(database)
        sources = RoomR16SourceStateRepository(database)
        playbackSources = RoomR16PlaybackSourceRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `exact and observed source preserve identity while availability changes`() = runBlocking {
        val key = SourceKey(ProviderId("youtube"), SourceItemType.VIDEO, "dQw4w9WgXcQ")
        val recordingId = RecordingId("00000000-0000-0000-0000-000000000001")
        val capturedAt = Instant.parse("2026-08-19T00:00:00Z")
        ingestion.transaction {
            persist(
                R16IngestionCommand(
                    observation =
                        R16SourceObservation(
                            sourceKey = key,
                            sourceKind = SourceKind.YOUTUBE_MUSIC,
                            title = "Fixture",
                            artistNames = listOf("Artist"),
                            releaseTitle = "Release",
                            durationMs = 180_000,
                            version = RecordingVersion(VersionKind.ORIGINAL),
                            explicitness = Explicitness.UNKNOWN,
                            artwork = emptyList(),
                            externalIdentifiers = emptySet(),
                            originalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                            asset = null,
                            capturedAt = capturedAt,
                        ),
                    recordingId = recordingId,
                    newRecording =
                        RecordingDraft(
                            title = "Fixture",
                            primaryArtist = "Artist",
                            durationMs = 180_000,
                            version = RecordingVersion(VersionKind.ORIGINAL),
                        ),
                    resolution = "NEW_RECORDING",
                    identityEvidence = null,
                    assetWriteMode = R16AssetWriteMode.NONE,
                    exactManagedAssetId = null,
                    reviewCandidateIds = emptySet(),
                )
            )
        }

        val initial = sources.exact(key)!!
        assertEquals(recordingId, initial.recordingId)
        assertEquals(SourceKind.YOUTUBE, initial.kind)
        assertEquals(AvailabilityState.RESOLVABLE, initial.availability.state)
        assertEquals(initial, sources.observe(recordingId).first().single())
        assertEquals(initial, sources.get(initial.id))
        assertEquals(null, sources.get(SourceReferenceId("00000000-0000-0000-0000-000000000099")))

        val checkedAt = capturedAt.plusSeconds(60)
        sources.updateAvailability(
            key = key,
            availability =
                AvailabilitySnapshot(
                    state = AvailabilityState.DEGRADED,
                    checkedAt = checkedAt,
                    expiresAt = checkedAt.plusSeconds(300),
                    failure = AvailabilityFailure("NETWORK", retryable = true),
                ),
            updatedAt = checkedAt,
        )

        val updated = sources.exact(key)!!
        assertEquals(recordingId, updated.recordingId)
        assertEquals(SourceKind.YOUTUBE, updated.kind)
        assertEquals(AvailabilityState.DEGRADED, updated.availability.state)
        assertEquals("NETWORK", updated.availability.failure?.code)
        assertEquals(true, updated.availability.failure?.retryable)
    }

    @Test
    fun `playback options return only verified assets with their linked durable sources`() =
        runBlocking {
            val key = SourceKey(ProviderId("youtube"), SourceItemType.VIDEO, "video-1")
            val recordingId = RecordingId("00000000-0000-0000-0000-000000000010")
            val capturedAt = Instant.parse("2026-08-19T00:00:00Z")
            ingestion.transaction {
                persist(
                    R16IngestionCommand(
                        observation =
                            R16SourceObservation(
                                sourceKey = key,
                                sourceKind = SourceKind.YOUTUBE,
                                title = "Fixture",
                                artistNames = listOf("Artist"),
                                releaseTitle = null,
                                durationMs = 180_000,
                                version = RecordingVersion(VersionKind.ORIGINAL),
                                explicitness = Explicitness.UNKNOWN,
                                artwork = emptyList(),
                                externalIdentifiers = emptySet(),
                                originalUrl = "https://www.youtube.com/watch?v=video-1",
                                asset =
                                    R16ObservedAsset(
                                        kind = MediaAssetKind.SHIPPY_DOWNLOAD,
                                        location =
                                            AssetLocation("content://downloads/shippy/video-1"),
                                        locationType = "CONTENT_URI",
                                        documentId = "video-1",
                                        mediaStoreId = null,
                                        normalizedPathToken = null,
                                        downloadJobId = "job-1",
                                        lastModifiedEpochMs = capturedAt.toEpochMilli(),
                                        technical =
                                            AudioTechnicalMetadata(
                                                mimeType = "audio/mp4",
                                                codec = "aac",
                                                bitrateBps = 192_000,
                                                sampleRateHz = 48_000,
                                                channelCount = 2,
                                                contentLength = 1_024,
                                            ),
                                        checksum = null,
                                        fingerprintId = null,
                                        verifiedAt = capturedAt,
                                    ),
                                capturedAt = capturedAt,
                            ),
                        recordingId = recordingId,
                        newRecording =
                            RecordingDraft(
                                title = "Fixture",
                                primaryArtist = "Artist",
                                durationMs = 180_000,
                                version = RecordingVersion(VersionKind.ORIGINAL),
                            ),
                        resolution = "NEW_RECORDING",
                        identityEvidence = null,
                        assetWriteMode = R16AssetWriteMode.REGISTER,
                        exactManagedAssetId = null,
                        reviewCandidateIds = emptySet(),
                    )
                )
            }

            val options = playbackSources.options(recordingId)

            assertEquals(1, options.assets.size)
            assertEquals(MediaAssetKind.SHIPPY_DOWNLOAD, options.assets.single().kind)
            assertEquals("content://downloads/shippy/video-1", options.assets.single().location)
            assertEquals(key, options.sources.single().source)
        }
}
