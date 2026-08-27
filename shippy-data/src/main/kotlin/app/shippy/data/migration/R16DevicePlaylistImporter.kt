/*
 * Copyright (c) 2026 Auxio Project
 * R16DevicePlaylistImporter.kt is part of Auxio.
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
import app.shippy.core.identity.RecordingId
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.LibraryLayoutEntryEntity
import app.shippy.data.db.entity.PlaylistEntity
import app.shippy.data.db.entity.PlaylistEntryEntity
import org.json.JSONArray
import org.json.JSONObject

data class R16DevicePlaylistImport(
    val originKey: String,
    val name: String,
    val orderedRecordingIds: List<RecordingId>,
) {
    init {
        require(originKey.isNotBlank() && originKey == originKey.trim()) {
            "Device playlist origin key must be non-blank and trimmed"
        }
        require(orderedRecordingIds.size <= MAX_DEVICE_PLAYLIST_ENTRIES) {
            "Device playlist contains too many entries"
        }
    }
}

data class R16DevicePlaylistPageResult(
    val processedPlaylistCount: Int,
    val importedPlaylistCount: Int,
    val reusedPlaylistCount: Int,
    val importedEntryCount: Int,
    val complete: Boolean,
)

interface R16DevicePlaylistImportRepository {
    suspend fun importPage(
        migrationId: String,
        snapshotOriginKeys: Set<String>,
        startOrdinal: Int,
        playlists: List<R16DevicePlaylistImport>,
        importedAtEpochMs: Long,
    ): R16DevicePlaylistPageResult
}

internal class RoomR16DevicePlaylistImportRepository(private val database: ShippyR16Database) :
    R16DevicePlaylistImportRepository {
    override suspend fun importPage(
        migrationId: String,
        snapshotOriginKeys: Set<String>,
        startOrdinal: Int,
        playlists: List<R16DevicePlaylistImport>,
        importedAtEpochMs: Long,
    ): R16DevicePlaylistPageResult {
        validatePage(migrationId, snapshotOriginKeys, startOrdinal, playlists, importedAtEpochMs)
        var imported = 0
        var reused = 0
        var entries = 0
        var acceptedPlaylists = 0

        database.withTransaction {
            val audit = requireActiveLegacyAudit(database, migrationId)
            val importDao = database.legacyImportDao()
            if (startOrdinal == 0) {
                importDao
                    .playlistsByOrigin(ORIGIN_KIND)
                    .filter { it.originKey !in snapshotOriginKeys }
                    .forEach { stale ->
                        importDao.deleteLibraryLayout(PLAYLIST_LAYOUT_TYPE, stale.playlistId)
                        check(database.playlistDao().deletePlaylist(stale.playlistId) == 1) {
                            "Stale device playlist disappeared during replacement"
                        }
                    }
            }

            val baseOrder = importDao.maximumPlaylistOrderExcluding(ORIGIN_KIND) ?: 0L
            playlists.forEachIndexed { pageIndex, input ->
                // M4 owns legacy Shippy playlists. Reuse only an exact origin-key match; do not
                // merge by name/content or mutate the legacy row and its memberships.
                if (
                    importDao.playlistByOrigin(LEGACY_PLAYLIST_ORIGIN_KIND, input.originKey) != null
                ) {
                    reused++
                    return@forEachIndexed
                }
                val deterministicId = LegacyIdMapper.devicePlaylist(input.originKey).value
                val existing = importDao.playlistByOrigin(ORIGIN_KIND, input.originKey)
                val occupied = importDao.playlist(deterministicId)
                check(
                    occupied == null ||
                        (occupied.originKind == ORIGIN_KIND &&
                            occupied.originKey == input.originKey)
                ) {
                    "Deterministic device playlist identity is already owned"
                }
                val playlistId = existing?.playlistId ?: deterministicId
                val orderOrdinal = Math.addExact(startOrdinal, pageIndex)
                val orderKey = deviceOrderKey(baseOrder, orderOrdinal)
                val normalizedName = input.name.trim().ifEmpty { "Untitled" }
                importDao.upsertPlaylist(
                    PlaylistEntity(
                        playlistId = playlistId,
                        name = normalizedName,
                        pinned = existing?.pinned ?: false,
                        libraryOrderKey = orderKey,
                        artworkOverride = existing?.artworkOverride,
                        displaySortMode = "CUSTOM",
                        displaySortDirection = "ASC",
                        originKind = ORIGIN_KIND,
                        originKey = input.originKey,
                        createdAtEpochMs = existing?.createdAtEpochMs ?: importedAtEpochMs,
                        updatedAtEpochMs = importedAtEpochMs,
                    )
                )
                importDao.upsertLibraryLayout(
                    LibraryLayoutEntryEntity(
                        targetType = PLAYLIST_LAYOUT_TYPE,
                        targetId = playlistId,
                        pinned = existing?.pinned ?: false,
                        orderKey = orderKey,
                    )
                )

                val previousEntries = database.playlistDao().entries(playlistId)
                val previousAddedAtByEntryId =
                    previousEntries.associateBy(PlaylistEntryEntity::playlistEntryId)
                if (previousEntries.isNotEmpty()) {
                    database.playlistDao().deleteEntries(previousEntries)
                }
                val nextEntries =
                    input.orderedRecordingIds.mapIndexed { position, recordingId ->
                        val playlistEntryId =
                            LegacyIdMapper.devicePlaylistEntry(
                                    input.originKey,
                                    position,
                                    recordingId,
                                )
                                .value
                        check(database.recordingDao().get(recordingId.value) != null) {
                            "M5 recording is missing for device playlist entry"
                        }
                        check(
                            importDao.makeRecordingDurable(recordingId.value, importedAtEpochMs) ==
                                1
                        ) {
                            "M5 device playlist recording disappeared"
                        }
                        PlaylistEntryEntity(
                            playlistEntryId = playlistEntryId,
                            playlistId = playlistId,
                            recordingId = recordingId.value,
                            orderKey = entryOrderKey(position),
                            addedAtEpochMs =
                                previousAddedAtByEntryId[playlistEntryId]?.addedAtEpochMs
                                    ?: importedAtEpochMs,
                        )
                    }
                if (nextEntries.isNotEmpty()) database.playlistDao().insertEntries(nextEntries)
                if (existing == null) imported++ else reused++
                acceptedPlaylists++
                entries += nextEntries.size
            }

            val processed = Math.addExact(startOrdinal, playlists.size)
            val complete = processed == snapshotOriginKeys.size
            val targetCounts =
                audit.targetCountsJson?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: JSONObject()
            MigrationExpectedCountEvidence.recordPage(
                target = targetCounts,
                phase = LegacyImportPhase.DEVICE_PLAYLISTS,
                pageToken = "M5:ordinal:$startOrdinal",
                delta =
                    MigrationExpectedCountDelta(
                        playlists = acceptedPlaylists.toLong(),
                        playlistEntries = entries.toLong(),
                    ),
            )
            if (complete) {
                MigrationExpectedCountEvidence.markPhaseComplete(
                    target = targetCounts,
                    phase = LegacyImportPhase.DEVICE_PLAYLISTS,
                    allowEmpty = false,
                )
            }
            targetCounts
                .put("devicePlaylists", importDao.playlistCountByOrigin(ORIGIN_KIND))
                .put("devicePlaylistEntries", importDao.playlistEntryCountByOrigin(ORIGIN_KIND))
                .put(
                    "checkpoint",
                    JSONObject()
                        .put("phase", LegacyImportPhase.DEVICE_PLAYLISTS.code)
                        .put("processedPlaylistCount", processed)
                        .put("snapshotPlaylistCount", snapshotOriginKeys.size)
                        .put("complete", complete),
                )
            val warnings = runCatching { JSONArray(audit.warningsJson) }.getOrElse { JSONArray() }
            playlists
                .filter { it.name.isBlank() }
                .take((MAX_WARNINGS - warnings.length()).coerceAtLeast(0))
                .forEach { warnings.put("${it.originKey}: blank device playlist name replaced") }
            database
                .migrationAuditDao()
                .updateProgress(
                    migrationId = migrationId,
                    targetCountsJson = targetCounts.toString(),
                    warningsJson = warnings.toString(),
                    status = "IMPORTING",
                )
        }

        val processed = Math.addExact(startOrdinal, playlists.size)
        return R16DevicePlaylistPageResult(
            processedPlaylistCount = processed,
            importedPlaylistCount = imported,
            reusedPlaylistCount = reused,
            importedEntryCount = entries,
            complete = processed == snapshotOriginKeys.size,
        )
    }
}

private fun validatePage(
    migrationId: String,
    snapshotOriginKeys: Set<String>,
    startOrdinal: Int,
    playlists: List<R16DevicePlaylistImport>,
    importedAtEpochMs: Long,
) {
    require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
    require(importedAtEpochMs >= 0) { "Import timestamp cannot be negative" }
    require(startOrdinal >= 0) { "Device playlist page ordinal cannot be negative" }
    require(snapshotOriginKeys.size <= MAX_DEVICE_PLAYLISTS) {
        "Device playlist snapshot is too large"
    }
    require(snapshotOriginKeys.all { it.isNotBlank() && it == it.trim() }) {
        "Device playlist snapshot contains an invalid origin key"
    }
    require(playlists.size <= MAX_DEVICE_PLAYLIST_PAGE_SIZE) {
        "Device playlist import page is too large"
    }
    require(playlists.map(R16DevicePlaylistImport::originKey).toSet().size == playlists.size) {
        "Device playlist page contains duplicate origin keys"
    }
    require(playlists.all { it.originKey in snapshotOriginKeys }) {
        "Device playlist page is not part of the declared snapshot"
    }
    require(Math.addExact(startOrdinal, playlists.size) <= snapshotOriginKeys.size) {
        "Device playlist page exceeds the declared snapshot"
    }
    require(playlists.isNotEmpty() || (startOrdinal == 0 && snapshotOriginKeys.isEmpty())) {
        "Only an empty device playlist snapshot may use an empty page"
    }
}

private fun deviceOrderKey(baseOrder: Long, ordinal: Int): Long =
    Math.addExact(baseOrder, entryOrderKey(ordinal))

private fun entryOrderKey(ordinal: Int): Long =
    Math.multiplyExact(Math.addExact(ordinal.toLong(), 1L), ORDER_GAP)

private const val ORIGIN_KIND = "MUSIKR_DEVICE"
private const val LEGACY_PLAYLIST_ORIGIN_KIND = "LEGACY_SHIPPY"
private const val PLAYLIST_LAYOUT_TYPE = "PLAYLIST"
private const val ORDER_GAP = 1_024L
private const val MAX_DEVICE_PLAYLIST_PAGE_SIZE = 100
private const val MAX_DEVICE_PLAYLISTS = 10_000
private const val MAX_DEVICE_PLAYLIST_ENTRIES = 100_000
private const val MAX_WARNINGS = 1_000
