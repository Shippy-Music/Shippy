/*
 * Copyright (c) 2026 Auxio Project
 * CrewMediaTransport.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.oxycblt.auxio.shippy.crew.media

import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport
import org.oxycblt.auxio.shippy.crew.transport.CrewSendResult
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame

/** Narrow media-channel adapter; control/session signaling and UI remain outside this boundary. */
class CrewMediaTransport(
    private val activeSessionId: CrewSessionId,
    private val policy: ActiveCrewPushPullPolicy,
    private val peer: CrewPeerTransport,
) {
    fun send(frame: CrewMediaWireFrame): CrewSendResult = send(frame, frame.transfer.sessionId)

    fun receive(frame: CrewTransportFrame): CrewMediaWireFrame? {
        if (frame.channel != CrewTransportChannel.MEDIA || !policy.accepts(activeSessionId))
            return null
        return runCatching { CrewMediaWireCodec.decode(frame.copyPayload()) }
            .getOrNull()
            ?.takeIf { it.transfer.sessionId == activeSessionId }
    }

    private fun send(frame: CrewMediaWireFrame, sessionId: CrewSessionId): CrewSendResult {
        if (sessionId != activeSessionId || !policy.accepts(activeSessionId))
            return CrewSendResult.ChannelNotOpen
        return peer.trySend(
            CrewTransportFrame(CrewTransportChannel.MEDIA, CrewMediaWireCodec.encode(frame))
        )
    }
}
