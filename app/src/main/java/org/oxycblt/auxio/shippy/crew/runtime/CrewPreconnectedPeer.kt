/*
 * Copyright (c) 2026 Auxio Project
 * CrewPreconnectedPeer.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.runtime

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.crew.connection.CrewDirectPeerFailure
import org.oxycblt.auxio.shippy.crew.connection.CrewDirectPeerState
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalPeer
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportState

/** A QR-authenticated transport that is already ready and does not need WebRTC negotiation. */
interface CrewPreconnectedSignalPeer : CrewSignalPeer {
    val preconnectedTransport: CrewPeerTransport
}

/** Adapts an offline proximity transport to the existing admission/join state machine. */
class CrewPreconnectedConnectionHandle(private val peer: CrewPreconnectedSignalPeer) :
    CrewDirectConnectionHandle {
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow<CrewDirectPeerState>(CrewDirectPeerState.New)

    override val state: StateFlow<CrewDirectPeerState> = mutableState.asStateFlow()

    override fun start() {
        if (!closed.get()) {
            mutableState.value = CrewDirectPeerState.Connected(peer.preconnectedTransport)
            scope.launch {
                peer.preconnectedTransport.state.collect { transportState ->
                    if (closed.get()) return@collect
                    when (transportState) {
                        CrewTransportState.FAILED ->
                            mutableState.value =
                                CrewDirectPeerState.Failed(CrewDirectPeerFailure.TRANSPORT)
                        CrewTransportState.CLOSED -> mutableState.value = CrewDirectPeerState.Closed
                        else -> Unit
                    }
                }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        peer.close()
        mutableState.value = CrewDirectPeerState.Closed
    }
}

fun CrewSignalPeer.toResponderHandleOr(
    fallback: (CrewSignalPeer) -> CrewDirectConnectionHandle
): CrewDirectConnectionHandle =
    if (this is CrewPreconnectedSignalPeer) CrewPreconnectedConnectionHandle(this)
    else fallback(this)

fun CrewSignalPeer.toInitiatorHandleOr(
    fallback: (CrewSignalPeer) -> CrewDirectConnectionHandle
): CrewDirectConnectionHandle =
    if (this is CrewPreconnectedSignalPeer) CrewPreconnectedConnectionHandle(this)
    else fallback(this)
