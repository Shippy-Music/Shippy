/*
 * Copyright (c) 2026 Shippy contributors
 * CrewMediaModel.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.crew.media

import java.security.MessageDigest
import java.io.InputStream
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.QueueItemId

/** Wire bounds intentionally leave room below the 48 KiB Crew media-channel payload limit. */
const val CREW_MEDIA_MAX_CHUNK_BYTES = 44 * 1024
const val CREW_MEDIA_MAX_CHUNKS = 512
/** The manifest has no paging in this foundation, so this is the actual transferable-object cap. */
const val CREW_MEDIA_MAX_OBJECT_BYTES = 512L * CREW_MEDIA_MAX_CHUNK_BYTES
const val CREW_MEDIA_DIGEST_BYTES = 32
const val CREW_MEDIA_MAX_REQUEST_ID_BYTES = 96
const val CREW_MEDIA_MAX_ID_BYTES = 128
const val CREW_MEDIA_MAX_FRAME_BYTES = 48 * 1024

@JvmInline
value class CrewMediaRequestId(val value: String) {
    init { require(value.toByteArray(Charsets.UTF_8).size in 1..CREW_MEDIA_MAX_REQUEST_ID_BYTES) }
}

/** Exact, path-free identity carried by every transfer frame. */
data class CrewMediaTransferRef(
    val sessionId: CrewSessionId,
    val requestId: CrewMediaRequestId,
    val queueItemId: QueueItemId,
    val candidateId: CandidateId,
    val targetMemberId: CrewMemberId,
    val supplierMemberId: CrewMemberId,
)

data class CrewMediaRequest(val transfer: CrewMediaTransferRef)

/** An authorized source is intentionally bytes only: no path, URI, provider, or credential crosses this seam. */
interface CrewAuthorizedMediaSource {
    val lengthBytes: Long
    val mimeType: String?
    fun open(): InputStream
}

class CrewMediaDigest(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init {
        require(value.size == CREW_MEDIA_DIGEST_BYTES) { "Crew media digest must be SHA-256" }
    }

    fun copyBytes() = value.copyOf()
    override fun equals(other: Any?) = other is CrewMediaDigest && value.contentEquals(other.value)
    override fun hashCode() = value.contentHashCode()
    override fun toString() = value.joinToString("") { "%02x".format(it) }

    companion object {
        fun sha256(bytes: ByteArray) = CrewMediaDigest(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

data class CrewMediaChunkDescriptor(
    val index: Int,
    val sizeBytes: Int,
    val integrity: CrewMediaDigest,
) {
    init {
        require(index >= 0) { "Chunk index cannot be negative" }
        require(sizeBytes in 1..CREW_MEDIA_MAX_CHUNK_BYTES) { "Chunk size is outside Crew bounds" }
    }
}

/** Path-free declaration for one exact canonical candidate in one active Crew only. */
data class CrewMediaManifest(
    val transfer: CrewMediaTransferRef,
    val mimeType: String?,
    val objectSizeBytes: Long,
    val objectIntegrity: CrewMediaDigest,
    val chunks: List<CrewMediaChunkDescriptor>,
) {
    val sessionId get() = transfer.sessionId
    val candidateId get() = transfer.candidateId
    init {
        require(mimeType == null || mimeType.toByteArray(Charsets.UTF_8).size <= 256) {
            "MIME type is too large"
        }
        require(objectSizeBytes in 1..CREW_MEDIA_MAX_OBJECT_BYTES) { "Crew media object is outside bounds" }
        require(chunks.size in 1..CREW_MEDIA_MAX_CHUNKS) { "Crew media chunk count is outside bounds" }
        require(chunks.map { it.index }.sorted() == chunks.indices.toList()) { "Chunk indexes must be contiguous" }
        require(chunks.sumOf { it.sizeBytes.toLong() } == objectSizeBytes) {
            "Chunk sizes must equal object size"
        }
    }
}

class CrewMediaChunk(
    val transfer: CrewMediaTransferRef,
    val objectIntegrity: CrewMediaDigest,
    val index: Int,
    payload: ByteArray,
) {
    val sessionId get() = transfer.sessionId
    private val bytes = payload.copyOf()

    init {
        require(index >= 0) { "Chunk index cannot be negative" }
        require(bytes.size in 1..CREW_MEDIA_MAX_CHUNK_BYTES) { "Chunk payload is outside bounds" }
    }

    val sizeBytes get() = bytes.size
    fun copyPayload() = bytes.copyOf()

    override fun equals(other: Any?) =
        other is CrewMediaChunk &&
            transfer == other.transfer &&
            objectIntegrity == other.objectIntegrity &&
            index == other.index &&
            bytes.contentEquals(other.bytes)

    override fun hashCode() =
        31 * (31 * (31 * transfer.hashCode() + objectIntegrity.hashCode()) + index) +
            bytes.contentHashCode()
}

/** Reads an already-authorized source only after bounding its declared and observed size. */
object CrewMediaProducer {
    fun produce(transfer: CrewMediaTransferRef, source: CrewAuthorizedMediaSource): Pair<CrewMediaManifest, List<CrewMediaChunk>> {
        require(source.lengthBytes in 1..CREW_MEDIA_MAX_OBJECT_BYTES) { "Media source is outside Crew bounds" }
        val bytes = source.open().use { input ->
            val out = ByteArray(source.lengthBytes.toInt())
            var offset = 0
            while (offset < out.size) {
                val read = input.read(out, offset, out.size - offset)
                require(read >= 0) { "Media source ended early" }
                if (read == 0) {
                    val byte = input.read()
                    require(byte >= 0) { "Media source ended early" }
                    out[offset++] = byte.toByte()
                } else {
                    offset += read
                }
            }
            require(input.read() == -1) { "Media source exceeded declared bound" }
            out
        }
        val parts = buildList {
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + CREW_MEDIA_MAX_CHUNK_BYTES, bytes.size)
                add(bytes.copyOfRange(offset, end))
                offset = end
            }
        }
        require(parts.size <= CREW_MEDIA_MAX_CHUNKS)
        val manifest = CrewMediaManifest(transfer, source.mimeType, bytes.size.toLong(), CrewMediaDigest.sha256(bytes),
            parts.mapIndexed { index, payload -> CrewMediaChunkDescriptor(index, payload.size, CrewMediaDigest.sha256(payload)) })
        return manifest to parts.mapIndexed { index, payload -> CrewMediaChunk(transfer, manifest.objectIntegrity, index, payload) }
    }
}

/** One user-visible setting, deliberately valid only for the current active Crew. */
class ActiveCrewPushPullPolicy {
    private var activeSessionId: CrewSessionId? = null
    private var enabled = false

    @Synchronized
    fun activate(sessionId: CrewSessionId, pushPullEnabled: Boolean) {
        activeSessionId = sessionId
        enabled = pushPullEnabled
    }

    @Synchronized
    fun deactivate(sessionId: CrewSessionId) {
        if (activeSessionId == sessionId) {
            activeSessionId = null
            enabled = false
        }
    }

    @Synchronized
    fun accepts(sessionId: CrewSessionId) = activeSessionId == sessionId && enabled
}
