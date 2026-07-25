/*
 * Copyright (c) 2026 Shippy contributors
 * CrewMediaSessionRouter.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.media

import java.util.concurrent.ConcurrentHashMap
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId

/**
 * Product-specific authorization stays outside the media wire protocol. Implementations resolve
 * only the exact requested candidate and never expose a path, library enumeration, or provider
 * credential to a Crew peer.
 */
interface CrewMediaSessionCallbacks {
    fun authorizeSupplierSource(
        transfer: CrewMediaTransferRef,
        requestingMemberId: CrewMemberId,
    ): CrewAuthorizedMediaSource?

    fun onTemporaryMediaComplete(
        manifest: CrewMediaManifest,
        supplyingMemberId: CrewMemberId,
    )

    fun onTransferRetryLater(
        transfer: CrewMediaTransferRef,
        peerMemberId: CrewMemberId,
    ) = Unit

    fun onTransferRejected(
        transfer: CrewMediaTransferRef,
        peerMemberId: CrewMemberId,
    ) = Unit
}

/**
 * Authenticated media endpoint installed behind [CrewAuthenticatedMediaLifecycle]. The session
 * engine remains the sole transport collector and supplies the peer identity with every frame.
 * This class owns only bounded, per-peer media state; it does not discover peers or inspect media.
 */
class CrewMediaSessionRouter(
    private val activeSessionId: CrewSessionId,
    private val localMemberId: CrewMemberId,
    private val pushPullPolicy: ActiveCrewPushPullPolicy,
    private val receiver: CrewMediaReceiver,
    private val callbacks: CrewMediaSessionCallbacks,
) : CrewAuthenticatedMediaLifecycle {
    private val peers = ConcurrentHashMap<CrewMemberId, PeerState>()

    override fun onPeerAttached(peer: CrewAuthenticatedMediaPeer) {
        if (peer.memberId != peer.transport.remoteMemberId || peer.memberId == localMemberId) return
        val media = CrewMediaTransport(activeSessionId, pushPullPolicy, peer.transport)
        val replacement = PeerState(peer, media, CrewMediaTransferController(
            activeSessionId,
            localMemberId,
            peer.transport,
            media,
            pushPullPolicy,
        ))
        peers.put(peer.memberId, replacement)?.let { previous ->
            previous.receivingTransfers.forEach(receiver::cancel)
            previous.receivingTransfers.clear()
        }
    }

    override fun onMediaFrame(peer: CrewAuthenticatedMediaPeer, frame: org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame): CrewMediaFrameResult {
        val state = peers[peer.memberId]
            ?.takeIf { it.peer.transport === peer.transport && it.peer.memberId == peer.memberId }
            ?: return CrewMediaFrameResult.Rejected("media peer is not attached")
        if (!pushPullPolicy.accepts(activeSessionId)) {
            // Disabling Push & Pull is local policy, not peer protocol corruption. Keep the
            // authenticated control connection alive while refusing all media work.
            return CrewMediaFrameResult.Accepted
        }
        val decoded = state.media.receive(frame)
            ?: return CrewMediaFrameResult.Rejected("malformed, inactive, or wrong-session media frame")
        if (!isInboundForLocal(decoded, peer.memberId)) {
            state.media.send(CrewMediaWireFrame.Rejected(decoded.transfer))
            return CrewMediaFrameResult.Rejected("media transfer does not match authenticated peer")
        }
        if (state.controller.receive(decoded) == CrewMediaTransferState.REJECTED) {
            state.media.send(CrewMediaWireFrame.Rejected(decoded.transfer))
            return CrewMediaFrameResult.Rejected("media transfer was rejected")
        }

        return when (decoded) {
            is CrewMediaWireFrame.Request -> handleRequest(state, decoded)
            is CrewMediaWireFrame.Manifest -> handleManifest(state, decoded)
            is CrewMediaWireFrame.Chunk -> handleChunk(state, decoded)
            is CrewMediaWireFrame.ManifestAccepted -> {
                state.controller.resume(decoded.transfer)
                CrewMediaFrameResult.Accepted
            }
            is CrewMediaWireFrame.RetryLater -> {
                runCatching {
                    callbacks.onTransferRetryLater(decoded.transfer, state.peer.memberId)
                }
                CrewMediaFrameResult.Accepted
            }
            is CrewMediaWireFrame.ObjectComplete -> {
                state.controller.forget(decoded.transfer)
                CrewMediaFrameResult.Accepted
            }
            is CrewMediaWireFrame.Cancel -> {
                if (decoded.transfer.targetMemberId == localMemberId) {
                    receiver.cancel(decoded.transfer)
                    state.receivingTransfers.remove(decoded.transfer)
                }
                state.controller.forget(decoded.transfer)
                CrewMediaFrameResult.Accepted
            }
            is CrewMediaWireFrame.Rejected -> {
                if (decoded.transfer.targetMemberId == localMemberId) {
                    receiver.cancel(decoded.transfer)
                    state.receivingTransfers.remove(decoded.transfer)
                }
                state.controller.forget(decoded.transfer)
                runCatching {
                    callbacks.onTransferRejected(decoded.transfer, state.peer.memberId)
                }
                CrewMediaFrameResult.Accepted
            }
        }
    }

    override fun onPeerDetached(peer: CrewAuthenticatedMediaPeer) {
        val state = peers[peer.memberId] ?: return
        if (state.peer.transport === peer.transport && peers.remove(peer.memberId, state)) {
            state.receivingTransfers.forEach(receiver::cancel)
            state.receivingTransfers.clear()
        }
    }

    /** An external readiness signal may resume one previously backpressured supplier transfer. */
    fun resumeSupplierTransfer(peerMemberId: CrewMemberId, transfer: CrewMediaTransferRef) {
        peers[peerMemberId]?.controller?.resume(transfer)
    }

    private fun handleRequest(state: PeerState, frame: CrewMediaWireFrame.Request): CrewMediaFrameResult {
        val source = runCatching {
            callbacks.authorizeSupplierSource(frame.transfer, state.peer.memberId)
        }.getOrNull()
        if (source == null) {
            state.media.send(CrewMediaWireFrame.Rejected(frame.transfer))
            state.controller.forget(frame.transfer)
            // A valid peer request may legitimately be unavailable on this supplier. The
            // requester can select another supplier without losing the Crew connection.
            return CrewMediaFrameResult.Accepted
        }
        val offered = runCatching { state.controller.offer(frame.transfer, source) }.getOrNull()
        if (offered == null) {
            state.media.send(CrewMediaWireFrame.Rejected(frame.transfer))
            state.controller.forget(frame.transfer)
            return CrewMediaFrameResult.Accepted
        }
        return CrewMediaFrameResult.Accepted
    }

    private fun handleManifest(state: PeerState, frame: CrewMediaWireFrame.Manifest): CrewMediaFrameResult =
        when (val result = receiver.accept(frame.value)) {
            is CrewMediaReceiveResult.Accepted -> {
                state.receivingTransfers.add(frame.transfer)
                state.media.send(CrewMediaWireFrame.ManifestAccepted(frame.transfer))
                CrewMediaFrameResult.Accepted
            }
            is CrewMediaReceiveResult.Retry -> {
                state.media.send(CrewMediaWireFrame.RetryLater(frame.transfer))
                CrewMediaFrameResult.Accepted
            }
            is CrewMediaReceiveResult.Rejected -> reject(state, frame.transfer, result.reason)
            is CrewMediaReceiveResult.Complete -> reject(state, frame.transfer, "manifest cannot complete media")
        }

    private fun handleChunk(state: PeerState, frame: CrewMediaWireFrame.Chunk): CrewMediaFrameResult =
        when (val result = receiver.accept(frame.value)) {
            is CrewMediaReceiveResult.Accepted -> {
                // This acknowledgement also advances the supplier's bounded one-chunk window.
                state.media.send(CrewMediaWireFrame.ManifestAccepted(frame.transfer))
                CrewMediaFrameResult.Accepted
            }
            is CrewMediaReceiveResult.Complete -> {
                runCatching { callbacks.onTemporaryMediaComplete(result.manifest, state.peer.memberId) }
                state.media.send(CrewMediaWireFrame.ObjectComplete(frame.transfer))
                state.receivingTransfers.remove(frame.transfer)
                state.controller.forget(frame.transfer)
                CrewMediaFrameResult.Accepted
            }
            is CrewMediaReceiveResult.Retry -> {
                state.media.send(CrewMediaWireFrame.RetryLater(frame.transfer))
                CrewMediaFrameResult.Accepted
            }
            is CrewMediaReceiveResult.Rejected -> reject(state, frame.transfer, result.reason)
        }

    private fun reject(state: PeerState, transfer: CrewMediaTransferRef, reason: String): CrewMediaFrameResult {
        receiver.cancel(transfer)
        state.receivingTransfers.remove(transfer)
        state.media.send(CrewMediaWireFrame.Rejected(transfer))
        state.controller.forget(transfer)
        return CrewMediaFrameResult.Rejected(reason)
    }

    private fun isInboundForLocal(frame: CrewMediaWireFrame, peerId: CrewMemberId): Boolean {
        val transfer = frame.transfer
        if (transfer.sessionId != activeSessionId || !pushPullPolicy.accepts(activeSessionId)) return false
        val localIsSupplier = transfer.supplierMemberId == localMemberId && transfer.targetMemberId == peerId
        val localIsTarget = transfer.targetMemberId == localMemberId && transfer.supplierMemberId == peerId
        return when (frame) {
            is CrewMediaWireFrame.Request,
            is CrewMediaWireFrame.ManifestAccepted,
            is CrewMediaWireFrame.RetryLater,
            is CrewMediaWireFrame.ObjectComplete -> localIsSupplier
            is CrewMediaWireFrame.Manifest,
            is CrewMediaWireFrame.Chunk -> localIsTarget
            is CrewMediaWireFrame.Cancel,
            is CrewMediaWireFrame.Rejected -> localIsSupplier || localIsTarget
        }
    }

    private data class PeerState(
        val peer: CrewAuthenticatedMediaPeer,
        val media: CrewMediaTransport,
        val controller: CrewMediaTransferController,
        val receivingTransfers: MutableSet<CrewMediaTransferRef> = ConcurrentHashMap.newKeySet(),
    )
}
