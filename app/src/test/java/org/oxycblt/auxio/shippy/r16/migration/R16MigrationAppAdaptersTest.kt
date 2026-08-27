/*
 * Copyright (c) 2026 Auxio Project
 * R16MigrationAppAdaptersTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.migration

import app.shippy.data.migration.precutover.R16MigrationAssetKind
import app.shippy.data.migration.precutover.R16MigrationAssetVerificationRequest
import app.shippy.data.migration.precutover.R16MigrationPreCutoverFolderCheck
import app.shippy.data.migration.precutover.R16MigrationPreCutoverFolderInspection
import app.shippy.data.migration.precutover.R16MigrationVerifiedAsset
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.music.locations.LocationMode
import org.oxycblt.auxio.shippy.download.DownloadDestination

class R16MigrationAppAdaptersTest {
    private val temporaryDirectories = mutableListOf<File>()

    @After
    fun cleanUp() {
        temporaryDirectories.forEach(File::deleteRecursively)
    }

    @Test
    fun `storage paths are stable private and create only snapshot directory`() {
        val root = temporaryDirectory()
        val privateRoot = File(root, "no-backup")
        val legacy = File(root, "databases/shippy.db")
        val adapter = R16MigrationStorageAdapter(privateRoot, legacy)

        adapter.storage()
        adapter.storage()

        assertEquals(adapter.rootDirectoryPath, File(root, "no-backup"))
        assertEquals(adapter.legacyDatabasePath, legacy)
        assertEquals(adapter.bootstrapPath, File(privateRoot, "bootstrap-v1.json"))
        assertEquals(adapter.snapshotDirectoryPath, File(privateRoot, "legacy-v10-snapshot"))
        assertEquals(adapter.recoveryArchivePath, File(privateRoot, "shippy-r16-recovery-v1.zip"))
        assertTrue(privateRoot.isDirectory)
        assertTrue(File(privateRoot, "legacy-v10-snapshot").isDirectory)
        assertFalse(File(privateRoot, "bootstrap-v1.json").exists())
        assertFalse(File(privateRoot, "shippy-r16-recovery-v1.zip").exists())
        assertFalse(adapter.migrationAttemptExists())
        check(adapter.bootstrapPath.createNewFile())
        assertTrue(adapter.migrationAttemptExists())
        check(adapter.bootstrapPath.delete())
        check(adapter.bootstrapPath.mkdirs())
        assertTrue(adapter.migrationAttemptExists())
    }

    @Test
    fun `folder inspection reports readable configured roots`() = runBlocking {
        val source = "content://music/tree/readable"
        val destination = DownloadDestination("content://downloads/tree/ready", "Downloads")
        val inspection =
            folderAdapter(
                    sourceUris = listOf(source),
                    destination = destination,
                    probeResults =
                        mapOf(
                            source to probeResult(),
                            destination.treeUri to probeResult(writable = true),
                        ),
                )
                .inspect()

        val checks = checks(inspection)
        assertTrue(checks.all { it.exists && it.readable })
        assertTrue(checks.single { it.label == "download-destination" }.writable)
        assertTrue(warnings(inspection).isEmpty())
    }

    @Test
    fun `folder inspection reports missing and revoked roots without exposing uris`() =
        runBlocking {
            val revoked = "content://music/tree/revoked"
            val destination = DownloadDestination("content://downloads/tree/missing", "Downloads")
            val inspection =
                folderAdapter(
                        sourceUris = listOf(revoked),
                        destination = destination,
                        probeResults =
                            mapOf(
                                revoked to
                                    probeResult(exists = true, readable = false, grant = false),
                                destination.treeUri to probeResult(exists = false, readable = false),
                            ),
                    )
                    .inspect()

            assertTrue(warnings(inspection).contains("music_saf_source_permission_revoked"))
            assertTrue(warnings(inspection).contains("download_destination_missing"))
            assertTrue(checks(inspection).none { it.label.contains("content://") })
        }

    @Test
    fun `download root with only read grant is not reported writable`() = runBlocking {
        val destination = DownloadDestination("content://downloads/tree/read-only", "Downloads")
        val inspection =
            folderAdapter(
                    sourceUris = listOf("content://music/tree/readable"),
                    destination = destination,
                    probeResults =
                        mapOf(
                            "content://music/tree/readable" to probeResult(),
                            destination.treeUri to
                                probeResult(writable = false, grant = true, writeGrant = false),
                        ),
                )
                .inspect()

        assertFalse(checks(inspection).single { it.label == "download-destination" }.writable)
        assertTrue(warnings(inspection).contains("download_destination_not_writable"))
    }

    @Test
    fun `folder inspection reports empty saf source and bounds warning evidence`() = runBlocking {
        val emptyInspection =
            folderAdapter(sourceUris = emptyList(), destination = null, probeResults = emptyMap())
                .inspect()
        assertTrue(warnings(emptyInspection).contains("music_saf_source_missing"))
        assertTrue(warnings(emptyInspection).contains("download_destination_not_selected"))

        val manySources = (0 until 100).map { "content://music/tree/$it" }
        val boundedInspection =
            folderAdapter(
                    sourceUris = manySources,
                    destination = null,
                    probeResults = manySources.associateWith { probeResult() },
                )
                .inspect()

        assertTrue(checks(boundedInspection).size <= 64)
        assertTrue(warnings(boundedInspection).size <= 64)
        assertTrue(warnings(boundedInspection).contains("music_saf_source_truncated"))
    }

    @Test
    fun `asset verifier returns observed length and evidence for readable asset`() = runBlocking {
        val locator = "content://media/external/audio/media/42"
        val expected = verifiedAsset(locator, length = 321, mediaStoreId = 42L)
        val verifier =
            R16MigrationAssetVerifierAdapter(
                R16MigrationAssetProbe { candidateLocator, _ ->
                    expected.takeIf { candidateLocator == locator }
                }
            )

        val actual =
            verifier.verify(
                R16MigrationAssetVerificationRequest(
                    kind = R16MigrationAssetKind.CANDIDATE,
                    locator = locator,
                    expectedLength = 999,
                    mimeType = "audio/mpeg",
                )
            )

        assertEquals(321L, actual?.contentLength)
        assertEquals(42L, actual?.mediaStoreId)
        assertNull(actual?.contentChecksum)
    }

    @Test
    fun `asset verifier rejects missing and revoked evidence`() = runBlocking {
        val verifier = R16MigrationAssetVerifierAdapter(R16MigrationAssetProbe { _, _ -> null })

        assertNull(
            verifier.verify(
                R16MigrationAssetVerificationRequest(
                    kind = R16MigrationAssetKind.CANDIDATE,
                    locator = "content://music/missing",
                    expectedLength = 1,
                    mimeType = null,
                )
            )
        )
        assertNull(
            verifier.verify(
                R16MigrationAssetVerificationRequest(
                    kind = R16MigrationAssetKind.CANDIDATE,
                    locator = "content://music/revoked",
                    expectedLength = null,
                    mimeType = null,
                )
            )
        )
    }

    @Test
    fun `download verifier accepts finalized artifact kind and not pending locator`() =
        runBlocking {
            val finalLocator = "content://downloads/final.mp3"
            val pendingLocator = "content://downloads/pending.mp3"
            val kinds = mutableListOf<R16MigrationAssetKind>()
            val verifier =
                R16MigrationAssetVerifierAdapter(
                    R16MigrationAssetProbe { candidateLocator, kind ->
                        kinds += kind
                        verifiedAsset(candidateLocator, length = 7).takeIf {
                            kind == R16MigrationAssetKind.DOWNLOAD_ARTIFACT &&
                                candidateLocator == finalLocator
                        }
                    }
                )

            val finalAsset =
                verifier.verify(
                    R16MigrationAssetVerificationRequest(
                        kind = R16MigrationAssetKind.DOWNLOAD_ARTIFACT,
                        locator = finalLocator,
                        expectedLength = 7,
                        mimeType = "audio/mpeg",
                    )
                )
            val pendingAsset =
                verifier.verify(
                    R16MigrationAssetVerificationRequest(
                        kind = R16MigrationAssetKind.DOWNLOAD_ARTIFACT,
                        locator = pendingLocator,
                        expectedLength = 7,
                        mimeType = "audio/mpeg",
                    )
                )

            assertEquals(finalLocator, finalAsset?.location)
            assertNull(pendingAsset)
            assertEquals(
                listOf(
                    R16MigrationAssetKind.DOWNLOAD_ARTIFACT,
                    R16MigrationAssetKind.DOWNLOAD_ARTIFACT,
                ),
                kinds,
            )
        }

    private fun folderAdapter(
        sourceUris: List<String>,
        destination: DownloadDestination?,
        probeResults: Map<String, R16MigrationFolderProbeResult>,
    ): R16MigrationFolderInspectionAdapter =
        R16MigrationFolderInspectionAdapter(
            locationMode = { LocationMode.SAF },
            safSourceUris = { sourceUris },
            downloadDestination = { destination },
            probe = R16MigrationFolderProbe { uri -> probeResults[uri] ?: probeResult() },
        )

    private fun probeResult(
        exists: Boolean = true,
        readable: Boolean = true,
        writable: Boolean = false,
        grant: Boolean = true,
        writeGrant: Boolean = grant,
    ) =
        R16MigrationFolderProbeResult(
            exists = exists,
            readable = readable,
            writable = writable,
            persistedReadGrant = grant,
            persistedWriteGrant = writeGrant,
        )

    private fun verifiedAsset(locator: String, length: Long, mediaStoreId: Long? = null) =
        R16MigrationVerifiedAsset(
            locationType = "CONTENT_URI",
            location = locator,
            mediaStoreId = mediaStoreId,
            contentLength = length,
        )

    @Suppress("UNCHECKED_CAST")
    private fun checks(
        inspection: R16MigrationPreCutoverFolderInspection
    ): List<R16MigrationPreCutoverFolderCheck> =
        inspection.javaClass.declaredMethods
            .first { it.name.startsWith("getChecks") }
            .apply { isAccessible = true }
            .invoke(inspection) as List<R16MigrationPreCutoverFolderCheck>

    @Suppress("UNCHECKED_CAST")
    private fun warnings(inspection: R16MigrationPreCutoverFolderInspection): List<String> =
        inspection.javaClass.declaredMethods
            .first { it.name.startsWith("getWarningCodes") }
            .apply { isAccessible = true }
            .invoke(inspection) as List<String>

    private fun temporaryDirectory(): File =
        File(System.getProperty("java.io.tmpdir"), "r16-app-adapters-${UUID.randomUUID()}").also {
            check(it.mkdirs())
            temporaryDirectories += it
        }
}
