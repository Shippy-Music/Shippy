/*
 * Copyright (c) 2026 Auxio Project
 * CrewClockProtocol.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

private const val CLOCK_WIRE_VERSION = 1
private const val CLOCK_REQUEST = 1
private const val CLOCK_RESPONSE = 2
private const val CLOCK_REQUEST_BYTES = 18
private const val CLOCK_RESPONSE_BYTES = 34

sealed interface CrewClockFrame {
    val probeId: Long

    data class Request(override val probeId: Long, val clientSentMs: Long) : CrewClockFrame

    data class Response(
        override val probeId: Long,
        val clientSentMs: Long,
        val coordinatorReceivedMs: Long,
        val coordinatorSentMs: Long,
    ) : CrewClockFrame
}

object CrewClockCodec {
    fun encode(frame: CrewClockFrame): ByteArray {
        require(frame.probeId >= 0) { "Clock probe ID cannot be negative" }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(CLOCK_WIRE_VERSION)
                when (frame) {
                    is CrewClockFrame.Request -> {
                        require(frame.clientSentMs >= 0) { "Clock timestamp cannot be negative" }
                        output.writeByte(CLOCK_REQUEST)
                        output.writeLong(frame.probeId)
                        output.writeLong(frame.clientSentMs)
                    }
                    is CrewClockFrame.Response -> {
                        require(
                            frame.clientSentMs >= 0 &&
                                frame.coordinatorReceivedMs >= 0 &&
                                frame.coordinatorSentMs >= frame.coordinatorReceivedMs
                        ) {
                            "Invalid clock response timestamps"
                        }
                        output.writeByte(CLOCK_RESPONSE)
                        output.writeLong(frame.probeId)
                        output.writeLong(frame.clientSentMs)
                        output.writeLong(frame.coordinatorReceivedMs)
                        output.writeLong(frame.coordinatorSentMs)
                    }
                }
            }
            bytes.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): CrewClockFrame {
        require(bytes.size == CLOCK_REQUEST_BYTES || bytes.size == CLOCK_RESPONSE_BYTES) {
            "Invalid clock frame size"
        }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readUnsignedByte() == CLOCK_WIRE_VERSION) {
                "Unsupported clock frame version"
            }
            val frame =
                when (input.readUnsignedByte()) {
                    CLOCK_REQUEST ->
                        CrewClockFrame.Request(
                            probeId = input.readLong(),
                            clientSentMs = input.readLong(),
                        )
                    CLOCK_RESPONSE ->
                        CrewClockFrame.Response(
                            probeId = input.readLong(),
                            clientSentMs = input.readLong(),
                            coordinatorReceivedMs = input.readLong(),
                            coordinatorSentMs = input.readLong(),
                        )
                    else -> error("Unknown clock frame type")
                }
            require(input.available() == 0) { "Clock frame has trailing bytes" }
            require(frame.probeId >= 0) { "Clock probe ID cannot be negative" }
            frame
        }
    }
}
