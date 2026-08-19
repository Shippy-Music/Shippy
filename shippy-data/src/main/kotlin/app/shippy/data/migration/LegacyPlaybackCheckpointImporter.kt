/*
 * Copyright (c) 2026 Auxio Project
 * LegacyPlaybackCheckpointImporter.kt is part of Auxio.
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
import app.shippy.data.db.entity.PlaybackCheckpointEntity
import app.shippy.data.db.entity.PlaybackCheckpointEntryEntity
import app.shippy.data.playback.PlaybackCheckpointIntegrity
import org.json.JSONArray
import org.json.JSONObject

internal data class PlaybackCheckpointImportResult(
    val importedEntryCount: Int,
    val skippedEntryCount: Int,
    val stored: Boolean,
    val warnings: List<String>,
)

internal class LegacyPlaybackCheckpointImporter(private val database: ShippyR16Database) {
    suspend fun importCheckpoint(
        migrationId: String,
        row: LegacyPlaybackCheckpointRow?,
        importedAtEpochMs: Long,
    ): PlaybackCheckpointImportResult {
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        require(importedAtEpochMs >= 0) { "Import timestamp cannot be negative" }
        require(row == null || row.items.size <= MAX_CHECKPOINT_IMPORT_ITEMS) {
            "Playback checkpoint import is too large"
        }

        val warnings = mutableListOf<String>()
        var converted: ConvertedPlaybackCheckpoint? = null
        database.withTransaction {
            val audit = requireActiveLegacyAudit(database, migrationId)
            converted = row?.convert(importedAtEpochMs, warnings)
            if (converted == null) {
                database.playbackCheckpointDao().clear(ACTIVE_SLOT)
            } else {
                checkNotNull(converted).entries.forEach { entry ->
                    check(
                        database
                            .legacyImportDao()
                            .makeRecordingDurable(entry.recordingId, importedAtEpochMs) == 1
                    ) {
                        "Imported playback Recording disappeared"
                    }
                }
                database
                    .playbackCheckpointDao()
                    .replace(checkNotNull(converted).checkpoint, checkNotNull(converted).entries)
            }
            database
                .migrationAuditDao()
                .updateProgress(
                    migrationId = migrationId,
                    targetCountsJson =
                        JSONObject()
                            .put("playbackCheckpointEntries", converted?.entries?.size ?: 0)
                            .put(
                                "checkpoint",
                                JSONObject()
                                    .put("phase", LegacyImportPhase.PLAYBACK_CHECKPOINT.code),
                            )
                            .toString(),
                    warningsJson = appendPlaybackWarnings(audit.warningsJson, warnings),
                    status = "IMPORTING",
                )
        }

        return PlaybackCheckpointImportResult(
            importedEntryCount = converted?.entries?.size ?: 0,
            skippedEntryCount = row?.items?.size?.minus(converted?.entries?.size ?: 0) ?: 0,
            stored = converted != null,
            warnings = warnings,
        )
    }

    private suspend fun LegacyPlaybackCheckpointRow.convert(
        importedAtEpochMs: Long,
        warnings: MutableList<String>,
    ): ConvertedPlaybackCheckpoint? {
        if (slot != ACTIVE_SLOT) {
            warnings += "$slot: unsupported playback checkpoint slot was skipped"
            return null
        }
        if (items.isEmpty()) {
            warnings += "$slot: empty playback checkpoint was skipped"
            return null
        }

        val sorted = items.sortedWith(compareBy({ it.heapPosition }, { it.queueItemId }))
        val seenPositions = mutableSetOf<Int>()
        val seenQueueItems = mutableSetOf<String>()
        val survivors = mutableListOf<SurvivingCheckpointItem>()
        for (item in sorted) {
            if (item.heapPosition < 0 || !seenPositions.add(item.heapPosition)) {
                warnings += "$slot/${item.queueItemId}: invalid duplicate heap position was skipped"
                continue
            }
            if (
                item.queueItemId.isBlank() ||
                    item.trackId.isBlank() ||
                    !seenQueueItems.add(item.queueItemId)
            ) {
                warnings +=
                    "$slot/${item.queueItemId}: invalid duplicate queue identity was skipped"
                continue
            }
            val recordingId = LegacyIdMapper.recording(item.trackId).value
            if (database.recordingDao().get(recordingId) == null) {
                warnings += "$slot/${item.queueItemId}: missing M1 Recording was skipped"
                continue
            }
            survivors +=
                SurvivingCheckpointItem(
                    oldHeapPosition = item.heapPosition,
                    queueEntryId = LegacyIdMapper.queueEntry(item.queueItemId).value,
                    recordingId = recordingId,
                    contextId = item.contextId?.takeIf(String::isNotBlank),
                    contributorId = item.contributorId?.takeIf(String::isNotBlank),
                )
        }
        if (survivors.isEmpty()) {
            warnings += "$slot: no valid playback checkpoint entries survived"
            return null
        }

        val oldPositions = sorted.map(LegacyPlaybackCheckpointItemRow::heapPosition)
        val parsedMapping = shuffledMapping.parseMapping()
        val mappingIsValid =
            parsedMapping != null &&
                parsedMapping.isNotEmpty() &&
                oldPositions == oldPositions.indices.toList() &&
                parsedMapping.size == oldPositions.size &&
                parsedMapping.sorted() == oldPositions.indices.toList()
        if (shuffledMapping.isNotEmpty() && !mappingIsValid) {
            warnings += "$slot: inconsistent shuffle mapping fell back to base order"
        }
        val survivorByOldPosition = survivors.associateBy(SurvivingCheckpointItem::oldHeapPosition)
        val base = survivors.sortedBy(SurvivingCheckpointItem::oldHeapPosition)
        val oldTraversal =
            if (mappingIsValid) {
                checkNotNull(parsedMapping)
            } else {
                base.map(SurvivingCheckpointItem::oldHeapPosition)
            }
        val traversal = oldTraversal.mapNotNull(survivorByOldPosition::get)

        val selectedSurvivor = survivorByOldPosition[heapIndex]
        val current =
            selectedSurvivor
                ?: oldTraversal
                    .indexOf(heapIndex)
                    .takeIf { it >= 0 }
                    ?.let { selectedIndex ->
                        (selectedIndex downTo 0).firstNotNullOfOrNull {
                            survivorByOldPosition[oldTraversal[it]]
                        }
                    }
                ?: traversal.firstOrNull()
                ?: base.first()
        if (selectedSurvivor == null) {
            warnings += "$slot: invalid current queue entry moved to the nearest surviving entry"
        }

        val repeat =
            when (repeatMode) {
                "NONE",
                "OFF" -> "OFF"
                "TRACK",
                "ONE" -> "ONE"
                "ALL" -> "ALL"
                else -> {
                    warnings += "$slot: invalid repeat mode fell back to OFF"
                    "OFF"
                }
            }
        val position =
            if (selectedSurvivor == null) {
                0L
            } else {
                positionMs.coerceAtLeast(0).also {
                    if (positionMs < 0) warnings += "$slot: negative playback position was reset"
                }
            }
        val entries =
            base.mapIndexed { index, item ->
                PlaybackCheckpointEntryEntity(
                    slot = ACTIVE_SLOT,
                    queueEntryId = item.queueEntryId,
                    position = index,
                    recordingId = item.recordingId,
                    originJson =
                        item.contextId?.let { contextId ->
                            JSONObject().put("legacyContextId", contextId).toString()
                        },
                    contributorId = item.contributorId,
                    presentationFallbackJson = "{}",
                )
            }
        val baseOrderJson = JSONArray(base.map(SurvivingCheckpointItem::queueEntryId)).toString()
        val traversalOrderJson =
            JSONArray(traversal.map(SurvivingCheckpointItem::queueEntryId)).toString()
        val checkpointWithoutChecksum =
            PlaybackCheckpointEntity(
                slot = ACTIVE_SLOT,
                checkpointVersion = CHECKPOINT_VERSION,
                sessionId = LegacyIdMapper.stableValue("playback-session", slot),
                currentQueueEntryId = current.queueEntryId,
                positionMs = position,
                playingIntent = false,
                repeatMode = repeat,
                shuffleEnabled = mappingIsValid,
                shuffleSeed = null,
                baseOrderJson = baseOrderJson,
                traversalOrderJson = traversalOrderJson,
                updatedAtEpochMs = importedAtEpochMs,
                checksum = "pending",
            )
        return ConvertedPlaybackCheckpoint(
            checkpoint =
                checkpointWithoutChecksum.copy(
                    checksum =
                        PlaybackCheckpointIntegrity.checksum(checkpointWithoutChecksum, entries)
                ),
            entries = entries,
        )
    }
}

private data class SurvivingCheckpointItem(
    val oldHeapPosition: Int,
    val queueEntryId: String,
    val recordingId: String,
    val contextId: String?,
    val contributorId: String?,
)

private data class ConvertedPlaybackCheckpoint(
    val checkpoint: PlaybackCheckpointEntity,
    val entries: List<PlaybackCheckpointEntryEntity>,
)

private fun String.parseMapping(): List<Int>? =
    if (isEmpty()) emptyList() else runCatching { split(',').map(String::toInt) }.getOrNull()

private fun appendPlaybackWarnings(existingJson: String, additions: List<String>): String {
    val result = runCatching { JSONArray(existingJson) }.getOrElse { JSONArray() }
    additions.take((MAX_PLAYBACK_WARNINGS - result.length()).coerceAtLeast(0)).forEach(result::put)
    return result.toString()
}

private const val ACTIVE_SLOT = "active"
private const val CHECKPOINT_VERSION = 1
private const val MAX_CHECKPOINT_IMPORT_ITEMS = 10_000
private const val MAX_PLAYBACK_WARNINGS = 1_000
