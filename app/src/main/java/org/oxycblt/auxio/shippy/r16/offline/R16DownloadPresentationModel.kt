/*
 * Copyright (c) 2026 Auxio Project
 * R16DownloadPresentationModel.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.offline

import app.shippy.core.identity.MediaAssetId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.data.offline.R16DownloadJobSnapshot
import app.shippy.data.offline.R16DownloadJobState

/** Presentation model for user-facing download status of a Recording. */
public sealed interface R16DownloadPresentationState {
    public val recordingId: RecordingId

    public data class Unavailable(override val recordingId: RecordingId) :
        R16DownloadPresentationState

    public data class ReadyToRequest(
        override val recordingId: RecordingId,
        val availableSourceReferenceIds: List<SourceReferenceId>,
    ) : R16DownloadPresentationState

    public data class InProgress(
        override val recordingId: RecordingId,
        val jobId: String,
        val state: R16DownloadJobState,
        val bytesTransferred: Long,
        val expectedBytes: Long?,
    ) : R16DownloadPresentationState {
        public val progressFraction: Float?
            get() =
                if (expectedBytes != null && expectedBytes > 0) {
                    (bytesTransferred.toFloat() / expectedBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                }
    }

    public data class Paused(
        override val recordingId: RecordingId,
        val jobId: String,
        val bytesTransferred: Long,
        val expectedBytes: Long?,
    ) : R16DownloadPresentationState

    public data class Retryable(
        override val recordingId: RecordingId,
        val jobId: String,
        val failureKind: String?,
    ) : R16DownloadPresentationState

    public data class AwaitingStoragePermission(
        override val recordingId: RecordingId,
        val jobId: String,
        val destinationIdentity: String?,
    ) : R16DownloadPresentationState

    public data class Available(
        override val recordingId: RecordingId,
        val jobId: String?,
        val publishedAssetId: MediaAssetId,
        val location: String?,
    ) : R16DownloadPresentationState

    public data class Removing(override val recordingId: RecordingId, val jobId: String) :
        R16DownloadPresentationState

    public data class FinalFailure(
        override val recordingId: RecordingId,
        val jobId: String,
        val failureKind: String?,
    ) : R16DownloadPresentationState

    public companion object {
        public fun fromSnapshot(
            recordingId: RecordingId,
            snapshot: R16DownloadJobSnapshot?,
            availableSources: List<SourceReferenceId> = emptyList(),
        ): R16DownloadPresentationState {
            if (snapshot == null) {
                return if (availableSources.isNotEmpty()) {
                    ReadyToRequest(recordingId, availableSources)
                } else {
                    Unavailable(recordingId)
                }
            }
            return when (snapshot.state) {
                R16DownloadJobState.REQUESTED,
                R16DownloadJobState.RESOLVING,
                R16DownloadJobState.QUEUED,
                R16DownloadJobState.TRANSFERRING,
                R16DownloadJobState.VERIFYING,
                R16DownloadJobState.PUBLISHING ->
                    InProgress(
                        recordingId = recordingId,
                        jobId = snapshot.jobId,
                        state = snapshot.state,
                        bytesTransferred = snapshot.bytesTransferred,
                        expectedBytes = snapshot.expectedBytes,
                    )
                R16DownloadJobState.PAUSED ->
                    Paused(
                        recordingId = recordingId,
                        jobId = snapshot.jobId,
                        bytesTransferred = snapshot.bytesTransferred,
                        expectedBytes = snapshot.expectedBytes,
                    )
                R16DownloadJobState.FAILED_RETRYABLE ->
                    if (
                        snapshot.failureKind == "STORAGE_PERMISSION_REVOKED" ||
                            snapshot.failureKind == "STORAGE_TREE_REVOKED"
                    ) {
                        AwaitingStoragePermission(
                            recordingId = recordingId,
                            jobId = snapshot.jobId,
                            destinationIdentity = snapshot.destinationIdentity,
                        )
                    } else {
                        Retryable(
                            recordingId = recordingId,
                            jobId = snapshot.jobId,
                            failureKind = snapshot.failureKind,
                        )
                    }
                R16DownloadJobState.AVAILABLE -> {
                    val assetId = snapshot.publishedAssetId
                    if (assetId != null) {
                        Available(
                            recordingId = recordingId,
                            jobId = snapshot.jobId,
                            publishedAssetId = assetId,
                            location = snapshot.pendingLocation,
                        )
                    } else {
                        ReadyToRequest(recordingId, availableSources)
                    }
                }
                R16DownloadJobState.CANCELLED,
                R16DownloadJobState.REMOVED ->
                    if (availableSources.isNotEmpty()) {
                        ReadyToRequest(recordingId, availableSources)
                    } else {
                        Unavailable(recordingId)
                    }
                R16DownloadJobState.FAILED_FINAL ->
                    FinalFailure(
                        recordingId = recordingId,
                        jobId = snapshot.jobId,
                        failureKind = snapshot.failureKind,
                    )
            }
        }
    }
}
