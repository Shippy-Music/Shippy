/*
 * Copyright (c) 2026 Shippy contributors
 * PlaybackDisplayItem.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.playback

import javax.inject.Inject
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.LocalCandidateResolution
import org.oxycblt.auxio.shippy.domain.LocalCandidateResolver
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.ResolvedQueueItem
import org.oxycblt.musikr.Song

data class PlaybackDisplayItem(
    val queueItem: QueueItem,
    val resolvedCandidateId: CandidateId,
    val localSong: Song?,
)

class PlaybackDisplayMapper
@Inject
constructor(
    private val localCandidateResolver: LocalCandidateResolver,
) {
    fun map(resolvedItem: ResolvedQueueItem): PlaybackDisplayItem {
        val candidate =
            resolvedItem.item.track.candidates.firstOrNull {
                it.id == resolvedItem.playback.candidateId
            }
        val localSong =
            when (val resolution = candidate?.let(localCandidateResolver::resolve)) {
                is LocalCandidateResolution.Ready -> resolution.song
                LocalCandidateResolution.InvalidIdentity,
                LocalCandidateResolution.Missing,
                LocalCandidateResolution.NotLocal,
                null -> null
            }
        return PlaybackDisplayItem(
            queueItem = resolvedItem.item,
            resolvedCandidateId = resolvedItem.playback.candidateId,
            localSong = localSong,
        )
    }
}
