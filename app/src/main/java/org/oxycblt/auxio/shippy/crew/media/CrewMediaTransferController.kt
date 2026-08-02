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

private const val CREW_MEDIA_SEND_WINDOW_CHUNKS = 4

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
    private val outgoing = mutableMapOf<CrewMediaTransferRef, Outgoing>()

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
        val manifest = CrewMediaProducer.describe(transfer, source)
        outgoing[transfer] = Outgoing(manifest, source)
        return resume(transfer)
    }

    @Synchronized
    fun cancel(transfer: CrewMediaTransferRef): CrewSendResult? {
        states[transfer] = CrewMediaTransferState.CANCELLED
        outgoing.remove(transfer)?.close()
        return send(transfer, CrewMediaWireFrame.Cancel(transfer))
    }

    /** Releases terminal or abandoned transfer bookkeeping; callers own retry scheduling. */
    @Synchronized
    fun forget(transfer: CrewMediaTransferRef) {
        states.remove(transfer)
        outgoing.remove(transfer)?.close()
    }

    /** Local policy/session teardown abandons all protocol work without sending another frame. */
    @Synchronized
    fun clear() {
        states.clear()
        outgoing.values.forEach(Outgoing::close)
        outgoing.clear()
    }

    @Synchronized
    fun resume(transfer: CrewMediaTransferRef): CrewSendResult? {
        if (states[transfer] == CrewMediaTransferState.CANCELLED) return null
        val outgoingState = outgoing[transfer] ?: return null
        var lastResult: CrewSendResult? = null
        while (true) {
            val next = outgoingState.next(states[transfer] ?: return lastResult) ?: break
            if (
                next is CrewMediaWireFrame.Chunk &&
                    states[transfer] == CrewMediaTransferState.MANIFEST_WAIT
            )
                break
            val result = send(transfer, next) ?: return lastResult
            lastResult = result
            if (result !is CrewSendResult.Sent) break
            outgoingState.sent(next)
            states[transfer] =
                if (next is CrewMediaWireFrame.Manifest) CrewMediaTransferState.MANIFEST_WAIT
                else CrewMediaTransferState.CHUNK_PROGRESS
        }
        return lastResult
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
        if (
            frame is CrewMediaWireFrame.ManifestAccepted &&
                outgoing[t]?.acknowledge(frame.nextChunkIndex) == false
        ) {
            states[t] = CrewMediaTransferState.REJECTED
            return CrewMediaTransferState.REJECTED
        }
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

    private class Outgoing(
        private val manifest: CrewMediaManifest,
        private val source: CrewAuthorizedMediaSource,
    ) {
        private var manifestSent = false
        private var reader: CrewMediaChunkReader? = null
        private var pending: CrewMediaWireFrame? = null
        private val inFlight = linkedMapOf<Int, CrewMediaWireFrame.Chunk>()
        private var nextProducedIndex = 0
        private var accepted = false

        fun next(state: CrewMediaTransferState): CrewMediaWireFrame? {
            pending?.let {
                return it
            }
            if (!manifestSent) {
                return CrewMediaWireFrame.Manifest(manifest).also { pending = it }
            }
            if (!accepted || state == CrewMediaTransferState.MANIFEST_WAIT) return null
            if (inFlight.size >= CREW_MEDIA_SEND_WINDOW_CHUNKS) return null
            val chunkReader =
                reader
                    ?: CrewMediaProducer.open(manifest, source, nextProducedIndex).also {
                        reader = it
                    }
            return chunkReader.next()?.let(CrewMediaWireFrame::Chunk)?.also { pending = it }
        }

        fun sent(frame: CrewMediaWireFrame) {
            check(pending === frame)
            if (frame is CrewMediaWireFrame.Manifest) manifestSent = true
            if (frame is CrewMediaWireFrame.Chunk) {
                inFlight[frame.value.index] = frame
                nextProducedIndex = frame.value.index + 1
            }
            pending = null
        }

        fun acknowledge(nextChunkIndex: Int): Boolean {
            if (nextChunkIndex !in 0..manifest.chunks.size) return false
            if (!accepted) {
                accepted = true
                nextProducedIndex = nextChunkIndex
            } else if (nextChunkIndex > nextProducedIndex) {
                return false
            }
            inFlight.keys.removeAll { it < nextChunkIndex }
            return true
        }

        fun close() {
            reader?.close()
            reader = null
            pending = null
        }
    }
}
