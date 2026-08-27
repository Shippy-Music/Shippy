/*
 * Copyright (c) 2026 Auxio Project
 * R16MigrationPreCutoverCompositionTest.kt is part of Auxio.
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
package app.shippy.data.migration.precutover

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.data.backup.R16BackupCoverageCodec
import app.shippy.data.backup.R16BackupExportReport
import app.shippy.data.backup.R16PortableSettingsSnapshot
import app.shippy.data.backup.R16SanitizedLastFmConfig
import app.shippy.data.backup.ShippyBackupSection
import app.shippy.data.backup.ShippyBackupV1
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.MigrationAuditEntity
import app.shippy.data.migration.LegacyAssetVerifier
import app.shippy.data.migration.LegacyDownloadArtifactVerifier
import app.shippy.data.migration.LegacyImportPhase
import app.shippy.data.migration.MigrationExpectedCountDelta
import app.shippy.data.migration.MigrationExpectedCountEvidence
import app.shippy.data.migration.R16MigrationBootstrapStore
import app.shippy.data.migration.orchestration.R16MigrationPhaseContext
import app.shippy.data.migration.orchestration.R16MigrationPhaseHandler
import app.shippy.data.migration.pipeline.R16MigrationCallbackPageResult
import app.shippy.data.migration.pipeline.R16MusikrDevicePlaylistPageRequest
import app.shippy.data.migration.pipeline.R16MusikrLocalReindexPageRequest
import app.shippy.data.migration.pipeline.R16MusikrMigrationBridge
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16MigrationPreCutoverCompositionTest {
    private lateinit var root: File
    private lateinit var legacyDatabase: File
    private lateinit var database: ShippyR16Database

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "shippy-precutover-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        legacyDatabase = File(root, "legacy-v10.db")
        createLegacyDatabase()
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
        root.deleteRecursively()
    }

    @Test
    fun `composition returns exactly M0 through M13 in order`() {
        composition().use { composition ->
            assertEquals(
                LegacyImportPhase.entries.dropLast(1),
                composition.handlers().map(R16MigrationPhaseHandler::phase),
            )
            assertFalse(composition.handlers().any { it.phase == LegacyImportPhase.CUTOVER })
        }
    }

    @Test
    fun `folder usability only requires writable permission for write-purpose checks`() {
        val readOnlyCheck =
            R16MigrationFolderCheck(
                label = "legacy-db",
                exists = true,
                readable = true,
                writable = false,
                required = true,
            )
        assertTrue(readOnlyCheck.usable)
        assertFalse(readOnlyCheck.copy(requiresWritable = true).usable)
        assertTrue(readOnlyCheck.copy(writable = true, requiresWritable = true).usable)
    }

    @Test
    fun `M0 captures evidence creates one audit and reuses it on retry`() = runBlocking {
        composition().use { composition ->
            val handler = composition.handlers().single { it.phase == LegacyImportPhase.PREFLIGHT }
            val first = handler.run(context(LegacyImportPhase.PREFLIGHT))
            val firstAudit = checkNotNull(database.migrationAuditDao().get(MIGRATION_ID))
            val second = handler.run(context(LegacyImportPhase.PREFLIGHT))
            val secondAudit = checkNotNull(database.migrationAuditDao().get(MIGRATION_ID))

            assertTrue(first.backupSatisfied == true)
            assertTrue(first.auditCommitted)
            assertEquals(first.legacyDatabaseSha256, second.legacyDatabaseSha256)
            assertEquals(firstAudit.sourceCountsJson, secondAudit.sourceCountsJson)
            assertTrue(firstAudit.sourceCountsJson.contains("\"rowCounts\""))
            assertTrue(firstAudit.sourceCountsJson.contains("\"folderChecks\""))
            assertTrue(firstAudit.sourceCountsJson.contains("\"requiresWritable\""))
            assertEquals("audit=$secondAudit", 1, activeAuditCount())
            assertEquals("PREPARING", secondAudit.status)
        }
    }

    @Test
    fun `M1 reads the M0 snapshot while M13 rejects live checksum drift`() = runBlocking {
        composition().use { composition ->
            val m0 = composition.handlers().single { it.phase == LegacyImportPhase.PREFLIGHT }
            val m0Result = m0.run(context(LegacyImportPhase.PREFLIGHT))
            val snapshotSha = checkNotNull(m0Result.legacyDatabaseSha256)

            SQLiteDatabase.openOrCreateDatabase(legacyDatabase, null).use { live ->
                live.execSQL(
                    "INSERT INTO canonical_track " +
                        "(trackId, realm, title, artists, album, durationMs, versionLabel, " +
                        "explicit, live, remix, artwork) VALUES " +
                        "('track-live', 'PROVIDER', 'Live only', 'Artist', NULL, NULL, NULL, " +
                        "0, 0, 0, NULL)"
                )
            }

            val m1 =
                composition.handlers().single { it.phase == LegacyImportPhase.CANONICAL_TRACKS }
            val m1Result =
                m1.run(
                    context(
                        LegacyImportPhase.CANONICAL_TRACKS,
                        completed = setOf(LegacyImportPhase.PREFLIGHT),
                        legacyDatabaseSha256 = snapshotSha,
                    )
                )
            assertTrue(m1Result.complete)
            assertEquals(1L, tableCount("recording"))

            val m13 = composition.handlers().single { it.phase == LegacyImportPhase.VERIFY }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    m13.run(
                        context(
                            LegacyImportPhase.VERIFY,
                            completed = importPhases(),
                            legacyDatabaseSha256 = snapshotSha,
                        )
                    )
                }
            }
            assertEquals(
                "FAILED_RECOVERABLE",
                database.migrationAuditDao().get(MIGRATION_ID)?.status,
            )
        }
    }

    @Test
    fun `M13 verifies and publishes checksum verified recovery archive`() = runBlocking {
        startAudit()
        composition(archiveExporter = coverageExporter(true, true)).use { composition ->
            val handler = composition.handlers().single { it.phase == LegacyImportPhase.VERIFY }
            val result = handler.run(context(LegacyImportPhase.VERIFY, completed = importPhases()))
            val archive = File(root, ARCHIVE_NAME)
            val audit = checkNotNull(database.migrationAuditDao().get(MIGRATION_ID))

            assertTrue(result.verificationPassed)
            assertTrue(archive.isFile)
            FileInputStream(archive).use { input -> ShippyBackupV1.stage(input).close() }
            assertEquals("READY_TO_SWITCH", audit.status)
            val archiveEvidence =
                JSONObject(audit.targetCountsJson!!).getJSONObject("m13RecoveryArchive")
            assertTrue(archiveEvidence.getBoolean("portableAppSettingsIncluded"))
            assertTrue(archiveEvidence.getBoolean("sanitizedLastFmConfigIncluded"))
        }
    }

    @Test
    fun `M13 backup failure downgrades ready audit and leaves no archive`() = runBlocking {
        startAudit()
        val failingExporter = R16MigrationArchiveExporter { _, _, _ ->
            error("injected archive failure")
        }
        composition(archiveExporter = failingExporter).use { composition ->
            val handler = composition.handlers().single { it.phase == LegacyImportPhase.VERIFY }

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    handler.run(context(LegacyImportPhase.VERIFY, completed = importPhases()))
                }
            }

            val audit = checkNotNull(database.migrationAuditDao().get(MIGRATION_ID))
            assertEquals("FAILED_RECOVERABLE", audit.status)
            assertTrue(audit.warningsJson.contains("M13 BACKUP_FAILED"))
            assertFalse(File(root, ARCHIVE_NAME).exists())
        }
    }

    @Test
    fun `M13 blocks an archive without portable settings and Last FM coverage`() = runBlocking {
        startAudit()
        composition(archiveExporter = coverageExporter(false, false)).use { composition ->
            val handler = composition.handlers().single { it.phase == LegacyImportPhase.VERIFY }

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    handler.run(context(LegacyImportPhase.VERIFY, completed = importPhases()))
                }
            }

            assertEquals(
                "FAILED_RECOVERABLE",
                database.migrationAuditDao().get(MIGRATION_ID)?.status,
            )
            assertFalse(File(root, ARCHIVE_NAME).exists())
        }
    }

    @Test
    fun `M13 retry reuses an equal prior archive without changing its checksum`() = runBlocking {
        startAudit()
        composition(archiveExporter = coverageExporter(true, true)).use { composition ->
            val handler = composition.handlers().single { it.phase == LegacyImportPhase.VERIFY }
            val context = context(LegacyImportPhase.VERIFY, completed = importPhases())
            handler.run(context)
            val archive = File(root, ARCHIVE_NAME)
            val firstBytes = archive.readBytes()
            handler.run(context)

            assertEquals(firstBytes.toList(), archive.readBytes().toList())
            assertEquals("READY_TO_SWITCH", database.migrationAuditDao().get(MIGRATION_ID)?.status)
        }
    }

    private fun composition(
        archiveExporter: R16MigrationArchiveExporter? = null
    ): R16MigrationPreCutoverComposition =
        R16MigrationPreCutoverComposition(
            bootstrap = R16MigrationBootstrapStore(File(root, "bootstrap.json")),
            database = database,
            legacyDatabase = legacyDatabase,
            snapshotDirectory = File(root, "snapshots").also { check(it.mkdirs()) },
            recoveryArchive = File(root, ARCHIVE_NAME),
            folderInspectionProvider =
                R16MigrationFolderInspectionProvider {
                    R16MigrationFolderInspection(
                        checks =
                            listOf(
                                R16MigrationFolderCheck(
                                    label = "legacy-db",
                                    exists = true,
                                    readable = true,
                                    writable = false,
                                    required = true,
                                    requiresWritable = false,
                                )
                            )
                    )
                },
            assetVerifier = LegacyAssetVerifier { null },
            artifactVerifier = LegacyDownloadArtifactVerifier { null },
            musikrBridge = TestMusikrBridge,
            importedAtEpochMs = { 100L },
            nowEpochMs = { 100L },
            archiveExporter = archiveExporter,
        )

    private fun coverageExporter(
        portableAppSettingsIncluded: Boolean,
        sanitizedLastFmConfigIncluded: Boolean,
    ): R16MigrationArchiveExporter =
        R16MigrationArchiveExporter { output, createdAtEpochMs, includesHistory ->
            val sections =
                ShippyBackupSection.entries
                    .filter {
                        it.required || (includesHistory && it == ShippyBackupSection.HISTORY)
                    }
                    .associateWith { ByteArray(0) }
                    .toMutableMap()
            if (portableAppSettingsIncluded && sanitizedLastFmConfigIncluded) {
                sections[ShippyBackupSection.PORTABLE_SETTINGS] =
                    R16BackupCoverageCodec.encodePortableSettings(
                        R16PortableSettingsSnapshot(emptyMap())
                    )
                sections[ShippyBackupSection.SANITIZED_LASTFM_CONFIG] =
                    R16BackupCoverageCodec.encodeLastFmConfig(
                        R16SanitizedLastFmConfig(username = null)
                    )
            }
            ShippyBackupV1.write(
                output = output,
                databaseSchemaVersion = ShippyR16Database.SCHEMA_VERSION,
                createdAtEpochMs = createdAtEpochMs,
                includesHistory = includesHistory,
                sections = sections,
                portableAppSettingsIncluded = portableAppSettingsIncluded,
                sanitizedLastFmConfigIncluded = sanitizedLastFmConfigIncluded,
            )
            R16BackupExportReport(
                databaseSchemaVersion = ShippyR16Database.SCHEMA_VERSION,
                createdAtEpochMs = createdAtEpochMs,
                includesHistory = includesHistory,
                rowCounts = emptyMap(),
                sectionByteCounts = sections.mapValues { 0L },
                sectionChecksums = emptyMap(),
                portableAppSettingsIncluded = portableAppSettingsIncluded,
                sanitizedLastFmConfigIncluded = sanitizedLastFmConfigIncluded,
            )
        }

    private fun context(
        phase: LegacyImportPhase,
        completed: Set<LegacyImportPhase> = emptySet(),
        legacyDatabaseSha256: String = liveLegacySha256(),
    ) =
        R16MigrationPhaseContext(
            migrationId = MIGRATION_ID,
            phase = phase,
            lastStableKey = null,
            completedPhases = completed,
            legacyDatabaseSha256 = legacyDatabaseSha256,
            backupSatisfied = true,
            cancellationRequested = { false },
        )

    private suspend fun startAudit() {
        database
            .migrationAuditDao()
            .start(
                MigrationAuditEntity(
                    migrationId = MIGRATION_ID,
                    sourceVersion = 10,
                    targetVersion = ShippyR16Database.SCHEMA_VERSION,
                    startedAtEpochMs = 1,
                    completedAtEpochMs = null,
                    sourceCountsJson = "{}",
                    targetCountsJson = null,
                    warningsJson = "[]",
                    checksum = null,
                    status = "IMPORTING",
                )
            )
        database
            .migrationAuditDao()
            .updateProgress(
                migrationId = MIGRATION_ID,
                targetCountsJson = zeroExpectedEvidence(),
                warningsJson = "[]",
                status = "IMPORTING",
            )
    }

    private fun zeroExpectedEvidence(): String {
        val target = JSONObject()
        listOf(
                LegacyImportPhase.LIBRARY_RELATIONSHIPS,
                LegacyImportPhase.USER_PLAYLISTS,
                LegacyImportPhase.DEVICE_PLAYLISTS,
                LegacyImportPhase.DOWNLOADS,
                LegacyImportPhase.LASTFM_OUTBOX,
                LegacyImportPhase.PLAYBACK_CHECKPOINT,
                LegacyImportPhase.SAVED_PROVIDER_ENTITIES,
            )
            .forEach { phase ->
                MigrationExpectedCountEvidence.recordPage(
                    target = target,
                    phase = phase,
                    pageToken = "test:${phase.code}",
                    delta = MigrationExpectedCountDelta(),
                )
                MigrationExpectedCountEvidence.markPhaseComplete(
                    target = target,
                    phase = phase,
                    allowEmpty = false,
                )
            }
        return target.toString()
    }

    private fun importPhases(): Set<LegacyImportPhase> =
        LegacyImportPhase.entries.filter { it.ordinal < LegacyImportPhase.VERIFY.ordinal }.toSet()

    private fun activeAuditCount(): Int =
        database.openHelper.writableDatabase
            .query(
                "SELECT COUNT(*) FROM migration_audit " +
                    "WHERE source_version IN (10, 11) AND target_version = " +
                    ShippyR16Database.SCHEMA_VERSION +
                    " " +
                    "AND completed_at_epoch_ms IS NULL"
            )
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }

    private fun tableCount(table: String): Long =
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM \"$table\"").use { cursor
            ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun createLegacyDatabase() {
        SQLiteDatabase.openOrCreateDatabase(legacyDatabase, null).use { database ->
            database.version = 10
            LEGACY_TABLES.forEach { table ->
                if (table == "canonical_track") {
                    database.execSQL(
                        """
                        CREATE TABLE canonical_track (
                            trackId TEXT NOT NULL PRIMARY KEY,
                            realm TEXT NOT NULL,
                            title TEXT NOT NULL,
                            artists TEXT NOT NULL,
                            album TEXT,
                            durationMs INTEGER,
                            versionLabel TEXT,
                            explicit INTEGER,
                            live INTEGER NOT NULL,
                            remix INTEGER NOT NULL,
                            artwork TEXT
                        )
                        """
                            .trimIndent()
                    )
                    database.execSQL(
                        "INSERT INTO canonical_track " +
                            "(trackId, realm, title, artists, album, durationMs, versionLabel, " +
                            "explicit, live, remix, artwork) VALUES " +
                            "('track-snapshot', 'PROVIDER', 'Snapshot only', 'Artist', NULL, NULL, NULL, " +
                            "0, 0, 0, NULL)"
                    )
                } else {
                    database.execSQL("CREATE TABLE `$table` (id TEXT)")
                }
            }
        }
    }

    private fun liveLegacySha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(legacyDatabase).use { input ->
            val buffer = ByteArray(8 * 1_024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private companion object {
        const val MIGRATION_ID = "migration-precutover"
        const val ARCHIVE_NAME = "r16-recovery.zip"
        val LEGACY_TABLES =
            listOf(
                "library_relationship",
                "user_playlist",
                "playlist_membership",
                "download_job",
                "download_candidate",
                "lyrics_cache",
                "crew_active_checkpoint",
                "canonical_track",
                "canonical_track_candidate",
                "lastfm_scrobble_outbox",
                "playback_checkpoint",
                "playback_checkpoint_item",
                "saved_provider_entity",
            )
    }
}

private object TestMusikrBridge : R16MusikrMigrationBridge {
    override suspend fun importDevicePlaylistPage(
        request: R16MusikrDevicePlaylistPageRequest
    ): R16MigrationCallbackPageResult =
        R16MigrationCallbackPageResult(complete = true, lastStableKey = null)

    override suspend fun reindexLocalPage(
        request: R16MusikrLocalReindexPageRequest
    ): R16MigrationCallbackPageResult =
        R16MigrationCallbackPageResult(complete = true, lastStableKey = null)
}
