/*
 * Copyright (c) 2026 Auxio Project
 * R16MigrationPreCutoverComposition.kt is part of Auxio.
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

import androidx.room.withTransaction
import app.shippy.data.backup.R16BackupExportReport
import app.shippy.data.backup.R16BackupExporter
import app.shippy.data.backup.R16PortableSettingsProvider
import app.shippy.data.backup.R16SanitizedLastFmConfigProvider
import app.shippy.data.backup.ShippyBackupV1
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.MigrationAuditEntity
import app.shippy.data.migration.LEGACY_SCHEMA_VERSION
import app.shippy.data.migration.LegacyAssetVerifier
import app.shippy.data.migration.LegacyDatabaseReader
import app.shippy.data.migration.LegacyDownloadArtifactVerifier
import app.shippy.data.migration.LegacyImportPhase
import app.shippy.data.migration.MigrationExpectedCountEvidence
import app.shippy.data.migration.MigrationVerifier
import app.shippy.data.migration.R16MigrationBootstrapStore
import app.shippy.data.migration.SUPPORTED_LEGACY_SCHEMA_VERSIONS
import app.shippy.data.migration.orchestration.R16MigrationOrchestrator
import app.shippy.data.migration.orchestration.R16MigrationPhaseContext
import app.shippy.data.migration.orchestration.R16MigrationPhaseHandler
import app.shippy.data.migration.orchestration.R16MigrationPhaseResult
import app.shippy.data.migration.pipeline.R16MigrationPipelineFactory
import app.shippy.data.migration.pipeline.R16MusikrMigrationBridge
import app.shippy.data.migration.preflight.LegacyV10Preflight
import app.shippy.data.migration.preflight.LegacyV10PreflightEvidence
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A bounded, app-supplied inspection of migration-relevant folders and permissions. */
internal data class R16MigrationFolderCheck(
    val label: String,
    val exists: Boolean,
    val readable: Boolean,
    val writable: Boolean,
    val required: Boolean,
    val requiresWritable: Boolean = false,
) {
    init {
        require(label.matches(LABEL_PATTERN)) { "Folder check label is unsafe" }
    }

    val usable: Boolean
        get() = !required || (exists && readable && (!requiresWritable || writable))
}

internal data class R16MigrationFolderInspection(
    val checks: List<R16MigrationFolderCheck>,
    val warningCodes: List<String> = emptyList(),
) {
    init {
        require(checks.isNotEmpty()) { "Migration folder inspection must contain a check" }
        require(checks.size <= MAX_FOLDER_CHECKS) {
            "Migration folder inspection exceeds its bound"
        }
        require(checks.map(R16MigrationFolderCheck::label).distinct().size == checks.size) {
            "Migration folder inspection labels must be unique"
        }
        require(warningCodes.size <= MAX_WARNING_CODES) {
            "Migration folder inspection warnings exceed their bound"
        }
        require(warningCodes.all { it.matches(WARNING_PATTERN) }) {
            "Migration folder inspection warning code is unsafe"
        }
    }

    val passed: Boolean
        get() = checks.all(R16MigrationFolderCheck::usable)
}

internal fun interface R16MigrationFolderInspectionProvider {
    suspend fun inspect(): R16MigrationFolderInspection
}

/** Testable seam around the existing streaming ShippyBackupV1 exporter. */
internal fun interface R16MigrationArchiveExporter {
    fun export(
        output: OutputStream,
        createdAtEpochMs: Long,
        includeHistory: Boolean,
    ): R16BackupExportReport
}

/** Composes exactly M0, the existing M1-M12 pipeline, and M13; M14 is excluded. */
internal class R16MigrationPreCutoverComposition(
    private val bootstrap: R16MigrationBootstrapStore,
    private val database: ShippyR16Database,
    private val legacyDatabase: File,
    private val snapshotDirectory: File,
    private val recoveryArchive: File,
    private val folderInspectionProvider: R16MigrationFolderInspectionProvider,
    private val assetVerifier: LegacyAssetVerifier,
    private val artifactVerifier: LegacyDownloadArtifactVerifier,
    private val musikrBridge: R16MusikrMigrationBridge,
    private val importedAtEpochMs: () -> Long = System::currentTimeMillis,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val pageSize: Int = 500,
    private val includeHistory: Boolean = false,
    private val archiveExporter: R16MigrationArchiveExporter? = null,
    private val portableSettingsProvider: R16PortableSettingsProvider? = null,
    private val sanitizedLastFmConfigProvider: R16SanitizedLastFmConfigProvider? = null,
) : Closeable {
    private val snapshotPipeline =
        R16MigrationSnapshotPipeline(
            snapshotFile = File(snapshotDirectory, LegacyV10Preflight.DEFAULT_SNAPSHOT_FILE_NAME),
            database = database,
            assetVerifier = assetVerifier,
            artifactVerifier = artifactVerifier,
            musikrBridge = musikrBridge,
            importedAtEpochMs = importedAtEpochMs,
            pageSize = pageSize,
        )
    private val m0 =
        R16MigrationM0Handler(
            database = database,
            legacyDatabase = legacyDatabase,
            snapshotDirectory = snapshotDirectory,
            folderInspectionProvider = folderInspectionProvider,
            nowEpochMs = nowEpochMs,
        )
    private val m1ToM12 = snapshotPipeline.handlers()
    private val m13 =
        R16MigrationM13Handler(
            database = database,
            legacyDatabase = legacyDatabase,
            recoveryArchive = recoveryArchive,
            includeHistory = includeHistory,
            nowEpochMs = nowEpochMs,
            archiveExporter =
                archiveExporter
                    ?: R16MigrationArchiveExporter { output, createdAt, history ->
                        R16BackupExporter(
                                database = database,
                                portableSettingsProvider = portableSettingsProvider,
                                sanitizedLastFmConfigProvider = sanitizedLastFmConfigProvider,
                            )
                            .export(output, createdAt, history)
                    },
        )

    init {
        val phases = listOf(m0) + m1ToM12 + m13
        require(phases.map(R16MigrationPhaseHandler::phase) == IMPORT_PHASES) {
            "Pre-cutover composition must contain exactly M0-M13 in order"
        }
    }

    fun handlers(): List<R16MigrationPhaseHandler> = listOf(m0) + m1ToM12 + m13

    fun orchestrator(): R16MigrationOrchestrator =
        R16MigrationOrchestrator(
            bootstrap = bootstrap,
            handlers = handlers(),
            nowEpochMs = nowEpochMs,
        )

    override fun close() {
        snapshotPipeline.close()
    }

    private companion object {
        val IMPORT_PHASES = LegacyImportPhase.entries.dropLast(1)
    }
}

private class R16MigrationSnapshotPipeline(
    private val snapshotFile: File,
    private val database: ShippyR16Database,
    private val assetVerifier: LegacyAssetVerifier,
    private val artifactVerifier: LegacyDownloadArtifactVerifier,
    private val musikrBridge: R16MusikrMigrationBridge,
    private val importedAtEpochMs: () -> Long,
    private val pageSize: Int,
) : Closeable {
    private var snapshotReader: LegacyDatabaseReader? = null
    private var delegates: Map<LegacyImportPhase, R16MigrationPhaseHandler>? = null
    private var snapshotSha: String? = null
    private var closed = false

    fun handlers(): List<R16MigrationPhaseHandler> =
        PIPELINE_PHASES.map { phase ->
            object : R16MigrationPhaseHandler {
                override val phase: LegacyImportPhase = phase

                override suspend fun run(
                    context: R16MigrationPhaseContext
                ): R16MigrationPhaseResult = delegate(phase, context).run(context)
            }
        }

    private fun delegate(
        phase: LegacyImportPhase,
        context: R16MigrationPhaseContext,
    ): R16MigrationPhaseHandler {
        require(!closed) { "Snapshot migration pipeline is closed" }
        require(LegacyImportPhase.PREFLIGHT in context.completedPhases) {
            "M1-M12 cannot run before the verified M0 snapshot"
        }
        val expectedSha =
            checkNotNull(context.legacyDatabaseSha256) {
                "M1-M12 require the M0 legacy database checksum"
            }
        val current = delegates
        if (current != null) {
            check(snapshotSha == expectedSha) {
                "Migration checksum changed while using the M0 snapshot"
            }
            return checkNotNull(current[phase])
        }
        synchronized(this) {
            delegates?.let {
                check(snapshotSha == expectedSha) {
                    "Migration checksum changed while using the M0 snapshot"
                }
                return checkNotNull(it[phase])
            }
            require(snapshotFile.isFile) { "Verified M0 snapshot is missing" }
            requireNoUnsafeSidecars(snapshotFile)
            require(sha256(snapshotFile) == expectedSha) {
                "Verified M0 snapshot checksum does not match migration state"
            }
            val reader = LegacyDatabaseReader.openReadOnly(snapshotFile)
            try {
                require(reader.schemaSnapshot().compatible) {
                    "Verified M0 snapshot schema is incompatible"
                }
                val built =
                    R16MigrationPipelineFactory(
                            legacyReader = reader,
                            database = database,
                            assetVerifier = assetVerifier,
                            artifactVerifier = artifactVerifier,
                            musikrBridge = musikrBridge,
                            importedAtEpochMs = importedAtEpochMs,
                            pageSize = pageSize,
                        )
                        .handlers()
                        .associateBy(R16MigrationPhaseHandler::phase)
                require(built.keys == PIPELINE_PHASES.toSet()) {
                    "Snapshot migration pipeline phases are incomplete"
                }
                snapshotReader = reader
                snapshotSha = expectedSha
                delegates = built
                return checkNotNull(built[phase])
            } catch (failure: Throwable) {
                reader.close()
                throw failure
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        snapshotReader?.close()
        snapshotReader = null
        snapshotSha = null
        delegates = null
    }

    private companion object {
        val PIPELINE_PHASES =
            LegacyImportPhase.entries.filter {
                it != LegacyImportPhase.PREFLIGHT &&
                    it != LegacyImportPhase.VERIFY &&
                    it != LegacyImportPhase.CUTOVER
            }
    }
}

private class R16MigrationM0Handler(
    private val database: ShippyR16Database,
    private val legacyDatabase: File,
    private val snapshotDirectory: File,
    private val folderInspectionProvider: R16MigrationFolderInspectionProvider,
    private val nowEpochMs: () -> Long,
    private val preflight: LegacyV10Preflight = LegacyV10Preflight(),
) : R16MigrationPhaseHandler {
    override val phase: LegacyImportPhase = LegacyImportPhase.PREFLIGHT

    override suspend fun run(context: R16MigrationPhaseContext): R16MigrationPhaseResult {
        require(context.phase == phase) { "M0 handler received ${context.phase.code}" }
        val inspection = folderInspectionProvider.inspect()
        check(inspection.passed) { "Migration folder/permission inspection did not pass" }
        val evidence = preflightEvidence()
        val sourceCountsJson = sourceCountsJson(evidence, inspection)
        ensureSingleActiveAudit(context.migrationId, sourceCountsJson, inspection)
        return R16MigrationPhaseResult(
            complete = true,
            legacyDatabaseSha256 = evidence.sha256,
            backupSatisfied = true,
            auditCommitted = true,
        )
    }

    private fun preflightEvidence(): LegacyV10PreflightEvidence {
        require(snapshotDirectory.isDirectory) { "Migration snapshot directory is missing" }
        val snapshot = File(snapshotDirectory, LegacyV10Preflight.DEFAULT_SNAPSHOT_FILE_NAME)
        val manifest = File(snapshotDirectory, "${snapshot.name}.manifest.json")
        return when {
            !snapshot.exists() && !manifest.exists() ->
                preflight.capture(legacyDatabase, snapshotDirectory)
            snapshot.exists() && manifest.exists() -> {
                requireNoUnsafeSidecars(legacyDatabase)
                val reusable = runCatching { validateReusable(snapshot, manifest) }.getOrNull()
                if (reusable != null) {
                    requireNoUnsafeSidecars(legacyDatabase)
                    reusable
                } else {
                    requireNoUnsafeSidecars(legacyDatabase)
                    deleteOutput(snapshot)
                    deleteOutput(manifest)
                    preflight.capture(legacyDatabase, snapshotDirectory)
                }
            }
            else -> {
                requireNoUnsafeSidecars(legacyDatabase)
                deleteOutput(snapshot)
                deleteOutput(manifest)
                preflight.capture(legacyDatabase, snapshotDirectory)
            }
        }
    }

    private fun validateReusable(snapshot: File, manifestFile: File): LegacyV10PreflightEvidence {
        require(snapshot.isFile && manifestFile.isFile) {
            "Preflight output pair is not regular files"
        }
        require(manifestFile.length() <= MAX_PREFLIGHT_MANIFEST_BYTES) {
            "Preflight manifest exceeds its size limit"
        }
        val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
        require(json.keys().asSequence().toSet() == PREFLIGHT_MANIFEST_KEYS) {
            "Preflight manifest contains unexpected fields"
        }
        require(json.getString("format") == PREFLIGHT_FORMAT) {
            "Preflight manifest format is unsupported"
        }
        require(json.getInt("formatVersion") == PREFLIGHT_FORMAT_VERSION) {
            "Preflight manifest version is unsupported"
        }
        val schemaVersion = json.getInt("databaseSchemaVersion")
        require(schemaVersion in SUPPORTED_LEGACY_SCHEMA_VERSIONS) {
            "Preflight manifest schema version is unsupported"
        }
        require(json.getString("legacyFileName") == legacyDatabase.name) {
            "Preflight manifest source does not match the legacy database"
        }
        require(json.getString("snapshotFileName") == snapshot.name) {
            "Preflight manifest snapshot name does not match its file"
        }
        val byteCount = json.getLong("byteCount")
        val checksum = json.getString("sha256")
        require(byteCount >= 0 && checksum.matches(SHA256_PATTERN)) {
            "Preflight manifest checksum metadata is invalid"
        }
        require(json.getString("integrityCheck") == "ok") {
            "Preflight manifest does not contain a successful integrity check"
        }
        val presentTables =
            json.getJSONArray("presentTables").let { array ->
                List(array.length()) { index -> array.getString(index) }
            }
        require(presentTables == presentTables.distinct().sorted()) {
            "Preflight manifest tables are not canonical"
        }
        val rowCountsJson = json.getJSONObject("rowCounts")
        val rowCounts =
            rowCountsJson.keys().asSequence().toList().sorted().associateWith { table ->
                val count = rowCountsJson.getLong(table)
                require(count >= 0) { "Preflight row count is negative" }
                count
            }
        require(rowCountsJson.keys().asSequence().toList() == rowCounts.keys.sorted()) {
            "Preflight manifest row counts are not canonical"
        }
        require(snapshot.length() == byteCount)
        require(sha256(snapshot) == checksum)
        require(legacyDatabase.length() == byteCount)
        require(sha256(legacyDatabase) == checksum)
        val schema =
            LegacyDatabaseReader.openReadOnly(snapshot).use { reader ->
                reader.schemaSnapshot().also { actual ->
                    require(actual.compatible) { "Reusable preflight snapshot schema is invalid" }
                    require(actual.version == schemaVersion)
                    require(actual.presentTables.toSortedSet().toList() == presentTables)
                    require(actual.rowCounts.toSortedMap() == rowCounts)
                }
            }
        verifyIntegrity(snapshot)
        return LegacyV10PreflightEvidence(
            databaseSchemaVersion = schema.version,
            legacyFileName = legacyDatabase.name,
            snapshotFile = snapshot,
            manifestFile = manifestFile,
            byteCount = byteCount,
            sha256 = checksum,
            rowCounts = rowCounts,
            presentTables = presentTables.toSet(),
        )
    }

    private suspend fun ensureSingleActiveAudit(
        migrationId: String,
        sourceCountsJson: String,
        inspection: R16MigrationFolderInspection,
    ) {
        database.withTransaction {
            val activeIds =
                database.openHelper.writableDatabase
                    .query(
                        "SELECT migration_id FROM migration_audit " +
                            "WHERE source_version IN (10, 11) AND target_version = $R16_MIGRATION_TARGET_VERSION " +
                            "AND completed_at_epoch_ms IS NULL ORDER BY migration_id"
                    )
                    .use { cursor ->
                        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                    }
            check(activeIds.all { it == migrationId }) {
                "Another active R15-to-R16 migration audit already exists"
            }
            val dao = database.migrationAuditDao()
            val existing = dao.get(migrationId)
            if (existing == null) {
                val sourceVersion =
                    JSONObject(sourceCountsJson)
                        .optInt("databaseSchemaVersion", LEGACY_SCHEMA_VERSION)
                dao.start(
                    MigrationAuditEntity(
                        migrationId = migrationId,
                        sourceVersion = sourceVersion,
                        targetVersion = R16_MIGRATION_TARGET_VERSION,
                        startedAtEpochMs =
                            nowEpochMs().also {
                                require(it >= 0) { "Migration audit timestamp cannot be negative" }
                            },
                        completedAtEpochMs = null,
                        sourceCountsJson = sourceCountsJson,
                        targetCountsJson = null,
                        warningsJson = JSONArray(inspection.warningCodes.sorted()).toString(),
                        checksum = null,
                        status = "PREPARING",
                    )
                )
            } else {
                require(existing.completedAtEpochMs == null) {
                    "Migration audit is already completed"
                }
                require(existing.sourceVersion in SUPPORTED_LEGACY_SCHEMA_VERSIONS)
                require(existing.targetVersion == R16_MIGRATION_TARGET_VERSION)
                val existingSource = JSONObject(existing.sourceCountsJson)
                require(
                    existingSource.getString("sha256") ==
                        JSONObject(sourceCountsJson).getString("sha256")
                ) {
                    "Existing migration audit belongs to a different legacy database"
                }
            }
        }
    }

    private fun sourceCountsJson(
        evidence: LegacyV10PreflightEvidence,
        inspection: R16MigrationFolderInspection,
    ): String {
        val counts = JSONObject()
        evidence.rowCounts.toSortedMap().forEach { (table, count) -> counts.put(table, count) }
        val tables = JSONArray()
        evidence.presentTables.toSortedSet().forEach(tables::put)
        val folders = JSONArray()
        inspection.checks.sortedBy(R16MigrationFolderCheck::label).forEach { check ->
            folders.put(
                JSONObject()
                    .put("label", check.label)
                    .put("exists", check.exists)
                    .put("readable", check.readable)
                    .put("writable", check.writable)
                    .put("required", check.required)
                    .put("requiresWritable", check.requiresWritable)
            )
        }
        return JSONObject()
            .put("format", "ShippyM0PreflightEvidence")
            .put("formatVersion", 1)
            .put("databaseSchemaVersion", evidence.databaseSchemaVersion)
            .put("legacyFileName", evidence.legacyFileName)
            .put("snapshotFileName", evidence.snapshotFile.name)
            .put("manifestFileName", evidence.manifestFile.name)
            .put("byteCount", evidence.byteCount)
            .put("sha256", evidence.sha256)
            .put("presentTables", tables)
            .put("rowCounts", counts)
            .put("folderChecks", folders)
            .put("folderWarningCodes", JSONArray(inspection.warningCodes.sorted()))
            .toString()
    }

    private fun deleteOutput(file: File) {
        if (file.exists()) check(file.delete()) { "Unable to remove stale preflight output" }
    }

    private fun verifyIntegrity(file: File) {
        android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY or
                    android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            .use { database ->
                database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                        "Reusable preflight snapshot failed integrity verification"
                    }
                }
            }
    }
}

private class R16MigrationM13Handler(
    private val database: ShippyR16Database,
    private val legacyDatabase: File,
    private val recoveryArchive: File,
    private val includeHistory: Boolean,
    private val nowEpochMs: () -> Long,
    private val archiveExporter: R16MigrationArchiveExporter,
) : R16MigrationPhaseHandler {
    override val phase: LegacyImportPhase = LegacyImportPhase.VERIFY

    override suspend fun run(context: R16MigrationPhaseContext): R16MigrationPhaseResult {
        require(context.phase == phase) { "M13 handler received ${context.phase.code}" }
        try {
            verifyLiveLegacyUnchanged(context.legacyDatabaseSha256)
            val expected =
                MigrationExpectedCountEvidence.read(
                    database.migrationAuditDao().get(context.migrationId)?.targetCountsJson
                )
            val report =
                MigrationVerifier(database)
                    .verify(
                        migrationId = context.migrationId,
                        expected = expected,
                        completedPhases = context.completedPhases,
                    )
            if (!report.passed) {
                return R16MigrationPhaseResult(
                    complete = true,
                    legacyDatabaseSha256 = context.legacyDatabaseSha256,
                    auditCommitted = true,
                    verificationPassed = false,
                )
            }
            val archive = publishVerifiedArchive()
            recordArchiveEvidence(context.migrationId, archive)
            return R16MigrationPhaseResult(
                complete = true,
                legacyDatabaseSha256 = context.legacyDatabaseSha256,
                auditCommitted = true,
                verificationPassed = true,
            )
        } catch (error: CancellationException) {
            withContext(NonCancellable) { markBackupFailure(context.migrationId) }
            throw error
        } catch (error: Exception) {
            markBackupFailure(context.migrationId)
            throw error
        }
    }

    private fun verifyLiveLegacyUnchanged(expectedSha: String?) {
        val expected = checkNotNull(expectedSha) { "M13 requires the M0 legacy database checksum" }
        require(expected.matches(SHA256_PATTERN)) { "M13 legacy database checksum is invalid" }
        require(legacyDatabase.isFile && legacyDatabase.length() > 0L) {
            "Live legacy database is missing"
        }
        requireNoUnsafeSidecars(legacyDatabase)
        val expectedLength = legacyDatabase.length()
        val expectedModifiedAt = legacyDatabase.lastModified()
        val actual = sha256(legacyDatabase)
        requireNoUnsafeSidecars(legacyDatabase)
        require(legacyDatabase.length() == expectedLength) {
            "Live legacy database length changed during M13 verification"
        }
        require(legacyDatabase.lastModified() == expectedModifiedAt) {
            "Live legacy database timestamp changed during M13 verification"
        }
        require(actual == expected) { "Live legacy database changed after the M0 snapshot" }
    }

    private fun publishVerifiedArchive(): RecoveryArchiveEvidence {
        val parent =
            checkNotNull(recoveryArchive.parentFile) {
                "Recovery archive must have a destination directory"
            }
        require(parent.isDirectory) { "Recovery archive destination directory is missing" }
        require(recoveryArchive.name.matches(ARCHIVE_NAME_PATTERN)) {
            "Recovery archive file name is unsafe"
        }
        val existing =
            if (recoveryArchive.exists()) {
                check(recoveryArchive.isFile) { "Recovery archive destination is not a file" }
                verifyArchive(recoveryArchive)
            } else {
                null
            }
        val createdAt = existing?.createdAtEpochMs ?: nowEpochMs()
        require(createdAt >= 0) { "Recovery archive timestamp cannot be negative" }
        val temp = File.createTempFile(".${recoveryArchive.name}.", ".tmp", parent)
        var published = false
        try {
            val report =
                FileOutputStream(temp).use { output ->
                    archiveExporter.export(output, createdAt, includeHistory)
                }
            require(report.databaseSchemaVersion == ShippyR16Database.SCHEMA_VERSION) {
                "Recovery archive exporter returned an unsupported schema version"
            }
            require(report.createdAtEpochMs == createdAt)
            require(report.includesHistory == includeHistory)
            require(report.portableAppSettingsIncluded) {
                "Recovery archive does not include portable app settings"
            }
            require(report.sanitizedLastFmConfigIncluded) {
                "Recovery archive does not include sanitized Last.fm config"
            }
            syncFile(temp)
            val candidate = verifyArchive(temp)
            require(candidate.createdAtEpochMs == createdAt)
            require(candidate.includesHistory == includeHistory)
            if (existing != null) {
                check(existing.sha256 == candidate.sha256) {
                    "Refusing to overwrite an unequal existing recovery archive"
                }
                return existing
            }
            check(!recoveryArchive.exists()) { "Recovery archive appeared while publishing" }
            check(temp.parentFile == recoveryArchive.parentFile)
            check(temp.renameTo(recoveryArchive)) {
                "Unable to publish the verified recovery archive"
            }
            published = true
            return candidate
        } finally {
            if (!published) temp.delete()
        }
    }

    private fun verifyArchive(file: File): RecoveryArchiveEvidence {
        require(file.isFile && file.length() > 0L) { "Recovery archive is missing or empty" }
        val manifest =
            FileInputStream(file).use { input ->
                ShippyBackupV1.stage(input).use { staged -> staged.manifest }
            }
        require(manifest.databaseSchemaVersion == ShippyR16Database.SCHEMA_VERSION) {
            "Recovery archive schema version is unsupported"
        }
        require(manifest.portableAppSettingsIncluded) {
            "Recovery archive does not include portable app settings"
        }
        require(manifest.sanitizedLastFmConfigIncluded) {
            "Recovery archive does not include sanitized Last.fm config"
        }
        return RecoveryArchiveEvidence(
            sha256 = sha256(file),
            byteCount = file.length(),
            createdAtEpochMs = manifest.createdAtEpochMs,
            includesHistory = manifest.includesHistory,
            portableAppSettingsIncluded = manifest.portableAppSettingsIncluded,
            sanitizedLastFmConfigIncluded = manifest.sanitizedLastFmConfigIncluded,
        )
    }

    private suspend fun recordArchiveEvidence(
        migrationId: String,
        archive: RecoveryArchiveEvidence,
    ) {
        database.withTransaction {
            val audit =
                requireNotNull(database.migrationAuditDao().get(migrationId)) {
                    "Migration audit is missing after M13 verification"
                }
            check(audit.completedAtEpochMs == null) { "Migration audit is already complete" }
            val target = JSONObject(audit.targetCountsJson ?: "{}")
            target.put(
                "m13RecoveryArchive",
                JSONObject()
                    .put("format", "ShippyBackupV1")
                    .put("formatVersion", 1)
                    .put("fileName", recoveryArchive.name)
                    .put("byteCount", archive.byteCount)
                    .put("sha256", archive.sha256)
                    .put("createdAtEpochMs", archive.createdAtEpochMs)
                    .put("includesHistory", archive.includesHistory)
                    .put("portableAppSettingsIncluded", archive.portableAppSettingsIncluded)
                    .put("sanitizedLastFmConfigIncluded", archive.sanitizedLastFmConfigIncluded)
                    .put("verified", true),
            )
            database
                .migrationAuditDao()
                .updateProgress(
                    migrationId = migrationId,
                    targetCountsJson = target.toString(),
                    warningsJson = audit.warningsJson,
                    status = "READY_TO_SWITCH",
                )
        }
    }

    private suspend fun markBackupFailure(migrationId: String) {
        runCatching {
            database.withTransaction {
                val audit = database.migrationAuditDao().get(migrationId) ?: return@withTransaction
                val target = JSONObject(audit.targetCountsJson ?: "{}")
                target.put(
                    "m13RecoveryArchive",
                    JSONObject().put("format", "ShippyBackupV1").put("status", "FAILED"),
                )
                val warnings =
                    runCatching { JSONArray(audit.warningsJson) }.getOrElse { JSONArray() }
                val warning = "M13 BACKUP_FAILED"
                if ((0 until warnings.length()).none { warnings.optString(it) == warning }) {
                    warnings.put(warning)
                }
                database
                    .migrationAuditDao()
                    .updateProgress(
                        migrationId = migrationId,
                        targetCountsJson = target.toString(),
                        warningsJson = warnings.toString(),
                        status = "FAILED_RECOVERABLE",
                    )
            }
        }
    }

    private data class RecoveryArchiveEvidence(
        val sha256: String,
        val byteCount: Long,
        val createdAtEpochMs: Long,
        val includesHistory: Boolean,
        val portableAppSettingsIncluded: Boolean,
        val sanitizedLastFmConfigIncluded: Boolean,
    )
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    BufferedInputStream(FileInputStream(file), HASH_BUFFER_BYTES).use { input ->
        val buffer = ByteArray(HASH_BUFFER_BYTES)
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

private fun syncFile(file: File) {
    FileOutputStream(file, true).use { output -> output.fd.sync() }
}

private fun requireNoUnsafeSidecars(database: File) {
    for (suffix in arrayOf("-wal", "-journal")) {
        val sidecar = File(database.path + suffix)
        check(!sidecar.exists() || (sidecar.isFile && sidecar.length() == 0L)) {
            "Legacy database has an unsafe sidecar: ${sidecar.name}"
        }
    }
}

private val IMPORT_PHASES = LegacyImportPhase.entries.dropLast(1)
private val LABEL_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
private val WARNING_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val ARCHIVE_NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val PREFLIGHT_MANIFEST_KEYS =
    setOf(
        "format",
        "formatVersion",
        "databaseSchemaVersion",
        "legacyFileName",
        "snapshotFileName",
        "byteCount",
        "sha256",
        "integrityCheck",
        "presentTables",
        "rowCounts",
    )
private const val PREFLIGHT_FORMAT = "ShippyLegacyPreflightV1"
private const val PREFLIGHT_FORMAT_VERSION = 1
private val R16_MIGRATION_TARGET_VERSION = ShippyR16Database.SCHEMA_VERSION
private const val PREFLIGHT_MANIFEST_BYTES = 64 * 1_024
private const val MAX_PREFLIGHT_MANIFEST_BYTES = PREFLIGHT_MANIFEST_BYTES.toLong()
private const val MAX_FOLDER_CHECKS = 64
private const val MAX_WARNING_CODES = 64
private const val HASH_BUFFER_BYTES = 1 * 1_024 * 1_024
