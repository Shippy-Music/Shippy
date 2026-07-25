/*
 * Copyright (c) 2026 Shippy contributors
 * CrewSessionOrchestrator.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.crew.rejoin

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewSnapshot
import org.oxycblt.auxio.shippy.crew.session.CrewSessionEngine
import org.oxycblt.auxio.shippy.persistence.crew.CrewCheckpointLoadResult
import org.oxycblt.auxio.shippy.persistence.crew.CrewCheckpointRepository
import org.oxycblt.auxio.shippy.persistence.crew.CrewRejoinLeaseStore

fun interface CrewSessionEngineFactory {
    fun create(lease: CrewRejoinLease, checkpoint: CrewSnapshot): CrewSessionEngine
}

/** Connector implementations must attach only authenticated [CrewPeerTransport] instances. */
fun interface CrewRejoinConnector {
    suspend fun reconnect(lease: CrewRejoinLease, engine: CrewSessionEngine): CrewRejoinConnectResult
}

sealed interface CrewRejoinConnectResult {
    data object Connected : CrewRejoinConnectResult

    /** Network/peer absence: retain the lease until its existing expiry. */
    data object RetryableFailure : CrewRejoinConnectResult

    /** Authenticated refusal/revocation: never retry this credential. */
    data object Revoked : CrewRejoinConnectResult
}

sealed interface CrewRestoreResult {
    data object NothingToRestore : CrewRestoreResult

    data object RestoredAndConnected : CrewRestoreResult

    data object RestoredAwaitingNetwork : CrewRestoreResult

    data class Discarded(val decision: CrewRejoinRestoreDecision) : CrewRestoreResult
}

/**
 * Binds the non-secret Room checkpoint to the sole Keystore lease. Reconnects use the ordinary
 * authenticated transport path, so no restore path can bypass snapshot, publisher, or election
 * validation already enforced by [CrewSessionEngine].
 */
class CrewSessionOrchestrator(
    private val checkpoints: CrewCheckpointRepository,
    private val leases: CrewRejoinLeaseStore,
    private val engines: CrewSessionEngineFactory,
    private val connector: CrewRejoinConnector,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var active: Pair<CrewRejoinLease, CrewSessionEngine>? = null

    suspend fun restoreAfterProcessStart(): CrewRestoreResult =
        mutex.withLock {
            active?.let { return@withLock reconnectLocked(it.first, it.second) }
            val checkpoint = (checkpoints.load() as? CrewCheckpointLoadResult.Loaded)?.checkpoint?.snapshot
            val lease = leases.load()
            val decision = CrewRejoinRestorePolicy.decide(checkpoint, lease, nowEpochMs())
            if (decision != CrewRejoinRestoreDecision.RESTORE) {
                cleanupLocked(checkpoint?.sessionId, lease?.sessionId)
                return@withLock if (checkpoint == null && lease == null) {
                    CrewRestoreResult.NothingToRestore
                } else {
                    CrewRestoreResult.Discarded(decision)
                }
            }
            val validLease = checkNotNull(lease)
            val validCheckpoint = checkNotNull(checkpoint)
            val engine = engines.create(validLease, validCheckpoint)
            engine.start()
            active = validLease to engine
            reconnectLocked(validLease, engine)
        }

    /** Network recovery hook; loss itself is handled by the engine's reconnect-grace policy. */
    suspend fun onNetworkAvailable(): CrewRestoreResult =
        if (mutex.withLock { active == null }) {
            restoreAfterProcessStart()
        } else mutex.withLock {
            val current = active
                ?: return@withLock CrewRestoreResult.NothingToRestore
            if (nowEpochMs() >= current.first.expiresAtEpochMs) {
                cleanupLocked(current.first.sessionId, current.first.sessionId)
                current.second.close()
                active = null
                CrewRestoreResult.Discarded(CrewRejoinRestoreDecision.DISCARD_EXPIRED_LEASE)
            } else {
                reconnectLocked(current.first, current.second)
            }
        }

    /** Call only after an accepted Leave/End event has revoked this local membership. */
    suspend fun revokeAfterAcceptedLeave(sessionId: CrewSessionId) {
        mutex.withLock {
            if (active?.first?.sessionId == sessionId) {
                active?.second?.close()
                active = null
            }
            cleanupLocked(sessionId, sessionId)
        }
    }

    private suspend fun reconnectLocked(
        lease: CrewRejoinLease,
        engine: CrewSessionEngine,
    ): CrewRestoreResult =
        when (connector.reconnect(lease, engine)) {
            CrewRejoinConnectResult.Connected -> CrewRestoreResult.RestoredAndConnected
            CrewRejoinConnectResult.RetryableFailure -> CrewRestoreResult.RestoredAwaitingNetwork
            CrewRejoinConnectResult.Revoked -> {
                cleanupLocked(lease.sessionId, lease.sessionId)
                engine.close()
                active = null
                CrewRestoreResult.Discarded(CrewRejoinRestoreDecision.DISCARDED_REVOKED)
            }
        }

    private suspend fun cleanupLocked(checkpointSessionId: CrewSessionId?, leaseSessionId: CrewSessionId?) {
        checkpointSessionId?.let(checkpoints::clear)
        leaseSessionId?.let(leases::clear)
    }
}
