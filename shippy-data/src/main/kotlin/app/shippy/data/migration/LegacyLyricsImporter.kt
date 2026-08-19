/*
 * Copyright (c) 2026 Auxio Project
 * LegacyLyricsImporter.kt is part of Auxio.
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
import app.shippy.data.db.entity.LyricsCacheEntity
import org.json.JSONArray
import org.json.JSONObject

internal data class LyricsPageImportResult(
    val importedCount: Int,
    val skippedCount: Int,
    val lastTrackId: String?,
    val lastFingerprint: String?,
    val warnings: List<String>,
)

internal class LegacyLyricsImporter(private val database: ShippyR16Database) {
    suspend fun importPage(
        migrationId: String,
        rows: List<LegacyLyricsRow>,
        importedAtEpochMs: Long,
    ): LyricsPageImportResult {
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        require(importedAtEpochMs >= 0) { "Import timestamp cannot be negative" }
        require(rows.size <= MAX_LYRICS_IMPORT_PAGE_SIZE) { "Lyrics import page is too large" }
        require(rows.map { it.trackId to it.fingerprint }.toSet().size == rows.size) {
            "Lyrics import page contains duplicate legacy keys"
        }
        require(
            rows.zipWithNext().all { (first, second) ->
                compareValuesBy(first, second, { it.trackId }, { it.fingerprint }) < 0
            }
        ) {
            "Lyrics import page must be in stable legacy key order"
        }
        if (rows.isEmpty()) return LyricsPageImportResult(0, 0, null, null, emptyList())

        val warnings = mutableListOf<String>()
        var importedCount = 0
        database.withTransaction {
            val audit = requireActiveLegacyAudit(database, migrationId)
            for (row in rows) {
                val recordingId = LegacyIdMapper.recording(row.trackId).value
                check(database.recordingDao().get(recordingId) != null) {
                    "M1 recording is missing for legacy track ${row.trackId}"
                }
                if (row.fingerprint != row.expectedFingerprint()) {
                    warnings += "${row.stableKey()}: metadata fingerprint was inconsistent"
                    continue
                }
                val plainLyrics = row.plainLyrics?.takeIf(String::isNotBlank)
                val synchronizedLyrics = row.syncedLyrics?.takeIf(String::isNotBlank)
                if (!row.instrumental && plainLyrics == null && synchronizedLyrics == null) {
                    warnings += "${row.stableKey()}: empty non-instrumental lyrics row was skipped"
                    continue
                }
                val providerId =
                    row.sourceId.trim().ifEmpty {
                        warnings += "${row.stableKey()}: blank lyrics source mapped to legacy"
                        "legacy"
                    }
                database
                    .lyricsDao()
                    .upsert(
                        LyricsCacheEntity(
                            recordingId = recordingId,
                            metadataFingerprint = row.fingerprint,
                            providerId = providerId,
                            providerRecordId = row.recordId.toString(),
                            plainLyrics = plainLyrics,
                            synchronizedLyrics = synchronizedLyrics,
                            instrumental = row.instrumental,
                            cachedAtEpochMs =
                                row.cachedAtEpochMs.takeIf { it >= 0 } ?: importedAtEpochMs,
                            expiresAtEpochMs = importedAtEpochMs,
                        )
                    )
                importedCount++
            }

            val last = rows.last()
            database
                .migrationAuditDao()
                .updateProgress(
                    migrationId = migrationId,
                    targetCountsJson =
                        JSONObject()
                            .put("lyricsCache", database.legacyImportDao().lyricsCount())
                            .put(
                                "checkpoint",
                                JSONObject()
                                    .put("phase", LegacyImportPhase.LYRICS.code)
                                    .put("lastTrackId", last.trackId)
                                    .put("lastFingerprint", last.fingerprint),
                            )
                            .toString(),
                    warningsJson = appendLyricsWarnings(audit.warningsJson, warnings),
                    status = "IMPORTING",
                )
        }

        val last = rows.last()
        return LyricsPageImportResult(
            importedCount = importedCount,
            skippedCount = rows.size - importedCount,
            lastTrackId = last.trackId,
            lastFingerprint = last.fingerprint,
            warnings = warnings,
        )
    }
}

private fun LegacyLyricsRow.expectedFingerprint(): String =
    listOf(titleKey, artistsKey, albumKey, durationSeconds.toString()).joinToString("\u001F")

private fun LegacyLyricsRow.stableKey(): String = "$trackId/$fingerprint"

private fun appendLyricsWarnings(existingJson: String, additions: List<String>): String {
    val result = runCatching { JSONArray(existingJson) }.getOrElse { JSONArray() }
    additions.take((MAX_LYRICS_WARNINGS - result.length()).coerceAtLeast(0)).forEach(result::put)
    return result.toString()
}

private const val MAX_LYRICS_IMPORT_PAGE_SIZE = 500
private const val MAX_LYRICS_WARNINGS = 1_000
