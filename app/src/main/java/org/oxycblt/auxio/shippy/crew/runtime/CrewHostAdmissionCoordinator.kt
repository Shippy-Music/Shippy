/*
 * Copyright (c) 2026 Shippy contributors
 * CrewHostAdmissionCoordinator.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.runtime

import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.crew.connection.CrewDirectPeerConnection
import org.oxycblt.auxio.shippy.crew.connection.CrewDirectPeerFailure
import org.oxycblt.auxio.shippy.crew.connection.CrewDirectPeerState
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.DurableEventId
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalPeer
import org.oxycblt.auxio.shippy.crew.session.CrewAdmissionRejection
import org.oxycblt.auxio.shippy.crew.session.CrewAdmissionResult
import org.oxycblt.auxio.shippy.crew.session.CrewSessionEngine
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport

private const val MAX_ADMISSION_EVENT_ID_BYTES = 128

/** Narrow session boundary used by host-side connection admission. */
interface CrewSessionAdmissionPort {
    fun attachPeer(transport: CrewPeerTransport)

    suspend fun admitPeer(
        transportMemberId: CrewMemberId,
        member: CrewMember,
        requestId: DurableEventId,
    ): CrewAdmissionResult
}

/** Production adapter; runtime construction remains outside this coordinator. */
class CrewSessionEngineAdmissionPort(
    private val engine: CrewSessionEngine,
) : CrewSessionAdmissionPort {
    override fun attachPeer(transport: CrewPeerTransport) = engine.attachPeer(transport)

    override suspend fun admitPeer(
        transportMemberId: CrewMemberId,
        member: CrewMember,
        requestId: DurableEventId,
    ) = engine.admitPeer(transportMemberId, member, requestId)
}

/** A responder direct connection with no dependency on its WebRTC construction details. */
interface CrewDirectConnectionHandle : Closeable {
    val state: StateFlow<CrewDirectPeerState>

    fun start()
}

/** Factory boundary for a responder connection created from a QR-authenticated signaling peer. */
fun interface CrewDirectResponderHandleFactory {
    fun createResponder(signalingPeer: CrewSignalPeer): CrewDirectConnectionHandle
}

/** Adapter for an already-constructed direct peer connection. */
class CrewDirectPeerConnectionHandle(
    private val connection: CrewDirectPeerConnection,
) : CrewDirectConnectionHandle {
    override val state: StateFlow<CrewDirectPeerState> = connection.state

    override fun start() = connection.start()

    override fun close() = connection.close()
}

fun interface CrewAdmissionEventIdSource {
    /** Returns a fresh ID no longer than the durable control protocol limit. */
    fun nextId(): DurableEventId
}

object SecureCrewAdmissionEventIdSource : CrewAdmissionEventIdSource {
    override fun nextId() = DurableEventId("crew-admission-${UUID.randomUUID()}")
}

sealed interface CrewHostAdmissionState {
    data object Connecting : CrewHostAdmissionState

    data class Active(val admission: CrewAdmissionResult) : CrewHostAdmissionState

    data class Rejected(val reason: CrewHostAdmissionFailure) : CrewHostAdmissionState
}

sealed interface CrewHostAdmissionFailure {
    data object SessionMismatch : CrewHostAdmissionFailure

    data object ProtocolMismatch : CrewHostAdmissionFailure

    data object ClaimedLocalMember : CrewHostAdmissionFailure

    data object MemberIdMismatch : CrewHostAdmissionFailure

    data class AdmissionRejected(val reason: CrewAdmissionRejection) : CrewHostAdmissionFailure

    data class ConnectionFailed(val reason: CrewDirectPeerFailure) : CrewHostAdmissionFailure

    data object ConnectionClosed : CrewHostAdmissionFailure

    data object Exception : CrewHostAdmissionFailure
}

/**
 * Host-side admission boundary for QR-authenticated signaling peers.
 *
 * Signaling identity is a provisional claim. The display name is admitted only after the
 * fingerprint-bound transport proves exactly the same member ID.
 */
class CrewHostAdmissionCoordinator(
    private val sessionId: CrewSessionId,
    private val localMemberId: CrewMemberId,
    private val session: CrewSessionAdmissionPort,
    private val responders: CrewDirectResponderHandleFactory,
    private val eventIds: CrewAdmissionEventIdSource = SecureCrewAdmissionEventIdSource,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Closeable {
    private data class ActiveHandle(
        val signalingPeer: CrewSignalPeer,
        val handle: CrewDirectConnectionHandle,
        var admissionStarted: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val handles = mutableMapOf<CrewMemberId, ActiveHandle>()
    private val generations = mutableMapOf<CrewMemberId, Long>()
    private val mutableStates = MutableStateFlow<Map<CrewMemberId, CrewHostAdmissionState>>(emptyMap())
    private var closed = false

    val states: StateFlow<Map<CrewMemberId, CrewHostAdmissionState>> = mutableStates.asStateFlow()

    fun accept(signalingPeer: CrewSignalPeer) {
        val claimedMemberId = signalingPeer.remoteMemberClaim
        when {
            signalingPeer.sessionId.protocolVersion != sessionId.protocolVersion ||
                claimedMemberId.protocolVersion != sessionId.protocolVersion ->
                rejectImmediately(claimedMemberId, signalingPeer, CrewHostAdmissionFailure.ProtocolMismatch)
            signalingPeer.sessionId != sessionId ->
                rejectImmediately(claimedMemberId, signalingPeer, CrewHostAdmissionFailure.SessionMismatch)
            claimedMemberId == localMemberId ->
                rejectImmediately(claimedMemberId, signalingPeer, CrewHostAdmissionFailure.ClaimedLocalMember)
            else -> {
                val generation = synchronized(lock) {
                    ((generations[claimedMemberId] ?: 0L) + 1L).also {
                        generations[claimedMemberId] = it
                    }
                }
                scope.launch { install(claimedMemberId, signalingPeer, generation) }
            }
        }
    }

    private fun rejectImmediately(
        memberId: CrewMemberId,
        signalingPeer: CrewSignalPeer,
        reason: CrewHostAdmissionFailure,
    ) {
        signalingPeer.close()
        synchronized(lock) {
            if (handles[memberId] == null) {
                mutableStates.value =
                    mutableStates.value + (memberId to CrewHostAdmissionState.Rejected(reason))
            }
        }
    }

    private suspend fun install(
        memberId: CrewMemberId,
        signalingPeer: CrewSignalPeer,
        generation: Long,
    ) {
        val handle =
            runCatching { responders.createResponder(signalingPeer) }
                .getOrElse {
                    signalingPeer.close()
                    publishGenerationExact(
                        memberId,
                        generation,
                        CrewHostAdmissionState.Rejected(CrewHostAdmissionFailure.Exception),
                    )
                    return
                }
        val active = ActiveHandle(signalingPeer, handle)
        val install = synchronized(lock) {
            if (closed || generations[memberId] != generation) null else handles.put(memberId, active)
        }
        if (install == null && synchronized(lock) { closed || generations[memberId] != generation }) {
            closePair(active)
            return
        }
        val replaced = install
        replaced?.let(::closePair)
        publish(memberId, CrewHostAdmissionState.Connecting)
        runCatching { handle.start() }
            .onFailure {
                failExact(memberId, active, CrewHostAdmissionFailure.Exception)
                return
            }
        scope.launch {
            handle.state.collect { state -> observe(memberId, active, state) }
        }
    }

    private suspend fun observe(
        memberId: CrewMemberId,
        active: ActiveHandle,
        state: CrewDirectPeerState,
    ) {
        when (state) {
            is CrewDirectPeerState.Connected -> admitConnected(memberId, active, state.transport)
            is CrewDirectPeerState.Failed ->
                failExact(memberId, active, CrewHostAdmissionFailure.ConnectionFailed(state.reason))
            CrewDirectPeerState.Closed -> failExact(memberId, active, CrewHostAdmissionFailure.ConnectionClosed)
            CrewDirectPeerState.New,
            CrewDirectPeerState.Negotiating,
            CrewDirectPeerState.Authenticating -> Unit
        }
    }

    private suspend fun admitConnected(
        memberId: CrewMemberId,
        active: ActiveHandle,
        transport: CrewPeerTransport,
    ) {
        val shouldAdmit =
            synchronized(lock) {
                if (handles[memberId] !== active || active.admissionStarted) false
                else {
                    active.admissionStarted = true
                    true
                }
            }
        if (!shouldAdmit) return
        if (transport.remoteMemberId != memberId) {
            failExact(memberId, active, CrewHostAdmissionFailure.MemberIdMismatch)
            return
        }
        val member = CrewMember(memberId, active.signalingPeer.remoteDisplayName)
        val result =
            runCatching {
                session.attachPeer(transport)
                session.admitPeer(memberId, member, nextAdmissionEventId())
            }.getOrElse {
                failExact(memberId, active, CrewHostAdmissionFailure.Exception)
                return
            }
        when (result) {
            is CrewAdmissionResult.Admitted,
            is CrewAdmissionResult.AlreadyActive -> publishExact(memberId, active, CrewHostAdmissionState.Active(result))
            is CrewAdmissionResult.Rejected ->
                failExact(memberId, active, CrewHostAdmissionFailure.AdmissionRejected(result.reason))
        }
    }

    private suspend fun failExact(
        memberId: CrewMemberId,
        active: ActiveHandle,
        reason: CrewHostAdmissionFailure,
    ) {
        val removed = synchronized(lock) { if (handles[memberId] === active) handles.remove(memberId) else null }
        if (removed != null) {
            closePair(removed)
            publish(memberId, CrewHostAdmissionState.Rejected(reason))
        }
    }

    private suspend fun publishExact(
        memberId: CrewMemberId,
        active: ActiveHandle,
        state: CrewHostAdmissionState,
    ) {
        if (synchronized(lock) { handles[memberId] === active }) publish(memberId, state)
    }

    private fun publish(memberId: CrewMemberId, state: CrewHostAdmissionState) {
        synchronized(lock) {
            mutableStates.value = mutableStates.value + (memberId to state)
        }
    }

    private fun publishGenerationExact(
        memberId: CrewMemberId,
        generation: Long,
        state: CrewHostAdmissionState,
    ) {
        synchronized(lock) {
            if (!closed && generations[memberId] == generation && handles[memberId] == null) {
                mutableStates.value = mutableStates.value + (memberId to state)
            }
        }
    }

    private fun nextAdmissionEventId(): DurableEventId =
        eventIds.nextId().also {
            require(it.value.toByteArray(Charsets.UTF_8).size <= MAX_ADMISSION_EVENT_ID_BYTES) {
                "Crew admission event ID exceeds $MAX_ADMISSION_EVENT_ID_BYTES bytes"
            }
        }

    override fun close() {
        val toClose = synchronized(lock) {
            if (closed) return
            closed = true
            handles.values.toList().also {
                handles.clear()
                generations.clear()
            }
        }
        toClose.forEach(::closePair)
        scope.cancel()
    }

    private fun closePair(active: ActiveHandle) {
        active.handle.close()
        active.signalingPeer.close()
    }
}
