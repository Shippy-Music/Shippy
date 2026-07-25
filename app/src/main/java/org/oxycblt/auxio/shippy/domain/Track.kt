/*
 * Copyright (c) 2026 Shippy contributors
 * Track.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.domain

enum class TrackRealm {
    PROVIDER,
    LOCAL,
}

data class Track(
    val id: TrackId,
    val realm: TrackRealm,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationMs: Long? = null,
    val version: TrackVersion = TrackVersion(),
    val artwork: String? = null,
    val candidates: List<TrackCandidate>,
) {
    init {
        require(title.isNotBlank()) { "Track title cannot be blank" }
        require(artists.none(String::isBlank)) { "Track artists cannot contain blank values" }
        require(durationMs == null || durationMs >= 0) { "Track duration cannot be negative" }
        require(candidates.all { it.trackId == id }) {
            "Every candidate must belong to the containing track"
        }
    }
}

data class TrackVersion(
    val label: String? = null,
    val explicit: Boolean? = null,
    val isLive: Boolean = false,
    val isRemix: Boolean = false,
)

enum class CandidateKind {
    LOCAL,
    DOWNLOAD,
    PROVIDER,
    CREW_TEMPORARY,
    CREW_PEER,
}

enum class CandidateAvailability {
    AVAILABLE,
    RESOLVABLE,
    UNAVAILABLE,
}

data class TrackCandidate(
    val id: CandidateId,
    val trackId: TrackId,
    val kind: CandidateKind,
    val sourceId: String,
    val sourceItemId: String,
    val availability: CandidateAvailability,
    val locator: String? = null,
    val providerId: ProviderId? = null,
    val media: MediaDescriptor? = null,
) {
    init {
        require(sourceId.isNotBlank()) { "Candidate sourceId cannot be blank" }
        require(sourceItemId.isNotBlank()) { "Candidate sourceItemId cannot be blank" }
        require(kind == CandidateKind.PROVIDER || providerId == null) {
            "Only provider candidates can declare providerId"
        }
        require(kind != CandidateKind.PROVIDER || providerId != null) {
            "Provider candidates require providerId"
        }
    }
}

data class MediaDescriptor(
    val mimeType: String? = null,
    val container: String? = null,
    val bitrateBps: Int? = null,
    val contentLength: Long? = null,
) {
    init {
        require(bitrateBps == null || bitrateBps > 0) { "Bitrate must be positive" }
        require(contentLength == null || contentLength >= 0) {
            "Content length cannot be negative"
        }
    }
}

data class QueueItem(
    val id: QueueItemId,
    val track: Track,
    val contextId: String? = null,
    val contributorId: String? = null,
)
