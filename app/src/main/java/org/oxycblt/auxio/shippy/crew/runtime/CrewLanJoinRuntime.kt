/*
 * Copyright (c) 2026 Shippy contributors
 * CrewLanJoinRuntime.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.runtime

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.oxycblt.auxio.shippy.crew.connection.CrewDirectPeerConnection
import org.oxycblt.auxio.shippy.crew.connection.CrewDirectPeerRole
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.core.toCrewState
import org.oxycblt.auxio.shippy.crew.invite.CrewInvite
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteCodec
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteDecodeResult
import org.oxycblt.auxio.shippy.crew.lan.CrewLanDiscovery
import org.oxycblt.auxio.shippy.crew.lan.CrewLanDiscoverySession
import org.oxycblt.auxio.shippy.crew.lan.CrewLanFailureOperation
import org.oxycblt.auxio.shippy.crew.lan.CrewLanOperationState
import org.oxycblt.auxio.shippy.crew.lan.CrewLanRendezvous
import org.oxycblt.auxio.shippy.crew.lan.CrewLanSignalConnectFailure
import org.oxycblt.auxio.shippy.crew.lan.CrewLanSignalConnectResult
import org.oxycblt.auxio.shippy.crew.lan.CrewLanSignalingClient
import org.oxycblt.auxio.shippy.crew.media.CrewActiveMediaRuntime
import org.oxycblt.auxio.shippy.crew.media.CrewActiveMediaRuntimeFactory
import org.oxycblt.auxio.shippy.crew.rejoin.CrewRejoinLease
import org.oxycblt.auxio.shippy.crew.session.CrewSessionEngine
import org.oxycblt.auxio.shippy.crew.settings.CrewProfileSettings
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewWebRtcRuntime
import org.oxycblt.auxio.shippy.persistence.crew.CrewCheckpointRepository
import org.oxycblt.auxio.shippy.persistence.crew.CrewRejoinLeaseStore

private const val CREW_JOIN_PROTOCOL_V1 = 1
private const val CREW_LAN_DISCOVERY_TIMEOUT_MS = 10_000L

sealed interface CrewLanJoinLaunchResult {
    data class Started(val session: CrewLanJoinedSession) : CrewLanJoinLaunchResult

    data class Failed(val reason: CrewLanJoinLaunchFailure) : CrewLanJoinLaunchResult
}

sealed interface CrewLanJoinLaunchFailure {
    data class InviteRejected(val reason: CrewInviteDecodeResult.Rejected) : CrewLanJoinLaunchFailure

    data class DiscoveryFailed(
        val operation: CrewLanFailureOperation,
        val platformCode: Int?,
    ) : CrewLanJoinLaunchFailure

    data object DiscoveryClosed : CrewLanJoinLaunchFailure

    data object DiscoveryTimedOut : CrewLanJoinLaunchFailure

    data class SignalingFailed(val reason: CrewLanSignalConnectFailure) : CrewLanJoinLaunchFailure

    data class JoinRejected(val reason: CrewJoinFailure) : CrewLanJoinLaunchFailure

    data object Initialization : CrewLanJoinLaunchFailure

    data object EngineOrPersistence : CrewLanJoinLaunchFailure
}

/**
 * A live LAN join. [close] is recovery-safe and retains the exact persisted checkpoint and lease.
 * [leave] is the explicit local teardown; it does not claim a graceful ordered Crew leave yet.
 */
class CrewLanJoinedSession internal constructor(
    val sessionId: CrewSessionId,
    val localMemberId: CrewMemberId,
    private val engine: CrewSessionEngine,
    val joinState: StateFlow<CrewJoinState>,
    private val coordinator: CrewJoinCoordinator,
    private val mediaRuntime: CrewActiveMediaRuntime,
    private val webRtcRuntime: CrewWebRtcRuntime,
    private val checkpoints: CrewCheckpointRepository,
    private val leases: CrewRejoinLeaseStore,
) : AutoCloseable {
    private val lock = Any()
    private var released = false
    private var left = false

    val state: StateFlow<CrewState> = engine.state

    override fun toString() = "CrewLanJoinedSession(identity=redacted, invite=redacted)"

    override fun close() {
        if (!markReleased()) return
        runCatching { coordinator.close() }
        runCatching { engine.close() }
        runCatching { mediaRuntime.close() }
        runCatching { webRtcRuntime.close() }
    }

    suspend fun leave() {
        val shouldLeave = synchronized(lock) {
            if (left) false else {
                left = true
                true
            }
        }
        if (!shouldLeave) return
        close()
        runCatching { checkpoints.clear(sessionId) }
        runCatching { leases.clear(sessionId) }
    }

    private fun markReleased() = synchronized(lock) {
        if (released) false else {
            released = true
            true
        }
    }
}

/** Production composition root for one LAN join attempt. Active-Crew exclusion stays external. */
class CrewLanJoinLauncher
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val lanDiscovery: CrewLanDiscovery,
    private val profileSettings: CrewProfileSettings,
    private val checkpoints: CrewCheckpointRepository,
    private val leases: CrewRejoinLeaseStore,
    private val mediaRuntimeFactory: CrewActiveMediaRuntimeFactory,
) {
    private val discoveryTimeoutMs = CREW_LAN_DISCOVERY_TIMEOUT_MS
    private val nowEpochMs: () -> Long = System::currentTimeMillis

    suspend fun join(inviteLink: String): CrewLanJoinLaunchResult {
        val protocol = ProtocolVersion(CREW_JOIN_PROTOCOL_V1)
        val invite =
            when (val decoded = CrewInviteCodec(setOf(protocol)).decode(inviteLink, nowEpochMs())) {
                is CrewInviteDecodeResult.Accepted -> decoded.invite
                is CrewInviteDecodeResult.Rejected ->
                    return CrewLanJoinLaunchResult.Failed(
                        CrewLanJoinLaunchFailure.InviteRejected(decoded),
                    )
            }
        val localMemberId = runCatching { profileSettings.memberId(protocol) }.getOrElse {
            return CrewLanJoinLaunchResult.Failed(CrewLanJoinLaunchFailure.Initialization)
        }
        val localMember =
            runCatching { CrewMember(localMemberId, profileSettings.displayName) }.getOrElse {
                return CrewLanJoinLaunchResult.Failed(CrewLanJoinLaunchFailure.Initialization)
            }
        val discovery =
            runCatching { lanDiscovery.discover(invite) }.getOrElse {
                return CrewLanJoinLaunchResult.Failed(CrewLanJoinLaunchFailure.Initialization)
            }
        val discoveryResult = awaitRendezvous(discovery, invite)
        if (discoveryResult !is RendezvousAwaitResult.Found) {
            runCatching { discovery.close() }
            return CrewLanJoinLaunchResult.Failed(discoveryResult.toFailure())
        }

        val signaling =
            runCatching {
                    CrewLanSignalingClient.connect(
                        rendezvous = discoveryResult.rendezvous,
                        invite = invite,
                        localMemberId = localMemberId,
                        localDisplayName = localMember.displayName,
                        nowEpochMs = nowEpochMs(),
                    )
                }
                .getOrElse {
                    runCatching { discovery.close() }
                    return CrewLanJoinLaunchResult.Failed(CrewLanJoinLaunchFailure.Initialization)
                }
        runCatching { discovery.close() }
        val signalPeer =
            when (signaling) {
                is CrewLanSignalConnectResult.Connected -> signaling.peer
                is CrewLanSignalConnectResult.Failed ->
                    return CrewLanJoinLaunchResult.Failed(
                        CrewLanJoinLaunchFailure.SignalingFailed(signaling.reason),
                    )
            }

        var engine: CrewSessionEngine? = null
        var mediaRuntime: CrewActiveMediaRuntime? = null
        var webRtc: CrewWebRtcRuntime? = null
        var coordinator: CrewJoinCoordinator? = null
        val sessionId = signalPeer.sessionId

        suspend fun fail(reason: CrewLanJoinLaunchFailure): CrewLanJoinLaunchResult.Failed {
            runCatching { coordinator?.close() }
            runCatching { engine?.close() }
            runCatching { signalPeer.close() }
            runCatching { mediaRuntime?.close() }
            runCatching { webRtc?.close() }
            runCatching { checkpoints.clear(sessionId) }
            runCatching { leases.clear(sessionId) }
            return CrewLanJoinLaunchResult.Failed(reason)
        }

        webRtc = runCatching { CrewWebRtcRuntime(context.applicationContext) }.getOrElse {
            return fail(CrewLanJoinLaunchFailure.Initialization)
        }
        val activeWebRtc = checkNotNull(webRtc)
        coordinator =
            runCatching {
                    CrewJoinCoordinator(
                        sessionId = sessionId,
                        localMemberId = localMemberId,
                        signalingPeer = signalPeer,
                        initiators =
                            CrewDirectInitiatorHandleFactory { peer ->
                                CrewDirectPeerConnectionHandle(
                                    CrewDirectPeerConnection(
                                        peerFactory = activeWebRtc,
                                        signalingPeer = peer,
                                        invite = invite,
                                        localMemberId = localMemberId,
                                        role = CrewDirectPeerRole.INITIATOR,
                                        iceServers = emptyList(),
                                    ),
                                )
                            },
                        sessions =
                            CrewJoinSessionFactory { snapshot ->
                                check(mediaRuntime == null) {
                                    "A LAN join may create only one media runtime"
                                }
                                check(engine == null) {
                                    "A LAN join may create only one session engine"
                                }
                                mediaRuntime =
                                    mediaRuntimeFactory.create(
                                        sessionId = snapshot.sessionId,
                                        localMemberId = localMemberId,
                                        stateProvider = {
                                            checkNotNull(engine) {
                                                "Crew media state requested before joined engine assignment"
                                            }.state.value
                                        },
                                    )
                                val activeMediaRuntime = checkNotNull(mediaRuntime)
                                CrewSessionEngine(
                                    initialState = snapshot.toCrewState(),
                                    localMemberId = localMemberId,
                                    checkpointRepository = checkpoints,
                                    mediaLifecycle = activeMediaRuntime,
                                )
                                    .also { engine = it }
                                    .let(::CrewSessionEngineJoinPort)
                            },
                    )
                }
                .getOrElse { return fail(CrewLanJoinLaunchFailure.Initialization) }
        val activeCoordinator = checkNotNull(coordinator)
        runCatching { activeCoordinator.start() }.getOrElse {
            return fail(CrewLanJoinLaunchFailure.Initialization)
        }
        when (val joinState = activeCoordinator.state.first { it is CrewJoinState.Active || it is CrewJoinState.Rejected }) {
            is CrewJoinState.Rejected -> return fail(CrewLanJoinLaunchFailure.JoinRejected(joinState.reason))
            is CrewJoinState.Active -> {
                val activeEngine = engine ?: return fail(CrewLanJoinLaunchFailure.EngineOrPersistence)
                val activeMediaRuntime = mediaRuntime ?: return fail(CrewLanJoinLaunchFailure.EngineOrPersistence)
                val activeSessionId = joinState.snapshot.sessionId
                if (runCatching { leases.save(CrewRejoinLease(activeSessionId, localMemberId, invite)) }.isFailure) {
                    return fail(CrewLanJoinLaunchFailure.EngineOrPersistence)
                }
                return CrewLanJoinLaunchResult.Started(
                    CrewLanJoinedSession(
                        sessionId = activeSessionId,
                        localMemberId = localMemberId,
                        engine = activeEngine,
                        joinState = activeCoordinator.state,
                        coordinator = activeCoordinator,
                        mediaRuntime = activeMediaRuntime,
                        webRtcRuntime = activeWebRtc,
                        checkpoints = checkpoints,
                        leases = leases,
                    ),
                )
            }
            CrewJoinState.Connecting,
            CrewJoinState.Bootstrapping -> error("Join coordinator emitted a non-terminal join state")
        }
    }

    private suspend fun awaitRendezvous(
        discovery: CrewLanDiscoverySession,
        invite: CrewInvite,
    ): RendezvousAwaitResult =
        withTimeoutOrNull(discoveryTimeoutMs) {
            discovery.state.combine(discovery.matches) { state, matches ->
                when (state) {
                    is CrewLanOperationState.Failed -> RendezvousAwaitResult.Failed(state.operation, state.platformCode)
                    CrewLanOperationState.Closed -> RendezvousAwaitResult.Closed
                    CrewLanOperationState.Starting,
                    CrewLanOperationState.Active ->
                        matches
                            .firstOrNull { rendezvous ->
                                rendezvous.identity.protocolVersion == invite.protocolVersion &&
                                    rendezvous.identity.sessionLocator == invite.sessionLocator &&
                                    rendezvous.identity.inviteId == invite.inviteId
                            }
                            ?.let(RendezvousAwaitResult::Found)
                            ?: RendezvousAwaitResult.Waiting
                }
            }.first { it !is RendezvousAwaitResult.Waiting }
        } ?: RendezvousAwaitResult.TimedOut

    private sealed interface RendezvousAwaitResult {
        data object Waiting : RendezvousAwaitResult

        data class Found(val rendezvous: CrewLanRendezvous) : RendezvousAwaitResult

        data class Failed(
            val operation: CrewLanFailureOperation,
            val platformCode: Int?,
        ) : RendezvousAwaitResult

        data object Closed : RendezvousAwaitResult

        data object TimedOut : RendezvousAwaitResult

        fun toFailure(): CrewLanJoinLaunchFailure =
            when (this) {
                is Failed -> CrewLanJoinLaunchFailure.DiscoveryFailed(operation, platformCode)
                Closed -> CrewLanJoinLaunchFailure.DiscoveryClosed
                TimedOut -> CrewLanJoinLaunchFailure.DiscoveryTimedOut
                Waiting,
                is Found -> error("A non-terminal discovery result cannot be a launch failure")
            }
    }
}
