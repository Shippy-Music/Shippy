/*
 * Copyright (c) 2026 Shippy contributors
 * CrewJoinReconnectController.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.runtime

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.session.CrewSessionEngine
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportState

/** Minimal view of an already joined session. It deliberately cannot bootstrap another session. */
interface CrewJoinedSessionReconnectPort {
    val state: StateFlow<CrewState>
    val peerStates: StateFlow<Map<CrewMemberId, CrewTransportState>>

    fun attachPeer(transport: CrewPeerTransport)

    suspend fun requestSnapshot(): Boolean
}

class CrewSessionEngineReconnectPort(
    private val engine: CrewSessionEngine,
) : CrewJoinedSessionReconnectPort {
    override val state: StateFlow<CrewState> = engine.state
    override val peerStates: StateFlow<Map<CrewMemberId, CrewTransportState>> = engine.peerStates

    override fun attachPeer(transport: CrewPeerTransport) = engine.attachPeer(transport)

    override suspend fun requestSnapshot() = engine.requestSnapshot()
}

/** A dial result transfers its owned [connection] to the controller with [transport]. */
sealed interface CrewReconnectDialResult {
    data class Authenticated(
        val connection: Closeable,
        val transport: CrewPeerTransport,
    ) : CrewReconnectDialResult

    data object Retryable : CrewReconnectDialResult

    data object Expired : CrewReconnectDialResult
}

fun interface CrewJoinedSessionReconnectDialer {
    suspend fun dial(expectedCoordinatorMemberId: CrewMemberId): CrewReconnectDialResult
}

sealed interface CrewJoinReconnectState {
    data object Connected : CrewJoinReconnectState

    data class Reconnecting(val attempt: Int) : CrewJoinReconnectState

    data class Waiting(val attempt: Int, val delayMillis: Long) : CrewJoinReconnectState

    data object Expired : CrewJoinReconnectState

    data object Closed : CrewJoinReconnectState
}

/**
 * Reconnects one joined member to the current coordinator without rebuilding session state.
 *
 * A single loop owns every dial result. In particular, collectors only wake that loop; they never
 * own a connection, so cancelling a state observer cannot accidentally close an attached peer.
 */
class CrewJoinReconnectController(
    private val localMemberId: CrewMemberId,
    private val session: CrewJoinedSessionReconnectPort,
    private val dialer: CrewJoinedSessionReconnectDialer,
    private val initialDelayMillis: Long = 500L,
    private val maximumDelayMillis: Long = 15_000L,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow<CrewJoinReconnectState>(CrewJoinReconnectState.Connected)
    private val ownershipLock = Any()

    @Volatile
    private var closed = false
    private var started = false
    private var ownedConnection: Closeable? = null
    private var loop: Job? = null

    val state: StateFlow<CrewJoinReconnectState> = mutableState.asStateFlow()

    init {
        require(initialDelayMillis >= 0) { "Initial reconnect delay cannot be negative" }
        require(maximumDelayMillis >= initialDelayMillis) { "Maximum reconnect delay is too small" }
    }

    fun start() {
        if (
            !synchronized(ownershipLock) {
                if (started || closed) false
                else {
                    started = true
                    true
                }
            }
        ) {
            return
        }
        scope.launch { session.state.collect { wakeups.trySend(Unit) } }
        scope.launch { session.peerStates.collect { wakeups.trySend(Unit) } }
        loop = scope.launch { runLoop() }
        wakeups.trySend(Unit)
    }

    /** Call after a network change to bypass the current retry wait. */
    fun wake() {
        if (!closed) wakeups.trySend(Unit)
    }

    override fun close() {
        synchronized(ownershipLock) {
            if (closed) return
            closed = true
            ownedConnection?.close()
            ownedConnection = null
        }
        mutableState.value = CrewJoinReconnectState.Closed
        scope.cancel()
    }

    private suspend fun runLoop() {
        var failures = 0
        while (!closed) {
            val coordinator = reconnectCoordinatorOrNull()
            if (coordinator == null) {
                failures = 0
                mutableState.value = CrewJoinReconnectState.Connected
                wakeups.receiveCatching()
                continue
            }

            val attempt = failures + 1
            mutableState.value = CrewJoinReconnectState.Reconnecting(attempt)
            val dialResult =
                try {
                    dialer.dial(coordinator)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    CrewReconnectDialResult.Retryable
                }
            when (val result = dialResult) {
                CrewReconnectDialResult.Expired -> {
                    mutableState.value = CrewJoinReconnectState.Expired
                    return
                }
                CrewReconnectDialResult.Retryable -> failures = attempt
                is CrewReconnectDialResult.Authenticated -> {
                    val attached =
                        if (closed || !isStillExpected(coordinator, result.transport)) {
                            false
                        } else {
                            try {
                                // The session engine synchronously publishes the attached peer.
                                // Keep controller ownership only after that boundary succeeds.
                                session.attachPeer(result.transport)
                                session.requestSnapshot()
                            } catch (error: CancellationException) {
                                result.connection.close()
                                throw error
                            } catch (_: Exception) {
                                false
                            }
                        }
                    if (!attached || closed) {
                        result.connection.close()
                        failures = attempt
                    } else {
                        val previous =
                            synchronized(ownershipLock) {
                                if (closed) {
                                    null
                                } else {
                                    ownedConnection.also {
                                        ownedConnection = result.connection
                                    }
                                }
                            }
                        if (closed) {
                            result.connection.close()
                            return
                        }
                        previous?.close()
                        failures = 0
                        mutableState.value = CrewJoinReconnectState.Connected
                        continue
                    }
                }
            }

            if (closed) break
            val delayMillis = retryDelay(failures)
            mutableState.value = CrewJoinReconnectState.Waiting(failures, delayMillis)
            waitForWakeOrDelay(delayMillis)
        }
    }

    private fun reconnectCoordinatorOrNull(): CrewMemberId? {
        val coordinator = session.state.value.coordinatorMemberId
        return coordinator.takeUnless {
            it == localMemberId || it in session.peerStates.value
        }
    }

    private fun isStillExpected(expected: CrewMemberId, transport: CrewPeerTransport): Boolean =
        transport.remoteMemberId == expected && reconnectCoordinatorOrNull() == expected

    private fun retryDelay(failures: Int): Long {
        var delay = initialDelayMillis
        repeat((failures - 1).coerceAtLeast(0)) { delay = (delay * 2).coerceAtMost(maximumDelayMillis) }
        return delay
    }

    private suspend fun waitForWakeOrDelay(delayMillis: Long) {
        withTimeoutOrNull(delayMillis) { wakeups.receiveCatching() }
    }
}
