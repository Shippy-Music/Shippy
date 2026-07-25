/*
 * Copyright (c) 2026 Shippy contributors
 * QueueItemFactory.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.domain

import java.util.UUID
import javax.inject.Inject
import org.oxycblt.musikr.Song

class QueueItemFactory
@Inject
constructor(
    private val localTrackMapper: LocalTrackCandidateMapper,
) {
    fun fromTrack(
        track: Track,
        contextId: String? = null,
        contributorId: String? = null,
    ) =
        QueueItem(
            id = QueueItemId(UUID.randomUUID().toString()),
            track = track,
            contextId = contextId,
            contributorId = contributorId,
        )

    fun fromLocal(
        song: Song,
        contextId: String? = null,
        contributorId: String? = null,
    ) = fromTrack(localTrackMapper.map(song), contextId, contributorId)
}
