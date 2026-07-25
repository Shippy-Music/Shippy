/* Copyright (c) 2026 Shippy contributors */
package org.oxycblt.auxio.shippy.crew.media

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.QueueItemId

sealed interface CrewMediaWireFrame {
    val transfer: CrewMediaTransferRef
    data class Request(override val transfer: CrewMediaTransferRef) : CrewMediaWireFrame
    data class Cancel(override val transfer: CrewMediaTransferRef) : CrewMediaWireFrame
    data class Manifest(val value: CrewMediaManifest) : CrewMediaWireFrame { override val transfer get() = value.transfer }
    data class Chunk(val value: CrewMediaChunk) : CrewMediaWireFrame { override val transfer get() = value.transfer }
    data class ManifestAccepted(override val transfer: CrewMediaTransferRef) : CrewMediaWireFrame
    data class RetryLater(override val transfer: CrewMediaTransferRef) : CrewMediaWireFrame
    data class Rejected(override val transfer: CrewMediaTransferRef) : CrewMediaWireFrame
    data class ObjectComplete(override val transfer: CrewMediaTransferRef) : CrewMediaWireFrame
}

/** Strict bounded binary framing. It carries no filesystem locator, provider URL, or credential. */
object CrewMediaWireCodec {
    private const val VERSION = 3
    private const val MANIFEST = 1; private const val CHUNK = 2; private const val REQUEST = 3
    private const val CANCEL = 4; private const val ACCEPTED = 5; private const val RETRY = 6
    private const val REJECTED = 7; private const val COMPLETE = 8

    fun encode(frame: CrewMediaWireFrame): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeByte(VERSION)
            when (frame) {
                is CrewMediaWireFrame.Manifest -> { out.writeByte(MANIFEST); frame.value.writeTo(out) }
                is CrewMediaWireFrame.Chunk -> { out.writeByte(CHUNK); frame.value.writeTo(out) }
                is CrewMediaWireFrame.Request -> simple(out, REQUEST, frame.transfer)
                is CrewMediaWireFrame.Cancel -> simple(out, CANCEL, frame.transfer)
                is CrewMediaWireFrame.ManifestAccepted -> simple(out, ACCEPTED, frame.transfer)
                is CrewMediaWireFrame.RetryLater -> simple(out, RETRY, frame.transfer)
                is CrewMediaWireFrame.Rejected -> simple(out, REJECTED, frame.transfer)
                is CrewMediaWireFrame.ObjectComplete -> simple(out, COMPLETE, frame.transfer)
            }
        }
        bytes.toByteArray().also {
            require(it.size <= CREW_MEDIA_MAX_FRAME_BYTES) { "Crew media frame exceeds channel bound" }
        }
    }
    fun decode(bytes: ByteArray): CrewMediaWireFrame {
        require(bytes.size in 3..CREW_MEDIA_MAX_FRAME_BYTES)
        DataInputStream(bytes.inputStream()).use { input ->
            require(input.readUnsignedByte() == VERSION)
            val frame = when (input.readUnsignedByte()) {
                MANIFEST -> CrewMediaWireFrame.Manifest(input.readManifest()); CHUNK -> CrewMediaWireFrame.Chunk(input.readChunk())
                REQUEST -> CrewMediaWireFrame.Request(input.readTransfer()); CANCEL -> CrewMediaWireFrame.Cancel(input.readTransfer())
                ACCEPTED -> CrewMediaWireFrame.ManifestAccepted(input.readTransfer()); RETRY -> CrewMediaWireFrame.RetryLater(input.readTransfer())
                REJECTED -> CrewMediaWireFrame.Rejected(input.readTransfer()); COMPLETE -> CrewMediaWireFrame.ObjectComplete(input.readTransfer())
                else -> throw IllegalArgumentException("Unknown Crew media frame")
            }; require(input.available() == 0); return frame
        }
    }
    private fun simple(out: DataOutputStream, type: Int, transfer: CrewMediaTransferRef) { out.writeByte(type); out.writeTransfer(transfer) }
    private fun CrewMediaManifest.writeTo(out: DataOutputStream) { out.writeTransfer(transfer); out.writeNullableString(mimeType, 256); out.writeLong(objectSizeBytes); out.write(objectIntegrity.copyBytes()); out.writeShort(chunks.size); chunks.forEach { out.writeInt(it.index); out.writeInt(it.sizeBytes); out.write(it.integrity.copyBytes()) } }
    private fun CrewMediaChunk.writeTo(out: DataOutputStream) { out.writeTransfer(transfer); out.write(objectIntegrity.copyBytes()); out.writeInt(index); out.writeInt(sizeBytes); out.write(copyPayload()) }
    private fun DataInputStream.readManifest(): CrewMediaManifest { val transfer = readTransfer(); val mime = readNullableString(256); val size = readLong(); val integrity = CrewMediaDigest(readExact(CREW_MEDIA_DIGEST_BYTES)); val count = readUnsignedShort().also { require(it in 1..CREW_MEDIA_MAX_CHUNKS) }; val chunks = List(count) { CrewMediaChunkDescriptor(readInt(), readInt(), CrewMediaDigest(readExact(CREW_MEDIA_DIGEST_BYTES))) }; return CrewMediaManifest(transfer, mime, size, integrity, chunks) }
    private fun DataInputStream.readChunk(): CrewMediaChunk { val transfer = readTransfer(); val integrity = CrewMediaDigest(readExact(CREW_MEDIA_DIGEST_BYTES)); val index = readInt(); val size = readInt().also { require(it in 1..CREW_MEDIA_MAX_CHUNK_BYTES) }; return CrewMediaChunk(transfer, integrity, index, readExact(size)) }
    private fun DataOutputStream.writeTransfer(v: CrewMediaTransferRef) { writeSession(v.sessionId); writeString(v.requestId.value, CREW_MEDIA_MAX_REQUEST_ID_BYTES); writeString(v.queueItemId.value, CREW_MEDIA_MAX_ID_BYTES); writeString(v.candidateId.value, CREW_MEDIA_MAX_ID_BYTES); writeMember(v.targetMemberId); writeMember(v.supplierMemberId) }
    private fun DataInputStream.readTransfer() = CrewMediaTransferRef(readSession(), CrewMediaRequestId(readString(CREW_MEDIA_MAX_REQUEST_ID_BYTES)), QueueItemId(readString(CREW_MEDIA_MAX_ID_BYTES)), CandidateId(readString(CREW_MEDIA_MAX_ID_BYTES)), readMember(), readMember())
    private fun DataOutputStream.writeSession(v: CrewSessionId) { writeString(v.value, CREW_MEDIA_MAX_ID_BYTES); writeInt(v.protocolVersion.value) }
    private fun DataInputStream.readSession() = CrewSessionId(readString(CREW_MEDIA_MAX_ID_BYTES), ProtocolVersion(readInt()))
    private fun DataOutputStream.writeMember(v: CrewMemberId) { writeString(v.value, CREW_MEDIA_MAX_ID_BYTES); writeInt(v.protocolVersion.value) }
    private fun DataInputStream.readMember() = CrewMemberId(readString(CREW_MEDIA_MAX_ID_BYTES), ProtocolVersion(readInt()))
    private fun DataOutputStream.writeString(v: String, max: Int) { val b = v.toByteArray(StandardCharsets.UTF_8); require(b.size in 1..max); writeShort(b.size); write(b) }
    private fun DataInputStream.readString(max: Int): String { val size = readUnsignedShort(); require(size in 1..max); return String(readExact(size), StandardCharsets.UTF_8) }
    private fun DataOutputStream.writeNullableString(v: String?, max: Int) { writeBoolean(v != null); v?.let { writeString(it, max) } }
    private fun DataInputStream.readNullableString(max: Int) = if (readBoolean()) readString(max) else null
    private fun DataInputStream.readExact(size: Int) = ByteArray(size).also(::readFully)
}
