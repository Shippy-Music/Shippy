/*
 * Copyright (c) 2026 Shippy contributors
 * CrewSignaling.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.signaling

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewIceCandidate
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewSessionDescription
import org.oxycblt.auxio.shippy.crew.transport.webrtc.CrewSessionDescriptionType

const val MAX_CREW_SIGNAL_MESSAGE_BYTES = 1024 * 1024 + 4096
private const val SIGNAL_FORMAT_VERSION = 1
private const val MAX_SDP_BYTES = 1024 * 1024
private const val MAX_ICE_SDP_BYTES = 64 * 1024
private const val MAX_SDP_MID_BYTES = 256

sealed interface CrewSignalMessage {
    data class SessionDescription(val value: CrewSessionDescription) : CrewSignalMessage

    data class IceCandidate(val value: CrewIceCandidate) : CrewSignalMessage

    data class EndOfCandidates(val generation: Long) : CrewSignalMessage {
        init {
            require(generation >= 0) { "ICE generation cannot be negative" }
        }
    }

    data class IceRestartRequested(val generation: Long) : CrewSignalMessage {
        init {
            require(generation >= 0) { "ICE generation cannot be negative" }
        }
    }

    data class Close(val reason: CrewSignalCloseReason) : CrewSignalMessage
}

enum class CrewSignalCloseReason {
    NORMAL,
    SESSION_ENDED,
    REPLACED,
    PROTOCOL_ERROR,
}

sealed interface CrewSignalDecodeResult {
    data class Accepted(val message: CrewSignalMessage) : CrewSignalDecodeResult

    enum class Rejected : CrewSignalDecodeResult {
        MALFORMED,
        UNSUPPORTED_FORMAT,
    }
}

/** Versioned, dependency-free signaling payload codec. Encryption is a transport concern. */
object CrewSignalMessageCodec {
    fun encode(message: CrewSignalMessage): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(SIGNAL_FORMAT_VERSION)
                when (message) {
                    is CrewSignalMessage.SessionDescription -> {
                        output.writeByte(1)
                        output.writeByte(
                            when (message.value.type) {
                                CrewSessionDescriptionType.OFFER -> 1
                                CrewSessionDescriptionType.ANSWER -> 2
                            },
                        )
                        output.writeLong(message.value.generation)
                        output.writeSizedString(message.value.sdp, MAX_SDP_BYTES)
                    }
                    is CrewSignalMessage.IceCandidate -> {
                        output.writeByte(2)
                        output.writeLong(message.value.generation)
                        output.writeBoolean(message.value.sdpMid != null)
                        message.value.sdpMid?.let {
                            output.writeSizedString(it, MAX_SDP_MID_BYTES)
                        }
                        output.writeInt(message.value.sdpMLineIndex)
                        output.writeSizedString(message.value.sdp, MAX_ICE_SDP_BYTES)
                    }
                    is CrewSignalMessage.EndOfCandidates -> {
                        output.writeByte(3)
                        output.writeLong(message.generation)
                    }
                    is CrewSignalMessage.IceRestartRequested -> {
                        output.writeByte(4)
                        output.writeLong(message.generation)
                    }
                    is CrewSignalMessage.Close -> {
                        output.writeByte(5)
                        output.writeByte(message.reason.wireValue)
                    }
                }
            }
            bytes.toByteArray().also {
                require(it.size <= MAX_CREW_SIGNAL_MESSAGE_BYTES) {
                    "Crew signaling payload is too large"
                }
            }
        }

    fun decode(payload: ByteArray): CrewSignalDecodeResult {
        if (payload.isEmpty() || payload.size > MAX_CREW_SIGNAL_MESSAGE_BYTES) {
            return CrewSignalDecodeResult.Rejected.MALFORMED
        }
        return try {
            val message =
                DataInputStream(ByteArrayInputStream(payload)).use { input ->
                    if (input.readUnsignedByte() != SIGNAL_FORMAT_VERSION) {
                        return CrewSignalDecodeResult.Rejected.UNSUPPORTED_FORMAT
                    }
                    val decoded =
                        when (input.readUnsignedByte()) {
                            1 -> {
                                val type =
                                    when (input.readUnsignedByte()) {
                                        1 -> CrewSessionDescriptionType.OFFER
                                        2 -> CrewSessionDescriptionType.ANSWER
                                        else ->
                                            return CrewSignalDecodeResult.Rejected.MALFORMED
                                    }
                                CrewSignalMessage.SessionDescription(
                                    CrewSessionDescription(
                                        type,
                                        input.readLong(),
                                        input.readSizedString(MAX_SDP_BYTES),
                                    ),
                                )
                            }
                            2 -> {
                                val generation = input.readLong()
                                val sdpMid =
                                    when (input.readUnsignedByte()) {
                                        0 -> null
                                        1 -> input.readSizedString(MAX_SDP_MID_BYTES)
                                        else ->
                                            return CrewSignalDecodeResult.Rejected.MALFORMED
                                    }
                                CrewSignalMessage.IceCandidate(
                                    CrewIceCandidate(
                                        generation,
                                        sdpMid,
                                        input.readInt(),
                                        input.readSizedString(MAX_ICE_SDP_BYTES),
                                    ),
                                )
                            }
                            3 -> CrewSignalMessage.EndOfCandidates(input.readLong())
                            4 -> CrewSignalMessage.IceRestartRequested(input.readLong())
                            5 -> {
                                CrewSignalMessage.Close(
                                    decodeCloseReason(input.readUnsignedByte())
                                        ?: return CrewSignalDecodeResult.Rejected.MALFORMED,
                                )
                            }
                            else -> return CrewSignalDecodeResult.Rejected.MALFORMED
                        }
                    require(input.available() == 0) {
                        "Crew signaling payload has trailing data"
                    }
                    decoded
                }
            CrewSignalDecodeResult.Accepted(message)
        } catch (_: IllegalArgumentException) {
            CrewSignalDecodeResult.Rejected.MALFORMED
        } catch (_: Exception) {
            CrewSignalDecodeResult.Rejected.MALFORMED
        }
    }
}

private val CrewSignalCloseReason.wireValue: Int
    get() =
        when (this) {
            CrewSignalCloseReason.NORMAL -> 1
            CrewSignalCloseReason.SESSION_ENDED -> 2
            CrewSignalCloseReason.REPLACED -> 3
            CrewSignalCloseReason.PROTOCOL_ERROR -> 4
        }

private fun decodeCloseReason(value: Int) =
    when (value) {
        1 -> CrewSignalCloseReason.NORMAL
        2 -> CrewSignalCloseReason.SESSION_ENDED
        3 -> CrewSignalCloseReason.REPLACED
        4 -> CrewSignalCloseReason.PROTOCOL_ERROR
        else -> null
    }

private fun DataOutputStream.writeSizedString(value: String, maxBytes: Int) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.size in 1..maxBytes) { "Crew signaling value has invalid size" }
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readSizedString(maxBytes: Int): String {
    val size = readInt()
    require(size in 1..maxBytes) { "Crew signaling value has invalid size" }
    val bytes = ByteArray(size)
    readFully(bytes)
    return bytes.toString(Charsets.UTF_8).also {
        require(it.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
            "Crew signaling value is not valid UTF-8"
        }
    }
}
