/*
 * Copyright (c) 2026 Shippy contributors
 * DownloadJob.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.download

import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.TrackId

@JvmInline
value class DownloadJobId(val value: String) {
    init {
        require(value.isNotBlank()) { "DownloadJobId cannot be blank" }
    }
}

enum class DownloadState {
    REQUESTED,
    RESOLVING,
    QUEUED,
    TRANSFERRING,
    PAUSED,
    VERIFYING,
    FINALIZING,
    AVAILABLE,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    CANCELLED,
    REMOVED,
}

data class DownloadJob(
    val id: DownloadJobId,
    val trackId: TrackId,
    val candidateId: CandidateId,
    val state: DownloadState = DownloadState.REQUESTED,
    val bytesTransferred: Long = 0,
    val expectedBytes: Long? = null,
    val failure: DownloadFailure? = null,
    val artifact: DownloadArtifact? = null,
) {
    init {
        require(bytesTransferred >= 0) { "Transferred bytes cannot be negative" }
        require(expectedBytes == null || expectedBytes >= 0) {
            "Expected bytes cannot be negative"
        }
        require(expectedBytes == null || bytesTransferred <= expectedBytes) {
            "Transferred bytes cannot exceed expected bytes"
        }
        require(state == DownloadState.AVAILABLE || artifact == null) {
            "Only an available job can expose an artifact"
        }
        require(state != DownloadState.AVAILABLE || artifact != null) {
            "Available download requires a verified artifact"
        }
    }
}

data class DownloadArtifact(
    val contentUri: String,
    val contentLength: Long,
    val mimeType: String?,
    val verifiedAtEpochMs: Long,
) {
    init {
        require(contentUri.isNotBlank()) { "Download artifact URI cannot be blank" }
        require(contentLength >= 0) { "Artifact length cannot be negative" }
        require(verifiedAtEpochMs >= 0) { "Verification timestamp cannot be negative" }
    }
}

data class DownloadFailure(
    val code: String,
    val message: String? = null,
) {
    init {
        require(code.isNotBlank()) { "Download failure code cannot be blank" }
    }
}

sealed interface DownloadEvent {
    data object Resolve : DownloadEvent

    data class Enqueued(val expectedBytes: Long?) : DownloadEvent

    data object TransferStarted : DownloadEvent

    data class Progress(val bytesTransferred: Long, val expectedBytes: Long?) : DownloadEvent

    data object TransferCompleted : DownloadEvent

    data object Verified : DownloadEvent

    data class Finalized(val artifact: DownloadArtifact) : DownloadEvent

    data object Pause : DownloadEvent

    data object Resume : DownloadEvent

    data class Fail(val failure: DownloadFailure, val retryable: Boolean) : DownloadEvent

    data object Retry : DownloadEvent

    data object Cancel : DownloadEvent

    data object Remove : DownloadEvent
}

sealed interface DownloadTransition {
    data class Applied(val job: DownloadJob) : DownloadTransition

    data class Rejected(
        val state: DownloadState,
        val event: DownloadEvent,
    ) : DownloadTransition
}

class DownloadReducer {
    fun apply(job: DownloadJob, event: DownloadEvent): DownloadTransition {
        val next =
            when (event) {
                DownloadEvent.Resolve ->
                    job.takeIf { it.state == DownloadState.REQUESTED }
                        ?.copy(state = DownloadState.RESOLVING, failure = null)
                is DownloadEvent.Enqueued ->
                    job.takeIf {
                            it.state == DownloadState.RESOLVING &&
                                (event.expectedBytes == null || event.expectedBytes >= 0)
                        }
                        ?.copy(state = DownloadState.QUEUED, expectedBytes = event.expectedBytes)
                DownloadEvent.TransferStarted ->
                    job.takeIf { it.state == DownloadState.QUEUED }
                        ?.copy(state = DownloadState.TRANSFERRING)
                is DownloadEvent.Progress ->
                    job.takeIf {
                            val expected = event.expectedBytes ?: job.expectedBytes
                            it.state == DownloadState.TRANSFERRING &&
                                event.bytesTransferred >= job.bytesTransferred &&
                                event.bytesTransferred >= 0 &&
                                (expected == null || event.bytesTransferred <= expected)
                        }
                        ?.copy(
                            bytesTransferred = event.bytesTransferred,
                            expectedBytes = event.expectedBytes ?: job.expectedBytes,
                        )
                DownloadEvent.TransferCompleted ->
                    job.takeIf { it.state == DownloadState.TRANSFERRING }
                        ?.copy(state = DownloadState.VERIFYING)
                DownloadEvent.Verified ->
                    job.takeIf { it.state == DownloadState.VERIFYING }
                        ?.copy(state = DownloadState.FINALIZING)
                is DownloadEvent.Finalized ->
                    job.takeIf { it.state == DownloadState.FINALIZING }
                        ?.copy(
                            state = DownloadState.AVAILABLE,
                            bytesTransferred = event.artifact.contentLength,
                            expectedBytes = event.artifact.contentLength,
                            artifact = event.artifact,
                        )
                DownloadEvent.Pause ->
                    job.takeIf {
                            it.state == DownloadState.REQUESTED ||
                                it.state == DownloadState.RESOLVING ||
                                it.state == DownloadState.QUEUED ||
                                it.state == DownloadState.TRANSFERRING
                        }
                        ?.copy(state = DownloadState.PAUSED)
                DownloadEvent.Resume ->
                    job.takeIf { it.state == DownloadState.PAUSED }
                        ?.copy(
                            state = DownloadState.RESOLVING,
                            bytesTransferred = 0,
                            expectedBytes = null,
                            failure = null,
                        )
                is DownloadEvent.Fail ->
                    job.takeIf { it.state.isActive() }
                        ?.copy(
                            state =
                                if (event.retryable) DownloadState.FAILED_RETRYABLE
                                else DownloadState.FAILED_FINAL,
                            failure = event.failure,
                        )
                DownloadEvent.Retry ->
                    job.takeIf { it.state == DownloadState.FAILED_RETRYABLE }
                        ?.copy(
                            state = DownloadState.RESOLVING,
                            bytesTransferred = 0,
                            expectedBytes = null,
                            failure = null,
                        )
                DownloadEvent.Cancel ->
                    job.takeIf { it.state.isActive() || it.state == DownloadState.PAUSED }
                        ?.copy(state = DownloadState.CANCELLED)
                DownloadEvent.Remove ->
                    job.takeIf {
                            it.state == DownloadState.AVAILABLE ||
                                it.state == DownloadState.CANCELLED ||
                                it.state == DownloadState.FAILED_FINAL
                        }
                        ?.copy(state = DownloadState.REMOVED, artifact = null)
            }

        return next?.let(DownloadTransition::Applied)
            ?: DownloadTransition.Rejected(job.state, event)
    }

    private fun DownloadState.isActive() =
        this == DownloadState.REQUESTED ||
            this == DownloadState.RESOLVING ||
            this == DownloadState.QUEUED ||
            this == DownloadState.TRANSFERRING ||
            this == DownloadState.VERIFYING ||
            this == DownloadState.FINALIZING
}
