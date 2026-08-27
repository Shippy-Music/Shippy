/*
 * Copyright (c) 2026 Auxio Project
 * DownloadDestinationReconciler.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.download

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobRepository
import org.oxycblt.auxio.shippy.persistence.download.PersistedDownload
import org.oxycblt.auxio.shippy.persistence.library.LibraryRelationshipRepository
import org.oxycblt.auxio.shippy.persistence.library.ShippyDatabase

/**
 * Repairs the Downloads projection from the selected directory without treating similarly named
 * files as Shippy downloads. A persisted artifact remains available only when the scanned SAF
 * document has the same URI and exact verified length.
 */
@Singleton
class DownloadDestinationReconciler
@Inject
internal constructor(
    private val storage: SafDownloadStorage,
    private val jobs: DownloadJobRepository,
    private val relationships: LibraryRelationshipRepository,
    private val database: ShippyDatabase,
    private val publicationGate: DownloadPublicationGate,
) {
    suspend fun reconcile(
        nowEpochMs: Long = System.currentTimeMillis()
    ): DownloadReconciliationResult =
        publicationGate.run {
            val plan =
                DownloadReconciliationPlanner.plan(storage.inspectDestination(), jobs.getAll())

            database.withTransaction {
                plan.missingArtifactJobIds.forEach { jobId ->
                    jobs.apply(jobId, DownloadEvent.Remove, nowEpochMs)
                }
                plan.relationshipRepairs.forEach { repair ->
                    relationships.setDownloaded(repair.trackId, repair.downloaded)
                }
            }

            plan.result
        }
}

data class DownloadReconciliationResult(
    val destinationState: DownloadDestinationState,
    val repairedMissingArtifactJobIds: List<DownloadJobId>,
    val unmanagedDocuments: List<StoredAudioDocument>,
)

internal data class DownloadRelationshipRepair(val trackId: TrackId, val downloaded: Boolean)

internal data class DownloadReconciliationPlan(
    val result: DownloadReconciliationResult,
    val missingArtifactJobIds: List<DownloadJobId>,
    val relationshipRepairs: List<DownloadRelationshipRepair>,
)

internal object DownloadReconciliationPlanner {
    fun plan(
        destinationState: DownloadDestinationState,
        downloads: List<PersistedDownload>,
    ): DownloadReconciliationPlan {
        val available = availableDownloads(downloads)
        val knownDocumentUris = knownDocumentUris(downloads)
        return when (destinationState) {
            is DownloadDestinationState.Ready -> {
                readyPlan(destinationState, downloads, available, knownDocumentUris)
            }
            else -> unavailablePlan(destinationState)
        }
    }

    private fun availableDownloads(downloads: List<PersistedDownload>): List<PersistedDownload> =
        downloads.filter { it.job.state == DownloadState.AVAILABLE }

    private fun knownDocumentUris(downloads: List<PersistedDownload>): Set<String> = buildSet {
        downloads.forEach { download ->
            download.job.artifact?.contentUri?.let(::add)
            download.pendingDocument?.contentUri?.let(::add)
        }
    }

    private fun unavailablePlan(
        destinationState: DownloadDestinationState
    ): DownloadReconciliationPlan =
        DownloadReconciliationPlan(
            result =
                DownloadReconciliationResult(
                    destinationState = destinationState,
                    repairedMissingArtifactJobIds = emptyList(),
                    unmanagedDocuments = emptyList(),
                ),
            missingArtifactJobIds = emptyList(),
            relationshipRepairs = emptyList(),
        )

    private fun readyPlan(
        destinationState: DownloadDestinationState.Ready,
        downloads: List<PersistedDownload>,
        available: List<PersistedDownload>,
        knownDocumentUris: Set<String>,
    ): DownloadReconciliationPlan {
        val documentsByUri =
            destinationState.existingAudio.associateBy(StoredAudioDocument::contentUri)
        val verifiedArtifactJobIds = verifiedArtifactJobIds(available, documentsByUri)
        val missing = available.filterNot { it.job.id in verifiedArtifactJobIds }

        return DownloadReconciliationPlan(
            result =
                DownloadReconciliationResult(
                    destinationState = destinationState,
                    repairedMissingArtifactJobIds = missing.map { it.job.id },
                    unmanagedDocuments =
                        destinationState.existingAudio.filterNot {
                            it.contentUri in knownDocumentUris
                        },
                ),
            missingArtifactJobIds = missing.map { it.job.id },
            relationshipRepairs =
                downloads.relationshipRepairs(verifiedTrackIds(available, verifiedArtifactJobIds)),
        )
    }

    private fun verifiedArtifactJobIds(
        available: List<PersistedDownload>,
        documentsByUri: Map<String, StoredAudioDocument>,
    ): Set<DownloadJobId> = buildSet {
        available.forEach { download ->
            val artifact = download.job.artifact ?: return@forEach
            if (documentsByUri[artifact.contentUri]?.contentLength == artifact.contentLength) {
                add(download.job.id)
            }
        }
    }

    private fun verifiedTrackIds(
        available: List<PersistedDownload>,
        verifiedArtifactJobIds: Set<DownloadJobId>,
    ): Set<TrackId> = buildSet {
        available.forEach { download ->
            if (download.job.id in verifiedArtifactJobIds) add(download.track.id)
        }
    }

    private fun List<PersistedDownload>.relationshipRepairs(
        verifiedTracks: Set<TrackId>
    ): List<DownloadRelationshipRepair> =
        map { it.track.id }
            .distinct()
            .map { trackId ->
                DownloadRelationshipRepair(
                    trackId = trackId,
                    downloaded = trackId in verifiedTracks,
                )
            }
}
