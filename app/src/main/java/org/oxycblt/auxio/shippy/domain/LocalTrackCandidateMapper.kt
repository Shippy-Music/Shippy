/*
 * Copyright (c) 2026 Shippy contributors
 * LocalTrackCandidateMapper.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.domain

import javax.inject.Inject
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.Name

/**
 * Adapts Auxio's exact local-file model into Shippy without changing Musikr or merging the item
 * with provider identity.
 */
class LocalTrackCandidateMapper @Inject constructor() {
    fun map(song: Song): Track {
        val sourceItemId = song.uid.toString()
        val trackId = TrackId("local:$sourceItemId")

        return Track(
            id = trackId,
            realm = TrackRealm.LOCAL,
            title = song.name.raw,
            artists = song.artists.mapNotNull { it.name.knownValue() },
            album = song.album.name.knownValue(),
            durationMs = song.durationMs,
            candidates =
                listOf(
                    TrackCandidate(
                        id = CandidateId("local:$sourceItemId"),
                        trackId = trackId,
                        kind = CandidateKind.LOCAL,
                        sourceId = LOCAL_SOURCE_ID,
                        sourceItemId = sourceItemId,
                        availability = CandidateAvailability.AVAILABLE,
                        locator = song.uri.toString(),
                        media =
                            MediaDescriptor(
                                mimeType = song.format.mimeType,
                                bitrateBps = song.bitrateKbps.takeIf { it > 0 }?.times(1000),
                                contentLength = song.size.takeIf { it >= 0 },
                            ),
                    )
                ),
        )
    }

    private fun Name.knownValue() = (this as? Name.Known)?.raw?.takeIf(String::isNotBlank)

    private companion object {
        const val LOCAL_SOURCE_ID = "device-local"
    }
}
