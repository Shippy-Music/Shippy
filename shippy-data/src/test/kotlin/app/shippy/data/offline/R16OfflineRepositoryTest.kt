/*
 * Copyright (c) 2026 Auxio Project
 * R16OfflineRepositoryTest.kt is part of Auxio.
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
package app.shippy.data.offline

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.core.asset.AssetLocation
import app.shippy.core.asset.AssetState
import app.shippy.core.asset.AudioTechnicalMetadata
import app.shippy.core.asset.ContentChecksum
import app.shippy.core.asset.MediaAsset
import app.shippy.core.asset.MediaAssetKind
import app.shippy.core.identity.MediaAssetId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.data.db.R16LibraryMembershipTriggers
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.LibraryRecordingEntity
import app.shippy.data.db.entity.PlaylistEntity
import app.shippy.data.db.entity.PlaylistEntryEntity
import java.time.Instant
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16OfflineRepositoryTest {
    private lateinit var database: ShippyR16Database
    private lateinit var repository: R16OfflineRepository

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    ShippyR16Database::class.java,
                )
                .addCallback(R16LibraryMembershipTriggers)
                .allowMainThreadQueries()
                .build()
        repository = RoomR16OfflineRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `verified publication is strict idempotent and exposes one playable asset`() = runBlocking {
        insertRecording()
        requestAndEnterPublishing()

        val first = applied(repository.publishVerified(publication()))

        assertTrue(first.changed)
        assertEquals(R16DownloadJobState.AVAILABLE, first.job.state)
        assertEquals(ASSET_ID, first.job.publishedAssetId)
        assertEquals(
            listOf(ASSET_ID.value),
            database.assetDao().verifiedPlayable(RECORDING_ID.value).map { it.assetId },
        )

        val repeated = applied(repository.publishVerified(publication()))

        assertFalse(repeated.changed)
        assertEquals(first.job, repeated.job)
        assertEquals(1, database.assetDao().forRecording(RECORDING_ID.value).size)
    }

    @Test
    fun `migrated null reverse link still supports publication replay and removal`() = runBlocking {
        insertRecording()
        requestAndEnterPublishing()
        applied(repository.publishVerified(publication()))
        database.openHelper.writableDatabase.execSQL(
            "UPDATE media_asset SET download_job_id = NULL WHERE asset_id = ?",
            arrayOf<Any?>(ASSET_ID.value),
        )

        val replay = applied(repository.publishVerified(publication()))
        assertFalse(replay.changed)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE download_job SET destination_identity = NULL WHERE job_id = ?",
            arrayOf<Any?>(JOB_ID),
        )

        var physicalRemovalCalls = 0
        applied(
            repository.removeManagedDownload(removalCommand(destinationIdentity = null)) {
                physicalRemovalCalls++
                R16PhysicalRemovalResult.DELETED
            }
        )
        assertEquals(1, physicalRemovalCalls)
        assertNull(database.assetDao().get(ASSET_ID.value))
    }

    @Test
    fun `shared asset is retained until the last forward job reference is removed`() = runBlocking {
        insertRecording()
        requestAndEnterPublishing()
        applied(repository.publishVerified(publication()))
        requestAndEnterPublishing(SECOND_JOB_ID)
        applied(repository.publishVerified(publication(SECOND_JOB_ID)))

        applied(repository.markMissing(removalCommand()))
        assertEquals(AssetState.MISSING.name, database.assetDao().get(ASSET_ID.value)?.assetState)
        assertEquals(
            R16DownloadJobState.FAILED_RETRYABLE,
            database.downloadDao().get(JOB_ID)?.let { R16DownloadJobState.fromStored(it.state) },
        )
        assertEquals(
            R16DownloadJobState.FAILED_RETRYABLE,
            database.downloadDao().get(SECOND_JOB_ID)?.let {
                R16DownloadJobState.fromStored(it.state)
            },
        )

        val physicalRemovalCalls = mutableListOf<String>()
        val storage = R16ManagedDownloadStorage {
            physicalRemovalCalls += it.assetId.value
            R16PhysicalRemovalResult.DELETED
        }
        val firstRemoval = applied(repository.removeManagedDownload(removalCommand(), storage))
        assertEquals(R16DownloadJobState.REMOVED, firstRemoval.job.state)
        assertTrue(physicalRemovalCalls.isEmpty())
        assertNotNull(database.assetDao().get(ASSET_ID.value))
        assertEquals(
            R16DownloadJobState.FAILED_RETRYABLE,
            database.downloadDao().get(SECOND_JOB_ID)?.let {
                R16DownloadJobState.fromStored(it.state)
            },
        )

        val secondRemoval =
            applied(repository.removeManagedDownload(removalCommand(SECOND_JOB_ID), storage))
        assertEquals(R16DownloadJobState.REMOVED, secondRemoval.job.state)
        assertEquals(listOf(ASSET_ID.value), physicalRemovalCalls)
        assertNull(database.assetDao().get(ASSET_ID.value))
    }

    @Test
    fun `legacy finalizing is normalized for publication and removal decisions`() = runBlocking {
        insertRecording()
        requestAndEnterPublishing()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE download_job SET state = 'FINALIZING' WHERE job_id = ?",
            arrayOf<Any?>(JOB_ID),
        )

        val published = applied(repository.publishVerified(publication()))

        assertTrue(published.changed)
        assertEquals(R16DownloadJobState.AVAILABLE, published.job.state)
        assertEquals(R16DownloadJobState.AVAILABLE.name, database.downloadDao().get(JOB_ID)?.state)

        database.openHelper.writableDatabase.execSQL(
            "UPDATE download_job SET state = 'FINALIZING' WHERE job_id = ?",
            arrayOf<Any?>(JOB_ID),
        )
        var physicalRemovalCalls = 0
        val removal =
            repository.removeManagedDownload(removalCommand()) {
                physicalRemovalCalls++
                R16PhysicalRemovalResult.DELETED
            }

        assertEquals(
            R16OfflineRejection.INVALID_STATE,
            (removal as R16OfflineMutationResult.Rejected).reason,
        )
        assertEquals(0, physicalRemovalCalls)
        assertEquals("FINALIZING", database.downloadDao().get(JOB_ID)?.state)
        assertEquals(
            R16DownloadJobState.PUBLISHING,
            database.downloadDao().get(JOB_ID)?.let { R16DownloadJobState.fromStored(it.state) },
        )
        assertEquals(AssetState.AVAILABLE.name, database.assetDao().get(ASSET_ID.value)?.assetState)
    }

    @Test
    fun `pending cleanup locations expose every durable non null evidence row`() = runBlocking {
        insertRecording()
        requestAndEnterPublishing()
        requestAndEnterPublishing(SECOND_JOB_ID)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE download_job SET state = 'FINALIZING', " +
                "pending_location = 'content://downloads/legacy-finalizing' WHERE job_id = ?",
            arrayOf<Any?>(SECOND_JOB_ID),
        )
        applied(
            repository.request(
                R16DownloadRequest(
                    jobId = "queued-job",
                    recordingId = RECORDING_ID,
                    requestedSourceReferenceId = VALID_SOURCE_ID,
                    requestedMediaVariant = "AAC",
                    destinationIdentity = "downloads:primary",
                    expectedBytes = CONTENT_LENGTH,
                    pendingLocation = "content://downloads/not-publishing",
                    createdAt = Instant.ofEpochMilli(1),
                )
            )
        )

        assertEquals(
            listOf(
                "content://downloads/final",
                "content://downloads/legacy-finalizing",
                "content://downloads/not-publishing",
            ),
            repository.pendingCleanupLocations(),
        )
    }

    @Test
    fun `removal deletes physical target first and preserves recording library and playlist`() =
        runBlocking {
            insertRecording()
            database
                .libraryDao()
                .upsertRelationship(
                    LibraryRecordingEntity(
                        recordingId = RECORDING_ID.value,
                        liked = true,
                        explicitlySaved = true,
                        userEdited = false,
                        manuallyIdentified = false,
                        firstAddedAtEpochMs = 1,
                        updatedAtEpochMs = 1,
                    )
                )
            database
                .playlistDao()
                .create(
                    playlist =
                        PlaylistEntity(
                            playlistId = PLAYLIST_ID.value,
                            name = "Offline mix",
                            pinned = false,
                            libraryOrderKey = 1,
                            artworkOverride = null,
                            displaySortMode = "CUSTOM",
                            displaySortDirection = "ASC",
                            originKind = "USER",
                            originKey = null,
                            createdAtEpochMs = 1,
                            updatedAtEpochMs = 1,
                        ),
                    initialEntries =
                        listOf(
                            PlaylistEntryEntity(
                                playlistEntryId = PLAYLIST_ENTRY_ID.value,
                                playlistId = PLAYLIST_ID.value,
                                recordingId = RECORDING_ID.value,
                                orderKey = 1,
                                addedAtEpochMs = 1,
                            )
                        ),
                )
            requestAndEnterPublishing()
            applied(repository.publishVerified(publication()))

            val targets = mutableListOf<R16ManagedDownloadTarget>()
            var stateBeforePhysicalDelete: String? = null
            val storage = R16ManagedDownloadStorage { target ->
                targets += target
                stateBeforePhysicalDelete =
                    database.assetDao().get(target.assetId.value)?.assetState
                R16PhysicalRemovalResult.DELETED
            }
            val removal = applied(repository.removeManagedDownload(removalCommand(), storage))

            assertTrue(removal.changed)
            assertEquals(R16DownloadJobState.REMOVED, removal.job.state)
            assertEquals(AssetState.AVAILABLE.name, stateBeforePhysicalDelete)
            assertEquals(1, targets.size)
            assertEquals(ASSET_ID, targets.single().assetId)
            assertEquals("CONTENT_URI", targets.single().locationType)
            assertEquals("content://downloads/asset-1", targets.single().location.opaqueHandle)
            assertEquals("downloads:primary", targets.single().destinationIdentity)
            assertNull(database.assetDao().get(ASSET_ID.value))
            assertTrue(database.assetDao().verifiedPlayable(RECORDING_ID.value).isEmpty())
            assertNotNull(database.recordingDao().get(RECORDING_ID.value))
            assertTrue(database.libraryDao().relationship(RECORDING_ID.value)?.liked == true)
            assertEquals(
                listOf(PLAYLIST_ENTRY_ID.value),
                database.playlistDao().entries(PLAYLIST_ID.value).map { it.playlistEntryId },
            )
            assertEquals(listOf(RECORDING_ID.value), indexedRecordingIds())

            val repeated = applied(repository.removeManagedDownload(removalCommand(), storage))
            assertFalse(repeated.changed)
            assertEquals(1, targets.size)
        }

    @Test
    fun `identity storage failure and cancellation keep durable truth exact`() = runBlocking {
        insertRecording()
        val missingSource =
            repository.request(
                R16DownloadRequest(
                    jobId = JOB_ID,
                    recordingId = RECORDING_ID,
                    requestedSourceReferenceId = MISSING_SOURCE_ID,
                    requestedMediaVariant = "AAC",
                    destinationIdentity = "downloads:primary",
                    expectedBytes = CONTENT_LENGTH,
                    pendingLocation = "content://downloads/pending",
                    createdAt = Instant.ofEpochMilli(1),
                )
            )
        assertEquals(
            R16OfflineRejection.SOURCE_NOT_FOUND,
            (missingSource as R16OfflineMutationResult.Rejected).reason,
        )
        assertNull(database.downloadDao().get(JOB_ID))

        requestAndEnterPublishing()

        val incomplete =
            repository.publishVerified(
                publication(
                    evidence =
                        R16DownloadVerificationEvidence(
                            bytesComplete = false,
                            finalLocationExists = true,
                            accessRetained = true,
                            media3Readable = true,
                        )
                )
            )
        assertEquals(
            R16OfflineRejection.VERIFICATION_INCOMPLETE,
            (incomplete as R16OfflineMutationResult.Rejected).reason,
        )
        assertEquals(
            R16DownloadJobState.PUBLISHING,
            database.downloadDao().get(JOB_ID)?.let { R16DownloadJobState.fromStored(it.state) },
        )
        assertNull(database.assetDao().get(ASSET_ID.value))

        applied(repository.publishVerified(publication()))
        val failedRemoval =
            repository.removeManagedDownload(removalCommand()) { R16PhysicalRemovalResult.FAILED }

        assertEquals(
            R16OfflineRejection.STORAGE_FAILURE,
            (failedRemoval as R16OfflineMutationResult.Rejected).reason,
        )
        assertEquals(
            R16DownloadJobState.AVAILABLE,
            database.downloadDao().get(JOB_ID)?.let { R16DownloadJobState.fromStored(it.state) },
        )
        assertEquals(AssetState.AVAILABLE.name, database.assetDao().get(ASSET_ID.value)?.assetState)
        assertEquals(
            listOf(ASSET_ID.value),
            database.assetDao().verifiedPlayable(RECORDING_ID.value).map { it.assetId },
        )

        supervisorScope {
            launch {
                    repository.removeManagedDownload(removalCommand()) {
                        currentCoroutineContext().cancel()
                        R16PhysicalRemovalResult.DELETED
                    }
                }
                .join()
        }
        assertEquals(R16DownloadJobState.REMOVED.name, database.downloadDao().get(JOB_ID)?.state)
        assertNull(database.assetDao().get(ASSET_ID.value))
    }

    @Test
    fun `request persists stable identity and includes it in same job idempotency`() = runBlocking {
        insertRecording()
        val request =
            R16DownloadRequest(
                jobId = JOB_ID,
                recordingId = RECORDING_ID,
                requestedSourceReferenceId = VALID_SOURCE_ID,
                requestedMediaVariant = "AAC",
                destinationIdentity = "downloads:primary",
                expectedBytes = CONTENT_LENGTH,
                pendingLocation = "content://downloads/pending",
                createdAt = Instant.ofEpochMilli(1),
            )

        val first = applied(repository.request(request))
        assertTrue(first.changed)
        assertEquals(VALID_SOURCE_ID, first.job.requestedSourceReferenceId)
        assertEquals("AAC", first.job.requestedMediaVariant)
        assertEquals("downloads:primary", first.job.destinationIdentity)

        val repeated = applied(repository.request(request))
        assertFalse(repeated.changed)
        assertEquals(first.job, repeated.job)

        val variantMismatch = repository.request(request.copy(requestedMediaVariant = "FLAC"))
        assertEquals(
            R16OfflineRejection.IDENTITY_MISMATCH,
            (variantMismatch as R16OfflineMutationResult.Rejected).reason,
        )
        val destinationMismatch =
            repository.request(request.copy(destinationIdentity = "downloads:secondary"))
        assertEquals(
            R16OfflineRejection.IDENTITY_MISMATCH,
            (destinationMismatch as R16OfflineMutationResult.Rejected).reason,
        )
    }

    @Test
    fun `load returns exact complete worker snapshot`() = runBlocking {
        insertRecording()
        applied(
            repository.request(
                R16DownloadRequest(
                    jobId = JOB_ID,
                    recordingId = RECORDING_ID,
                    requestedSourceReferenceId = VALID_SOURCE_ID,
                    requestedMediaVariant = "AAC",
                    destinationIdentity = "downloads:primary",
                    expectedBytes = CONTENT_LENGTH,
                    pendingLocation = "content://downloads/pending",
                    createdAt = Instant.ofEpochMilli(1),
                )
            )
        )

        val snapshot = checkNotNull(repository.load(JOB_ID))
        assertEquals(JOB_ID, snapshot.jobId)
        assertEquals(RECORDING_ID, snapshot.recordingId)
        assertEquals(VALID_SOURCE_ID, snapshot.requestedSourceReferenceId)
        assertEquals("AAC", snapshot.requestedMediaVariant)
        assertEquals("downloads:primary", snapshot.destinationIdentity)
        assertEquals("{}", snapshot.displayFallbackJson)
        assertEquals("content://downloads/pending", snapshot.pendingLocation)
        assertEquals(R16DownloadJobState.REQUESTED, snapshot.state)
        assertNull(repository.load(" $JOB_ID"))
    }

    @Test
    fun `begin publishing requires verifying exact identity and is idempotent`() = runBlocking {
        insertRecording()
        requestAndEnterVerifying()
        val command = publishingStart()

        val first = applied(repository.beginPublishing(command))
        assertTrue(first.changed)
        assertEquals(R16DownloadJobState.PUBLISHING, first.job.state)
        assertEquals("content://downloads/final", first.job.pendingLocation)

        val repeated = applied(repository.beginPublishing(command))
        assertFalse(repeated.changed)
        assertEquals(first.job, repeated.job)

        val locationMismatch =
            repository.beginPublishing(command.copy(pendingLocation = "content://downloads/other"))
        assertEquals(
            R16OfflineRejection.IDENTITY_MISMATCH,
            (locationMismatch as R16OfflineMutationResult.Rejected).reason,
        )
    }

    @Test
    fun `pending cleanup evidence is exact idempotent and preserves terminal state`() =
        runBlocking {
            insertRecording()
            applied(
                repository.request(
                    R16DownloadRequest(
                        jobId = JOB_ID,
                        recordingId = RECORDING_ID,
                        requestedSourceReferenceId = VALID_SOURCE_ID,
                        requestedMediaVariant = "AAC",
                        destinationIdentity = "downloads:primary",
                        expectedBytes = CONTENT_LENGTH,
                        pendingLocation = null,
                        createdAt = Instant.ofEpochMilli(1),
                    )
                )
            )
            applied(
                repository.updateProgress(
                    R16DownloadProgress(
                        jobId = JOB_ID,
                        state = R16DownloadJobState.CANCELLED,
                        bytesTransferred = 0,
                        expectedBytes = CONTENT_LENGTH,
                        failureKind = null,
                        retryAfter = null,
                        updatedAt = Instant.ofEpochMilli(2),
                    )
                )
            )
            val command = pendingCleanupEvidence()

            val retained = applied(repository.retainPendingCleanupEvidence(command))
            assertTrue(retained.changed)
            assertEquals(R16DownloadJobState.CANCELLED, retained.job.state)
            assertEquals(command.pendingLocation, retained.job.pendingLocation)

            val repeatedRetain = applied(repository.retainPendingCleanupEvidence(command))
            assertFalse(repeatedRetain.changed)
            assertEquals(R16DownloadJobState.CANCELLED, repeatedRetain.job.state)

            val conflict =
                repository.retainPendingCleanupEvidence(
                    command.copy(pendingLocation = "content://downloads/conflict")
                )
            assertEquals(
                R16OfflineRejection.IDENTITY_MISMATCH,
                (conflict as R16OfflineMutationResult.Rejected).reason,
            )
            assertEquals(
                command.pendingLocation,
                checkNotNull(repository.load(JOB_ID)).pendingLocation,
            )

            val wrongClear =
                repository.clearPendingCleanupEvidence(
                    command.copy(pendingLocation = "content://downloads/conflict")
                )
            assertEquals(
                R16OfflineRejection.IDENTITY_MISMATCH,
                (wrongClear as R16OfflineMutationResult.Rejected).reason,
            )

            val cleared = applied(repository.clearPendingCleanupEvidence(command))
            assertTrue(cleared.changed)
            assertEquals(R16DownloadJobState.CANCELLED, cleared.job.state)
            assertNull(cleared.job.pendingLocation)

            val repeatedClear = applied(repository.clearPendingCleanupEvidence(command))
            assertFalse(repeatedClear.changed)
            assertEquals(R16DownloadJobState.CANCELLED, repeatedClear.job.state)
        }

    @Test
    fun `pending cleanup evidence requires trimmed exact identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            pendingCleanupEvidence().copy(jobId = " $JOB_ID")
        }
        assertThrows(IllegalArgumentException::class.java) {
            pendingCleanupEvidence().copy(requestedMediaVariant = " AAC")
        }
        assertThrows(IllegalArgumentException::class.java) {
            pendingCleanupEvidence().copy(destinationIdentity = "downloads:primary ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            pendingCleanupEvidence().copy(pendingLocation = " content://downloads/orphan")
        }
    }

    @Test
    fun `generic progress cannot enter publishing or restart retryable failure`() = runBlocking {
        insertRecording()
        requestAndEnterVerifying()

        val publishing =
            repository.updateProgress(
                R16DownloadProgress(
                    jobId = JOB_ID,
                    state = R16DownloadJobState.PUBLISHING,
                    bytesTransferred = CONTENT_LENGTH,
                    expectedBytes = CONTENT_LENGTH,
                    failureKind = null,
                    retryAfter = null,
                    updatedAt = Instant.ofEpochMilli(6),
                )
            )
        assertEquals(
            R16OfflineRejection.INVALID_STATE,
            (publishing as R16OfflineMutationResult.Rejected).reason,
        )
        assertEquals(R16DownloadJobState.VERIFYING, checkNotNull(repository.load(JOB_ID)).state)

        val failed =
            applied(
                repository.updateProgress(
                    R16DownloadProgress(
                        jobId = JOB_ID,
                        state = R16DownloadJobState.FAILED_RETRYABLE,
                        bytesTransferred = CONTENT_LENGTH,
                        expectedBytes = CONTENT_LENGTH,
                        failureKind = "NETWORK",
                        retryAfter = Instant.ofEpochMilli(8),
                        updatedAt = Instant.ofEpochMilli(9),
                    )
                )
            )
        assertEquals(R16DownloadJobState.FAILED_RETRYABLE, failed.job.state)

        val restart =
            repository.updateProgress(
                R16DownloadProgress(
                    jobId = JOB_ID,
                    state = R16DownloadJobState.QUEUED,
                    bytesTransferred = 0,
                    expectedBytes = CONTENT_LENGTH,
                    failureKind = null,
                    retryAfter = null,
                    updatedAt = Instant.ofEpochMilli(10),
                )
            )
        assertEquals(
            R16OfflineRejection.INVALID_STATE,
            (restart as R16OfflineMutationResult.Rejected).reason,
        )
        assertEquals(
            R16DownloadJobState.FAILED_RETRYABLE,
            checkNotNull(repository.load(JOB_ID)).state,
        )
    }

    @Test
    fun `retry reset clears transfer residue and preserves requested expected length`() =
        runBlocking {
            insertRecording()
            applied(
                repository.request(
                    R16DownloadRequest(
                        jobId = JOB_ID,
                        recordingId = RECORDING_ID,
                        requestedSourceReferenceId = VALID_SOURCE_ID,
                        requestedMediaVariant = "AAC",
                        destinationIdentity = "downloads:primary",
                        expectedBytes = CONTENT_LENGTH,
                        pendingLocation = "content://downloads/pending",
                        createdAt = Instant.ofEpochMilli(1),
                    )
                )
            )
            applied(
                repository.updateProgress(
                    R16DownloadProgress(
                        jobId = JOB_ID,
                        state = R16DownloadJobState.FAILED_RETRYABLE,
                        bytesTransferred = 17,
                        expectedBytes = CONTENT_LENGTH,
                        failureKind = "NETWORK",
                        retryAfter = Instant.ofEpochMilli(8),
                        updatedAt = Instant.ofEpochMilli(9),
                    )
                )
            )
            val command = retryReset()

            val mismatch = repository.resetForRetry(command.copy(destinationIdentity = "other"))
            assertEquals(
                R16OfflineRejection.IDENTITY_MISMATCH,
                (mismatch as R16OfflineMutationResult.Rejected).reason,
            )

            val reset = applied(repository.resetForRetry(command))
            assertTrue(reset.changed)
            assertEquals(R16DownloadJobState.QUEUED, reset.job.state)
            assertEquals(0, reset.job.bytesTransferred)
            assertEquals(CONTENT_LENGTH, reset.job.expectedBytes)
            assertNull(reset.job.failureKind)
            assertNull(reset.job.retryAfter)
            assertNull(reset.job.pendingLocation)

            val repeated = repository.resetForRetry(command)
            assertEquals(
                R16OfflineRejection.INVALID_STATE,
                (repeated as R16OfflineMutationResult.Rejected).reason,
            )
        }

    @Test
    fun `worker operations reject wrong state`() = runBlocking {
        insertRecording()
        applied(
            repository.request(
                R16DownloadRequest(
                    jobId = JOB_ID,
                    recordingId = RECORDING_ID,
                    requestedSourceReferenceId = VALID_SOURCE_ID,
                    requestedMediaVariant = "AAC",
                    destinationIdentity = "downloads:primary",
                    expectedBytes = CONTENT_LENGTH,
                    pendingLocation = "content://downloads/pending",
                    createdAt = Instant.ofEpochMilli(1),
                )
            )
        )

        val begin = repository.beginPublishing(publishingStart())
        assertEquals(
            R16OfflineRejection.INVALID_STATE,
            (begin as R16OfflineMutationResult.Rejected).reason,
        )
        val reset = repository.resetForRetry(retryReset())
        assertEquals(
            R16OfflineRejection.INVALID_STATE,
            (reset as R16OfflineMutationResult.Rejected).reason,
        )
    }

    @Test
    fun `new requests require trimmed non blank stable identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            R16DownloadRequest(
                jobId = JOB_ID,
                recordingId = RECORDING_ID,
                requestedSourceReferenceId = VALID_SOURCE_ID,
                requestedMediaVariant = " AAC",
                destinationIdentity = "downloads:primary",
                expectedBytes = CONTENT_LENGTH,
                pendingLocation = null,
                createdAt = Instant.ofEpochMilli(1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            R16DownloadRequest(
                jobId = JOB_ID,
                recordingId = RECORDING_ID,
                requestedSourceReferenceId = VALID_SOURCE_ID,
                requestedMediaVariant = "AAC",
                destinationIdentity = " ",
                expectedBytes = CONTENT_LENGTH,
                pendingLocation = null,
                createdAt = Instant.ofEpochMilli(1),
            )
        }
    }

    @Test
    fun `publication requires the job destination identity and rejects legacy null`() =
        runBlocking {
            insertRecording()
            requestAndEnterPublishing()

            val mismatch =
                repository.publishVerified(publication(destinationIdentity = "downloads:secondary"))
            assertEquals(
                R16OfflineRejection.IDENTITY_MISMATCH,
                (mismatch as R16OfflineMutationResult.Rejected).reason,
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE download_job SET destination_identity = NULL WHERE job_id = ?",
                arrayOf<Any?>(JOB_ID),
            )
            val legacy = repository.publishVerified(publication())
            assertEquals(
                R16OfflineRejection.IDENTITY_MISMATCH,
                (legacy as R16OfflineMutationResult.Rejected).reason,
            )
        }

    @Test
    fun `migrated snapshot may still expose a null source identity`() = runBlocking {
        insertRecording()
        requestAndEnterVerifying()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE download_job SET requested_source_reference_id = NULL WHERE job_id = ?",
            arrayOf<Any?>(JOB_ID),
        )

        assertNull(checkNotNull(repository.load(JOB_ID)).requestedSourceReferenceId)
        val retain = repository.retainPendingCleanupEvidence(pendingCleanupEvidence())
        assertEquals(
            R16OfflineRejection.IDENTITY_MISMATCH,
            (retain as R16OfflineMutationResult.Rejected).reason,
        )
    }

    @Test
    fun `all externally constructed offline commands reject untrimmed job IDs`() {
        assertThrows(IllegalArgumentException::class.java) {
            R16DownloadRequest(
                jobId = " $JOB_ID",
                recordingId = RECORDING_ID,
                requestedSourceReferenceId = VALID_SOURCE_ID,
                requestedMediaVariant = "AAC",
                destinationIdentity = "downloads:primary",
                expectedBytes = CONTENT_LENGTH,
                pendingLocation = null,
                createdAt = Instant.ofEpochMilli(1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            R16DownloadProgress(
                jobId = " $JOB_ID",
                state = R16DownloadJobState.RESOLVING,
                bytesTransferred = 0,
                expectedBytes = null,
                failureKind = null,
                retryAfter = null,
                updatedAt = Instant.ofEpochMilli(1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            R16VerifiedDownloadPublication(
                jobId = " $JOB_ID",
                destinationIdentity = "downloads:primary",
                asset = publication().asset,
                locationType = "CONTENT_URI",
                documentId = null,
                mediaStoreId = null,
                displayName = null,
                container = null,
                normalizedPathToken = null,
                lastModifiedAt = null,
                evidence = COMPLETE_EVIDENCE,
                publishedAt = Instant.ofEpochMilli(1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            R16ManagedDownloadRemoval(
                jobId = " $JOB_ID",
                recordingId = RECORDING_ID,
                assetId = ASSET_ID,
                updatedAt = Instant.ofEpochMilli(1),
            )
        }
    }

    private suspend fun requestAndEnterPublishing(jobId: String = JOB_ID) {
        requestAndEnterVerifying(jobId)
        assertTrue(
            repository.beginPublishing(publishingStart(jobId)) is R16OfflineMutationResult.Applied
        )
    }

    private suspend fun requestAndEnterVerifying(jobId: String = JOB_ID) {
        assertTrue(
            repository.request(
                R16DownloadRequest(
                    jobId = jobId,
                    recordingId = RECORDING_ID,
                    requestedSourceReferenceId = VALID_SOURCE_ID,
                    requestedMediaVariant = "AAC",
                    destinationIdentity = "downloads:primary",
                    expectedBytes = CONTENT_LENGTH,
                    pendingLocation = "content://downloads/pending",
                    createdAt = Instant.ofEpochMilli(1),
                )
            ) is R16OfflineMutationResult.Applied
        )
        listOf(
                R16DownloadJobState.RESOLVING to 0L,
                R16DownloadJobState.QUEUED to 0L,
                R16DownloadJobState.TRANSFERRING to CONTENT_LENGTH,
                R16DownloadJobState.VERIFYING to CONTENT_LENGTH,
            )
            .forEachIndexed { index, (state, bytesTransferred) ->
                assertTrue(
                    repository.updateProgress(
                        R16DownloadProgress(
                            jobId = jobId,
                            state = state,
                            bytesTransferred = bytesTransferred,
                            expectedBytes = CONTENT_LENGTH,
                            failureKind = null,
                            retryAfter = null,
                            updatedAt = Instant.ofEpochMilli(2L + index),
                        )
                    ) is R16OfflineMutationResult.Applied
                )
            }
    }

    private fun publishingStart(
        jobId: String = JOB_ID,
        pendingLocation: String = "content://downloads/final",
    ) =
        R16DownloadPublishingStart(
            jobId = jobId,
            recordingId = RECORDING_ID,
            requestedSourceReferenceId = VALID_SOURCE_ID,
            requestedMediaVariant = "AAC",
            destinationIdentity = "downloads:primary",
            pendingLocation = pendingLocation,
            updatedAt = Instant.ofEpochMilli(7),
        )

    private fun retryReset(jobId: String = JOB_ID) =
        R16DownloadRetryReset(
            jobId = jobId,
            recordingId = RECORDING_ID,
            requestedSourceReferenceId = VALID_SOURCE_ID,
            requestedMediaVariant = "AAC",
            destinationIdentity = "downloads:primary",
            updatedAt = Instant.ofEpochMilli(10),
        )

    private fun pendingCleanupEvidence(
        jobId: String = JOB_ID,
        pendingLocation: String = "content://downloads/orphan",
    ) =
        R16PendingCleanupEvidence(
            jobId = jobId,
            recordingId = RECORDING_ID,
            requestedSourceReferenceId = VALID_SOURCE_ID,
            requestedMediaVariant = "AAC",
            destinationIdentity = "downloads:primary",
            pendingLocation = pendingLocation,
            updatedAt = Instant.ofEpochMilli(11),
        )

    private fun publication(
        jobId: String = JOB_ID,
        destinationIdentity: String = "downloads:primary",
        evidence: R16DownloadVerificationEvidence = COMPLETE_EVIDENCE,
    ) =
        R16VerifiedDownloadPublication(
            jobId = jobId,
            destinationIdentity = destinationIdentity,
            asset =
                MediaAsset(
                    id = ASSET_ID,
                    recordingId = RECORDING_ID,
                    sourceReferenceId = VALID_SOURCE_ID,
                    kind = MediaAssetKind.SHIPPY_DOWNLOAD,
                    location = AssetLocation("content://downloads/asset-1"),
                    state = AssetState.AVAILABLE,
                    technical =
                        AudioTechnicalMetadata(
                            mimeType = "audio/flac",
                            codec = "flac",
                            bitrateBps = 900_000,
                            sampleRateHz = 48_000,
                            channelCount = 2,
                            contentLength = CONTENT_LENGTH,
                        ),
                    checksum = ContentChecksum("SHA-256", "checksum-1"),
                    fingerprint = null,
                    lastVerifiedAt = Instant.ofEpochMilli(3),
                ),
            locationType = "CONTENT_URI",
            documentId = "document-1",
            mediaStoreId = null,
            displayName = "Track.flac",
            container = "flac",
            normalizedPathToken = "primary/Music/Track.flac",
            lastModifiedAt = Instant.ofEpochMilli(3),
            evidence = evidence,
            publishedAt = Instant.ofEpochMilli(4),
        )

    private fun removalCommand(
        jobId: String = JOB_ID,
        destinationIdentity: String? = "downloads:primary",
    ) =
        R16ManagedDownloadRemoval(
            jobId = jobId,
            recordingId = RECORDING_ID,
            assetId = ASSET_ID,
            updatedAt = Instant.ofEpochMilli(5),
            destinationIdentity = destinationIdentity,
        )

    private fun insertRecording() {
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO recording (
                recording_id, canonical_title, duration_ms, version_kind, version_label,
                explicitness, preferred_release_id, preferred_artwork_id, retention_kind,
                retained_until_epoch_ms, created_at_epoch_ms, updated_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
                .trimIndent(),
            arrayOf<Any?>(
                RECORDING_ID.value,
                "Track",
                180_000L,
                "ORIGINAL",
                null,
                "UNKNOWN",
                null,
                null,
                "DURABLE",
                null,
                1L,
                1L,
            ),
        )
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO source_reference (
                source_reference_id, recording_id, provider_id, source_kind, item_type,
                source_item_id, original_url, availability_state,
                availability_checked_at_epoch_ms, availability_expires_at_epoch_ms,
                failure_kind, failure_retryable, identity_status, raw_metadata_observation_id,
                created_at_epoch_ms, updated_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
                .trimIndent(),
            arrayOf<Any?>(
                VALID_SOURCE_ID.value,
                RECORDING_ID.value,
                "test-provider",
                "SHIPPY_DOWNLOAD",
                "RECORDING",
                "test-item",
                null,
                "AVAILABLE",
                1L,
                null,
                null,
                null,
                "UNRESOLVED",
                "test-observation",
                1L,
                1L,
            ),
        )
    }

    private fun indexedRecordingIds(): List<String> =
        database.openHelper.readableDatabase
            .query("SELECT recording_id FROM library_membership_index ORDER BY recording_id")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun applied(result: R16OfflineMutationResult): R16OfflineMutationResult.Applied =
        result as? R16OfflineMutationResult.Applied ?: error("Expected applied result, got $result")

    private companion object {
        val RECORDING_ID = RecordingId("00000000-0000-0000-0000-000000000001")
        val ASSET_ID = MediaAssetId("00000000-0000-0000-0000-000000000002")
        val VALID_SOURCE_ID = SourceReferenceId("00000000-0000-0000-0000-000000000006")
        val MISSING_SOURCE_ID = SourceReferenceId("00000000-0000-0000-0000-000000000005")
        val PLAYLIST_ID =
            app.shippy.core.identity.PlaylistId("00000000-0000-0000-0000-000000000003")
        val PLAYLIST_ENTRY_ID =
            app.shippy.core.identity.PlaylistEntryId("00000000-0000-0000-0000-000000000004")
        const val JOB_ID = "download-job-1"
        const val SECOND_JOB_ID = "download-job-2"
        const val CONTENT_LENGTH = 42L
        val COMPLETE_EVIDENCE =
            R16DownloadVerificationEvidence(
                bytesComplete = true,
                finalLocationExists = true,
                accessRetained = true,
                media3Readable = true,
            )
    }
}
