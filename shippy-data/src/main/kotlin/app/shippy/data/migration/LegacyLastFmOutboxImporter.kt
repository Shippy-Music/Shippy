/*
 * Copyright (c) 2026 Auxio Project
 * LegacyLastFmOutboxImporter.kt is part of Auxio.
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
import app.shippy.data.db.entity.ArtistEntity
import app.shippy.data.db.entity.CanonicalFieldProvenanceEntity
import app.shippy.data.db.entity.LastFmScrobbleOutboxEntity
import app.shippy.data.db.entity.MetadataObservationEntity
import app.shippy.data.db.entity.RecordingArtistCreditEntity
import app.shippy.data.db.entity.RecordingEntity
import org.json.JSONArray
import org.json.JSONObject

internal data class LastFmOutboxPageImportResult(
    val acceptedCount: Int,
    val skippedCount: Int,
    val lastQueuedAtEpochMs: Long?,
    val lastId: String?,
    val warnings: List<String>,
)

internal class LegacyLastFmOutboxImporter(private val database: ShippyR16Database) {
    suspend fun importPage(
        migrationId: String,
        rows: List<LegacyLastFmOutboxRow>,
        importedAtEpochMs: Long,
    ): LastFmOutboxPageImportResult {
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        require(importedAtEpochMs >= 0) { "Import timestamp cannot be negative" }
        require(rows.size <= MAX_LASTFM_IMPORT_PAGE_SIZE) { "Last.fm import page is too large" }
        require(rows.map(LegacyLastFmOutboxRow::id).toSet().size == rows.size) {
            "Last.fm import page contains duplicate legacy IDs"
        }
        require(
            rows.zipWithNext().all { (first, second) ->
                compareValuesBy(first, second, { it.queuedAtEpochMs }, { it.id }) < 0
            }
        ) {
            "Last.fm import page must preserve stable FIFO order"
        }
        if (rows.isEmpty()) return LastFmOutboxPageImportResult(0, 0, null, null, emptyList())

        val warnings = mutableListOf<String>()
        var acceptedCount = 0
        database.withTransaction {
            val audit = requireActiveLegacyAudit(database, migrationId)
            for (row in rows) {
                val artist = row.artist.trim()
                val track = row.track.trim()
                if (artist.isEmpty() || track.isEmpty() || row.startedAtEpochSeconds <= 0) {
                    warnings += "${row.id}: invalid required scrobble metadata was skipped"
                    continue
                }
                val recordingId = resolveRecording(row, artist, track, importedAtEpochMs, warnings)
                val duration =
                    row.durationSeconds?.takeIf { it >= 0 }?.toLong()
                        ?: run {
                            if (row.durationSeconds != null) {
                                warnings += "${row.id}: negative scrobble duration was discarded"
                            }
                            null
                        }
                database
                    .lastFmOutboxDao()
                    .enqueue(
                        LastFmScrobbleOutboxEntity(
                            outboxId = LegacyIdMapper.lastFmOutbox(row.id),
                            listeningSessionId = LegacyIdMapper.lastFmListeningSession(row.id),
                            recordingId = recordingId,
                            artist = artist,
                            track = track,
                            album = row.album?.trim()?.takeIf(String::isNotEmpty),
                            durationSeconds = duration,
                            startedAtEpochSeconds = row.startedAtEpochSeconds,
                            chosenByUser = false,
                            queuedAtEpochMs =
                                row.queuedAtEpochMs.takeIf { it >= 0 } ?: importedAtEpochMs,
                            attemptCount = 0,
                            lastAttemptAtEpochMs = null,
                        )
                    )
                check(
                    database
                        .legacyImportDao()
                        .makeRecordingDurable(recordingId, importedAtEpochMs) == 1
                ) {
                    "Imported Last.fm recording disappeared"
                }
                acceptedCount++
            }

            val last = rows.last()
            database
                .migrationAuditDao()
                .updateProgress(
                    migrationId = migrationId,
                    targetCountsJson =
                        JSONObject()
                            .put("lastFmOutbox", database.lastFmOutboxDao().count())
                            .put(
                                "checkpoint",
                                JSONObject()
                                    .put("phase", LegacyImportPhase.LASTFM_OUTBOX.code)
                                    .put("lastQueuedAtEpochMs", last.queuedAtEpochMs)
                                    .put("lastId", last.id),
                            )
                            .toString(),
                    warningsJson = appendLastFmWarnings(audit.warningsJson, warnings),
                    status = "IMPORTING",
                )
        }

        val last = rows.last()
        return LastFmOutboxPageImportResult(
            acceptedCount = acceptedCount,
            skippedCount = rows.size - acceptedCount,
            lastQueuedAtEpochMs = last.queuedAtEpochMs,
            lastId = last.id,
            warnings = warnings,
        )
    }

    private suspend fun resolveRecording(
        row: LegacyLastFmOutboxRow,
        artistName: String,
        trackName: String,
        importedAtEpochMs: Long,
        warnings: MutableList<String>,
    ): String {
        row.trackId?.let { oldTrackId ->
            val mapped = LegacyIdMapper.recording(oldTrackId).value
            if (database.recordingDao().get(mapped) != null) return mapped
            warnings += "${row.id}: checkpoint track was missing; created an isolated fallback"
        }

        val recordingId = LegacyIdMapper.lastFmRecording(row.id).value
        if (database.recordingDao().get(recordingId) != null) return recordingId
        val artistId = LegacyIdMapper.lastFmArtist(row.id, artistName).value
        val observationId = LegacyIdMapper.lastFmObservation(row.id).value
        val durationMs =
            row.durationSeconds
                ?.takeIf { it >= 0 }
                ?.let { seconds ->
                    runCatching { Math.multiplyExact(seconds.toLong(), 1_000L) }.getOrNull()
                }
        val dao = database.legacyImportDao()
        dao.upsertArtists(
            listOf(
                ArtistEntity(
                    artistId = artistId,
                    canonicalName = artistName,
                    sortName = null,
                    disambiguation = null,
                    createdAtEpochMs = importedAtEpochMs,
                    updatedAtEpochMs = importedAtEpochMs,
                )
            )
        )
        dao.upsertRecording(
            RecordingEntity(
                recordingId = recordingId,
                canonicalTitle = trackName,
                durationMs = durationMs,
                versionKind = "UNKNOWN",
                versionLabel = null,
                explicitness = "UNKNOWN",
                preferredReleaseId = null,
                preferredArtworkId = null,
                retentionKind = "DURABLE",
                retainedUntilEpochMs = null,
                createdAtEpochMs = importedAtEpochMs,
                updatedAtEpochMs = importedAtEpochMs,
            )
        )
        dao.deleteArtistCredits(recordingId)
        dao.upsertArtistCredits(
            listOf(
                RecordingArtistCreditEntity(
                    recordingId = recordingId,
                    position = 0,
                    artistId = artistId,
                    creditedName = artistName,
                    joinPhrase = "",
                )
            )
        )
        dao.upsertObservation(
            MetadataObservationEntity(
                observationId = observationId,
                sourceType = "LEGACY_R15_LASTFM_OUTBOX",
                sourceReferenceId = null,
                assetId = null,
                title = trackName,
                artistCreditJson = JSONArray().put(artistName).toString(),
                releaseTitle = row.album,
                releaseArtist = null,
                durationMs = durationMs,
                artworkJson = null,
                releaseYear = null,
                trackNumber = null,
                discNumber = null,
                genresJson = null,
                versionHintsJson = null,
                externalIdsJson = JSONObject().put("legacyOutboxId", row.id).toString(),
                extrasJson = null,
                capturedAtEpochMs = importedAtEpochMs,
            )
        )
        dao.upsertProvenance(
            listOf("title", "artists", "release", "duration").map { field ->
                CanonicalFieldProvenanceEntity(
                    recordingId = recordingId,
                    fieldName = field,
                    selectedSourceType = "LEGACY_R15_LASTFM_OUTBOX",
                    selectedSourceId = observationId,
                    confidence = 1.0,
                    selectedAtEpochMs = importedAtEpochMs,
                )
            }
        )
        database.searchDao().refresh(recordingId)
        return recordingId
    }
}

private fun appendLastFmWarnings(existingJson: String, additions: List<String>): String {
    val result = runCatching { JSONArray(existingJson) }.getOrElse { JSONArray() }
    additions.take((MAX_LASTFM_WARNINGS - result.length()).coerceAtLeast(0)).forEach(result::put)
    return result.toString()
}

private const val MAX_LASTFM_IMPORT_PAGE_SIZE = 500
private const val MAX_LASTFM_WARNINGS = 1_000
