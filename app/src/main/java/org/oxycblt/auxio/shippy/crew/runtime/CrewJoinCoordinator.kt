/*
 * Copyright (c) 2026 Shippy contributors
 * CrewJoinCoordinator.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.runtime

import java.io.Closeable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.crew.connection.CrewDirectPeerFailure
import org.oxycblt.auxio.shippy.crew.connection.CrewDirectPeerState
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewSnapshot
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalPeer
import org.oxycblt.auxio.shippy.crew.session.CrewSessionEngine
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport

private const val DEFAULT_JOIN_TIMEOUT_MS = 15_000L

/** The joined session, deliberately independent from its runtime construction. */
interface CrewJoinedSessionPort : Closeable {
    suspend fun start()

    fun attachPeer(transport: CrewPeerTransport)
}

/** Production adapter; dependencies for [CrewSessionEngine] remain outside this coordinator. */
class CrewSessionEngineJoinPort(
    private val engine: CrewSessionEngine,
) : CrewJoinedSessionPort {
    override suspend fun start() = engine.start()

    override fun attachPeer(transport: CrewPeerTransport) = engine.attachPeer(transport)

    override fun close() = engine.close()
}

fun interface CrewJoinSessionFactory {
    suspend fun create(snapshot: CrewSnapshot): CrewJoinedSessionPort
}

fun interface CrewDirectInitiatorHandleFactory {
    fun createInitiator(signalingPeer: CrewSignalPeer): CrewDirectConnectionHandle
}

sealed interface CrewJoinState {
    data object Connecting : CrewJoinState

    data object Bootstrapping : CrewJoinState

    data class Active(val snapshot: CrewSnapshot) : CrewJoinState

    data class Rejected(val reason: CrewJoinFailure) : CrewJoinState
}

sealed interface CrewJoinFailure {
    data object SessionMismatch : CrewJoinFailure

    data object ProtocolMismatch : CrewJoinFailure

    data object ClaimedLocalMember : CrewJoinFailure

    data object MemberIdMismatch : CrewJoinFailure

    data class ConnectionFailed(val reason: CrewDirectPeerFailure) : CrewJoinFailure

    data object ConnectionClosed : CrewJoinFailure

    data class BootstrapRejected(val reason: CrewJoinBootstrapResult.Rejected) : CrewJoinFailure

    data object TimedOut : CrewJoinFailure

    data object ConnectionSetupFailed : CrewJoinFailure

    data object SessionFailure : CrewJoinFailure

    data object Closed : CrewJoinFailure
}

/**
 * One join attempt from a QR-authenticated signaling peer through an authenticated direct peer.
 *
 * Before [CrewJoinState.Active], this coordinator owns every resource. Once active, the joined
 * session owns the engine and transport lifecycle; [close] still tears down the complete attempt.
 */
class CrewJoinCoordinator(
    private val sessionId: CrewSessionId,
    private val localMemberId: CrewMemberId,
    private val signalingPeer: CrewSignalPeer,
    private val initiators: CrewDirectInitiatorHandleFactory,
    private val sessions: CrewJoinSessionFactory,
    private val timeoutMs: Long = DEFAULT_JOIN_TIMEOUT_MS,
    private val nowMonotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val mutableState = MutableStateFlow<CrewJoinState>(CrewJoinState.Connecting)
    private var handle: CrewDirectConnectionHandle? = null
    private var session: CrewJoinedSessionPort? = null
    private var stateJob: Job? = null
    private var bootstrapJob: Job? = null
    private var timeoutJob: Job? = null
    private var started = false
    private var active = false
    private var closed = false

    val state: StateFlow<CrewJoinState> = mutableState.asStateFlow()

    fun start() {
        val invalid =
            when {
                timeoutMs <= 0L -> CrewJoinFailure.TimedOut
                sessionId.protocolVersion != localMemberId.protocolVersion ||
                    signalingPeer.sessionId.protocolVersion != sessionId.protocolVersion ||
                    signalingPeer.remoteMemberClaim.protocolVersion != sessionId.protocolVersion ->
                    CrewJoinFailure.ProtocolMismatch
                signalingPeer.sessionId != sessionId -> CrewJoinFailure.SessionMismatch
                signalingPeer.remoteMemberClaim == localMemberId -> CrewJoinFailure.ClaimedLocalMember
                else -> null
            }
        synchronized(lock) {
            if (started || closed) return
            started = true
        }
        if (invalid != null) {
            reject(invalid)
            return
        }
        val created = runCatching { initiators.createInitiator(signalingPeer) }.getOrElse {
            reject(CrewJoinFailure.ConnectionSetupFailed)
            return
        }
        synchronized(lock) { handle = created }
        stateJob = scope.launch { created.state.collect { observe(created, it) } }
        timeoutJob = scope.launch {
            delay(timeoutMs)
            reject(CrewJoinFailure.TimedOut)
        }
        runCatching { created.start() }
            .onFailure { reject(CrewJoinFailure.ConnectionSetupFailed) }
    }

    private fun observe(
        observedHandle: CrewDirectConnectionHandle,
        directState: CrewDirectPeerState,
    ) {
        if (synchronized(lock) { handle !== observedHandle || active || closed }) return
        when (directState) {
            is CrewDirectPeerState.Connected -> connected(observedHandle, directState.transport)
            is CrewDirectPeerState.Failed -> reject(CrewJoinFailure.ConnectionFailed(directState.reason))
            CrewDirectPeerState.Closed -> reject(CrewJoinFailure.ConnectionClosed)
            CrewDirectPeerState.New,
            CrewDirectPeerState.Negotiating,
            CrewDirectPeerState.Authenticating -> Unit
        }
    }

    private fun connected(observedHandle: CrewDirectConnectionHandle, transport: CrewPeerTransport) {
        if (transport.remoteMemberId != signalingPeer.remoteMemberClaim) {
            reject(CrewJoinFailure.MemberIdMismatch)
            return
        }
        val startBootstrap = synchronized(lock) {
            if (handle !== observedHandle || bootstrapJob != null || active || closed) false
            else true
        }
        if (!startBootstrap) return
        mutableState.value = CrewJoinState.Bootstrapping
        bootstrapJob =
            scope.launch {
                val accumulator = CrewJoinBootstrapAccumulator(sessionId, localMemberId)
                transport.incoming.collect { frame ->
                    when (val result = accumulator.accept(transport.remoteMemberId, frame, nowMonotonicMs())) {
                        CrewJoinBootstrapResult.Waiting -> Unit
                        is CrewJoinBootstrapResult.Rejected -> reject(CrewJoinFailure.BootstrapRejected(result))
                        is CrewJoinBootstrapResult.Accepted -> activate(observedHandle, transport, result.snapshot)
                    }
                }
            }
    }

    private suspend fun activate(
        observedHandle: CrewDirectConnectionHandle,
        transport: CrewPeerTransport,
        snapshot: CrewSnapshot,
    ) {
        val joined =
            runCatching { sessions.create(snapshot) }.getOrElse {
                reject(CrewJoinFailure.SessionFailure)
                return
            }
        val mayActivate = synchronized(lock) {
            if (handle !== observedHandle || active || closed) false else {
                session = joined
                true
            }
        }
        if (!mayActivate) {
            joined.close()
            return
        }
        val started = runCatching { joined.start(); joined.attachPeer(transport) }.isSuccess
        if (!started) {
            reject(CrewJoinFailure.SessionFailure)
            return
        }
        synchronized(lock) {
            if (handle === observedHandle && !closed && !active) {
                active = true
                timeoutJob?.cancel()
                bootstrapJob?.cancel()
                mutableState.value = CrewJoinState.Active(snapshot)
            }
        }
    }

    private fun reject(reason: CrewJoinFailure) {
        val resources = synchronized(lock) {
            if (active || closed || mutableState.value is CrewJoinState.Rejected) return
            closed = true
            mutableState.value = CrewJoinState.Rejected(reason)
            Pair(handle, session)
        }
        resources.first?.close()
        resources.second?.close()
        signalingPeer.close()
        scope.cancel()
    }

    override fun close() {
        val resources = synchronized(lock) {
            if (closed) return
            closed = true
            if (!active) mutableState.value = CrewJoinState.Rejected(CrewJoinFailure.Closed)
            Pair(handle, session)
        }
        resources.first?.close()
        resources.second?.close()
        signalingPeer.close()
        scope.cancel()
    }
}
