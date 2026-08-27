/*
 * Copyright (c) 2026 Auxio Project
 * LegacyCandidateImporter.kt is part of Auxio.
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
import app.shippy.data.db.entity.MediaAssetEntity
import app.shippy.data.db.entity.MetadataObservationEntity
import app.shippy.data.db.entity.SourceReferenceEntity
import app.shippy.data.db.transaction.CanonicalWriteTransactions
import org.json.JSONArray
import org.json.JSONObject

internal data class VerifiedLegacyAsset(
    val locationType: String,
    val location: String,
    val documentId: String? = null,
    val mediaStoreId: Long? = null,
    val displayName: String? = null,
    val contentLength: Long,
    val contentChecksum: String? = null,
) {
    init {
        require(locationType.isNotBlank()) { "Verified asset location type must not be blank" }
        require(location.isNotBlank()) { "Verified asset location must not be blank" }
        require(contentLength >= 0) { "Verified asset length cannot be negative" }
        require(contentChecksum == null || contentChecksum.isNotBlank()) {
            "Verified asset checksum must not be blank"
        }
    }
}

internal fun interface LegacyAssetVerifier {
    suspend fun verify(row: LegacyCanonicalCandidateRow): VerifiedLegacyAsset?
}

internal data class CandidatePageImportResult(
    val importedSourceCount: Int,
    val importedAssetCount: Int,
    val lastTrackId: String?,
    val lastCandidateId: String?,
    val warnings: List<String>,
)

internal class LegacyCandidateImporter(
    private val database: ShippyR16Database,
    private val assetVerifier: LegacyAssetVerifier,
) {
    suspend fun importPage(
        migrationId: String,
        rows: List<LegacyCanonicalCandidateRow>,
        importedAtEpochMs: Long,
    ): CandidatePageImportResult {
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        require(importedAtEpochMs >= 0) { "Import timestamp cannot be negative" }
        require(rows.size <= MAX_CANDIDATE_IMPORT_PAGE_SIZE) {
            "Candidate import page is too large"
        }
        require(rows.map { it.trackId to it.candidateId }.toSet().size == rows.size) {
            "Candidate import page contains duplicate legacy keys"
        }
        require(
            rows.zipWithNext().all { (first, second) ->
                compareValuesBy(first, second, { it.trackId }, { it.candidateId }) < 0
            }
        ) {
            "Candidate import page must be in stable legacy key order"
        }
        if (rows.isEmpty()) {
            return CandidatePageImportResult(0, 0, null, null, emptyList())
        }

        // File and SAF I/O must finish before Room owns the page transaction.
        val verifiedAssets =
            rows
                .filter { row -> row.kind == LOCAL_KIND || row.kind == DOWNLOAD_KIND }
                .associateWith { row -> assetVerifier.verify(row) }
        val warnings = mutableListOf<String>()
        var importedSourceCount = 0
        var importedAssetCount = 0
        val writes = CanonicalWriteTransactions(database)

        database.withTransaction {
            val audit =
                database.migrationAuditDao().get(migrationId)
                    ?: error("Migration audit must exist before importing")
            check(audit.completedAtEpochMs == null) { "Completed migration cannot accept new rows" }
            check(
                audit.sourceVersion in SUPPORTED_LEGACY_SCHEMA_VERSIONS &&
                    audit.targetVersion == ShippyR16Database.SCHEMA_VERSION
            ) {
                "Migration audit versions (${audit.sourceVersion}->${audit.targetVersion}) do not match the R15-to-R16 importer ($LEGACY_SCHEMA_VERSION->${ShippyR16Database.SCHEMA_VERSION})"
            }

            for (row in rows) {
                val recordingId = LegacyIdMapper.recording(row.trackId).value
                check(database.recordingDao().get(recordingId) != null) {
                    "M1 recording is missing for legacy track ${row.trackId}"
                }
                val verifiedAsset = verifiedAssets[row]
                val usableAsset = verifiedAsset?.takeIf { assetMatches(row, it, warnings) }
                val acceptedAsset =
                    usableAsset?.takeUnless { verified ->
                        val conflictingAsset =
                            database
                                .assetDao()
                                .exactLocation(verified.locationType, verified.location)
                        if (
                            conflictingAsset != null && conflictingAsset.recordingId != recordingId
                        ) {
                            warnings +=
                                "${row.stableKey()}: verified asset location belongs to another recording"
                            true
                        } else {
                            false
                        }
                    }
                val source =
                    row.toSource(
                        recordingId,
                        assetAvailable = acceptedAsset != null,
                        importedAtEpochMs = importedAtEpochMs,
                    )

                if (source == null) {
                    database
                        .legacyImportDao()
                        .upsertObservation(row.toObservation(null, null, importedAtEpochMs))
                    warnings +=
                        when (row.kind) {
                            CREW_TEMPORARY_KIND,
                            CREW_PEER_KIND ->
                                "${row.stableKey()}: temporary Crew candidate was not retained"
                            PROVIDER_KIND,
                            LOCAL_KIND,
                            DOWNLOAD_KIND -> "${row.stableKey()}: exact source key was blank"
                            else -> "${row.stableKey()}: unsupported candidate kind ${row.kind}"
                        }
                    continue
                }

                val existing =
                    database
                        .sourceDao()
                        .exact(source.providerId, source.itemType, source.sourceItemId)
                if (existing?.recordingId != null && existing.recordingId != recordingId) {
                    database
                        .legacyImportDao()
                        .upsertObservation(row.toObservation(null, null, importedAtEpochMs))
                    warnings +=
                        "${row.stableKey()}: exact source key already belongs to another recording"
                    continue
                }
                val sourceForIngestion =
                    if (existing == null) {
                        source
                    } else {
                        source.copy(
                            sourceReferenceId = existing.sourceReferenceId,
                            createdAtEpochMs = existing.createdAtEpochMs,
                        )
                    }

                val observation =
                    row.toObservation(sourceForIngestion, acceptedAsset, importedAtEpochMs)

                val stored = writes.ingestExactSource(observation, sourceForIngestion)
                if (stored.recordingId == null) {
                    database
                        .sourceDao()
                        .link(
                            stored.sourceReferenceId,
                            recordingId,
                            AUTOMATICALLY_LINKED,
                            importedAtEpochMs,
                        )
                }
                importedSourceCount++

                if (row.kind == LOCAL_KIND || row.kind == DOWNLOAD_KIND) {
                    if (acceptedAsset == null) {
                        if (verifiedAsset == null) {
                            warnings +=
                                "${row.stableKey()}: candidate had no verified durable asset"
                        }
                        continue
                    }
                    database
                        .assetDao()
                        .upsert(row.toAsset(recordingId, stored, acceptedAsset, importedAtEpochMs))
                    database.libraryMembershipDao().refresh(recordingId)
                    importedAssetCount++
                }
            }

            val last = rows.last()
            database
                .migrationAuditDao()
                .updateProgress(
                    migrationId = migrationId,
                    targetCountsJson =
                        JSONObject()
                            .put("recording", database.legacyImportDao().recordingCount())
                            .put("sourceReference", database.legacyImportDao().sourceCount())
                            .put("mediaAsset", database.legacyImportDao().assetCount())
                            .put(
                                "checkpoint",
                                JSONObject()
                                    .put("phase", LegacyImportPhase.CANDIDATES.code)
                                    .put("lastTrackId", last.trackId)
                                    .put("lastCandidateId", last.candidateId),
                            )
                            .toString(),
                    warningsJson = appendCandidateWarnings(audit.warningsJson, warnings),
                    status = "IMPORTING",
                )
        }

        val last = rows.last()
        return CandidatePageImportResult(
            importedSourceCount = importedSourceCount,
            importedAssetCount = importedAssetCount,
            lastTrackId = last.trackId,
            lastCandidateId = last.candidateId,
            warnings = warnings,
        )
    }
}

private fun LegacyCanonicalCandidateRow.toSource(
    recordingId: String,
    assetAvailable: Boolean,
    importedAtEpochMs: Long,
): SourceReferenceEntity? {
    val provider =
        when (kind) {
            PROVIDER_KIND -> providerId?.trim()?.takeIf(String::isNotEmpty) ?: sourceId.trim()
            LOCAL_KIND -> LOCAL_PROVIDER_ID
            DOWNLOAD_KIND -> DOWNLOAD_PROVIDER_ID
            else -> return null
        }
    if (provider.isBlank() || sourceItemId.isBlank()) return null
    val observationId = LegacyIdMapper.candidateObservation(trackId, candidateId).value
    return SourceReferenceEntity(
        sourceReferenceId = LegacyIdMapper.source(trackId, candidateId).value,
        recordingId = recordingId,
        providerId = provider,
        sourceKind = provider.toLegacyReferenceKind(kind),
        itemType = if (kind == LOCAL_KIND) LOCAL_ITEM_TYPE else RECORDING_ITEM_TYPE,
        sourceItemId = sourceItemId,
        originalUrl = null,
        availabilityState =
            if (kind == LOCAL_KIND || kind == DOWNLOAD_KIND) {
                if (assetAvailable) "AVAILABLE" else "UNAVAILABLE"
            } else {
                availability.toR16Availability()
            },
        availabilityCheckedAtEpochMs = importedAtEpochMs,
        availabilityExpiresAtEpochMs = null,
        failureKind = null,
        failureRetryable = null,
        identityStatus = AUTOMATICALLY_LINKED,
        rawMetadataObservationId = observationId,
        createdAtEpochMs = importedAtEpochMs,
        updatedAtEpochMs = importedAtEpochMs,
    )
}

private fun String.toLegacyReferenceKind(candidateKind: String): String =
    when {
        candidateKind == LOCAL_KIND -> "LOCAL_FILE"
        candidateKind == DOWNLOAD_KIND -> "SHIPPY_DOWNLOAD"
        this == "jiosaavn" -> "JIOSAAVN"
        this == "youtube" || this == "youtube_music" -> "YOUTUBE"
        else -> "IMPORTED_LINK"
    }

private fun LegacyCanonicalCandidateRow.toObservation(
    source: SourceReferenceEntity?,
    asset: VerifiedLegacyAsset?,
    importedAtEpochMs: Long,
): MetadataObservationEntity =
    MetadataObservationEntity(
        observationId = LegacyIdMapper.candidateObservation(trackId, candidateId).value,
        sourceType = "LEGACY_R15_CANDIDATE",
        sourceReferenceId = source?.sourceReferenceId,
        assetId = asset?.let { LegacyIdMapper.asset(trackId, candidateId).value },
        title = null,
        artistCreditJson = null,
        releaseTitle = null,
        releaseArtist = null,
        durationMs = null,
        artworkJson = null,
        releaseYear = null,
        trackNumber = null,
        discNumber = null,
        genresJson = null,
        versionHintsJson = null,
        externalIdsJson =
            JSONObject()
                .put("legacyTrackId", trackId)
                .put("legacyCandidateId", candidateId)
                .put("legacySourceItemId", sourceItemId)
                .toString(),
        extrasJson =
            JSONObject()
                .put("position", position)
                .put("kind", kind)
                .put("sourceId", sourceId)
                .put("providerId", providerId ?: JSONObject.NULL)
                .put("availability", availability)
                .put("legacyLocatorPresent", locator != null)
                .put("mimeType", mimeType ?: JSONObject.NULL)
                .put("container", container ?: JSONObject.NULL)
                .put("bitrateBps", bitrateBps ?: JSONObject.NULL)
                .put("contentLength", contentLength ?: JSONObject.NULL)
                .toString(),
        capturedAtEpochMs = importedAtEpochMs,
    )

private fun LegacyCanonicalCandidateRow.toAsset(
    recordingId: String,
    source: SourceReferenceEntity,
    verified: VerifiedLegacyAsset,
    importedAtEpochMs: Long,
): MediaAssetEntity =
    MediaAssetEntity(
        assetId = LegacyIdMapper.asset(trackId, candidateId).value,
        recordingId = recordingId,
        sourceReferenceId = source.sourceReferenceId,
        assetKind = if (kind == LOCAL_KIND) "LOCAL_FILE" else "SHIPPY_DOWNLOAD",
        assetState = "AVAILABLE",
        locationType = verified.locationType,
        location = verified.location,
        documentId = verified.documentId,
        mediaStoreId = verified.mediaStoreId,
        displayName = verified.displayName,
        mimeType = mimeType,
        container = container,
        codec = null,
        bitrateBps = bitrateBps?.takeIf { it > 0 }?.toLong(),
        sampleRateHz = null,
        channelCount = null,
        contentLength = verified.contentLength,
        contentChecksum = verified.contentChecksum,
        fingerprintId = null,
        createdAtEpochMs = importedAtEpochMs,
        updatedAtEpochMs = importedAtEpochMs,
        lastVerifiedAtEpochMs = importedAtEpochMs,
    )

private fun assetMatches(
    row: LegacyCanonicalCandidateRow,
    verified: VerifiedLegacyAsset,
    warnings: MutableList<String>,
): Boolean {
    val legacyLength = row.contentLength
    if (legacyLength != null && legacyLength != verified.contentLength) {
        warnings +=
            "${row.stableKey()}: verified asset length ${verified.contentLength} did not match legacy $legacyLength"
        return false
    }
    return true
}

private fun String.toR16Availability(): String =
    when (this) {
        "AVAILABLE",
        "RESOLVABLE",
        "UNAVAILABLE" -> this
        else -> "UNKNOWN"
    }

private fun LegacyCanonicalCandidateRow.stableKey(): String = "$trackId/$candidateId"

private fun appendCandidateWarnings(existingJson: String, additions: List<String>): String {
    val result = runCatching { JSONArray(existingJson) }.getOrElse { JSONArray() }
    additions.take((MAX_CANDIDATE_WARNINGS - result.length()).coerceAtLeast(0)).forEach(result::put)
    return result.toString()
}

private const val MAX_CANDIDATE_IMPORT_PAGE_SIZE = 500
private const val MAX_CANDIDATE_WARNINGS = 1_000
private const val PROVIDER_KIND = "PROVIDER"
private const val LOCAL_KIND = "LOCAL"
private const val DOWNLOAD_KIND = "DOWNLOAD"
private const val CREW_TEMPORARY_KIND = "CREW_TEMPORARY"
private const val CREW_PEER_KIND = "CREW_PEER"
private const val LOCAL_PROVIDER_ID = "local-file"
private const val DOWNLOAD_PROVIDER_ID = "shippy-download"
private const val LOCAL_ITEM_TYPE = "LOCAL_FILE"
private const val RECORDING_ITEM_TYPE = "RECORDING"
private const val AUTOMATICALLY_LINKED = "AUTOMATICALLY_LINKED"
