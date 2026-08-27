/*
 * Copyright (c) 2026 Auxio Project
 * LegacySavedSourceImporter.kt is part of Auxio.
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
import app.shippy.data.db.entity.SavedSourceEntity
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

internal data class SavedSourcePageImportResult(
    val importedCount: Int,
    val skippedCount: Int,
    val lastProviderId: String?,
    val lastEntityType: String?,
    val lastSourceItemId: String?,
    val warnings: List<String>,
)

internal class LegacySavedSourceImporter(private val database: ShippyR16Database) {
    suspend fun importPage(
        migrationId: String,
        rows: List<LegacySavedProviderEntityRow>,
        importedAtEpochMs: Long,
    ): SavedSourcePageImportResult {
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        require(importedAtEpochMs >= 0) { "Import timestamp cannot be negative" }
        require(rows.size <= MAX_SAVED_SOURCE_IMPORT_PAGE_SIZE) {
            "Saved-source import page is too large"
        }
        require(rows.map(LegacySavedProviderEntityRow::stableKey).toSet().size == rows.size) {
            "Saved-source import page contains duplicate legacy keys"
        }
        require(
            rows.zipWithNext().all { (first, second) ->
                compareValuesBy(
                    first,
                    second,
                    { it.providerId },
                    { it.entityType },
                    { it.sourceItemId },
                ) < 0
            }
        ) {
            "Saved-source import page must be in stable legacy key order"
        }
        if (rows.isEmpty()) return SavedSourcePageImportResult(0, 0, null, null, null, emptyList())

        val warnings = mutableListOf<String>()
        var importedCount = 0
        database.withTransaction {
            val audit = requireActiveLegacyAudit(database, migrationId)
            for (row in rows) {
                if (
                    row.providerId.isBlank() ||
                        row.entityType.isBlank() ||
                        row.sourceItemId.isBlank() ||
                        row.title.isBlank()
                ) {
                    warnings += "${row.stableKey()}: invalid required metadata was skipped"
                    continue
                }
                val artwork = row.artwork.publicHttpsOrNull()
                val originalUrl = row.originalUrl.publicHttpsOrNull()
                if (row.artwork != null && artwork == null) {
                    warnings += "${row.stableKey()}: non-public artwork locator was discarded"
                }
                if (row.originalUrl != null && originalUrl == null) {
                    warnings += "${row.stableKey()}: non-public original locator was discarded"
                }
                database
                    .savedSourceDao()
                    .upsert(
                        SavedSourceEntity(
                            providerId = row.providerId,
                            entityType = row.entityType,
                            sourceItemId = row.sourceItemId,
                            title = row.title,
                            subtitle = row.subtitle?.takeIf(String::isNotBlank),
                            artworkUrl = artwork,
                            originalUrl = originalUrl,
                            pinned = row.pinned,
                            savedAtEpochMs =
                                row.savedAtEpochMs.takeIf { it >= 0 } ?: importedAtEpochMs,
                            updatedAtEpochMs = importedAtEpochMs,
                        )
                    )
                importedCount++
            }
            val last = rows.last()
            val targetCounts =
                audit.targetCountsJson?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: JSONObject()
            MigrationExpectedCountEvidence.recordPage(
                target = targetCounts,
                phase = LegacyImportPhase.SAVED_PROVIDER_ENTITIES,
                pageToken = "M10:${last.providerId}:${last.entityType}:${last.sourceItemId}",
                delta = MigrationExpectedCountDelta(savedSourceEntities = importedCount.toLong()),
            )
            database
                .migrationAuditDao()
                .updateProgress(
                    migrationId = migrationId,
                    targetCountsJson =
                        targetCounts
                            .put(
                                "savedSourceEntities",
                                database.legacyImportDao().savedSourceCount(),
                            )
                            .put(
                                "checkpoint",
                                JSONObject()
                                    .put("phase", LegacyImportPhase.SAVED_PROVIDER_ENTITIES.code)
                                    .put("lastProviderId", last.providerId)
                                    .put("lastEntityType", last.entityType)
                                    .put("lastSourceItemId", last.sourceItemId),
                            )
                            .toString(),
                    warningsJson = appendSavedSourceWarnings(audit.warningsJson, warnings),
                    status = "IMPORTING",
                )
        }

        val last = rows.last()
        return SavedSourcePageImportResult(
            importedCount = importedCount,
            skippedCount = rows.size - importedCount,
            lastProviderId = last.providerId,
            lastEntityType = last.entityType,
            lastSourceItemId = last.sourceItemId,
            warnings = warnings,
        )
    }
}

private fun LegacySavedProviderEntityRow.stableKey(): String =
    "$providerId/$entityType/$sourceItemId"

private fun String?.publicHttpsOrNull(): String? {
    val value = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return runCatching { URI(value) }
        .getOrNull()
        ?.takeIf { uri ->
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null
        }
        ?.toString()
}

private fun appendSavedSourceWarnings(existingJson: String, additions: List<String>): String {
    val result = runCatching { JSONArray(existingJson) }.getOrElse { JSONArray() }
    additions
        .take((MAX_SAVED_SOURCE_WARNINGS - result.length()).coerceAtLeast(0))
        .forEach(result::put)
    return result.toString()
}

private const val MAX_SAVED_SOURCE_IMPORT_PAGE_SIZE = 500
private const val MAX_SAVED_SOURCE_WARNINGS = 1_000
