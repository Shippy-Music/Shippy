/*
 * Copyright (c) 2026 Shippy contributors
 * CrewLanHostRuntime.kt is part of Shippy.
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.oxycblt.auxio.shippy.crew.connection.CrewDirectPeerConnection
import org.oxycblt.auxio.shippy.crew.connection.CrewDirectPeerRole
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.DurableEventId
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.lan.CrewLanAdvertisement
import org.oxycblt.auxio.shippy.crew.lan.CrewLanDiscovery
import org.oxycblt.auxio.shippy.crew.lan.CrewLanFailureOperation
import org.oxycblt.auxio.shippy.crew.lan.CrewLanOperationState
import org.oxycblt.auxio.shippy.crew.lan.CrewLanSignalingHost
import org.oxycblt.auxio.shippy.crew.media.CrewActiveMediaRuntime
import org.oxycblt.auxio.shippy.crew.media.CrewActiveMediaRuntimeFactory
import org.oxycblt.auxio.shippy.crew.relay.CrewHostedRelayHost
import org.oxycblt.auxio.shippy.crew.relay.CrewHostedRelayRegistrationState
import org.oxycblt.auxio.shippy.crew.relay.CrewRelayHttpClient
import org.oxycblt.auxio.shippy.crew.rejoin.CrewRejoinLease
import org.oxycblt.auxio.shippy.crew.session.CrewActionRequest
import org.oxycblt.auxio.shippy.crew.session.CrewSessionEngine
import org.oxycblt.auxio.shippy.crew.session.CrewSubmitResult
import org.oxycblt.auxio.shippy.crew.session.CrewReactionSendResult
import org.oxycblt.auxio.shippy.crew.reaction.ActiveCrewReaction
import org.oxycblt.auxio.shippy.crew.settings.CrewProfileSettings
import org.oxycblt.auxio.shippy.crew.settings.CrewSettings
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewWebRtcRuntime
import org.oxycblt.auxio.shippy.persistence.crew.CrewCheckpointRepository
import org.oxycblt.auxio.shippy.persistence.crew.CrewRejoinLeaseStore
import okhttp3.OkHttpClient

private const val CREW_PROTOCOL_V1 = 1
private const val DEFAULT_ADVERTISEMENT_TIMEOUT_MS = 10_000L

enum class CrewConnectivityPresentation {
    NEARBY,
    NEARBY_AND_REMOTE,
    NEARBY_RELAY_UNAVAILABLE,
}

sealed interface CrewLanHostLaunchResult {
    data class Started(val session: CrewLanHostSession) : CrewLanHostLaunchResult

    data class Failed(val reason: CrewLanHostLaunchFailure) : CrewLanHostLaunchResult
}

sealed interface CrewLanHostLaunchFailure {
    data object Initialization : CrewLanHostLaunchFailure

    data object EngineOrPersistence : CrewLanHostLaunchFailure

    data class AdvertisementFailed(
        val operation: CrewLanFailureOperation,
        val platformCode: Int?,
    ) : CrewLanHostLaunchFailure

    data object AdvertisementClosed : CrewLanHostLaunchFailure

    data object AdvertisementTimedOut : CrewLanHostLaunchFailure
}

/**
 * A single LAN host session. Closing a live session is intentionally recovery-safe: the exact
 * checkpoint and rejoin lease stay available for a future process-recovery runtime. [end] is the
 * explicit host-session end operation for the current coordinator-centred topology; it does not
 * claim a graceful coordinator handoff.
 */
class CrewLanHostSession internal constructor(
    val sessionId: CrewSessionId,
    val localMemberId: CrewMemberId,
    val inviteLink: String,
    val connectivity: CrewConnectivityPresentation,
    private val engine: CrewSessionEngine,
    val admissionStates: StateFlow<Map<CrewMemberId, CrewHostAdmissionState>>,
    val advertisementState: StateFlow<CrewLanOperationState>,
    private val peerCollectorScope: CoroutineScope,
    private val peerCollectors: List<Job>,
    private val admissionCoordinator: CrewHostAdmissionCoordinator,
    private val advertisement: CrewLanAdvertisement,
    private val signalingHost: CrewLanSignalingHost,
    private val relayHost: CrewHostedRelayHost?,
    private val mediaRuntime: CrewActiveMediaRuntime,
    private val webRtcRuntime: CrewWebRtcRuntime,
    private val checkpoints: CrewCheckpointRepository,
    private val leases: CrewRejoinLeaseStore,
) : AutoCloseable {
    private val lock = Any()
    private var released = false
    private var ended = false

    val state: StateFlow<CrewState> = engine.state
    val reactions: SharedFlow<ActiveCrewReaction> = engine.reactions
    val peerMediaBlocked: StateFlow<Boolean> = mediaRuntime.peerMediaBlocked
    val allowedReactions: List<String> = engine.allowedReactions

    override fun toString() = "CrewLanHostSession(identity=redacted, inviteLink=redacted)"

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

    /** Releases live network/runtime resources but retains recovery persistence. */
    override fun close() {
        if (!markReleased()) return
        releaseAll()
    }

    /** Releases this session and removes only its exact checkpoint and credential lease. */
    suspend fun end() {
        val shouldEnd = synchronized(lock) {
            if (ended) false else {
                ended = true
                true
            }
        }
        if (!shouldEnd) return
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

    private fun releaseAll() {
        // Each boundary is isolated: a broken Android/RTC close must not retain later resources.
        peerCollectors.forEach { collector -> runCatching { collector.cancel() } }
        runCatching { peerCollectorScope.cancel() }
        runCatching { admissionCoordinator.close() }
        runCatching { advertisement.close() }
        runCatching { signalingHost.close() }
        runCatching { relayHost?.close() }
        runCatching { engine.close() }
        runCatching { mediaRuntime.close() }
        runCatching { webRtcRuntime.close() }
    }
}

/**
 * Production composition root for independent LAN host attempts. It deliberately does not decide
 * whether another Crew is active; that exclusivity belongs to the future ActiveCrewRuntime.
 */
class CrewLanHostLauncher
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val lanDiscovery: CrewLanDiscovery,
    private val profileSettings: CrewProfileSettings,
    private val crewSettings: CrewSettings,
    @CrewRelayHttpClient private val relayClient: OkHttpClient,
    private val checkpoints: CrewCheckpointRepository,
    private val leases: CrewRejoinLeaseStore,
    private val mediaRuntimeFactory: CrewActiveMediaRuntimeFactory,
) {
    private val advertisementTimeoutMs = DEFAULT_ADVERTISEMENT_TIMEOUT_MS
    private val nowEpochMs: () -> Long = System::currentTimeMillis

    suspend fun start(): CrewLanHostLaunchResult {
        if (advertisementTimeoutMs <= 0L) {
            return CrewLanHostLaunchResult.Failed(CrewLanHostLaunchFailure.AdvertisementTimedOut)
        }

        val protocol = ProtocolVersion(CREW_PROTOCOL_V1)
        val localMemberId = runCatching { profileSettings.memberId(protocol) }.getOrElse {
            return CrewLanHostLaunchResult.Failed(CrewLanHostLaunchFailure.Initialization)
        }
        val localMember =
            runCatching { CrewMember(localMemberId, profileSettings.displayName) }.getOrElse {
                return CrewLanHostLaunchResult.Failed(CrewLanHostLaunchFailure.Initialization)
            }
        var bootstrap =
            runCatching { CrewHostBootstrapFactory().create(localMember, nowEpochMs(), crewSettings.relayLocator) }.getOrElse {
                return CrewLanHostLaunchResult.Failed(CrewLanHostLaunchFailure.Initialization)
            }

        val requestedRelay = bootstrap.invite.relayLocator != null
        var relayHost: CrewHostedRelayHost? = null
        if (bootstrap.invite.relayLocator != null) {
            val candidate =
                runCatching {
                    CrewHostedRelayHost(
                        relayClient,
                        bootstrap.invite,
                        bootstrap.initialState.sessionId,
                        localMemberId,
                        localMember.displayName,
                    )
                }.getOrNull()
            val registration =
                try {
                    candidate?.awaitRegistration()
                } catch (error: CancellationException) {
                    runCatching { candidate?.close() }
                    throw error
                } catch (_: Exception) {
                    runCatching { candidate?.close() }
                    null
                }
            if (registration is CrewHostedRelayRegistrationState.Registered) {
                relayHost = candidate
            } else {
                runCatching { candidate?.close() }
                bootstrap =
                    runCatching { CrewHostBootstrapFactory().create(localMember, nowEpochMs()) }.getOrElse {
                        return CrewLanHostLaunchResult.Failed(CrewLanHostLaunchFailure.Initialization)
                    }
            }
        }
        val connectivity =
            when {
                relayHost != null -> CrewConnectivityPresentation.NEARBY_AND_REMOTE
                requestedRelay -> CrewConnectivityPresentation.NEARBY_RELAY_UNAVAILABLE
                else -> CrewConnectivityPresentation.NEARBY
            }

        var engine: CrewSessionEngine? = null
        var mediaRuntime: CrewActiveMediaRuntime? = null
        var webRtc: CrewWebRtcRuntime? = null
        var signaling: CrewLanSignalingHost? = null
        var admission: CrewHostAdmissionCoordinator? = null
        var advertisement: CrewLanAdvertisement? = null
        var collectorScope: CoroutineScope? = null
        var collector: Job? = null
        var relayCollector: Job? = null
        val sessionId = bootstrap.initialState.sessionId

        suspend fun fail(reason: CrewLanHostLaunchFailure): CrewLanHostLaunchResult.Failed {
            runCatching { collector?.cancel() }
            runCatching { relayCollector?.cancel() }
            runCatching { collectorScope?.cancel() }
            runCatching { admission?.close() }
            runCatching { advertisement?.close() }
            runCatching { signaling?.close() }
            runCatching { relayHost?.close() }
            runCatching { engine?.close() }
            runCatching { mediaRuntime?.close() }
            runCatching { webRtc?.close() }
            runCatching { checkpoints.clear(sessionId) }
            runCatching { leases.clear(sessionId) }
            return CrewLanHostLaunchResult.Failed(reason)
        }

        mediaRuntime =
            try {
                mediaRuntimeFactory.create(
                    sessionId = sessionId,
                    localMemberId = localMemberId,
                    stateProvider = {
                        checkNotNull(engine) {
                            "Crew media state requested before host engine assignment"
                        }.state.value
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return fail(CrewLanHostLaunchFailure.EngineOrPersistence)
            }
        val activeMediaRuntime = checkNotNull(mediaRuntime)
        engine =
            runCatching {
                    CrewSessionEngine(
                        initialState = bootstrap.initialState,
                        localMemberId = localMemberId,
                        checkpointRepository = checkpoints,
                        mediaLifecycle = activeMediaRuntime,
                    )
                }
                .getOrElse { return fail(CrewLanHostLaunchFailure.EngineOrPersistence) }
        val activeEngine = checkNotNull(engine)
        if (runCatching { activeEngine.start() }.isFailure) {
            return fail(CrewLanHostLaunchFailure.EngineOrPersistence)
        }
        if (runCatching { activeMediaRuntime.bind(activeEngine.state) }.isFailure) {
            return fail(CrewLanHostLaunchFailure.EngineOrPersistence)
        }

        webRtc = runCatching { CrewWebRtcRuntime(context.applicationContext) }
            .getOrElse { return fail(CrewLanHostLaunchFailure.Initialization) }
        val activeWebRtc = checkNotNull(webRtc)
        signaling =
            runCatching {
                    CrewLanSignalingHost(
                        invite = bootstrap.invite,
                        sessionId = sessionId,
                        localMemberId = localMemberId,
                        localDisplayName = localMember.displayName,
                        nowEpochMs = nowEpochMs(),
                    )
                }
                .getOrElse { return fail(CrewLanHostLaunchFailure.Initialization) }
        val activeSignaling = checkNotNull(signaling)
        admission =
            runCatching {
                    CrewHostAdmissionCoordinator(
                        sessionId = sessionId,
                        localMemberId = localMemberId,
                        session = CrewSessionEngineAdmissionPort(activeEngine),
                        responders =
                            CrewDirectResponderHandleFactory { peer ->
                                CrewDirectPeerConnectionHandle(
                                    CrewDirectPeerConnection(
                                        peerFactory = activeWebRtc,
                                        signalingPeer = peer,
                                        invite = bootstrap.invite,
                                        localMemberId = localMemberId,
                                        role = CrewDirectPeerRole.RESPONDER,
                                        iceServers = DEFAULT_CREW_REMOTE_ICE_SERVERS,
                                    ),
                                )
                            },
                    )
                }
                .getOrElse { return fail(CrewLanHostLaunchFailure.Initialization) }
        val activeAdmission = checkNotNull(admission)
        collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val activeCollectorScope = checkNotNull(collectorScope)
        collector = activeCollectorScope.launch { activeSignaling.peers.collect(activeAdmission::accept) }
        val activeCollector = checkNotNull(collector)
        relayCollector = relayHost?.let { hosted ->
            activeCollectorScope.launch { hosted.peers.collect(activeAdmission::accept) }
        }
        advertisement =
            runCatching { lanDiscovery.advertise(bootstrap.invite, activeSignaling.port) }
                .getOrElse { return fail(CrewLanHostLaunchFailure.Initialization) }
        val activeAdvertisement = checkNotNull(advertisement)

        when (val state = awaitAdvertisement(activeAdvertisement)) {
            CrewLanOperationState.Active -> Unit
            is CrewLanOperationState.Failed ->
                return fail(CrewLanHostLaunchFailure.AdvertisementFailed(state.operation, state.platformCode))
            CrewLanOperationState.Closed -> return fail(CrewLanHostLaunchFailure.AdvertisementClosed)
            null,
            CrewLanOperationState.Starting ->
                return fail(CrewLanHostLaunchFailure.AdvertisementTimedOut)
        }
        if (runCatching { leases.save(CrewRejoinLease(sessionId, localMemberId, bootstrap.invite)) }.isFailure) {
            return fail(CrewLanHostLaunchFailure.EngineOrPersistence)
        }

        return CrewLanHostLaunchResult.Started(
            CrewLanHostSession(
                sessionId = sessionId,
                localMemberId = localMemberId,
                inviteLink = bootstrap.inviteLink,
                connectivity = connectivity,
                engine = activeEngine,
                admissionStates = activeAdmission.states,
                advertisementState = activeAdvertisement.state,
                peerCollectorScope = activeCollectorScope,
                peerCollectors = listOfNotNull(activeCollector, relayCollector),
                admissionCoordinator = activeAdmission,
                advertisement = activeAdvertisement,
                signalingHost = activeSignaling,
                relayHost = relayHost,
                mediaRuntime = activeMediaRuntime,
                webRtcRuntime = activeWebRtc,
                checkpoints = checkpoints,
                leases = leases,
            ),
        )
    }

    private suspend fun awaitAdvertisement(advertisement: CrewLanAdvertisement): CrewLanOperationState? =
        withTimeoutOrNull(advertisementTimeoutMs) {
            advertisement.state.first { it !is CrewLanOperationState.Starting }
        }
}
