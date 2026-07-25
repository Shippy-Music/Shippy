/*
 * Copyright (c) 2026 Shippy contributors
 * CrewAuthenticatedMediaLifecycle.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.media

import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame

/** The authenticated identity accompanying media received from one active peer transport. */
data class CrewAuthenticatedMediaPeer(
    val memberId: CrewMemberId,
    val transport: CrewPeerTransport,
)

sealed interface CrewMediaFrameResult {
    data object Accepted : CrewMediaFrameResult

    data class Rejected(val reason: String) : CrewMediaFrameResult
}

/**
 * Lifecycle seam for authenticated Crew media. The session engine owns transport collection and
 * control routing; implementations only receive the already-authenticated media lifecycle.
 */
interface CrewAuthenticatedMediaLifecycle {
    fun onPeerAttached(peer: CrewAuthenticatedMediaPeer)

    fun onMediaFrame(
        peer: CrewAuthenticatedMediaPeer,
        frame: CrewTransportFrame,
    ): CrewMediaFrameResult

    fun onPeerDetached(peer: CrewAuthenticatedMediaPeer)
}
