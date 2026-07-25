/*
 * Copyright (c) 2026 Shippy contributors
 * ResolvedPlayback.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.domain

data class ResolvedPlayback(
    val queueItemId: QueueItemId,
    val candidateId: CandidateId,
    val uri: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val bitrateBps: Int? = null,
    val contentLength: Long? = null,
    val expiresAtEpochMs: Long? = null,
) {
    init {
        require(uri.isNotBlank()) { "Resolved playback URI cannot be blank" }
        require(bitrateBps == null || bitrateBps > 0) { "Bitrate must be positive" }
        require(contentLength == null || contentLength >= 0) {
            "Content length cannot be negative"
        }
    }
}

data class ResolvedQueueItem(
    val item: QueueItem,
    val playback: ResolvedPlayback,
) {
    init {
        require(playback.queueItemId == item.id) {
            "Resolved playback must belong to its queue item"
        }
        require(item.track.candidates.any { it.id == playback.candidateId }) {
            "Resolved candidate must belong to the queue item's track"
        }
    }
}
