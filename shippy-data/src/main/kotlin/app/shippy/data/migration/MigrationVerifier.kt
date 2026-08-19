/*
 * Copyright (c) 2026 Auxio Project
 * MigrationVerifier.kt is part of Auxio.
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
import androidx.sqlite.db.SupportSQLiteDatabase
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.dao.StoredPlaybackCheckpoint
import app.shippy.data.playback.PlaybackCheckpointIntegrity
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal data class MigrationExpectedCounts(
    val liked: Long,
    val playlists: Long,
    val playlistEntries: Long,
    val savedSourceEntities: Long,
    val verifiedDownloads: Long,
    val checkpointEntries: Long,
    val lastFmOutbox: Long,
) {
    init {
        require(
            listOf(
                    liked,
                    playlists,
                    playlistEntries,
                    savedSourceEntities,
                    verifiedDownloads,
                    checkpointEntries,
                    lastFmOutbox,
                )
                .all { it >= 0 }
        ) {
            "Expected migration counts cannot be negative"
        }
    }
}

internal data class MigrationActualCounts(
    val liked: Long,
    val playlists: Long,
    val playlistEntries: Long,
    val savedSourceEntities: Long,
    val verifiedDownloads: Long,
    val checkpointEntries: Long,
    val lastFmOutbox: Long,
)

internal data class MigrationVerificationIssue(val code: String, val detail: String)

internal data class MigrationVerificationReport(
    val counts: MigrationActualCounts,
    val unresolvedReferences: Long,
    val foreignKeyViolations: Long,
    val redirectCycles: Int,
    val duplicateSourceKeys: Long,
    val duplicateAssetLocations: Long,
    val issues: List<MigrationVerificationIssue>,
) {
    val passed: Boolean
        get() = issues.isEmpty()
}

internal class MigrationVerifier(private val database: ShippyR16Database) {
    suspend fun verify(
        migrationId: String,
        expected: MigrationExpectedCounts,
        completedPhases: Set<LegacyImportPhase>,
    ): MigrationVerificationReport {
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        lateinit var report: MigrationVerificationReport
        database.withTransaction {
            val audit = requireActiveLegacyAudit(database, migrationId)
            val sql = database.openHelper.writableDatabase
            val counts = sql.readCounts()
            val unresolvedReferences = sql.unresolvedReferenceCount()
            val foreignKeyViolations = sql.rowCount("PRAGMA foreign_key_check")
            val redirectCycles = sql.redirectCycleCount()
            val duplicateSourceKeys =
                sql.longValue(
                    """
                    SELECT COUNT(*) FROM (
                        SELECT provider_id, item_type, source_item_id
                        FROM source_reference
                        GROUP BY provider_id, item_type, source_item_id
                        HAVING COUNT(*) > 1
                    )
                    """
                        .trimIndent()
                )
            val duplicateAssetLocations =
                sql.longValue(
                    """
                    SELECT COUNT(*) FROM (
                        SELECT location_type, location
                        FROM media_asset
                        GROUP BY location_type, location
                        HAVING COUNT(*) > 1
                    )
                    """
                        .trimIndent()
                )
            val issues = mutableListOf<MigrationVerificationIssue>()
            (REQUIRED_PRE_VERIFICATION_PHASES - completedPhases)
                .sortedBy(LegacyImportPhase::ordinal)
                .forEach { phase ->
                    issues +=
                        MigrationVerificationIssue(
                            code = "MISSING_PHASE_${phase.code}",
                            detail = "Required import phase ${phase.code} has not completed",
                        )
                }
            counts.compareWith(expected, issues)
            if (unresolvedReferences != 0L) {
                issues +=
                    MigrationVerificationIssue(
                        "UNRESOLVED_REFERENCES",
                        "$unresolvedReferences logical references do not resolve",
                    )
            }
            if (foreignKeyViolations != 0L) {
                issues +=
                    MigrationVerificationIssue(
                        "FOREIGN_KEY_VIOLATIONS",
                        "$foreignKeyViolations foreign-key rows are invalid",
                    )
            }
            if (redirectCycles != 0) {
                issues +=
                    MigrationVerificationIssue(
                        "REDIRECT_CYCLES",
                        "$redirectCycles redirect cycles were found",
                    )
            }
            if (duplicateSourceKeys != 0L) {
                issues +=
                    MigrationVerificationIssue(
                        "DUPLICATE_SOURCE_KEYS",
                        "$duplicateSourceKeys exact source keys are duplicated",
                    )
            }
            if (duplicateAssetLocations != 0L) {
                issues +=
                    MigrationVerificationIssue(
                        "DUPLICATE_ASSET_LOCATIONS",
                        "$duplicateAssetLocations exact asset locations are duplicated",
                    )
            }
            database.playbackCheckpointDao().load(ACTIVE_SLOT).validateCheckpoint(issues)

            report =
                MigrationVerificationReport(
                    counts = counts,
                    unresolvedReferences = unresolvedReferences,
                    foreignKeyViolations = foreignKeyViolations,
                    redirectCycles = redirectCycles,
                    duplicateSourceKeys = duplicateSourceKeys,
                    duplicateAssetLocations = duplicateAssetLocations,
                    issues = issues,
                )
            database
                .migrationAuditDao()
                .updateProgress(
                    migrationId = migrationId,
                    targetCountsJson = report.toJson(completedPhases),
                    warningsJson = appendVerificationWarnings(audit.warningsJson, issues),
                    status = if (report.passed) "READY_TO_SWITCH" else "FAILED_RECOVERABLE",
                )
        }
        return report
    }
}

private fun SupportSQLiteDatabase.readCounts() =
    MigrationActualCounts(
        liked = longValue("SELECT COUNT(*) FROM library_recording WHERE liked = 1"),
        playlists = longValue("SELECT COUNT(*) FROM playlist"),
        playlistEntries = longValue("SELECT COUNT(*) FROM playlist_entry"),
        savedSourceEntities = longValue("SELECT COUNT(*) FROM saved_source_entity"),
        verifiedDownloads =
            longValue(
                """
                SELECT COUNT(*) FROM download_job AS job
                INNER JOIN media_asset AS asset ON asset.asset_id = job.published_asset_id
                WHERE job.state = 'AVAILABLE' AND asset.asset_state = 'AVAILABLE'
                """
                    .trimIndent()
            ),
        checkpointEntries =
            longValue("SELECT COUNT(*) FROM playback_checkpoint_entry WHERE slot = 'active'"),
        lastFmOutbox = longValue("SELECT COUNT(*) FROM lastfm_scrobble_outbox"),
    )

private fun SupportSQLiteDatabase.unresolvedReferenceCount(): Long =
    longValue(
        """
        SELECT
            (SELECT COUNT(*) FROM playback_checkpoint_entry AS entry
             LEFT JOIN recording AS recording ON recording.recording_id = entry.recording_id
             WHERE recording.recording_id IS NULL)
          + (SELECT COUNT(*) FROM source_reference AS source
             LEFT JOIN metadata_observation AS observation
               ON observation.observation_id = source.raw_metadata_observation_id
             WHERE observation.observation_id IS NULL)
          + (SELECT COUNT(*) FROM metadata_observation AS observation
             LEFT JOIN source_reference AS source
               ON source.source_reference_id = observation.source_reference_id
             WHERE observation.source_reference_id IS NOT NULL
               AND source.source_reference_id IS NULL)
          + (SELECT COUNT(*) FROM metadata_observation AS observation
             LEFT JOIN media_asset AS asset ON asset.asset_id = observation.asset_id
             WHERE observation.asset_id IS NOT NULL AND asset.asset_id IS NULL)
          + (SELECT COUNT(*) FROM entity_redirect AS redirect
             LEFT JOIN recording AS old_recording
               ON old_recording.recording_id = redirect.old_recording_id
             LEFT JOIN recording AS canonical
               ON canonical.recording_id = redirect.canonical_recording_id
             WHERE old_recording.recording_id IS NULL OR canonical.recording_id IS NULL)
        """
            .trimIndent()
    )

private fun SupportSQLiteDatabase.longValue(sql: String): Long =
    query(sql).use { cursor ->
        check(cursor.moveToFirst()) { "Migration verification query returned no row" }
        cursor.getLong(0)
    }

private fun SupportSQLiteDatabase.rowCount(sql: String): Long =
    query(sql).use { cursor ->
        var count = 0L
        while (cursor.moveToNext()) count++
        count
    }

private fun SupportSQLiteDatabase.redirectCycleCount(): Int {
    val redirects =
        query("SELECT old_recording_id, canonical_recording_id FROM entity_redirect").use { cursor
            ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }
    val cycles = mutableSetOf<String>()
    for (start in redirects.keys) {
        val visited = linkedMapOf<String, Int>()
        var current: String? = start
        while (current != null && current in redirects) {
            val prior = visited.putIfAbsent(current, visited.size)
            if (prior != null) {
                cycles += visited.keys.drop(prior).sorted().joinToString("|")
                break
            }
            current = redirects[current]
        }
    }
    return cycles.size
}

private fun MigrationActualCounts.compareWith(
    expected: MigrationExpectedCounts,
    issues: MutableList<MigrationVerificationIssue>,
) {
    fun compare(name: String, expectedValue: Long, actualValue: Long) {
        if (expectedValue != actualValue) {
            issues +=
                MigrationVerificationIssue(
                    code = "COUNT_${name.uppercase()}",
                    detail = "$name expected $expectedValue but imported $actualValue",
                )
        }
    }
    compare("liked", expected.liked, liked)
    compare("playlists", expected.playlists, playlists)
    compare("playlist_entries", expected.playlistEntries, playlistEntries)
    compare("saved_sources", expected.savedSourceEntities, savedSourceEntities)
    compare("verified_downloads", expected.verifiedDownloads, verifiedDownloads)
    compare("checkpoint_entries", expected.checkpointEntries, checkpointEntries)
    compare("lastfm_outbox", expected.lastFmOutbox, lastFmOutbox)
}

private fun StoredPlaybackCheckpoint?.validateCheckpoint(
    issues: MutableList<MigrationVerificationIssue>
) {
    if (this == null) return
    val entryIds = entries.sortedBy { it.position }.map { it.queueEntryId }
    val base = checkpoint.baseOrderJson.jsonStringListOrNull()
    val traversal = checkpoint.traversalOrderJson.jsonStringListOrNull()
    val validOrders =
        base == entryIds &&
            traversal != null &&
            traversal.size == entryIds.size &&
            traversal.toSet() == entryIds.toSet() &&
            checkpoint.currentQueueEntryId in entryIds
    if (!validOrders) {
        issues +=
            MigrationVerificationIssue(
                "CHECKPOINT_ORDER",
                "Playback checkpoint base/traversal/current identities are inconsistent",
            )
    }
    val expectedChecksum = PlaybackCheckpointIntegrity.checksum(checkpoint, entries)
    if (!MessageDigest.isEqual(expectedChecksum.toByteArray(), checkpoint.checksum.toByteArray())) {
        issues +=
            MigrationVerificationIssue(
                "CHECKPOINT_CHECKSUM",
                "Playback checkpoint checksum does not match its source-neutral payload",
            )
    }
}

private fun String.jsonStringListOrNull(): List<String>? =
    runCatching {
            val array = JSONArray(this)
            List(array.length()) { index -> array.getString(index) }
        }
        .getOrNull()

private fun MigrationVerificationReport.toJson(completedPhases: Set<LegacyImportPhase>): String =
    JSONObject()
        .put(
            "counts",
            JSONObject()
                .put("liked", counts.liked)
                .put("playlists", counts.playlists)
                .put("playlistEntries", counts.playlistEntries)
                .put("savedSourceEntities", counts.savedSourceEntities)
                .put("verifiedDownloads", counts.verifiedDownloads)
                .put("checkpointEntries", counts.checkpointEntries)
                .put("lastFmOutbox", counts.lastFmOutbox),
        )
        .put("unresolvedReferences", unresolvedReferences)
        .put("foreignKeyViolations", foreignKeyViolations)
        .put("redirectCycles", redirectCycles)
        .put("duplicateSourceKeys", duplicateSourceKeys)
        .put("duplicateAssetLocations", duplicateAssetLocations)
        .put("completedPhases", JSONArray(completedPhases.sortedBy { it.ordinal }.map { it.code }))
        .put(
            "issues",
            JSONArray(
                issues.map { issue ->
                    JSONObject().put("code", issue.code).put("detail", issue.detail)
                }
            ),
        )
        .put("passed", passed)
        .put("checkpoint", JSONObject().put("phase", LegacyImportPhase.VERIFY.code))
        .toString()

private fun appendVerificationWarnings(
    existingJson: String,
    issues: List<MigrationVerificationIssue>,
): String {
    val prior = runCatching { JSONArray(existingJson) }.getOrElse { JSONArray() }
    val retained =
        (0 until prior.length()).map(prior::optString).filterNot { it.startsWith("M13 ") }
    val result = JSONArray(retained)
    val existing = retained.toMutableSet()
    for (issue in issues) {
        val warning = "M13 ${issue.code}: ${issue.detail}"
        if (result.length() >= MAX_VERIFICATION_WARNINGS) break
        if (existing.add(warning)) result.put(warning)
    }
    return result.toString()
}

private val REQUIRED_PRE_VERIFICATION_PHASES =
    LegacyImportPhase.entries.filter { it.ordinal < LegacyImportPhase.VERIFY.ordinal }.toSet()
private const val ACTIVE_SLOT = "active"
private const val MAX_VERIFICATION_WARNINGS = 1_000
