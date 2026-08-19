/*
 * Copyright (c) 2026 Auxio Project
 * LegacyDownloadImporter.kt is part of Auxio.
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
import app.shippy.data.db.entity.DownloadJobEntity
import app.shippy.data.db.entity.MediaAssetEntity
import app.shippy.data.db.entity.SourceReferenceEntity
import org.json.JSONArray
import org.json.JSONObject

internal fun interface LegacyDownloadArtifactVerifier {
    suspend fun verify(row: LegacyDownloadJobRow): VerifiedLegacyAsset?
}

internal data class DownloadJobPageImportResult(
    val importedCount: Int,
    val verifiedArtifactCount: Int,
    val lastJobId: String?,
    val warnings: List<String>,
)

internal class LegacyDownloadImporter(
    private val database: ShippyR16Database,
    private val artifactVerifier: LegacyDownloadArtifactVerifier,
) {
    suspend fun importPage(
        migrationId: String,
        rows: List<LegacyDownloadJobRow>,
        importedAtEpochMs: Long,
    ): DownloadJobPageImportResult {
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        require(importedAtEpochMs >= 0) { "Import timestamp cannot be negative" }
        require(rows.size <= MAX_DOWNLOAD_IMPORT_PAGE_SIZE) { "Download import page is too large" }
        require(rows.map(LegacyDownloadJobRow::jobId).toSet().size == rows.size) {
            "Download import page contains duplicate legacy job IDs"
        }
        require(rows.zipWithNext().all { (first, second) -> first.jobId < second.jobId }) {
            "Download import page must be in stable legacy job order"
        }
        if (rows.isEmpty()) return DownloadJobPageImportResult(0, 0, null, emptyList())

        // Artifact access can block on DocumentsProvider; never hold Room while checking it.
        val verifiedArtifacts =
            rows
                .filter { row -> row.artifactUri != null }
                .associateWith { row -> artifactVerifier.verify(row) }
        val warnings = mutableListOf<String>()
        var verifiedArtifactCount = 0

        database.withTransaction {
            val audit = requireActiveLegacyAudit(database, migrationId)
            for (row in rows) {
                val recordingId = LegacyIdMapper.recording(row.trackId).value
                check(database.recordingDao().get(recordingId) != null) {
                    "M1 recording is missing for legacy track ${row.trackId}"
                }
                val requestedSource = findRequestedSource(row)
                if (requestedSource == null) {
                    warnings += "${row.jobId}: requested source could not be mapped"
                }
                val progress = sanitizeProgress(row, warnings)
                val verified = verifiedArtifacts[row]?.takeIf { artifactMatches(row, it, warnings) }
                val acceptedAsset =
                    verified?.let { evidence ->
                        acceptedAsset(
                            row = row,
                            recordingId = recordingId,
                            requestedSource = requestedSource,
                            evidence = evidence,
                            importedAtEpochMs = importedAtEpochMs,
                            warnings = warnings,
                        )
                    }
                val mappedState = mapState(row, acceptedAsset != null, warnings)
                val jobId = LegacyIdMapper.downloadJob(row.jobId)
                database
                    .downloadDao()
                    .save(
                        DownloadJobEntity(
                            jobId = jobId,
                            recordingId = recordingId,
                            requestedSourceReferenceId = requestedSource?.sourceReferenceId,
                            publishedAssetId = null,
                            state = if (acceptedAsset == null) mappedState.state else "VERIFYING",
                            bytesTransferred = progress.bytesTransferred,
                            expectedBytes = progress.expectedBytes,
                            failureKind =
                                if (acceptedAsset == null) mappedState.failureKind else null,
                            retryAfterEpochMs = null,
                            pendingLocation = null,
                            displayFallbackJson = row.toDisplayFallbackJson(),
                            createdAtEpochMs = validCreatedAt(row, importedAtEpochMs, warnings),
                            updatedAtEpochMs = importedAtEpochMs,
                        )
                    )
                if (acceptedAsset != null) {
                    database.downloadDao().publishVerified(jobId, acceptedAsset, importedAtEpochMs)
                    verifiedArtifactCount++
                }
                check(
                    database
                        .legacyImportDao()
                        .makeRecordingDurable(recordingId, importedAtEpochMs) == 1
                ) {
                    "Imported download recording disappeared"
                }
            }

            val last = rows.last()
            database
                .migrationAuditDao()
                .updateProgress(
                    migrationId = migrationId,
                    targetCountsJson =
                        JSONObject()
                            .put("downloadJob", database.legacyImportDao().downloadJobCount())
                            .put(
                                "availableDownload",
                                database.downloadDao().availablePublishedCount(),
                            )
                            .put(
                                "checkpoint",
                                JSONObject()
                                    .put("phase", LegacyImportPhase.DOWNLOADS.code)
                                    .put("lastJobId", last.jobId),
                            )
                            .toString(),
                    warningsJson = appendDownloadWarnings(audit.warningsJson, warnings),
                    status = "IMPORTING",
                )
        }

        return DownloadJobPageImportResult(
            importedCount = rows.size,
            verifiedArtifactCount = verifiedArtifactCount,
            lastJobId = rows.last().jobId,
            warnings = warnings,
        )
    }

    private suspend fun findRequestedSource(row: LegacyDownloadJobRow): SourceReferenceEntity? {
        val deterministicId = LegacyIdMapper.source(row.trackId, row.requestedCandidateId).value
        database.legacyImportDao().source(deterministicId)?.let {
            return it
        }
        val sourceItemId =
            row.requestedSourceItemId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val key = row.requestedExactKey() ?: return null
        return database.sourceDao().exact(key.providerId, key.itemType, sourceItemId)
    }

    private suspend fun acceptedAsset(
        row: LegacyDownloadJobRow,
        recordingId: String,
        requestedSource: SourceReferenceEntity?,
        evidence: VerifiedLegacyAsset,
        importedAtEpochMs: Long,
        warnings: MutableList<String>,
    ): MediaAssetEntity? {
        val existing = database.assetDao().exactLocation(evidence.locationType, evidence.location)
        if (existing != null) {
            if (existing.recordingId != recordingId || existing.assetKind != "SHIPPY_DOWNLOAD") {
                warnings += "${row.jobId}: verified artifact location conflicts with another asset"
                return null
            }
            if (
                existing.contentLength != null && existing.contentLength != evidence.contentLength
            ) {
                warnings +=
                    "${row.jobId}: existing managed asset length conflicts with verification"
                return null
            }
            if (
                requestedSource != null &&
                    existing.sourceReferenceId != null &&
                    existing.sourceReferenceId != requestedSource.sourceReferenceId
            ) {
                warnings += "${row.jobId}: existing managed asset has another requested source"
                return null
            }
            return existing.copy(
                sourceReferenceId =
                    existing.sourceReferenceId ?: requestedSource?.sourceReferenceId,
                assetState = "AVAILABLE",
                contentLength = evidence.contentLength,
                contentChecksum = evidence.contentChecksum ?: existing.contentChecksum,
                updatedAtEpochMs = importedAtEpochMs,
                lastVerifiedAtEpochMs = importedAtEpochMs,
            )
        }
        return MediaAssetEntity(
            assetId = LegacyIdMapper.downloadAsset(row.jobId).value,
            recordingId = recordingId,
            sourceReferenceId = requestedSource?.sourceReferenceId,
            assetKind = "SHIPPY_DOWNLOAD",
            assetState = "AVAILABLE",
            locationType = evidence.locationType,
            location = evidence.location,
            documentId = evidence.documentId,
            mediaStoreId = evidence.mediaStoreId,
            displayName = evidence.displayName,
            mimeType = row.artifactMimeType,
            container = null,
            codec = null,
            bitrateBps = null,
            sampleRateHz = null,
            channelCount = null,
            contentLength = evidence.contentLength,
            contentChecksum = evidence.contentChecksum,
            fingerprintId = null,
            createdAtEpochMs = importedAtEpochMs,
            updatedAtEpochMs = importedAtEpochMs,
            lastVerifiedAtEpochMs = importedAtEpochMs,
        )
    }
}

private data class RequestedExactKey(val providerId: String, val itemType: String)

private data class SanitizedDownloadProgress(val bytesTransferred: Long, val expectedBytes: Long?)

private data class MappedDownloadState(val state: String, val failureKind: String?)

private fun LegacyDownloadJobRow.requestedExactKey(): RequestedExactKey? =
    when (requestedKind) {
        "PROVIDER" -> {
            val provider =
                requestedProviderId?.trim()?.takeIf(String::isNotEmpty)
                    ?: requestedSourceId?.trim()?.takeIf(String::isNotEmpty)
                    ?: return null
            RequestedExactKey(provider, "RECORDING")
        }
        "LOCAL" -> RequestedExactKey("local-file", "LOCAL_FILE")
        "DOWNLOAD" -> RequestedExactKey("shippy-download", "RECORDING")
        else -> null
    }

private fun sanitizeProgress(
    row: LegacyDownloadJobRow,
    warnings: MutableList<String>,
): SanitizedDownloadProgress {
    val expected = row.expectedBytes?.takeIf { it >= 0 }
    val transferred =
        row.bytesTransferred.coerceAtLeast(0).let { bytes ->
            expected?.let(bytes::coerceAtMost) ?: bytes
        }
    if (expected != row.expectedBytes || transferred != row.bytesTransferred) {
        warnings += "${row.jobId}: invalid legacy byte progress was bounded"
    }
    return SanitizedDownloadProgress(transferred, expected)
}

private fun artifactMatches(
    row: LegacyDownloadJobRow,
    evidence: VerifiedLegacyAsset,
    warnings: MutableList<String>,
): Boolean {
    val declaredLengths = listOfNotNull(row.artifactLength, row.expectedBytes).filter { it >= 0 }
    if (declaredLengths.any { it != evidence.contentLength }) {
        warnings += "${row.jobId}: verified artifact length did not match legacy evidence"
        return false
    }
    return true
}

private fun mapState(
    row: LegacyDownloadJobRow,
    hasVerifiedArtifact: Boolean,
    warnings: MutableList<String>,
): MappedDownloadState {
    if (hasVerifiedArtifact) return MappedDownloadState("AVAILABLE", null)
    return when (row.state) {
        "REQUESTED",
        "PAUSED",
        "FAILED_RETRYABLE",
        "FAILED_FINAL",
        "CANCELLED",
        "REMOVED" ->
            MappedDownloadState(row.state, row.failureCode?.trim()?.takeIf(String::isNotEmpty))
        "AVAILABLE" -> {
            warnings += "${row.jobId}: available legacy download had no verified artifact"
            MappedDownloadState("FAILED_RETRYABLE", "LEGACY_ARTIFACT_UNAVAILABLE")
        }
        "RESOLVING",
        "QUEUED",
        "TRANSFERRING",
        "VERIFYING",
        "FINALIZING" -> {
            warnings += "${row.jobId}: in-progress legacy download requires safe restart"
            MappedDownloadState("FAILED_RETRYABLE", "LEGACY_DOWNLOAD_RESUME_REQUIRED")
        }
        else -> {
            warnings += "${row.jobId}: unknown legacy download state ${row.state}"
            MappedDownloadState("FAILED_RETRYABLE", "LEGACY_STATE_UNKNOWN")
        }
    }
}

private fun validCreatedAt(
    row: LegacyDownloadJobRow,
    importedAtEpochMs: Long,
    warnings: MutableList<String>,
): Long {
    if (row.createdAtEpochMs >= 0) return row.createdAtEpochMs
    warnings += "${row.jobId}: invalid creation timestamp replaced with migration time"
    return importedAtEpochMs
}

private fun LegacyDownloadJobRow.toDisplayFallbackJson(): String =
    JSONObject()
        .put("legacyJobId", jobId)
        .put("trackRealm", trackRealm)
        .put("title", title)
        .put("artistsEncoded", artists)
        .put("album", album ?: JSONObject.NULL)
        .put("durationMs", durationMs ?: JSONObject.NULL)
        .put("artwork", JSONObject.NULL)
        .put("legacyFailureMessagePresent", failureMessage != null)
        .put("legacyArtifactUriPresent", artifactUri != null)
        .put("legacyPendingUriPresent", pendingUri != null)
        .put("legacyPendingDisplayName", pendingDisplayName ?: JSONObject.NULL)
        .put("legacyPendingMimeType", pendingMimeType ?: JSONObject.NULL)
        .put("legacyUpdatedAtEpochMs", updatedAtEpochMs)
        .toString()

private fun appendDownloadWarnings(existingJson: String, additions: List<String>): String {
    val result = runCatching { JSONArray(existingJson) }.getOrElse { JSONArray() }
    additions.take((MAX_DOWNLOAD_WARNINGS - result.length()).coerceAtLeast(0)).forEach(result::put)
    return result.toString()
}

private const val MAX_DOWNLOAD_IMPORT_PAGE_SIZE = 500
private const val MAX_DOWNLOAD_WARNINGS = 1_000
