/*
 * Copyright (c) 2026 Auxio Project
 * LegacyLibraryRelationshipImporter.kt is part of Auxio.
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
package app.shippy.data.migration

import androidx.room.withTransaction
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.LibraryRecordingEntity
import org.json.JSONObject

internal data class LibraryRelationshipPageImportResult(
    val importedCount: Int,
    val pendingDownloadVerificationCount: Int,
    val lastTrackId: String?,
)

internal class LegacyLibraryRelationshipImporter(private val database: ShippyR16Database) {
    suspend fun importPage(
        migrationId: String,
        rows: List<LegacyLibraryRelationshipRow>,
        importedAtEpochMs: Long,
    ): LibraryRelationshipPageImportResult {
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        require(importedAtEpochMs >= 0) { "Import timestamp cannot be negative" }
        require(rows.size <= MAX_LIBRARY_IMPORT_PAGE_SIZE) {
            "Library relationship import page is too large"
        }
        require(rows.map(LegacyLibraryRelationshipRow::trackId).toSet().size == rows.size) {
            "Library relationship import page contains duplicate legacy track IDs"
        }
        require(rows.zipWithNext().all { (first, second) -> first.trackId < second.trackId }) {
            "Library relationship import page must be in stable legacy track order"
        }
        if (rows.isEmpty()) return LibraryRelationshipPageImportResult(0, 0, null)

        database.withTransaction {
            val audit = requireActiveLegacyAudit(database, migrationId)
            for (row in rows) {
                val recordingId = LegacyIdMapper.recording(row.trackId).value
                check(database.recordingDao().get(recordingId) != null) {
                    "M1 recording is missing for legacy track ${row.trackId}"
                }
                val existing = database.legacyImportDao().libraryRelationship(recordingId)
                database
                    .legacyImportDao()
                    .upsertLibraryRelationship(
                        LibraryRecordingEntity(
                            recordingId = recordingId,
                            liked = row.liked,
                            explicitlySaved = row.liked,
                            userEdited = existing?.userEdited ?: false,
                            manuallyIdentified = existing?.manuallyIdentified ?: false,
                            firstAddedAtEpochMs =
                                existing?.firstAddedAtEpochMs ?: importedAtEpochMs,
                            updatedAtEpochMs = importedAtEpochMs,
                        )
                    )
                check(
                    database
                        .legacyImportDao()
                        .makeRecordingDurable(recordingId, importedAtEpochMs) == 1
                ) {
                    "Imported Library recording disappeared"
                }
            }
            val lastTrackId = rows.last().trackId
            database
                .migrationAuditDao()
                .updateProgress(
                    migrationId = migrationId,
                    targetCountsJson =
                        JSONObject()
                            .put(
                                "libraryRecording",
                                database.legacyImportDao().libraryRecordingCount(),
                            )
                            .put(
                                "pagePendingLegacyDownloadVerification",
                                rows.count(LegacyLibraryRelationshipRow::downloaded),
                            )
                            .put(
                                "checkpoint",
                                JSONObject()
                                    .put("phase", LegacyImportPhase.LIBRARY_RELATIONSHIPS.code)
                                    .put("lastTrackId", lastTrackId),
                            )
                            .toString(),
                    warningsJson = audit.warningsJson,
                    status = "IMPORTING",
                )
        }
        return LibraryRelationshipPageImportResult(
            importedCount = rows.size,
            pendingDownloadVerificationCount = rows.count(LegacyLibraryRelationshipRow::downloaded),
            lastTrackId = rows.last().trackId,
        )
    }
}

internal suspend fun requireActiveLegacyAudit(database: ShippyR16Database, migrationId: String) =
    checkNotNull(database.migrationAuditDao().get(migrationId)) {
            "Migration audit must exist before importing"
        }
        .also { audit ->
            check(audit.completedAtEpochMs == null) { "Completed migration cannot accept new rows" }
            check(audit.sourceVersion == LEGACY_SCHEMA_VERSION && audit.targetVersion == 1) {
                "Migration audit versions do not match the R15-to-R16 importer"
            }
        }

private const val MAX_LIBRARY_IMPORT_PAGE_SIZE = 500
