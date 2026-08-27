/*
 * Copyright (c) 2026 Auxio Project
 * R16BackupRuntime.kt is part of Auxio.
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
package app.shippy.data.backup

import app.shippy.data.db.ShippyR16Database
import java.io.InputStream
import java.io.OutputStream

/** Public, data-owned entry point for the versioned R16 backup contract. */
public class R16BackupRuntime internal constructor(private val database: ShippyR16Database) {
    /**
     * Streams one deterministic archive from a consistent database snapshot.
     *
     * Portable settings and sanitized Last.fm state are all-or-nothing coverage. Supplying only one
     * provider is rejected by the underlying contract.
     */
    public fun export(
        output: OutputStream,
        createdAtEpochMs: Long,
        includeHistory: Boolean = false,
        portableSettingsProvider: R16PortableSettingsProvider? = null,
        sanitizedLastFmConfigProvider: R16SanitizedLastFmConfigProvider? = null,
    ): R16BackupExportResult =
        R16BackupExporter(
                database = database,
                portableSettingsProvider = portableSettingsProvider,
                sanitizedLastFmConfigProvider = sanitizedLastFmConfigProvider,
            )
            .export(output, createdAtEpochMs, includeHistory)
            .toPublic()

    /**
     * Fully stages and checksum-verifies an archive without opening a writable database.
     *
     * A future SAF workflow can call this after copying an archive and compare the returned entry
     * metadata with the original export result before presenting success.
     */
    public fun verify(input: InputStream): R16BackupArchiveMetadata =
        ShippyBackupV1.stage(input).use { staged ->
            require(staged.manifest.databaseSchemaVersion == ShippyR16Database.SCHEMA_VERSION) {
                "Backup database schema version is unsupported"
            }
            staged.manifest.toPublic()
        }

    /**
     * Restores a verified archive into this runtime's empty database.
     *
     * The importer stages and validates the complete archive before beginning its database
     * transaction. It then enforces an empty target and rolls back every inserted row if any
     * schema, value, derived-index, or foreign-key check fails.
     */
    public fun restore(input: InputStream): R16BackupRestoreResult =
        R16BackupImporter(database).restore(input).toPublic()
}

/** Manifest and per-entry evidence from a checksum-verified archive. */
public data class R16BackupArchiveMetadata(
    val formatVersion: Int,
    val databaseSchemaVersion: Int,
    val createdAtEpochMs: Long,
    val includesHistory: Boolean,
    val portableAppSettingsIncluded: Boolean,
    val sanitizedLastFmConfigIncluded: Boolean,
    val entries: List<R16BackupArchiveEntryMetadata>,
)

/** Stable archive-entry evidence suitable for comparing an internal and SAF copy. */
public data class R16BackupArchiveEntryMetadata(
    val entryName: String,
    val byteCount: Long,
    val sha256: String,
)

/** Result of exporting the current R16 database. */
public data class R16BackupExportResult(
    val archive: R16BackupArchiveMetadata,
    val rowCounts: Map<String, Int>,
)

/**
 * Restored database data plus app-owned values that must be applied before authority activation.
 */
public data class R16BackupRestoreResult(
    val databaseSchemaVersion: Int,
    val createdAtEpochMs: Long,
    val includesHistory: Boolean,
    val rowCounts: Map<String, Int>,
    val portableSettings: R16PortableSettingsSnapshot?,
    val sanitizedLastFmConfig: R16SanitizedLastFmConfig?,
)

private fun R16BackupExportReport.toPublic(): R16BackupExportResult {
    val entries =
        sectionByteCounts.entries
            .sortedBy { it.key.entryName }
            .map { (section, byteCount) ->
                R16BackupArchiveEntryMetadata(
                    entryName = section.entryName,
                    byteCount = byteCount,
                    sha256 = sectionChecksums.getValue(section),
                )
            }
    return R16BackupExportResult(
        archive =
            R16BackupArchiveMetadata(
                formatVersion = ShippyBackupV1.FORMAT_VERSION,
                databaseSchemaVersion = databaseSchemaVersion,
                createdAtEpochMs = createdAtEpochMs,
                includesHistory = includesHistory,
                portableAppSettingsIncluded = portableAppSettingsIncluded,
                sanitizedLastFmConfigIncluded = sanitizedLastFmConfigIncluded,
                entries = entries,
            ),
        rowCounts = rowCounts.toMap(),
    )
}

private fun ShippyBackupManifest.toPublic(): R16BackupArchiveMetadata =
    R16BackupArchiveMetadata(
        formatVersion = ShippyBackupV1.FORMAT_VERSION,
        databaseSchemaVersion = databaseSchemaVersion,
        createdAtEpochMs = createdAtEpochMs,
        includesHistory = includesHistory,
        portableAppSettingsIncluded = portableAppSettingsIncluded,
        sanitizedLastFmConfigIncluded = sanitizedLastFmConfigIncluded,
        entries =
            entries
                .sortedBy { it.section.entryName }
                .map { entry ->
                    R16BackupArchiveEntryMetadata(
                        entryName = entry.section.entryName,
                        byteCount = entry.byteCount,
                        sha256 = entry.sha256,
                    )
                },
    )

private fun R16BackupRestoreReport.toPublic(): R16BackupRestoreResult =
    R16BackupRestoreResult(
        databaseSchemaVersion = databaseSchemaVersion,
        createdAtEpochMs = createdAtEpochMs,
        includesHistory = includesHistory,
        rowCounts = rowCounts.toMap(),
        portableSettings = portableSettings,
        sanitizedLastFmConfig = sanitizedLastFmConfig,
    )
