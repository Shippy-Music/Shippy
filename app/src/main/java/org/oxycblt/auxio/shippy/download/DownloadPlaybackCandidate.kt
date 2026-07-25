/*
 * Copyright (c) 2026 Shippy contributors
 * DownloadPlaybackCandidate.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.download

import java.security.MessageDigest
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.MediaDescriptor
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.persistence.download.PersistedDownload

/**
 * Adds the verified artifact for this exact requested candidate as a transient playback candidate.
 *
 * The download record remains the source of truth; this only projects a usable artifact into the
 * item being resolved so provider and local candidates are left intact.
 */
fun QueueItem.withVerifiedDownloadCandidate(download: PersistedDownload?): QueueItem =
    copy(track = track.withVerifiedDownloadCandidate(download))

/** Adds the verified artifact for this exact requested candidate when it is safe to use. */
fun Track.withVerifiedDownloadCandidate(download: PersistedDownload?): Track {
    val job = download?.job ?: return this
    val artifact = job.artifact ?: return this
    if (
        job.trackId != id ||
            download.track.id != id ||
            job.state != DownloadState.AVAILABLE ||
            artifact.contentLength <= 0 ||
            !artifact.contentUri.startsWith(CONTENT_URI_PREFIX) ||
            candidates.none { it.id == job.candidateId } ||
            download.track.candidates.none { it.id == job.candidateId }
    ) {
        return this
    }

    val candidateId = syntheticDownloadCandidateId(job)
    val downloadCandidate =
        TrackCandidate(
            id = candidateId,
            trackId = id,
            kind = CandidateKind.DOWNLOAD,
            sourceId = DOWNLOAD_SOURCE_ID,
            sourceItemId = job.id.value,
            availability = CandidateAvailability.AVAILABLE,
            locator = artifact.contentUri,
            media =
                MediaDescriptor(
                    mimeType = artifact.mimeType,
                    contentLength = artifact.contentLength,
                ),
        )
    return copy(
        candidates = candidates.filterNot(TrackCandidate::isSyntheticDownload) + downloadCandidate
    )
}

private fun TrackCandidate.isSyntheticDownload(): Boolean =
    kind == CandidateKind.DOWNLOAD &&
        sourceId == DOWNLOAD_SOURCE_ID

private fun syntheticDownloadCandidateId(job: DownloadJob): CandidateId {
    val identity =
        "${job.trackId.value}\u0000${job.candidateId.value}\u0000${job.id.value}"
            .toByteArray(Charsets.UTF_8)
    val digest =
        MessageDigest.getInstance("SHA-256")
            .digest(identity)
            .joinToString("") { byte -> "%02x".format(byte) }
    return CandidateId("$DOWNLOAD_SOURCE_ID:$digest")
}

private const val DOWNLOAD_SOURCE_ID = "shippy-download"
private const val CONTENT_URI_PREFIX = "content://"
