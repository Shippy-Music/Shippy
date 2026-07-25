/*
 * Copyright (c) 2026 Shippy contributors
 * DownloadReducerTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.TrackId

class DownloadReducerTest {
    private val reducer = DownloadReducer()

    @Test
    fun `complete download publishes artifact only after finalization`() {
        val artifact = DownloadArtifact("content://download/song", 100, "audio/mp4", 123)
        var job = job()

        job = apply(job, DownloadEvent.Resolve)
        job = apply(job, DownloadEvent.Enqueued(100))
        job = apply(job, DownloadEvent.TransferStarted)
        job = apply(job, DownloadEvent.Progress(100, 100))
        job = apply(job, DownloadEvent.TransferCompleted)
        assertNull(job.artifact)
        job = apply(job, DownloadEvent.Verified)
        assertNull(job.artifact)
        job = apply(job, DownloadEvent.Finalized(artifact))

        assertEquals(DownloadState.AVAILABLE, job.state)
        assertEquals(artifact, job.artifact)
    }

    @Test
    fun `retryable failure resumes through resolution`() {
        var job = apply(job(), DownloadEvent.Resolve)
        job = apply(job, DownloadEvent.Enqueued(100))
        job = apply(job, DownloadEvent.TransferStarted)
        job = apply(job, DownloadEvent.Progress(40, 100))
        job =
            apply(
                job,
                DownloadEvent.Fail(DownloadFailure("network"), retryable = true),
            )

        assertEquals(DownloadState.FAILED_RETRYABLE, job.state)
        job = apply(job, DownloadEvent.Retry)
        assertEquals(DownloadState.RESOLVING, job.state)
        assertEquals(0L, job.bytesTransferred)
        assertNull(job.expectedBytes)
        assertNull(job.failure)
    }

    @Test
    fun `pause can stop queued work before transfer begins`() {
        var job = apply(job(), DownloadEvent.Resolve)
        job = apply(job, DownloadEvent.Pause)

        assertEquals(DownloadState.PAUSED, job.state)
        job = apply(job, DownloadEvent.Resume)
        assertEquals(DownloadState.RESOLVING, job.state)
        assertEquals(0L, job.bytesTransferred)
        assertNull(job.expectedBytes)
    }

    @Test
    fun `invalid transition is rejected without changing job`() {
        val job = job()
        val transition = reducer.apply(job, DownloadEvent.TransferCompleted)

        assertTrue(transition is DownloadTransition.Rejected)
        assertEquals(DownloadState.REQUESTED, job.state)
    }

    @Test
    fun `removing available download drops permanent artifact`() {
        val artifact = DownloadArtifact("content://download/song", 100, "audio/mp4", 123)
        val available =
            job().copy(
                state = DownloadState.AVAILABLE,
                bytesTransferred = 100,
                expectedBytes = 100,
                artifact = artifact,
            )

        val removed = apply(available, DownloadEvent.Remove)

        assertEquals(DownloadState.REMOVED, removed.state)
        assertNull(removed.artifact)
    }

    private fun apply(job: DownloadJob, event: DownloadEvent): DownloadJob {
        val transition = reducer.apply(job, event)
        assertTrue("Expected applied transition for $event", transition is DownloadTransition.Applied)
        return (transition as DownloadTransition.Applied).job
    }

    private fun job() =
        DownloadJob(
            id = DownloadJobId("download"),
            trackId = TrackId("track"),
            candidateId = CandidateId("candidate"),
        )
}
