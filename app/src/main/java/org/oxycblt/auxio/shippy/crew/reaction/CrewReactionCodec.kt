/*
 * Copyright (c) 2026 Auxio Project
 * CrewReactionCodec.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.reaction

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion

/** Bounded ephemeral reaction wire format. It deliberately carries no timestamps or history. */
object CrewReactionCodec {
    const val MAX_PAYLOAD_BYTES = 512
    private const val VERSION: Byte = 1
    private const val MAX_ID_BYTES = 128
    private const val MAX_EMOJI_BYTES = 32

    fun encode(event: CrewReactionEvent): ByteArray {
        val session = bounded(event.sessionId.value, MAX_ID_BYTES)
        val member = bounded(event.memberId.value, MAX_ID_BYTES)
        val id = bounded(event.id.value, MAX_ID_BYTES)
        val emoji = bounded(event.emoji, MAX_EMOJI_BYTES)
        require(event.sessionId.protocolVersion == event.memberId.protocolVersion) {
            "Crew reaction identities must use one protocol version"
        }
        val size = 1 + Int.SIZE_BYTES + 4 + session.size + member.size + id.size + emoji.size
        require(size <= MAX_PAYLOAD_BYTES) { "Crew reaction exceeds transport bound" }
        return ByteBuffer.allocate(size)
            .apply {
                put(VERSION)
                putInt(event.sessionId.protocolVersion.value)
                putField(session)
                putField(member)
                putField(id)
                putField(emoji)
            }
            .array()
    }

    fun decode(payload: ByteArray): CrewReactionDecodeResult {
        if (payload.isEmpty() || payload.size > MAX_PAYLOAD_BYTES)
            return CrewReactionDecodeResult.Rejected
        return try {
            val input = ByteBuffer.wrap(payload)
            if (input.get() != VERSION) return CrewReactionDecodeResult.Rejected
            if (input.remaining() < Int.SIZE_BYTES) return CrewReactionDecodeResult.Rejected
            val protocol = ProtocolVersion(input.getInt())
            val session = input.readField(MAX_ID_BYTES) ?: return CrewReactionDecodeResult.Rejected
            val member = input.readField(MAX_ID_BYTES) ?: return CrewReactionDecodeResult.Rejected
            val id = input.readField(MAX_ID_BYTES) ?: return CrewReactionDecodeResult.Rejected
            val emoji = input.readField(MAX_EMOJI_BYTES) ?: return CrewReactionDecodeResult.Rejected
            if (input.hasRemaining()) return CrewReactionDecodeResult.Rejected
            CrewReactionDecodeResult.Decoded(
                CrewReactionEvent(
                    CrewSessionId(session, protocol),
                    CrewMemberId(member, protocol),
                    CrewReactionId(id),
                    emoji,
                )
            )
        } catch (_: Exception) {
            CrewReactionDecodeResult.Rejected
        }
    }

    private fun bounded(value: String, maximum: Int): ByteArray =
        value.toByteArray(StandardCharsets.UTF_8).also {
            require(it.isNotEmpty() && it.size <= maximum) {
                "Crew reaction field is out of bounds"
            }
        }

    private fun ByteBuffer.putField(bytes: ByteArray) {
        put(bytes.size.toByte())
        put(bytes)
    }

    private fun ByteBuffer.readField(maximum: Int): String? {
        if (!hasRemaining()) return null
        val size = get().toInt() and 0xff
        if (size == 0 || size > maximum || remaining() < size) return null
        val bytes = ByteArray(size).also(::get)
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }
}

sealed interface CrewReactionDecodeResult {
    data class Decoded(val event: CrewReactionEvent) : CrewReactionDecodeResult

    data object Rejected : CrewReactionDecodeResult
}
