/*
 * Copyright (c) 2026 Auxio Project
 * CrewMediaTransferController.kt is part of Auxio.
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

import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport
import org.oxycblt.auxio.shippy.crew.transport.CrewSendResult

enum class CrewMediaTransferState {
    REQUESTED,
    MANIFEST_WAIT,
    CHUNK_PROGRESS,
    COMPLETE,
    CANCELLED,
    RETRY_LATER,
    REJECTED,
}

/**
 * Purely driven protocol controller. Call [resume] after a writable callback or acknowledgement; it
 * never spins.
 */
class CrewMediaTransferController(
    private val activeSessionId: CrewSessionId,
    private val localMemberId: CrewMemberId,
    private val peer: CrewPeerTransport,
    private val media: CrewMediaTransport,
    private val policy: ActiveCrewPushPullPolicy,
) {
    private val states = mutableMapOf<CrewMediaTransferRef, CrewMediaTransferState>()
    private val outgoing = mutableMapOf<CrewMediaTransferRef, List<CrewMediaWireFrame>>()

    @Synchronized
    fun request(transfer: CrewMediaTransferRef): CrewSendResult? {
        if (
            !valid(transfer) ||
                transfer.targetMemberId != localMemberId ||
                peer.remoteMemberId != transfer.supplierMemberId ||
                !policy.accepts(activeSessionId)
        )
            return null
        states.putIfAbsent(transfer, CrewMediaTransferState.REQUESTED)
        return send(transfer, CrewMediaWireFrame.Request(transfer))
    }

    /** Supplier-only: source must already have passed authorization. */
    @Synchronized
    fun offer(transfer: CrewMediaTransferRef, source: CrewAuthorizedMediaSource): CrewSendResult? {
        if (
            !valid(transfer) ||
                transfer.supplierMemberId != localMemberId ||
                peer.remoteMemberId != transfer.targetMemberId ||
                !policy.accepts(activeSessionId)
        )
            return null
        val prior = states[transfer]
        if (prior != null && prior != CrewMediaTransferState.REQUESTED) return null
        states[transfer] = CrewMediaTransferState.MANIFEST_WAIT
        val (manifest, chunks) = CrewMediaProducer.produce(transfer, source)
        outgoing[transfer] =
            listOf(CrewMediaWireFrame.Manifest(manifest)) + chunks.map(CrewMediaWireFrame::Chunk)
        return resume(transfer)
    }

    @Synchronized
    fun cancel(transfer: CrewMediaTransferRef): CrewSendResult? {
        states[transfer] = CrewMediaTransferState.CANCELLED
        outgoing.remove(transfer)
        return send(transfer, CrewMediaWireFrame.Cancel(transfer))
    }

    /** Releases terminal or abandoned transfer bookkeeping; callers own retry scheduling. */
    @Synchronized
    fun forget(transfer: CrewMediaTransferRef) {
        states.remove(transfer)
        outgoing.remove(transfer)
    }

    /** Local policy/session teardown abandons all protocol work without sending another frame. */
    @Synchronized
    fun clear() {
        states.clear()
        outgoing.clear()
    }

    @Synchronized
    fun resume(transfer: CrewMediaTransferRef): CrewSendResult? {
        if (states[transfer] == CrewMediaTransferState.CANCELLED) return null
        val next = outgoing[transfer]?.firstOrNull() ?: return null
        if (
            next is CrewMediaWireFrame.Chunk &&
                states[transfer] == CrewMediaTransferState.MANIFEST_WAIT
        )
            return null
        val result = send(transfer, next) ?: return null
        if (result is CrewSendResult.Sent) {
            outgoing[transfer] = outgoing.getValue(transfer).drop(1)
            states[transfer] =
                if (next is CrewMediaWireFrame.Manifest) CrewMediaTransferState.MANIFEST_WAIT
                else CrewMediaTransferState.CHUNK_PROGRESS
        }
        return result
    }

    @Synchronized
    fun receive(frame: CrewMediaWireFrame): CrewMediaTransferState {
        val t = frame.transfer
        if (!valid(t) || !policy.accepts(activeSessionId) || !remoteMatches(t, frame))
            return CrewMediaTransferState.REJECTED
        val prior = states[t]
        if (prior == CrewMediaTransferState.CANCELLED && frame !is CrewMediaWireFrame.Cancel)
            return CrewMediaTransferState.REJECTED
        // A retransmitted request must not reset a supplier that has already offered this exact
        // transfer. The router's fan-out permit remains attached to that one transfer.
        if (frame is CrewMediaWireFrame.Request && outgoing.containsKey(t))
            return prior ?: CrewMediaTransferState.REQUESTED
        val state =
            when (frame) {
                is CrewMediaWireFrame.Request -> CrewMediaTransferState.REQUESTED
                is CrewMediaWireFrame.Manifest -> CrewMediaTransferState.MANIFEST_WAIT
                is CrewMediaWireFrame.Chunk -> CrewMediaTransferState.CHUNK_PROGRESS
                is CrewMediaWireFrame.ObjectComplete -> CrewMediaTransferState.COMPLETE
                is CrewMediaWireFrame.Cancel -> CrewMediaTransferState.CANCELLED
                is CrewMediaWireFrame.RetryLater -> CrewMediaTransferState.RETRY_LATER
                is CrewMediaWireFrame.Rejected -> CrewMediaTransferState.REJECTED
                is CrewMediaWireFrame.ManifestAccepted -> CrewMediaTransferState.CHUNK_PROGRESS
            }
        states[t] = state
        return state
    }

    private fun send(t: CrewMediaTransferRef, frame: CrewMediaWireFrame): CrewSendResult? =
        if (valid(t) && policy.accepts(activeSessionId)) media.send(frame) else null

    private fun valid(t: CrewMediaTransferRef) =
        t.sessionId == activeSessionId && t.targetMemberId != t.supplierMemberId

    private fun remoteMatches(t: CrewMediaTransferRef, frame: CrewMediaWireFrame) =
        when (frame) {
            is CrewMediaWireFrame.Request ->
                localMemberId == t.supplierMemberId && peer.remoteMemberId == t.targetMemberId
            else ->
                localMemberId == t.targetMemberId && peer.remoteMemberId == t.supplierMemberId ||
                    localMemberId == t.supplierMemberId && peer.remoteMemberId == t.targetMemberId
        }
}
