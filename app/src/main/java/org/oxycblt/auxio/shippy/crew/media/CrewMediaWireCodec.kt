/* Copyright (c) 2026 Shippy contributors */
package org.oxycblt.auxio.shippy.crew.media

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.domain.CandidateId

sealed interface CrewMediaWireFrame {
    data class Manifest(val value: CrewMediaManifest) : CrewMediaWireFrame
    data class Chunk(val value: CrewMediaChunk) : CrewMediaWireFrame
}

/** Strict binary media framing. It carries no filesystem locator, provider URL, or credential. */
object CrewMediaWireCodec {
    private const val VERSION = 1
    private const val MANIFEST = 1
    private const val CHUNK = 2

    fun encode(frame: CrewMediaWireFrame): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeByte(VERSION)
            when (frame) {
                is CrewMediaWireFrame.Manifest -> {
                    out.writeByte(MANIFEST)
                    frame.value.writeTo(out)
                }
                is CrewMediaWireFrame.Chunk -> {
                    out.writeByte(CHUNK)
                    frame.value.writeTo(out)
                }
            }
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): CrewMediaWireFrame {
        require(bytes.size in 3..(CREW_MEDIA_MAX_CHUNK_BYTES + 2048)) {
            "Crew media frame is outside bounds"
        }
        DataInputStream(bytes.inputStream()).use { input ->
            require(input.readUnsignedByte() == VERSION) { "Unsupported Crew media version" }
            val frame = when (input.readUnsignedByte()) {
                MANIFEST -> CrewMediaWireFrame.Manifest(input.readManifest())
                CHUNK -> CrewMediaWireFrame.Chunk(input.readChunk())
                else -> throw IllegalArgumentException("Unknown Crew media frame")
            }
            require(input.available() == 0) { "Trailing Crew media bytes" }
            return frame
        }
    }

    private fun CrewMediaManifest.writeTo(out: DataOutputStream) {
        out.writeSession(sessionId)
        out.writeString(candidateId.value, 1024)
        out.writeNullableString(mimeType, 256)
        out.writeLong(objectSizeBytes)
        out.write(objectIntegrity.copyBytes())
        out.writeShort(chunks.size)
        chunks.forEach { out.writeInt(it.index); out.writeInt(it.sizeBytes); out.write(it.integrity.copyBytes()) }
    }

    private fun CrewMediaChunk.writeTo(out: DataOutputStream) {
        out.writeSession(sessionId)
        out.write(objectIntegrity.copyBytes())
        out.writeInt(index)
        out.writeInt(sizeBytes)
        out.write(copyPayload())
    }

    private fun DataInputStream.readManifest(): CrewMediaManifest {
        val session = readSession()
        val candidate = CandidateId(readString(1024))
        val mime = readNullableString(256)
        val size = readLong()
        val integrity = CrewMediaDigest(readExact(CREW_MEDIA_DIGEST_BYTES))
        val count = readUnsignedShort().also { require(it in 1..CREW_MEDIA_MAX_CHUNKS) }
        val chunks = List(count) {
            CrewMediaChunkDescriptor(
                readInt(),
                readInt(),
                CrewMediaDigest(readExact(CREW_MEDIA_DIGEST_BYTES)),
            )
        }
        return CrewMediaManifest(session, candidate, mime, size, integrity, chunks)
    }

    private fun DataInputStream.readChunk(): CrewMediaChunk {
        val session = readSession()
        val integrity = CrewMediaDigest(readExact(CREW_MEDIA_DIGEST_BYTES))
        val index = readInt()
        val size = readInt().also { require(it in 1..CREW_MEDIA_MAX_CHUNK_BYTES) }
        return CrewMediaChunk(session, integrity, index, readExact(size))
    }

    private fun DataOutputStream.writeSession(value: CrewSessionId) {
        writeString(value.value, 1024)
        writeInt(value.protocolVersion.value)
    }
    private fun DataInputStream.readSession() = CrewSessionId(readString(1024), ProtocolVersion(readInt()))

    private fun DataOutputStream.writeString(value: String, max: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 1..max) { "Crew media string is outside bounds" }
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableString(value: String?, max: Int) {
        writeBoolean(value != null)
        value?.let { writeString(it, max) }
    }

    private fun DataInputStream.readString(max: Int): String {
        val size = readUnsignedShort()
        require(size in 1..max)
        return String(readExact(size), StandardCharsets.UTF_8)
    }

    private fun DataInputStream.readNullableString(max: Int) = if (readBoolean()) readString(max) else null

    private fun DataInputStream.readExact(size: Int) = ByteArray(size).also { readFully(it) }
}
