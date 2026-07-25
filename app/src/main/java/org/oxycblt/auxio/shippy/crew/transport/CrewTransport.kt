/*
 * Copyright (c) 2026 Shippy contributors
 * CrewTransport.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.transport

import java.io.Closeable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId

/**
 * Independent WebRTC data channels prevent media pressure from delaying control or clock traffic.
 */
enum class CrewTransportChannel(
    val wireLabel: String,
    val ordered: Boolean,
    val maxRetransmits: Int?,
    val maxPayloadBytes: Int,
    val maxBufferedBytes: Long,
) {
    CONTROL("shippy-control-v1", true, null, 64 * 1024, 256 * 1024L),
    CLOCK("shippy-clock-v1", false, 0, 2 * 1024, 32 * 1024L),
    REACTION("shippy-reaction-v1", false, 0, 512, 16 * 1024L),
    MEDIA("shippy-media-v1", true, null, 48 * 1024, 2 * 1024 * 1024L);

    companion object {
        fun fromWireLabel(label: String) = entries.firstOrNull { it.wireLabel == label }
    }
}

class CrewTransportFrame(
    val channel: CrewTransportChannel,
    payload: ByteArray,
) {
    private val bytes = payload.copyOf()

    val size: Int
        get() = bytes.size

    init {
        require(bytes.isNotEmpty()) { "Crew transport payload cannot be empty" }
        require(bytes.size <= channel.maxPayloadBytes) {
            "Crew ${channel.name.lowercase()} payload exceeds ${channel.maxPayloadBytes} bytes"
        }
    }

    fun copyPayload(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?) =
        other is CrewTransportFrame && channel == other.channel && bytes.contentEquals(other.bytes)

    override fun hashCode() = 31 * channel.hashCode() + bytes.contentHashCode()

    override fun toString() = "CrewTransportFrame(channel=$channel, size=${bytes.size})"
}

enum class CrewTransportState {
    NEW,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    RECONNECTING,
    FAILED,
    CLOSED,
}

enum class CrewTransportDropReason {
    TRANSIENT_CHANNEL_FULL,
    MEDIA_WINDOW_FULL,
}

data class CrewTransportDrop(
    val channel: CrewTransportChannel,
    val payloadBytes: Int,
    val reason: CrewTransportDropReason,
)

sealed interface CrewSendResult {
    data class Sent(val bufferedBytes: Long) : CrewSendResult

    data class Backpressured(
        val bufferedBytes: Long,
        val limitBytes: Long,
    ) : CrewSendResult

    data object ChannelNotOpen : CrewSendResult
    data object NativeRejected : CrewSendResult
    data object Closed : CrewSendResult
}

/**
 * One authenticated Crew peer. Authentication and signaling are intentionally outside this
 * boundary; accepted frames are still checked against session/member authority by the Crew engine.
 */
interface CrewPeerTransport : Closeable {
    val remoteMemberId: CrewMemberId
    val state: StateFlow<CrewTransportState>
    val incoming: Flow<CrewTransportFrame>
    val drops: Flow<CrewTransportDrop>

    fun trySend(frame: CrewTransportFrame): CrewSendResult

    fun bufferedBytes(channel: CrewTransportChannel): Long
}
