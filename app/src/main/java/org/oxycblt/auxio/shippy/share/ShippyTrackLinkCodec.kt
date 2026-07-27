/*
 * Copyright (c) 2026 Auxio Project
 * ShippyTrackLinkCodec.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.share

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.domain.TrackVersion

/** Strict, transport-safe recording metadata link. It deliberately omits every playable locator. */
object ShippyTrackLinkCodec {
    private const val VERSION = 1
    private const val PREFIX = "shippy://track/v1/"
    private const val MAX_PAYLOAD_BYTES = 8 * 1024
    private const val MAX_CANDIDATES = 12
    private const val MAX_ARTISTS = 8
    private const val MAX_STRING_BYTES = 512
    private const val CHECKSUM_BYTES = 32

    fun encode(track: Track): String? {
        if (track.realm != TrackRealm.PROVIDER) return null
        val candidates =
            track.candidates.filter { it.kind == CandidateKind.PROVIDER && it.providerId != null }
        if (
            candidates.isEmpty() ||
                candidates.size > MAX_CANDIDATES ||
                track.artists.size !in 1..MAX_ARTISTS
        )
            return null
        return runCatching {
                val payload =
                    ByteArrayOutputStream().use { bytes ->
                        DataOutputStream(bytes).use { output ->
                            output.writeByte(VERSION)
                            output.writeString(track.id.value)
                            output.writeString(track.title)
                            output.writeByte(track.artists.size)
                            track.artists.forEach { output.writeString(it) }
                            output.writeOptionalString(track.album)
                            output.writeLong(track.durationMs ?: -1)
                            output.writeOptionalString(track.version.label)
                            output.writeByte(
                                if (track.version.explicit == null) 0
                                else if (track.version.explicit) 2 else 1
                            )
                            output.writeBoolean(track.version.isLive)
                            output.writeBoolean(track.version.isRemix)
                            output.writeByte(candidates.size)
                            candidates.forEach { candidate ->
                                output.writeString(candidate.id.value)
                                output.writeString(candidate.providerId!!.value)
                                output.writeString(candidate.sourceId)
                                output.writeString(candidate.sourceItemId)
                            }
                        }
                        bytes.toByteArray()
                    }
                require(payload.size <= MAX_PAYLOAD_BYTES)
                PREFIX +
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(payload + checksum(payload))
            }
            .getOrNull()
    }

    fun decode(link: String): Track? =
        runCatching {
                require(link.startsWith(PREFIX))
                val encoded = link.removePrefix(PREFIX)
                require(encoded.isNotBlank() && encoded.length <= 16 * 1024)
                val bytes = Base64.getUrlDecoder().decode(encoded)
                require(bytes.size in (CHECKSUM_BYTES + 1)..(MAX_PAYLOAD_BYTES + CHECKSUM_BYTES))
                val payload = bytes.copyOfRange(0, bytes.size - CHECKSUM_BYTES)
                require(
                    MessageDigest.isEqual(
                        checksum(payload),
                        bytes.copyOfRange(payload.size, bytes.size),
                    )
                )
                DataInputStream(ByteArrayInputStream(payload)).use { input ->
                    require(input.readUnsignedByte() == VERSION)
                    val id = TrackId(input.readString())
                    val title = input.readString()
                    val artistCount =
                        input.readUnsignedByte().also { require(it in 1..MAX_ARTISTS) }
                    val artists = List(artistCount) { input.readString() }
                    val album = input.readOptionalString()
                    val duration = input.readLong().takeIf { it >= 0 }
                    val label = input.readOptionalString()
                    val explicit =
                        when (input.readUnsignedByte()) {
                            0 -> null
                            1 -> false
                            2 -> true
                            else -> error("Invalid explicit flag")
                        }
                    val live = input.readBoolean()
                    val remix = input.readBoolean()
                    val candidateCount =
                        input.readUnsignedByte().also { require(it in 1..MAX_CANDIDATES) }
                    val candidates =
                        List(candidateCount) {
                            val candidateId = CandidateId(input.readString())
                            val providerId = ProviderId(input.readString())
                            TrackCandidate(
                                id = candidateId,
                                trackId = id,
                                kind = CandidateKind.PROVIDER,
                                sourceId = input.readString(),
                                sourceItemId = input.readString(),
                                availability = CandidateAvailability.RESOLVABLE,
                                providerId = providerId,
                            )
                        }
                    require(input.available() == 0)
                    Track(
                        id,
                        TrackRealm.PROVIDER,
                        title,
                        artists,
                        album,
                        duration,
                        TrackVersion(label, explicit, live, remix),
                        candidates = candidates,
                    )
                }
            }
            .getOrNull()

    private fun checksum(payload: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(payload)

    private fun DataOutputStream.writeOptionalString(value: String?) {
        writeBoolean(value != null)
        value?.let { writeString(it) }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(value.isNotBlank() && bytes.size <= MAX_STRING_BYTES)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readOptionalString(): String? =
        if (readBoolean()) readString() else null

    private fun DataInputStream.readString(): String {
        val size = readUnsignedShort()
        require(size in 1..MAX_STRING_BYTES)
        return ByteArray(size).also(::readFully).toString(StandardCharsets.UTF_8).also {
            require(it.isNotBlank())
        }
    }
}
