/*
 * Copyright (c) 2026 Auxio Project
 * CrewRelayProtocol.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.relay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URI

internal const val CREW_RELAY_MAX_DATA_BYTES = 32 * 1024
private const val VERSION = 1
private const val ROUTE_BYTES = 16
internal const val CREW_RELAY_RESUME_TOKEN_BYTES = 32
private const val MAX_FRAME_BYTES = 33 * 1024
private const val MAX_LOCATOR = 96
private const val MAX_INVITE = 64
private const val MAX_REASON = 64

enum class CrewRelayRole(val wire: Int) {
    HOST(1),
    JOIN(2),
}

enum class CrewRelayType(val wire: Int) {
    REGISTER(1),
    REGISTERED(2),
    ROUTE_OPEN(3),
    ROUTE_CLOSE(4),
    DATA(5),
    ERROR(6),
    PING(7),
    PONG(8),
    HOST_RESUME(9),
}

class CrewRelayRouteId(source: ByteArray) {
    private val raw = source.copyOf()

    init {
        require(raw.size == ROUTE_BYTES) { "Relay route IDs are 16 bytes" }
    }

    val bytes
        get() = raw.copyOf()

    override fun equals(other: Any?) = other is CrewRelayRouteId && raw.contentEquals(other.raw)

    override fun hashCode() = raw.contentHashCode()

    override fun toString() = "CrewRelayRouteId(redacted)"
}

sealed interface CrewRelayFrame {
    data class Register(
        val role: CrewRelayRole,
        val protocolVersion: Int,
        val sessionLocator: ByteArray,
        val inviteId: ByteArray,
    ) : CrewRelayFrame

    data class HostResume(
        val protocolVersion: Int,
        val sessionLocator: ByteArray,
        val inviteId: ByteArray,
        val resumeToken: ByteArray,
    ) : CrewRelayFrame

    /** Hosts receive a 32-byte opaque in-memory resume credential; joiners receive only a route. */
    data class Registered(
        val role: CrewRelayRole,
        val routeId: CrewRelayRouteId? = null,
        val resumeToken: ByteArray? = null,
    ) : CrewRelayFrame

    data class RouteOpen(val routeId: CrewRelayRouteId, val reason: ByteArray = ByteArray(0)) :
        CrewRelayFrame

    data class RouteClose(val routeId: CrewRelayRouteId, val reason: ByteArray = ByteArray(0)) :
        CrewRelayFrame

    data class Data(val routeId: CrewRelayRouteId, val payload: ByteArray) : CrewRelayFrame

    data class Error(val reason: ByteArray) : CrewRelayFrame

    data object Ping : CrewRelayFrame

    data object Pong : CrewRelayFrame
}

/** Exact binary v1 codec for relay/src/protocol.js. Registration is intentionally opaque. */
object CrewRelayCodec {
    fun encode(frame: CrewRelayFrame): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(VERSION)
                when (frame) {
                    is CrewRelayFrame.Register -> {
                        bounded(frame.sessionLocator, MAX_LOCATOR)
                        bounded(frame.inviteId, MAX_INVITE)
                        output.writeByte(CrewRelayType.REGISTER.wire)
                        output.writeByte(frame.role.wire)
                        output.writeByte(frame.protocolVersion)
                        output.writeByte(frame.sessionLocator.size)
                        output.write(frame.sessionLocator)
                        output.writeByte(frame.inviteId.size)
                        output.write(frame.inviteId)
                    }
                    is CrewRelayFrame.HostResume -> {
                        require(frame.protocolVersion == VERSION)
                        bounded(frame.sessionLocator, MAX_LOCATOR)
                        bounded(frame.inviteId, MAX_INVITE)
                        resumeToken(frame.resumeToken)
                        output.writeByte(CrewRelayType.HOST_RESUME.wire)
                        output.writeByte(frame.protocolVersion)
                        output.writeByte(frame.sessionLocator.size)
                        output.write(frame.sessionLocator)
                        output.writeByte(frame.inviteId.size)
                        output.write(frame.inviteId)
                        output.write(frame.resumeToken)
                    }
                    is CrewRelayFrame.Registered -> {
                        when (frame.role) {
                            CrewRelayRole.HOST -> {
                                require(frame.routeId == null)
                                resumeToken(checkNotNull(frame.resumeToken))
                            }
                            CrewRelayRole.JOIN -> {
                                require(frame.routeId != null && frame.resumeToken == null)
                            }
                        }
                        output.writeByte(CrewRelayType.REGISTERED.wire)
                        output.writeByte(frame.role.wire)
                        output.writeByte(if (frame.routeId == null) 0 else 1)
                        frame.routeId?.let { output.write(it.bytes) }
                        frame.resumeToken?.let(output::write)
                    }
                    is CrewRelayFrame.RouteOpen ->
                        writeRoute(output, CrewRelayType.ROUTE_OPEN, frame.routeId, frame.reason)
                    is CrewRelayFrame.RouteClose ->
                        writeRoute(output, CrewRelayType.ROUTE_CLOSE, frame.routeId, frame.reason)
                    is CrewRelayFrame.Data -> {
                        bounded(frame.payload, CREW_RELAY_MAX_DATA_BYTES)
                        output.writeByte(CrewRelayType.DATA.wire)
                        output.write(frame.routeId.bytes)
                        output.write(frame.payload)
                    }
                    is CrewRelayFrame.Error -> {
                        bounded(frame.reason, MAX_REASON)
                        output.writeByte(CrewRelayType.ERROR.wire)
                        output.writeByte(frame.reason.size)
                        output.write(frame.reason)
                    }
                    CrewRelayFrame.Ping -> output.writeByte(CrewRelayType.PING.wire)
                    CrewRelayFrame.Pong -> output.writeByte(CrewRelayType.PONG.wire)
                }
            }
            bytes.toByteArray().also { require(it.size <= MAX_FRAME_BYTES) }
        }

    fun decode(input: ByteArray): CrewRelayFrame {
        require(input.size in 2..MAX_FRAME_BYTES && input[0].toInt() == VERSION) {
            "Invalid relay frame"
        }
        return DataInputStream(ByteArrayInputStream(input)).use { data ->
            data.readUnsignedByte()
            val frame =
                when (data.readUnsignedByte()) {
                    1 ->
                        CrewRelayFrame.Register(
                            role(data.readUnsignedByte()),
                            data.readUnsignedByte(),
                            read(data, data.readUnsignedByte(), MAX_LOCATOR),
                            read(data, data.readUnsignedByte(), MAX_INVITE),
                        )
                    2 -> {
                        val registeredRole = role(data.readUnsignedByte())
                        val hasRoute = data.readUnsignedByte()
                        require(hasRoute in 0..1)
                        when (registeredRole) {
                            CrewRelayRole.HOST -> {
                                require(hasRoute == 0)
                                CrewRelayFrame.Registered(
                                    registeredRole,
                                    resumeToken =
                                        read(
                                            data,
                                            CREW_RELAY_RESUME_TOKEN_BYTES,
                                            CREW_RELAY_RESUME_TOKEN_BYTES,
                                        ),
                                )
                            }
                            CrewRelayRole.JOIN -> {
                                require(hasRoute == 1)
                                CrewRelayFrame.Registered(
                                    registeredRole,
                                    CrewRelayRouteId(read(data, ROUTE_BYTES, ROUTE_BYTES)),
                                )
                            }
                        }
                    }
                    3 -> readRoute(data, true)
                    4 -> readRoute(data, false)
                    5 ->
                        CrewRelayFrame.Data(
                            CrewRelayRouteId(read(data, ROUTE_BYTES, ROUTE_BYTES)),
                            read(data, data.available(), CREW_RELAY_MAX_DATA_BYTES),
                        )
                    6 -> CrewRelayFrame.Error(read(data, data.readUnsignedByte(), MAX_REASON))
                    7 -> CrewRelayFrame.Ping
                    8 -> CrewRelayFrame.Pong
                    9 -> {
                        val protocolVersion = data.readUnsignedByte()
                        require(protocolVersion == VERSION)
                        CrewRelayFrame.HostResume(
                            protocolVersion,
                            read(data, data.readUnsignedByte(), MAX_LOCATOR),
                            read(data, data.readUnsignedByte(), MAX_INVITE),
                            read(data, CREW_RELAY_RESUME_TOKEN_BYTES, CREW_RELAY_RESUME_TOKEN_BYTES),
                        )
                    }
                    else -> throw IllegalArgumentException("Unexpected relay frame")
                }
            require(data.available() == 0) { "Relay frame has trailing data" }
            frame
        }
    }

    private fun writeRoute(
        output: DataOutputStream,
        type: CrewRelayType,
        id: CrewRelayRouteId,
        reason: ByteArray,
    ) {
        boundedAllowEmpty(reason, MAX_REASON)
        output.writeByte(type.wire)
        output.write(id.bytes)
        output.writeByte(reason.size)
        output.write(reason)
    }

    private fun readRoute(data: DataInputStream, opened: Boolean): CrewRelayFrame {
        val id = CrewRelayRouteId(read(data, ROUTE_BYTES, ROUTE_BYTES))
        val reason = read(data, data.readUnsignedByte(), MAX_REASON, empty = true)
        return if (opened) CrewRelayFrame.RouteOpen(id, reason)
        else CrewRelayFrame.RouteClose(id, reason)
    }

    private fun role(value: Int) =
        CrewRelayRole.entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Bad relay role")

    private fun read(
        data: DataInputStream,
        size: Int,
        max: Int,
        empty: Boolean = false,
    ): ByteArray {
        require(size <= max && (empty || size > 0))
        return ByteArray(size).also(data::readFully)
    }

    private fun bounded(value: ByteArray, max: Int) = require(value.size in 1..max)

    private fun boundedAllowEmpty(value: ByteArray, max: Int) = require(value.size <= max)

    private fun resumeToken(value: ByteArray) = require(value.size == CREW_RELAY_RESUME_TOKEN_BYTES)
}

/** The relay locator is an HTTPS URL; only its scheme changes for the WebSocket connection. */
fun CrewRelayLocatorWebSocketUrl(locator: String): String {
    val uri = URI(locator)
    require(
        uri.scheme == "https" &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            uri.rawUserInfo == null &&
            uri.host != null
    )
    return "wss" + locator.removePrefix("https")
}
