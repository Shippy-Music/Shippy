/*
 * Copyright (c) 2026 Shippy contributors
 * DownloadDestinationReconciler.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.download

import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobRepository
import org.oxycblt.auxio.shippy.persistence.download.PersistedDownload
import org.oxycblt.auxio.shippy.persistence.library.LibraryRelationshipRepository

/**
 * Repairs the Downloads projection from the selected directory without treating similarly named
 * files as Shippy downloads. A persisted artifact remains available only when the scanned SAF
 * document has the same URI and exact verified length.
 */
@Singleton
class DownloadDestinationReconciler
@Inject
constructor(
    private val storage: SafDownloadStorage,
    private val jobs: DownloadJobRepository,
    private val relationships: LibraryRelationshipRepository,
    private val publicationGate: DownloadPublicationGate,
) {
    suspend fun reconcile(
        nowEpochMs: Long = System.currentTimeMillis()
    ): DownloadReconciliationResult =
        publicationGate.run {
            val plan =
                DownloadReconciliationPlanner.plan(
                    storage.inspectDestination(),
                    jobs.getAll(),
                )

            plan.missingArtifactJobIds.forEach { jobId ->
                jobs.apply(jobId, DownloadEvent.Remove, nowEpochMs)
            }
            plan.relationshipRepairs.forEach { repair ->
                relationships.setDownloaded(repair.trackId, repair.downloaded)
            }

            plan.result
        }
}

data class DownloadReconciliationResult(
    val destinationState: DownloadDestinationState,
    val repairedMissingArtifactJobIds: List<DownloadJobId>,
    val unmanagedDocuments: List<StoredAudioDocument>,
)

internal data class DownloadRelationshipRepair(
    val trackId: TrackId,
    val downloaded: Boolean,
)

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
        val available = downloads.filter { it.job.state == DownloadState.AVAILABLE }
        val knownDocumentUris =
            buildSet {
                downloads.forEach { download ->
                    download.job.artifact?.contentUri?.let(::add)
                    download.pendingDocument?.contentUri?.let(::add)
                }
            }

        if (destinationState !is DownloadDestinationState.Ready) {
            return DownloadReconciliationPlan(
                result =
                    DownloadReconciliationResult(
                        destinationState = destinationState,
                        repairedMissingArtifactJobIds = emptyList(),
                        unmanagedDocuments = emptyList(),
                ),
                missingArtifactJobIds = emptyList(),
                relationshipRepairs = emptyList(),
            )
        }

        val documentsByUri = destinationState.existingAudio.associateBy(StoredAudioDocument::contentUri)
        val verifiedArtifactJobIds =
            available.mapNotNullTo(mutableSetOf()) { download ->
                download.job.artifact
                    ?.takeIf { artifact ->
                        documentsByUri[artifact.contentUri]?.contentLength == artifact.contentLength
                    }
                    ?.let { download.job.id }
            }
        val missing = available.filterNot { it.job.id in verifiedArtifactJobIds }
        val verifiedTracks =
            available
                .filter { it.job.id in verifiedArtifactJobIds }
                .mapTo(mutableSetOf()) { it.track.id }

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
            relationshipRepairs = downloads.relationshipRepairs(verifiedTracks),
        )
    }

    private fun List<PersistedDownload>.relationshipRepairs(
        verifiedTracks: Set<TrackId>,
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
