/*
 * Copyright (c) 2026 Auxio Project
 * LegacyCanonicalTrackImporter.kt is part of Auxio.
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
import app.shippy.core.identitymatch.RecordingVersionParser
import app.shippy.core.music.VersionKind
import app.shippy.data.db.ShippyR16Database
import app.shippy.data.db.entity.ArtistEntity
import app.shippy.data.db.entity.CanonicalFieldProvenanceEntity
import app.shippy.data.db.entity.MetadataObservationEntity
import app.shippy.data.db.entity.RecordingArtistCreditEntity
import app.shippy.data.db.entity.RecordingEntity
import app.shippy.data.db.entity.ReleaseEntity
import app.shippy.data.db.entity.ReleaseTrackEntity
import org.json.JSONArray
import org.json.JSONObject

internal data class CanonicalTrackPageImportResult(
    val importedCount: Int,
    val lastTrackId: String?,
    val warnings: List<String>,
)

internal class LegacyCanonicalTrackImporter(private val database: ShippyR16Database) {
    suspend fun importPage(
        migrationId: String,
        rows: List<LegacyCanonicalTrackRow>,
        importedAtEpochMs: Long,
    ): CanonicalTrackPageImportResult {
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        require(importedAtEpochMs >= 0) { "Import timestamp cannot be negative" }
        require(rows.size <= MAX_IMPORT_PAGE_SIZE) { "Canonical import page is too large" }
        require(rows.map(LegacyCanonicalTrackRow::trackId).toSet().size == rows.size) {
            "Canonical import page contains duplicate legacy track IDs"
        }
        require(rows.zipWithNext().all { (first, second) -> first.trackId < second.trackId }) {
            "Canonical import page must be in stable legacy track order"
        }
        if (rows.isEmpty()) return CanonicalTrackPageImportResult(0, null, emptyList())
        val mapped = rows.map { row -> map(row, importedAtEpochMs) }
        val warnings = mapped.flatMap(MappedLegacyTrack::warnings)

        database.withTransaction {
            val audit =
                database.migrationAuditDao().get(migrationId)
                    ?: error("Migration audit must exist before importing")
            check(audit.completedAtEpochMs == null) { "Completed migration cannot accept new rows" }
            check(audit.sourceVersion == LEGACY_SCHEMA_VERSION && audit.targetVersion == 1) {
                "Migration audit versions do not match the R15-to-R16 importer"
            }
            for (track in mapped) {
                val dao = database.legacyImportDao()
                track.release?.let { dao.upsertRelease(it) }
                dao.upsertArtists(track.artists)
                dao.upsertRecording(track.recording)
                dao.deleteArtistCredits(track.recording.recordingId)
                dao.upsertArtistCredits(track.credits)
                track.releaseTrack?.let { dao.upsertReleaseTrack(it) }
                dao.upsertObservation(track.observation)
                dao.upsertProvenance(track.provenance)
                database.searchDao().refresh(track.recording.recordingId)
            }
            val lastTrackId = rows.lastOrNull()?.trackId
            database
                .migrationAuditDao()
                .updateProgress(
                    migrationId = migrationId,
                    targetCountsJson =
                        JSONObject()
                            .put("recording", database.legacyImportDao().recordingCount())
                            .put(
                                "checkpoint",
                                JSONObject()
                                    .put("phase", LegacyImportPhase.CANONICAL_TRACKS.code)
                                    .put("lastStableKey", lastTrackId ?: JSONObject.NULL),
                            )
                            .toString(),
                    warningsJson = appendWarnings(audit.warningsJson, warnings),
                    status = "IMPORTING",
                )
        }
        return CanonicalTrackPageImportResult(rows.size, rows.lastOrNull()?.trackId, warnings)
    }

    private fun map(row: LegacyCanonicalTrackRow, importedAtEpochMs: Long): MappedLegacyTrack {
        val warnings = mutableListOf<String>()
        val recordingId = LegacyIdMapper.recording(row.trackId).value
        val title =
            row.title.trim().ifEmpty {
                warnings += "${row.trackId}: blank title replaced with Unknown title"
                "Unknown title"
            }
        val artistNames = decodeArtists(row, warnings)
        val artists =
            artistNames.mapIndexed { index, name ->
                ArtistEntity(
                    artistId = LegacyIdMapper.artist(row.trackId, index, name).value,
                    canonicalName = name,
                    sortName = null,
                    disambiguation = null,
                    createdAtEpochMs = importedAtEpochMs,
                    updatedAtEpochMs = importedAtEpochMs,
                )
            }
        val credits =
            artists.mapIndexed { index, artist ->
                RecordingArtistCreditEntity(
                    recordingId = recordingId,
                    position = index,
                    artistId = artist.artistId,
                    creditedName = artist.canonicalName,
                    joinPhrase = if (index == artists.lastIndex) "" else ", ",
                )
            }
        val album = row.album?.trim()?.takeIf(String::isNotEmpty)
        val releaseId = album?.let { LegacyIdMapper.release(row.trackId, it).value }
        val duration =
            row.durationMs?.takeIf { it >= 0 }
                ?: run {
                    if (row.durationMs != null)
                        warnings += "${row.trackId}: negative duration discarded"
                    null
                }
        val versionLabel = row.versionLabel?.trim()?.takeIf(String::isNotEmpty)
        val parsedVersion = RecordingVersionParser.parse(versionLabel)
        val versionKind =
            when {
                row.live -> VersionKind.LIVE
                row.remix -> VersionKind.REMIX
                else -> parsedVersion.kind
            }
        val recording =
            RecordingEntity(
                recordingId = recordingId,
                canonicalTitle = title,
                durationMs = duration,
                versionKind = versionKind.name,
                versionLabel = versionLabel,
                explicitness =
                    when (row.explicit) {
                        true -> "EXPLICIT"
                        false -> "CLEAN"
                        null -> "UNKNOWN"
                    },
                preferredReleaseId = releaseId,
                preferredArtworkId = null,
                retentionKind = "TRANSIENT",
                retainedUntilEpochMs = importedAtEpochMs + TRANSIENT_RETENTION_MS,
                createdAtEpochMs = importedAtEpochMs,
                updatedAtEpochMs = importedAtEpochMs,
            )
        val observationId = LegacyIdMapper.observation(row.trackId).value
        val observation =
            MetadataObservationEntity(
                observationId = observationId,
                sourceType = "LEGACY_R15_CANONICAL",
                sourceReferenceId = null,
                assetId = null,
                title = row.title,
                artistCreditJson = JSONArray(artistNames).toString(),
                releaseTitle = row.album,
                releaseArtist = null,
                durationMs = row.durationMs,
                artworkJson = row.artwork?.let { JSONObject().put("legacy", it).toString() },
                releaseYear = null,
                trackNumber = null,
                discNumber = null,
                genresJson = null,
                versionHintsJson =
                    JSONObject()
                        .put("label", row.versionLabel ?: JSONObject.NULL)
                        .put("live", row.live)
                        .put("remix", row.remix)
                        .toString(),
                externalIdsJson = JSONObject().put("legacyTrackId", row.trackId).toString(),
                extrasJson =
                    JSONObject()
                        .put("realm", row.realm)
                        .put("legacyArtistsEncoded", row.artists)
                        .put("explicit", row.explicit ?: JSONObject.NULL)
                        .toString(),
                capturedAtEpochMs = importedAtEpochMs,
            )
        val provenance =
            listOf("title", "artists", "release", "duration", "version", "explicitness", "artwork")
                .map { field ->
                    CanonicalFieldProvenanceEntity(
                        recordingId = recordingId,
                        fieldName = field,
                        selectedSourceType = "LEGACY_R15_CANONICAL",
                        selectedSourceId = observationId,
                        confidence = 1.0,
                        selectedAtEpochMs = importedAtEpochMs,
                    )
                }
        return MappedLegacyTrack(
            recording = recording,
            artists = artists,
            credits = credits,
            release =
                album?.let {
                    ReleaseEntity(
                        releaseId = requireNotNull(releaseId),
                        canonicalTitle = it,
                        releaseType = "UNKNOWN",
                        releaseYear = null,
                        artworkId = null,
                        createdAtEpochMs = importedAtEpochMs,
                        updatedAtEpochMs = importedAtEpochMs,
                    )
                },
            releaseTrack =
                album?.let {
                    ReleaseTrackEntity(
                        releaseTrackId =
                            LegacyIdMapper.stableValue("release-track", row.trackId, it),
                        releaseId = requireNotNull(releaseId),
                        recordingId = recordingId,
                        discNumber = null,
                        trackNumber = null,
                        displayTitle = title,
                        orderKey = 1_024,
                    )
                },
            observation = observation,
            provenance = provenance,
            warnings = warnings,
        )
    }

    private fun decodeArtists(
        row: LegacyCanonicalTrackRow,
        warnings: MutableList<String>,
    ): List<String> {
        val decoded = runCatching { decodeLegacyStringList(row.artists) }.getOrNull()
        if (!decoded.isNullOrEmpty() && decoded.all(String::isNotBlank)) return decoded
        warnings += "${row.trackId}: invalid legacy artist encoding preserved as fallback"
        return listOf(row.artists.trim().ifEmpty { "Unknown artist" })
    }

    private fun appendWarnings(existingJson: String, additions: List<String>): String {
        val result = runCatching { JSONArray(existingJson) }.getOrElse { JSONArray() }
        additions.take((MAX_WARNINGS - result.length()).coerceAtLeast(0)).forEach(result::put)
        return result.toString()
    }
}

private data class MappedLegacyTrack(
    val recording: RecordingEntity,
    val artists: List<ArtistEntity>,
    val credits: List<RecordingArtistCreditEntity>,
    val release: ReleaseEntity?,
    val releaseTrack: ReleaseTrackEntity?,
    val observation: MetadataObservationEntity,
    val provenance: List<CanonicalFieldProvenanceEntity>,
    val warnings: List<String>,
)

private fun decodeLegacyStringList(value: String): List<String> = buildList {
    var cursor = 0
    while (cursor < value.length) {
        val separator = value.indexOf(':', cursor)
        require(separator > cursor) { "Missing legacy artist length separator" }
        val size = value.substring(cursor, separator).toInt()
        require(size >= 0) { "Negative legacy artist length" }
        val start = separator + 1
        require(start + size <= value.length) { "Legacy artist value is truncated" }
        add(value.substring(start, start + size))
        cursor = start + size
    }
}

private const val MAX_IMPORT_PAGE_SIZE = 500
private const val MAX_WARNINGS = 1_000
private const val TRANSIENT_RETENTION_MS = 30L * 24 * 60 * 60 * 1_000
