/*
 * Copyright (c) 2026 Auxio Project
 * LegacyPlaylistImporter.kt is part of Auxio.
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
import app.shippy.data.db.entity.LibraryLayoutEntryEntity
import app.shippy.data.db.entity.PlaylistEntity
import app.shippy.data.db.entity.PlaylistEntryEntity
import org.json.JSONArray
import org.json.JSONObject

internal data class PlaylistPageImportResult(
    val importedCount: Int,
    val lastPosition: Int?,
    val lastPlaylistId: String?,
    val warnings: List<String>,
)

internal data class PlaylistMembershipPageImportResult(
    val importedCount: Int,
    val lastPlaylistId: String?,
    val lastPosition: Int?,
    val lastTrackId: String?,
)

internal class LegacyPlaylistImporter(private val database: ShippyR16Database) {
    suspend fun importPlaylistPage(
        migrationId: String,
        rows: List<LegacyUserPlaylistRow>,
        importedAtEpochMs: Long,
    ): PlaylistPageImportResult {
        validatePlaylistPage(migrationId, rows, importedAtEpochMs)
        if (rows.isEmpty()) return PlaylistPageImportResult(0, null, null, emptyList())
        val warnings = mutableListOf<String>()

        database.withTransaction {
            val audit = requireActiveLegacyAudit(database, migrationId)
            for (row in rows) {
                require(row.orderOrdinal >= 0) { "Legacy playlist ordinal cannot be negative" }
                val playlistId = LegacyIdMapper.playlist(row.playlistId).value
                val existing = database.legacyImportDao().playlist(playlistId)
                val name =
                    row.name.trim().ifEmpty {
                        warnings += "${row.playlistId}: blank playlist name replaced with Untitled"
                        "Untitled"
                    }
                val orderKey = sparseOrderKey(row.orderOrdinal)
                database
                    .legacyImportDao()
                    .upsertPlaylist(
                        PlaylistEntity(
                            playlistId = playlistId,
                            name = name,
                            pinned = row.pinned,
                            libraryOrderKey = orderKey,
                            artworkOverride = row.artworkUri?.trim()?.takeIf(String::isNotEmpty),
                            displaySortMode = "CUSTOM",
                            displaySortDirection = "ASC",
                            createdAtEpochMs = existing?.createdAtEpochMs ?: importedAtEpochMs,
                            updatedAtEpochMs = importedAtEpochMs,
                        )
                    )
                database
                    .legacyImportDao()
                    .upsertLibraryLayout(
                        LibraryLayoutEntryEntity(
                            targetType = "PLAYLIST",
                            targetId = playlistId,
                            pinned = row.pinned,
                            orderKey = orderKey,
                        )
                    )
            }
            val last = rows.last()
            updatePlaylistAudit(
                migrationId = migrationId,
                warningsJson = appendPlaylistWarnings(audit.warningsJson, warnings),
                subphase = "PLAYLISTS",
                checkpoint =
                    JSONObject()
                        .put("lastPosition", last.position)
                        .put("lastPlaylistId", last.playlistId),
            )
        }

        val last = rows.last()
        return PlaylistPageImportResult(
            importedCount = rows.size,
            lastPosition = last.position,
            lastPlaylistId = last.playlistId,
            warnings = warnings,
        )
    }

    suspend fun importMembershipPage(
        migrationId: String,
        rows: List<LegacyPlaylistMembershipRow>,
        importedAtEpochMs: Long,
    ): PlaylistMembershipPageImportResult {
        validateMembershipPage(migrationId, rows, importedAtEpochMs)
        if (rows.isEmpty()) return PlaylistMembershipPageImportResult(0, null, null, null)

        database.withTransaction {
            val audit = requireActiveLegacyAudit(database, migrationId)
            for (row in rows) {
                require(row.orderOrdinal >= 0) {
                    "Legacy playlist-entry ordinal cannot be negative"
                }
                val playlistId = LegacyIdMapper.playlist(row.playlistId).value
                check(database.legacyImportDao().playlist(playlistId) != null) {
                    "M4 playlist is missing for legacy playlist ${row.playlistId}"
                }
                val recordingId = LegacyIdMapper.recording(row.trackId).value
                check(database.recordingDao().get(recordingId) != null) {
                    "M1 recording is missing for legacy track ${row.trackId}"
                }
                val entryId = LegacyIdMapper.playlistEntry(row.playlistId, row.trackId).value
                val existing = database.legacyImportDao().playlistEntry(entryId)
                database
                    .legacyImportDao()
                    .upsertPlaylistEntry(
                        PlaylistEntryEntity(
                            playlistEntryId = entryId,
                            playlistId = playlistId,
                            recordingId = recordingId,
                            orderKey = sparseOrderKey(row.orderOrdinal),
                            addedAtEpochMs = existing?.addedAtEpochMs ?: importedAtEpochMs,
                        )
                    )
                check(
                    database
                        .legacyImportDao()
                        .makeRecordingDurable(recordingId, importedAtEpochMs) == 1
                ) {
                    "Imported playlist recording disappeared"
                }
            }
            val last = rows.last()
            updatePlaylistAudit(
                migrationId = migrationId,
                warningsJson = audit.warningsJson,
                subphase = "ENTRIES",
                checkpoint =
                    JSONObject()
                        .put("lastPlaylistId", last.playlistId)
                        .put("lastPosition", last.position)
                        .put("lastTrackId", last.trackId),
            )
        }

        val last = rows.last()
        return PlaylistMembershipPageImportResult(
            importedCount = rows.size,
            lastPlaylistId = last.playlistId,
            lastPosition = last.position,
            lastTrackId = last.trackId,
        )
    }

    private suspend fun updatePlaylistAudit(
        migrationId: String,
        warningsJson: String,
        subphase: String,
        checkpoint: JSONObject,
    ) {
        database
            .migrationAuditDao()
            .updateProgress(
                migrationId = migrationId,
                targetCountsJson =
                    JSONObject()
                        .put("playlist", database.legacyImportDao().playlistCount())
                        .put("playlistEntry", database.legacyImportDao().playlistEntryCount())
                        .put(
                            "checkpoint",
                            checkpoint
                                .put("phase", LegacyImportPhase.USER_PLAYLISTS.code)
                                .put("subphase", subphase),
                        )
                        .toString(),
                warningsJson = warningsJson,
                status = "IMPORTING",
            )
    }
}

private fun validatePlaylistPage(
    migrationId: String,
    rows: List<LegacyUserPlaylistRow>,
    importedAtEpochMs: Long,
) {
    require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
    require(importedAtEpochMs >= 0) { "Import timestamp cannot be negative" }
    require(rows.size <= MAX_PLAYLIST_IMPORT_PAGE_SIZE) { "Playlist import page is too large" }
    require(rows.map(LegacyUserPlaylistRow::playlistId).toSet().size == rows.size) {
        "Playlist import page contains duplicate legacy playlist IDs"
    }
    require(
        rows.zipWithNext().all { (first, second) ->
            compareValuesBy(first, second, { it.position }, { it.playlistId }) < 0
        }
    ) {
        "Playlist import page must be in stable legacy order"
    }
}

private fun validateMembershipPage(
    migrationId: String,
    rows: List<LegacyPlaylistMembershipRow>,
    importedAtEpochMs: Long,
) {
    require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
    require(importedAtEpochMs >= 0) { "Import timestamp cannot be negative" }
    require(rows.size <= MAX_PLAYLIST_IMPORT_PAGE_SIZE) {
        "Playlist membership import page is too large"
    }
    require(rows.map { it.playlistId to it.trackId }.toSet().size == rows.size) {
        "Playlist membership page contains duplicate legacy keys"
    }
    require(
        rows.zipWithNext().all { (first, second) ->
            compareValuesBy(first, second, { it.playlistId }, { it.position }, { it.trackId }) < 0
        }
    ) {
        "Playlist membership page must be in stable legacy order"
    }
}

private fun sparseOrderKey(orderOrdinal: Long): Long =
    Math.multiplyExact(Math.addExact(orderOrdinal, 1), PLAYLIST_ORDER_GAP)

private fun appendPlaylistWarnings(existingJson: String, additions: List<String>): String {
    val result = runCatching { JSONArray(existingJson) }.getOrElse { JSONArray() }
    additions.take((MAX_PLAYLIST_WARNINGS - result.length()).coerceAtLeast(0)).forEach(result::put)
    return result.toString()
}

private const val MAX_PLAYLIST_IMPORT_PAGE_SIZE = 500
private const val MAX_PLAYLIST_WARNINGS = 1_000
private const val PLAYLIST_ORDER_GAP = 1_024L
