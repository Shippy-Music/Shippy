/*
 * Copyright (c) 2026 Auxio Project
 * R16LocalReindexAudit.kt is part of Auxio.
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
import org.json.JSONObject

data class R16LocalReindexCheckpoint(
    val snapshotFingerprint: String,
    val snapshotCount: Long,
    val processedCount: Long,
    val linkedCount: Long,
    val unresolvedCount: Long,
    val lastSourceKey: String?,
    val complete: Boolean,
) {
    init {
        require(snapshotFingerprint.isNotBlank()) { "Snapshot fingerprint cannot be blank" }
        require(snapshotCount >= 0) { "Snapshot count cannot be negative" }
        require(processedCount in 0..snapshotCount) { "Processed count exceeds snapshot count" }
        require(linkedCount >= 0 && unresolvedCount >= 0) {
            "Re-index result counts cannot be negative"
        }
        require(linkedCount + unresolvedCount == processedCount) {
            "Every processed source must be linked or unresolved"
        }
        require((processedCount == 0L) == (lastSourceKey == null)) {
            "A non-empty checkpoint requires its last source key"
        }
        require(!complete || processedCount == snapshotCount) {
            "A complete checkpoint must cover the snapshot"
        }
    }
}

interface R16LocalReindexAudit {
    suspend fun load(migrationId: String): R16LocalReindexCheckpoint?

    suspend fun save(migrationId: String, checkpoint: R16LocalReindexCheckpoint)
}

internal class RoomR16LocalReindexAudit(private val database: ShippyR16Database) :
    R16LocalReindexAudit {
    override suspend fun load(migrationId: String): R16LocalReindexCheckpoint? {
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        return database.withTransaction {
            val audit = requireActiveLegacyAudit(database, migrationId)
            val checkpoint =
                audit.targetCountsJson
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?.optJSONObject("checkpoint") ?: return@withTransaction null
            if (checkpoint.optString("phase") != LegacyImportPhase.LOCAL_REINDEX.code) {
                return@withTransaction null
            }
            R16LocalReindexCheckpoint(
                snapshotFingerprint = checkpoint.getString("snapshotFingerprint"),
                snapshotCount = checkpoint.getLong("snapshotCount"),
                processedCount = checkpoint.getLong("processedCount"),
                linkedCount = checkpoint.getLong("linkedCount"),
                unresolvedCount = checkpoint.getLong("unresolvedCount"),
                lastSourceKey =
                    if (checkpoint.isNull("lastSourceKey")) {
                        null
                    } else {
                        checkpoint.getString("lastSourceKey")
                    },
                complete = checkpoint.optBoolean("complete", false),
            )
        }
    }

    override suspend fun save(migrationId: String, checkpoint: R16LocalReindexCheckpoint) {
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        database.withTransaction {
            val audit = requireActiveLegacyAudit(database, migrationId)
            val targetCounts =
                audit.targetCountsJson?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: JSONObject()
            targetCounts
                .put("recordings", database.legacyImportDao().recordingCount())
                .put("sources", database.legacyImportDao().sourceCount())
                .put("assets", database.legacyImportDao().assetCount())
                .put("observations", database.sourceDao().observationCount())
                .put("checkpoint", checkpoint.toJson())
            database
                .migrationAuditDao()
                .updateProgress(
                    migrationId = migrationId,
                    targetCountsJson = targetCounts.toString(),
                    warningsJson = audit.warningsJson,
                    status = "IMPORTING",
                )
        }
    }
}

private fun R16LocalReindexCheckpoint.toJson(): JSONObject =
    JSONObject()
        .put("phase", LegacyImportPhase.LOCAL_REINDEX.code)
        .put("snapshotFingerprint", snapshotFingerprint)
        .put("snapshotCount", snapshotCount)
        .put("processedCount", processedCount)
        .put("linkedCount", linkedCount)
        .put("unresolvedCount", unresolvedCount)
        .put("lastSourceKey", lastSourceKey ?: JSONObject.NULL)
        .put("complete", complete)
