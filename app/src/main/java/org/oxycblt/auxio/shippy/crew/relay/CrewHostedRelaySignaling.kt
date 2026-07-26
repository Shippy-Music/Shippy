/*
 * Copyright (c) 2026 Shippy contributors
 * CrewHostedRelaySignaling.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.relay

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.invite.CrewInvite
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalConnectionState
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalPeer
import org.oxycblt.auxio.shippy.crew.lan.CrewSignalSendResult
import org.oxycblt.auxio.shippy.crew.signaling.CrewSignalDecodeResult
import org.oxycblt.auxio.shippy.crew.signaling.CrewSignalMessage
import org.oxycblt.auxio.shippy.crew.signaling.CrewSignalMessageCodec

private const val RELAY_MAX_ROUTES = 16
private const val RELAY_HANDSHAKE_TIMEOUT_MS = 8_000L
private const val RELAY_INCOMING_CAPACITY = 64
private const val RELAY_MAX_QUEUED_BYTES = 1024L * 1024L

enum class CrewHostedRelayFailure {
    INVITATION_INACTIVE,
    CONNECTION,
    REGISTRATION,
    AUTHENTICATION,
    PROTOCOL,
}

sealed interface CrewHostedRelayJoinResult {
    data class Connected(val peer: CrewSignalPeer) : CrewHostedRelayJoinResult

    data class Failed(val reason: CrewHostedRelayFailure) : CrewHostedRelayJoinResult
}

sealed interface CrewHostedRelayRegistrationState {
    data object Connecting : CrewHostedRelayRegistrationState

    data object Registered : CrewHostedRelayRegistrationState

    data class Failed(val reason: CrewHostedRelayFailure) : CrewHostedRelayRegistrationState

    data object Closed : CrewHostedRelayRegistrationState
}

/** One host WebSocket multiplexes bounded opaque routes and exposes readiness before advertisement. */
class CrewHostedRelayHost(
    private val client: OkHttpClient,
    private val invite: CrewInvite,
    private val sessionId: CrewSessionId,
    private val localMemberId: CrewMemberId,
    private val localDisplayName: String,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val maxRoutes: Int = RELAY_MAX_ROUTES,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val routes = ConcurrentHashMap<CrewRelayRouteId, PendingRoute>()
    private val peersChannel = Channel<CrewSignalPeer>(maxRoutes)
    private val registrationResult = CompletableDeferred<CrewHostedRelayRegistrationState>()
    private val mutableRegistration = MutableStateFlow<CrewHostedRelayRegistrationState>(CrewHostedRelayRegistrationState.Connecting)
    @Volatile private var socket: WebSocket? = null

    val peers: Flow<CrewSignalPeer> = peersChannel.receiveAsFlow()
    val registration: StateFlow<CrewHostedRelayRegistrationState> = mutableRegistration.asStateFlow()

    init {
        require(maxRoutes in 1..RELAY_MAX_ROUTES)
        require(sessionId.protocolVersion == invite.protocolVersion)
        require(localMemberId.protocolVersion == invite.protocolVersion)
        require(nowEpochMs() in invite.issuedAtEpochMs until invite.expiresAtEpochMs)
        val locator = checkNotNull(invite.relayLocator) { "Hosted relay requires a relay locator" }.value
        val opened = client.newWebSocket(Request.Builder().url(CrewRelayLocatorWebSocketUrl(locator)).build(), listener())
        socket = opened
        if (closed.get()) opened.cancel()
    }

    suspend fun awaitRegistration(timeoutMs: Long = RELAY_HANDSHAKE_TIMEOUT_MS): CrewHostedRelayRegistrationState =
        withTimeoutOrNull(timeoutMs) { registrationResult.await() }
            ?: CrewHostedRelayRegistrationState.Failed(CrewHostedRelayFailure.REGISTRATION).also {
                fail(CrewHostedRelayFailure.REGISTRATION)
            }

    private fun listener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!send(webSocket, registerFrame())) fail(CrewHostedRelayFailure.CONNECTION)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            runCatching { onFrame(CrewRelayCodec.decode(bytes.toByteArray())) }
                .onFailure { fail(CrewHostedRelayFailure.PROTOCOL) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            fail(CrewHostedRelayFailure.CONNECTION)
            webSocket.close(code, "")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            fail(CrewHostedRelayFailure.CONNECTION)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail(CrewHostedRelayFailure.CONNECTION)
        }
    }

    private fun registerFrame() =
        CrewRelayFrame.Register(
            CrewRelayRole.HOST,
            invite.protocolVersion.value,
            invite.sessionLocator.value.toByteArray(Charsets.UTF_8),
            invite.inviteId.value.toByteArray(Charsets.UTF_8),
        )

    private fun onFrame(frame: CrewRelayFrame) {
        when (frame) {
            is CrewRelayFrame.Registered -> {
                require(frame.role == CrewRelayRole.HOST && frame.routeId == null)
                completeRegistration(CrewHostedRelayRegistrationState.Registered)
            }
            is CrewRelayFrame.RouteOpen -> openRoute(frame.routeId)
            is CrewRelayFrame.Data ->
                runCatching { receive(frame) }
                    .onFailure { closeRoute(frame.routeId) }
            is CrewRelayFrame.RouteClose -> routes.remove(frame.routeId)?.peer?.closeFromRelay()
            is CrewRelayFrame.Error -> fail(CrewHostedRelayFailure.REGISTRATION)
            CrewRelayFrame.Ping -> send(CrewRelayFrame.Pong)
            else -> throw IllegalArgumentException("Unexpected relay frame")
        }
    }

    private fun openRoute(routeId: CrewRelayRouteId) {
        if (registration.value !is CrewHostedRelayRegistrationState.Registered || routes.size >= maxRoutes) {
            rejectRoute(routeId)
            return
        }
        val pending = PendingRoute(CrewRelayRouteCrypto(invite, routeId, CrewRelayRole.HOST))
        if (routes.putIfAbsent(routeId, pending) != null) {
            rejectRoute(routeId)
            return
        }
        if (!send(CrewRelayFrame.Data(routeId, pending.crypto.encrypt(localHello())))) {
            fail(CrewHostedRelayFailure.CONNECTION)
        }
    }

    private fun receive(frame: CrewRelayFrame.Data) {
        val pending =
            routes[frame.routeId] ?: run {
                rejectRoute(frame.routeId)
                return
            }
        val plain = pending.crypto.decrypt(frame.payload)
        val existing = pending.peer
        if (existing != null) {
            existing.receive(plain)
            return
        }
        val hello = CrewRelayHelloCodec.decode(plain)
        val peer =
            RelaySignalPeer(
                sessionId,
                CrewMemberId(hello.memberId, invite.protocolVersion),
                hello.displayName,
                pending.crypto,
                { encrypted -> send(CrewRelayFrame.Data(frame.routeId, encrypted)) },
                { closeRoute(frame.routeId) },
            )
        pending.peer = peer
        if (peersChannel.trySend(peer).isFailure) peer.close()
    }

    private fun localHello() = CrewRelayHelloCodec.encode(CrewRelayHello(localMemberId.value, localDisplayName))

    private fun rejectRoute(routeId: CrewRelayRouteId) {
        send(CrewRelayFrame.RouteClose(routeId))
    }

    private fun closeRoute(routeId: CrewRelayRouteId) {
        routes.remove(routeId)?.peer?.closeFromRelay()
        rejectRoute(routeId)
    }

    private fun send(frame: CrewRelayFrame): Boolean {
        val sent = socket?.let { send(it, frame) } == true
        if (!sent) fail(CrewHostedRelayFailure.CONNECTION)
        return sent
    }

    private fun send(target: WebSocket, frame: CrewRelayFrame): Boolean {
        if (closed.get() || target.queueSize() > RELAY_MAX_QUEUED_BYTES) return false
        return target.send(CrewRelayCodec.encode(frame).toByteString())
    }

    private fun completeRegistration(state: CrewHostedRelayRegistrationState) {
        mutableRegistration.value = state
        registrationResult.complete(state)
    }

    private fun fail(reason: CrewHostedRelayFailure) {
        if (!closed.compareAndSet(false, true)) return
        completeRegistration(CrewHostedRelayRegistrationState.Failed(reason))
        routes.values.forEach { it.peer?.closeFromRelay() }
        routes.clear()
        peersChannel.close()
        socket?.cancel()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        completeRegistration(CrewHostedRelayRegistrationState.Closed)
        routes.values.forEach { it.peer?.closeFromRelay() }
        routes.clear()
        peersChannel.close()
        socket?.close(1000, "")
        socket?.cancel()
    }

    private class PendingRoute(val crypto: CrewRelayRouteCrypto) {
        @Volatile var peer: RelaySignalPeer? = null
    }
}

object CrewHostedRelayJoin {
    suspend fun connect(
        client: OkHttpClient,
        invite: CrewInvite,
        localMemberId: CrewMemberId,
        localDisplayName: String,
        nowEpochMs: () -> Long = System::currentTimeMillis,
    ): CrewHostedRelayJoinResult {
        if (nowEpochMs() !in invite.issuedAtEpochMs until invite.expiresAtEpochMs || localMemberId.protocolVersion != invite.protocolVersion) {
            return CrewHostedRelayJoinResult.Failed(CrewHostedRelayFailure.INVITATION_INACTIVE)
        }
        val locator = invite.relayLocator?.value ?: return CrewHostedRelayJoinResult.Failed(CrewHostedRelayFailure.CONNECTION)
        val state = JoinState(invite, localMemberId, localDisplayName)
        val opened = client.newWebSocket(Request.Builder().url(CrewRelayLocatorWebSocketUrl(locator)).build(), state.listener())
        state.attach(opened)
        return try {
            withTimeoutOrNull(RELAY_HANDSHAKE_TIMEOUT_MS) { state.result.await() }
                ?: CrewHostedRelayJoinResult.Failed(CrewHostedRelayFailure.CONNECTION).also { state.close() }
        } catch (error: CancellationException) {
            state.close()
            throw error
        } finally {
            if (!state.result.isCompleted) state.close()
        }
    }

    private class JoinState(
        private val invite: CrewInvite,
        private val localMemberId: CrewMemberId,
        private val localDisplayName: String,
    ) {
        val result = CompletableDeferred<CrewHostedRelayJoinResult>()
        private val terminal = AtomicBoolean(false)
        @Volatile private var socket: WebSocket? = null
        private var route: CrewRelayRouteId? = null
        private var crypto: CrewRelayRouteCrypto? = null
        private var peer: RelaySignalPeer? = null

        fun attach(opened: WebSocket) {
            socket = opened
            if (terminal.get()) opened.cancel()
        }

        fun listener() = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!send(webSocket, registerFrame())) fail(CrewHostedRelayFailure.CONNECTION)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching { onFrame(CrewRelayCodec.decode(bytes.toByteArray())) }
                    .onFailure { fail(CrewHostedRelayFailure.PROTOCOL) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                fail(CrewHostedRelayFailure.CONNECTION)
                webSocket.close(code, "")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail(CrewHostedRelayFailure.CONNECTION)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = fail(CrewHostedRelayFailure.CONNECTION)
        }

        @Synchronized
        private fun onFrame(frame: CrewRelayFrame) {
            when (frame) {
                is CrewRelayFrame.Registered -> {
                    require(frame.role == CrewRelayRole.JOIN && frame.routeId != null && route == null)
                    route = frame.routeId
                    crypto = CrewRelayRouteCrypto(invite, frame.routeId, CrewRelayRole.JOIN)
                    require(send(CrewRelayFrame.Data(frame.routeId, crypto!!.encrypt(localHello()))))
                }
                is CrewRelayFrame.Data -> receive(frame)
                is CrewRelayFrame.Error,
                is CrewRelayFrame.RouteClose -> fail(CrewHostedRelayFailure.CONNECTION)
                CrewRelayFrame.Ping -> send(CrewRelayFrame.Pong)
                else -> throw IllegalArgumentException("Unexpected relay frame")
            }
        }

        private fun receive(frame: CrewRelayFrame.Data) {
            require(frame.routeId == route)
            val plain = checkNotNull(crypto).decrypt(frame.payload)
            val existing = peer
            if (existing != null) {
                existing.receive(plain)
                return
            }
            val hello = CrewRelayHelloCodec.decode(plain)
            val created =
                RelaySignalPeer(
                    CrewSessionId(invite.sessionLocator.value, invite.protocolVersion),
                    CrewMemberId(hello.memberId, invite.protocolVersion),
                    hello.displayName,
                    checkNotNull(crypto),
                    { encrypted -> send(CrewRelayFrame.Data(frame.routeId, encrypted)) },
                    ::close,
                )
            peer = created
            result.complete(CrewHostedRelayJoinResult.Connected(created))
        }

        private fun registerFrame() =
            CrewRelayFrame.Register(
                CrewRelayRole.JOIN,
                invite.protocolVersion.value,
                invite.sessionLocator.value.toByteArray(Charsets.UTF_8),
                invite.inviteId.value.toByteArray(Charsets.UTF_8),
            )

        private fun localHello() = CrewRelayHelloCodec.encode(CrewRelayHello(localMemberId.value, localDisplayName))

        private fun send(frame: CrewRelayFrame): Boolean = socket?.let { send(it, frame) } == true

        private fun send(target: WebSocket, frame: CrewRelayFrame): Boolean {
            if (terminal.get() || target.queueSize() > RELAY_MAX_QUEUED_BYTES) return false
            return target.send(CrewRelayCodec.encode(frame).toByteString())
        }

        fun fail(reason: CrewHostedRelayFailure) {
            if (!terminal.compareAndSet(false, true)) return
            peer?.closeFromRelay()
            result.complete(CrewHostedRelayJoinResult.Failed(reason))
            socket?.cancel()
        }

        fun close() {
            if (!terminal.compareAndSet(false, true)) return
            peer?.closeFromRelay()
            route?.let { routeId ->
                socket?.let { target ->
                    if (target.queueSize() <= RELAY_MAX_QUEUED_BYTES) {
                        target.send(CrewRelayCodec.encode(CrewRelayFrame.RouteClose(routeId)).toByteString())
                    }
                }
            }
            socket?.close(1000, "")
            socket?.cancel()
        }
    }
}

private class RelaySignalPeer(
    override val sessionId: CrewSessionId,
    override val remoteMemberClaim: CrewMemberId,
    override val remoteDisplayName: String,
    private val crypto: CrewRelayRouteCrypto,
    private val sendEncrypted: (ByteArray) -> Boolean,
    private val closedRoute: () -> Unit,
) : CrewSignalPeer {
    private val closed = AtomicBoolean(false)
    private val channel = Channel<CrewSignalMessage>(RELAY_INCOMING_CAPACITY)
    private val mutableState = MutableStateFlow(CrewSignalConnectionState.CONNECTED)

    override val state: StateFlow<CrewSignalConnectionState> = mutableState.asStateFlow()
    override val incoming: Flow<CrewSignalMessage> = channel.receiveAsFlow()

    override suspend fun send(message: CrewSignalMessage): CrewSignalSendResult {
        if (closed.get()) return CrewSignalSendResult.Closed
        return runCatching {
            require(sendEncrypted(crypto.encrypt(CrewSignalMessageCodec.encode(message))))
            if (message is CrewSignalMessage.Close) close()
            CrewSignalSendResult.Sent
        }.getOrElse {
            closeFromRelay()
            CrewSignalSendResult.Failed
        }
    }

    fun receive(plain: ByteArray) {
        val message = (CrewSignalMessageCodec.decode(plain) as? CrewSignalDecodeResult.Accepted)?.message
            ?: throw IllegalArgumentException("Invalid Crew signal")
        if (channel.trySend(message).isFailure) throw IllegalStateException("Relay receive window full")
        if (message is CrewSignalMessage.Close) closeFromRelay()
    }

    fun closeFromRelay() = closeInternal(CrewSignalConnectionState.CLOSED, notify = false)

    override fun close() = closeInternal(CrewSignalConnectionState.CLOSED, notify = true)

    private fun closeInternal(state: CrewSignalConnectionState, notify: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        mutableState.value = state
        channel.close()
        if (notify) closedRoute()
    }
}
