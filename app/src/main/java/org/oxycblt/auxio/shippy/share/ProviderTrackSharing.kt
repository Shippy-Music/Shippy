/*
 * Copyright (c) 2026 Shippy contributors
 * ProviderTrackSharing.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.share

import org.oxycblt.auxio.shippy.domain.Track

object ProviderTrackSharing {
    fun originalLink(track: Track): String? =
        track.candidates.firstNotNullOfOrNull { candidate ->
            candidate.takeIf { it.providerId?.value == "youtube_music" }
                ?.sourceItemId
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
                ?.let { "https://music.youtube.com/watch?v=$it" }
        }

    fun shippyLink(track: Track): String? = ShippyTrackLinkCodec.encode(track)
}
