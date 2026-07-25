/* Copyright (c) 2026 Shippy contributors */
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
    fun send(manifest: CrewMediaManifest): CrewSendResult = send(CrewMediaWireFrame.Manifest(manifest), manifest.sessionId)
    fun send(chunk: CrewMediaChunk): CrewSendResult = send(CrewMediaWireFrame.Chunk(chunk), chunk.sessionId)

    fun receive(frame: CrewTransportFrame): CrewMediaWireFrame? {
        if (frame.channel != CrewTransportChannel.MEDIA || !policy.accepts(activeSessionId)) return null
        return runCatching { CrewMediaWireCodec.decode(frame.copyPayload()) }.getOrNull()?.takeIf {
            when (it) {
                is CrewMediaWireFrame.Manifest -> it.value.sessionId == activeSessionId
                is CrewMediaWireFrame.Chunk -> it.value.sessionId == activeSessionId
            }
        }
    }

    private fun send(frame: CrewMediaWireFrame, sessionId: CrewSessionId): CrewSendResult {
        if (sessionId != activeSessionId || !policy.accepts(activeSessionId)) return CrewSendResult.ChannelNotOpen
        return peer.trySend(CrewTransportFrame(CrewTransportChannel.MEDIA, CrewMediaWireCodec.encode(frame)))
    }
}
