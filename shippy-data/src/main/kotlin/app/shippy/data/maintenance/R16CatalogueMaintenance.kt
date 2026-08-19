/*
 * Copyright (c) 2026 Auxio Project
 * R16CatalogueMaintenance.kt is part of Auxio.
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
package app.shippy.data.maintenance

import androidx.room.withTransaction
import app.shippy.core.identity.RecordingId
import app.shippy.data.db.ShippyR16Database
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object R16CatalogueRetentionPolicy {
    val transientTtl: Duration = Duration.ofDays(60)
}

data class R16CatalogueGcProtections(
    val activeQueue: Set<RecordingId> = emptySet(),
    val retainedCache: Set<RecordingId> = emptySet(),
    val pendingWork: Set<RecordingId> = emptySet(),
) {
    internal val all: Set<RecordingId> = activeQueue + retainedCache + pendingWork
}

data class R16CatalogueGcRequest(
    val now: Instant,
    val protections: R16CatalogueGcProtections = R16CatalogueGcProtections(),
    val limit: Int = DEFAULT_GC_BATCH_SIZE,
) {
    init {
        require(limit in 1..MAX_GC_BATCH_SIZE) {
            "Catalogue GC batch size must be between 1 and $MAX_GC_BATCH_SIZE"
        }
    }
}

data class R16CatalogueGcResult(
    val scannedCandidateCount: Int,
    val skippedProtectedCount: Int,
    val skippedChangedCount: Int,
    val deletedRecordingIds: List<RecordingId>,
)

interface R16CatalogueMaintenance {
    suspend fun collectExpiredTransient(request: R16CatalogueGcRequest): R16CatalogueGcResult
}

internal class RoomR16CatalogueMaintenance(private val database: ShippyR16Database) :
    R16CatalogueMaintenance {
    override suspend fun collectExpiredTransient(
        request: R16CatalogueGcRequest
    ): R16CatalogueGcResult {
        val protectedIds = request.protections.all.mapTo(hashSetOf(), RecordingId::value)
        val scanLimit = minOf(MAX_GC_SCAN_SIZE, request.limit * GC_SCAN_MULTIPLIER)
        val candidates =
            database
                .catalogueMaintenanceDao()
                .expiredEligibleRecordingIds(request.now.toEpochMilli(), scanLimit)
        val deleted = mutableListOf<RecordingId>()
        var scanned = 0
        var skippedProtected = 0
        var skippedChanged = 0
        for (recordingId in candidates) {
            currentCoroutineContext().ensureActive()
            if (deleted.size >= request.limit) break
            scanned += 1
            if (recordingId in protectedIds) {
                skippedProtected += 1
                continue
            }
            if (deleteIfStillEligible(recordingId, request.now.toEpochMilli())) {
                deleted += RecordingId(recordingId)
            } else {
                skippedChanged += 1
            }
        }
        return R16CatalogueGcResult(
            scannedCandidateCount = scanned,
            skippedProtectedCount = skippedProtected,
            skippedChangedCount = skippedChanged,
            deletedRecordingIds = deleted,
        )
    }

    private suspend fun deleteIfStillEligible(
        recordingId: String,
        expiredAtEpochMs: Long,
    ): Boolean =
        database.withTransaction {
            val dao = database.catalogueMaintenanceDao()
            if (dao.eligibleRecordingCount(recordingId, expiredAtEpochMs) != 1) {
                return@withTransaction false
            }
            val artistIds = dao.artistIds(recordingId)
            val releaseIds = dao.releaseIds(recordingId)
            dao.deleteSearchDocument(recordingId)
            dao.deleteExternalIdentifiers(recordingId)
            dao.deleteArtwork(recordingId)
            dao.deleteDerivedIdentityDecisions(recordingId)
            dao.deleteReleaseTracks(recordingId)
            dao.deleteSourceObservations(recordingId)
            dao.deleteSources(recordingId)
            check(dao.deleteRecording(recordingId) == 1) {
                "Eligible transient recording disappeared during collection"
            }
            deleteOrphanArtists(artistIds)
            deleteOrphanReleases(releaseIds)
            true
        }

    private suspend fun deleteOrphanArtists(candidateIds: List<String>) {
        if (candidateIds.isEmpty()) return
        val dao = database.catalogueMaintenanceDao()
        val orphanIds = dao.orphanArtistIds(candidateIds)
        if (orphanIds.isEmpty()) return
        dao.deleteOwnerExternalIdentifiers("ARTIST", orphanIds)
        dao.deleteOwnerArtwork("ARTIST", orphanIds)
        dao.deleteArtists(orphanIds)
    }

    private suspend fun deleteOrphanReleases(candidateIds: List<String>) {
        if (candidateIds.isEmpty()) return
        val dao = database.catalogueMaintenanceDao()
        val orphanIds = dao.orphanReleaseIds(candidateIds)
        if (orphanIds.isEmpty()) return
        dao.deleteOwnerExternalIdentifiers("RELEASE", orphanIds)
        dao.deleteOwnerArtwork("RELEASE", orphanIds)
        dao.deleteReleases(orphanIds)
    }
}

private const val DEFAULT_GC_BATCH_SIZE = 50
private const val MAX_GC_BATCH_SIZE = 200
private const val GC_SCAN_MULTIPLIER = 4
private const val MAX_GC_SCAN_SIZE = MAX_GC_BATCH_SIZE * GC_SCAN_MULTIPLIER
