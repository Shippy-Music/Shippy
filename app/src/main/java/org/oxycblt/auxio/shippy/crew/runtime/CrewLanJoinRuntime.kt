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
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.oxycblt.auxio.shippy.crew.connection.CrewDirectPeerConnection
import org.oxycblt.auxio.shippy.crew.connection.CrewDirectPeerState
import org.oxycblt.auxio.shippy.crew.connection.CrewDirectPeerRole
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.DurableEventId
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewPlaybackMode
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.core.toCrewState
import org.oxycblt.auxio.shippy.crew.invite.CrewInvite
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteCodec
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteDecodeResult
import org.oxycblt.auxio.shippy.crew.rejoin.CrewRejoinInviteFactory
import org.oxycblt.auxio.shippy.crew.lan.CrewLanDiscovery
import org.oxycblt.auxio.shippy.crew.lan.CrewLanDiscoverySession
import org.oxycblt.auxio.shippy.crew.lan.CrewLanFailureOperation
import org.oxycblt.auxio.shippy.crew.lan.CrewLanOperationState
import org.oxycblt.auxio.shippy.crew.lan.CrewLanRendezvous
import org.oxycblt.auxio.shippy.crew.lan.CrewLanSignalConnectFailure
import org.oxycblt.auxio.shippy.crew.lan.CrewLanSignalConnectResult
import org.oxycblt.auxio.shippy.crew.lan.CrewLanSignalingClient
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalPeer
import org.oxycblt.auxio.shippy.crew.media.CrewActiveMediaRuntime
import org.oxycblt.auxio.shippy.crew.media.CrewActiveMediaRuntimeFactory
import org.oxycblt.auxio.shippy.crew.relay.CrewHostedRelayJoin
import org.oxycblt.auxio.shippy.crew.relay.CrewHostedRelayJoinResult
import org.oxycblt.auxio.shippy.crew.relay.CrewRelayHttpClient
import org.oxycblt.auxio.shippy.crew.relay.CrewRelayIceServerProvider
import org.oxycblt.auxio.shippy.crew.session.CrewActionRequest
import org.oxycblt.auxio.shippy.crew.session.CrewRejoinCredentialReceiver
import org.oxycblt.auxio.shippy.crew.session.CrewSessionEngine
import org.oxycblt.auxio.shippy.crew.session.CrewSubmitResult
import org.oxycblt.auxio.shippy.crew.session.CrewReactionSendResult
import org.oxycblt.auxio.shippy.crew.reaction.ActiveCrewReaction
import org.oxycblt.auxio.shippy.crew.settings.CrewProfileSettings
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewWebRtcRuntime
import org.oxycblt.auxio.shippy.persistence.crew.CrewCheckpointRepository
import org.oxycblt.auxio.shippy.persistence.crew.CrewRejoinLeaseStore
import okhttp3.OkHttpClient

private const val CREW_JOIN_PROTOCOL_V1 = 1
private const val CREW_LAN_DISCOVERY_TIMEOUT_MS = 10_000L
private const val CREW_RECONNECT_DIAL_TIMEOUT_MS = 15_000L
private const val CREW_JOIN_TERMINAL_EVENT_WAIT_MS = 1_500L

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

    data object RemoteSignalingFailed : CrewLanJoinLaunchFailure

    data class JoinRejected(val reason: CrewJoinFailure) : CrewLanJoinLaunchFailure

    data object Initialization : CrewLanJoinLaunchFailure

    data object EngineOrPersistence : CrewLanJoinLaunchFailure
}

/**
 * A live LAN join. [close] is recovery-safe and retains the exact persisted checkpoint and lease.
 * [leave] requests an ordered departure (or terminal end when this is the sole member) before
 * explicit local teardown.
 */
class CrewLanJoinedSession internal constructor(
    val sessionId: CrewSessionId,
    val localMemberId: CrewMemberId,
    val connectivity: CrewConnectivityPresentation,
    private val engine: CrewSessionEngine,
    val joinState: StateFlow<CrewJoinState>,
    private val coordinator: CrewJoinCoordinator,
    private val reconnectController: CrewJoinReconnectController,
    private val networkChangeMonitor: CrewNetworkChangeMonitor,
    private val mediaRuntime: CrewActiveMediaRuntime,
    private val webRtcRuntime: CrewWebRtcRuntime,
    private val checkpoints: CrewCheckpointRepository,
    private val leases: CrewRejoinLeaseStore,
) : AutoCloseable {
    private val lock = Any()
    private var released = false
    private var left = false

    val state: StateFlow<CrewState> = engine.state
    val reactions: SharedFlow<ActiveCrewReaction> = engine.reactions
    val reconnectState: StateFlow<CrewJoinReconnectState> = reconnectController.state
    val peerMediaBlocked: StateFlow<Boolean> = mediaRuntime.peerMediaBlocked
    val allowedReactions: List<String> = engine.allowedReactions

    override fun toString() = "CrewLanJoinedSession(identity=redacted, invite=redacted)"

    suspend fun submit(action: CrewAction): CrewSubmitResult =
        engine.submit(
            CrewActionRequest(
                id = DurableEventId(UUID.randomUUID().toString()),
                issuingMemberId = localMemberId,
                clientMonotonicTimestampMs = (System.nanoTime() / 1_000_000L).coerceAtLeast(0L),
                action = action,
            ),
        )

    suspend fun sendReaction(emoji: String): CrewReactionSendResult = engine.sendReaction(emoji)

    override fun close() {
        if (!markReleased()) return
        runCatching { networkChangeMonitor.close() }
        runCatching { reconnectController.close() }
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
        val before = state.value
        if (before.members.size == 1 && before.members.single().id == localMemberId) {
            runCatching { submit(CrewAction.SessionEnded) }
        } else {
            val now = (System.nanoTime() / 1_000_000L).coerceAtLeast(0L)
            runCatching {
                engine.gracefulLeave(
                    DurableEventId(UUID.randomUUID().toString()),
                    DurableEventId(UUID.randomUUID().toString()),
                    now,
                )
            }
        }
        withTimeoutOrNull(CREW_JOIN_TERMINAL_EVENT_WAIT_MS) {
            state.first { crew ->
                crew.playback.mode == CrewPlaybackMode.ENDED ||
                    crew.members.none { it.id == localMemberId }
            }
        }
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
    @CrewRelayHttpClient private val relayClient: OkHttpClient,
    private val relayIceServerProvider: CrewRelayIceServerProvider,
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
        val selected =
            if (invite.relayLocator == null) {
                val discovery =
                    runCatching { lanDiscovery.discover(invite) }.getOrElse {
                        return CrewLanJoinLaunchResult.Failed(
                            CrewLanJoinLaunchFailure.Initialization
                        )
                    }
                val rendezvous = awaitRendezvous(discovery, invite)
                if (rendezvous !is RendezvousAwaitResult.Found) {
                    runCatching { discovery.close() }
                    return CrewLanJoinLaunchResult.Failed(rendezvous.toFailure())
                }
                val signaling =
                    try {
                        CrewLanSignalingClient.connect(
                            rendezvous.rendezvous,
                            invite,
                            localMemberId,
                            localMember.displayName,
                            nowEpochMs(),
                        )
                    } finally {
                        runCatching { discovery.close() }
                    }
                when (signaling) {
                    is CrewLanSignalConnectResult.Connected ->
                        SignalingSelection(signaling.peer, CrewConnectivityPresentation.NEARBY)
                    is CrewLanSignalConnectResult.Failed ->
                        return CrewLanJoinLaunchResult.Failed(
                            CrewLanJoinLaunchFailure.SignalingFailed(signaling.reason),
                        )
                }
            } else {
                val discovery = runCatching { lanDiscovery.discover(invite) }.getOrNull()
                selectSignalingPeer(discovery, invite, localMemberId, localMember.displayName)
                    ?: return CrewLanJoinLaunchResult.Failed(CrewLanJoinLaunchFailure.RemoteSignalingFailed)
            }
        val signalPeer = selected.peer

        var engine: CrewSessionEngine? = null
        var mediaRuntime: CrewActiveMediaRuntime? = null
        var webRtc: CrewWebRtcRuntime? = null
        var coordinator: CrewJoinCoordinator? = null
        var reconnectController: CrewJoinReconnectController? = null
        var networkChangeMonitor: CrewNetworkChangeMonitor? = null
        val sessionId = signalPeer.sessionId

        suspend fun fail(reason: CrewLanJoinLaunchFailure): CrewLanJoinLaunchResult.Failed {
            runCatching { networkChangeMonitor?.close() }
            runCatching { reconnectController?.close() }
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
        val iceServers = relayIceServerProvider.resolve(invite)
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
                                        iceServers = iceServers,
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
                                    rejoinCredentialReceiver =
                                        CrewRejoinCredentialReceiver { lease ->
                                            // The engine has already bound sender, session, protocol,
                                            // local member, and time window before this Keystore write.
                                            leases.save(lease)
                                        },
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
                if (runCatching { activeMediaRuntime.bind(activeEngine.state, activeEngine.availability, activeEngine::publishLocalAvailability) }.isFailure) {
                    return fail(CrewLanJoinLaunchFailure.EngineOrPersistence)
                }
                // Active-session credential issuance is intentionally not wired until the
                // coordinator can authenticate and verify the v2 credential registry. Do not
                // persist the public invite here: it is not a rejoin credential.
                val activeReconnectController =
                    CrewJoinReconnectController(
                        localMemberId = localMemberId,
                        session = CrewSessionEngineReconnectPort(activeEngine),
                        dialer =
                            CrewJoinedSessionReconnectDialer { expectedCoordinator ->
                                val reconnectInvite =
                                    reconnectInvite(
                                        publicInvite = invite,
                                        sessionId = activeSessionId,
                                        localMemberId = localMemberId,
                                    )
                                if (reconnectInvite == null) {
                                    CrewReconnectDialResult.Expired
                                } else {
                                    dialReconnect(
                                        invite = reconnectInvite,
                                        localMember = localMember,
                                        expectedCoordinator = expectedCoordinator,
                                        webRtcRuntime = activeWebRtc,
                                    )
                                }
                            },
                    )
                        .also {
                            reconnectController = it
                            it.start()
                        }
                val activeNetworkChangeMonitor =
                    CrewNetworkChangeMonitor(
                        context.applicationContext,
                        activeReconnectController::wake,
                    )
                        .also { networkChangeMonitor = it }
                return CrewLanJoinLaunchResult.Started(
                    CrewLanJoinedSession(
                        sessionId = activeSessionId,
                        localMemberId = localMemberId,
                        connectivity = selected.connectivity,
                        engine = activeEngine,
                        joinState = activeCoordinator.state,
                        coordinator = activeCoordinator,
                        reconnectController = activeReconnectController,
                        networkChangeMonitor = activeNetworkChangeMonitor,
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

    private suspend fun dialReconnect(
        invite: CrewInvite,
        localMember: CrewMember,
        expectedCoordinator: CrewMemberId,
        webRtcRuntime: CrewWebRtcRuntime,
    ): CrewReconnectDialResult {
        if (nowEpochMs() >= invite.expiresAtEpochMs) {
            return CrewReconnectDialResult.Expired
        }
        val selection =
            connectSignalingForReconnect(invite, localMember)
                ?: return CrewReconnectDialResult.Retryable
        val signalingPeer = selection.peer
        if (signalingPeer.remoteMemberClaim != expectedCoordinator) {
            signalingPeer.close()
            return CrewReconnectDialResult.Retryable
        }
        val handle =
            runCatching {
                    val iceServers = relayIceServerProvider.resolve(invite)
                    CrewDirectPeerConnectionHandle(
                        CrewDirectPeerConnection(
                            peerFactory = webRtcRuntime,
                            signalingPeer = signalingPeer,
                            invite = invite,
                            localMemberId = localMember.id,
                            role = CrewDirectPeerRole.INITIATOR,
                            iceServers = iceServers,
                        )
                    )
                }
                .getOrElse {
                    if (it is CancellationException) throw it
                    signalingPeer.close()
                    return CrewReconnectDialResult.Retryable
                }
        if (runCatching { handle.start() }.isFailure) {
            handle.close()
            return CrewReconnectDialResult.Retryable
        }
        val terminal =
            withTimeoutOrNull(CREW_RECONNECT_DIAL_TIMEOUT_MS) {
                handle.state.first {
                    it is CrewDirectPeerState.Connected ||
                        it is CrewDirectPeerState.Failed ||
                        it == CrewDirectPeerState.Closed
                }
            }
        return when (terminal) {
            is CrewDirectPeerState.Connected ->
                if (terminal.transport.remoteMemberId == expectedCoordinator) {
                    CrewReconnectDialResult.Authenticated(handle, terminal.transport)
                } else {
                    handle.close()
                    CrewReconnectDialResult.Retryable
                }
            is CrewDirectPeerState.Failed,
            CrewDirectPeerState.Closed,
            null -> {
                handle.close()
                CrewReconnectDialResult.Retryable
            }
            CrewDirectPeerState.New,
            CrewDirectPeerState.Negotiating,
            CrewDirectPeerState.Authenticating ->
                error("Reconnect wait returned a non-terminal direct state")
        }
    }

    /**
     * Prefer a valid, member-bound lease once it has been issued over authenticated CONTROL.
     * Initial QR joins still use the public invite and remain expiry-rejected. A network error
     * never clears a lease; only an expired or mismatched lease is removed.
     */
    private suspend fun reconnectInvite(
        publicInvite: CrewInvite,
        sessionId: CrewSessionId,
        localMemberId: CrewMemberId,
    ): CrewInvite? {
        val lease = leases.load()
        if (lease != null) {
            val matches =
                lease.sessionId == sessionId && lease.memberId == localMemberId &&
                    lease.protocolVersion == publicInvite.protocolVersion &&
                    lease.sessionLocator == publicInvite.sessionLocator &&
                    lease.rendezvousInviteId == publicInvite.inviteId
            if (!matches || nowEpochMs() >= lease.expiresAtEpochMs) {
                if (lease.sessionId == sessionId) runCatching { leases.clear(sessionId) }
            } else {
                return CrewRejoinInviteFactory.fromLease(lease)
            }
        }
        return publicInvite.takeIf { nowEpochMs() < it.expiresAtEpochMs }
    }

    private suspend fun connectSignalingForReconnect(
        invite: CrewInvite,
        localMember: CrewMember,
    ): SignalingSelection? {
        if (invite.relayLocator != null) {
            val discovery = runCatching { lanDiscovery.discover(invite) }.getOrNull()
            return selectSignalingPeer(
                discovery,
                invite,
                localMember.id,
                localMember.displayName,
            )
        }
        val discovery = runCatching { lanDiscovery.discover(invite) }.getOrNull() ?: return null
        return try {
            val rendezvous =
                awaitRendezvous(discovery, invite) as? RendezvousAwaitResult.Found
                    ?: return null
            when (
                val signaling =
                    CrewLanSignalingClient.connect(
                        rendezvous.rendezvous,
                        invite,
                        localMember.id,
                        localMember.displayName,
                        nowEpochMs(),
                    )
            ) {
                is CrewLanSignalConnectResult.Connected ->
                    SignalingSelection(
                        signaling.peer,
                        CrewConnectivityPresentation.NEARBY,
                    )
                is CrewLanSignalConnectResult.Failed -> null
            }
        } finally {
            runCatching { discovery.close() }
        }
    }

    private suspend fun selectSignalingPeer(
        discovery: CrewLanDiscoverySession?,
        invite: CrewInvite,
        localMemberId: CrewMemberId,
        localDisplayName: String,
    ): SignalingSelection? {
        return supervisorScope {
            val outcomes = Channel<SignalingSelection?>(2)
            suspend fun publish(selection: SignalingSelection?) {
                try {
                    outcomes.send(selection)
                } catch (error: CancellationException) {
                    selection?.peer?.close()
                    throw error
                }
            }
            val lan =
                discovery?.let { activeDiscovery ->
                    launch {
                        val selection =
                            try {
                                val rendezvous =
                                    awaitRendezvous(activeDiscovery, invite)
                                        as? RendezvousAwaitResult.Found
                                val result =
                                    rendezvous?.let {
                                        CrewLanSignalingClient.connect(
                                            it.rendezvous,
                                            invite,
                                            localMemberId,
                                            localDisplayName,
                                            nowEpochMs(),
                                        )
                                    }
                                (result as? CrewLanSignalConnectResult.Connected)?.peer?.let {
                                    SignalingSelection(
                                        it,
                                        CrewConnectivityPresentation.NEARBY_AND_REMOTE,
                                    )
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                null
                            } finally {
                                runCatching { activeDiscovery.close() }
                            }
                        publish(selection)
                    }
                }
            val relay =
                launch {
                    val selection =
                        try {
                            val result =
                                CrewHostedRelayJoin.connect(
                                    relayClient,
                                    invite,
                                    localMemberId,
                                    localDisplayName,
                                    nowEpochMs,
                                )
                            (result as? CrewHostedRelayJoinResult.Connected)?.peer?.let {
                                SignalingSelection(
                                    it,
                                    CrewConnectivityPresentation.NEARBY_AND_REMOTE,
                                )
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                    publish(selection)
                }
            var winner: SignalingSelection? = null
            var received = 0
            val expectedOutcomes = if (lan == null) 1 else 2
            while (received < expectedOutcomes && winner == null) {
                val candidate = outcomes.receive()
                received++
                if (candidate != null) winner = candidate
            }
            lan?.cancel()
            relay.cancel()
            lan?.join()
            relay.join()
            while (true) {
                val simultaneous = outcomes.tryReceive().getOrNull() ?: break
                simultaneous?.peer?.close()
            }
            outcomes.close()
            winner
        }
    }

    private data class SignalingSelection(
        val peer: CrewSignalPeer,
        val connectivity: CrewConnectivityPresentation,
    )

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
