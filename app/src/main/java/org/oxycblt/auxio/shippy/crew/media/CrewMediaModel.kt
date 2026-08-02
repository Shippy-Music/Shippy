/*
 * Copyright (c) 2026 Auxio Project
 * CrewMediaModel.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.media

import java.io.Closeable
import java.io.InputStream
import java.security.MessageDigest
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.QueueItemId

/** Wire bounds intentionally leave room below the 48 KiB Crew media-channel payload limit. */
const val CREW_MEDIA_MAX_CHUNK_BYTES = 44 * 1024
const val CREW_MEDIA_MAX_CHUNKS = 4096
/**
 * One temporary object remains bounded below the manifest frame ceiling. Objects are streamed in
 * one-chunk windows, so this is a protocol/product bound rather than a heap-allocation bound.
 */
const val CREW_MEDIA_MAX_OBJECT_BYTES = CREW_MEDIA_MAX_CHUNK_BYTES.toLong() * CREW_MEDIA_MAX_CHUNKS
const val CREW_MEDIA_SESSION_CACHE_BYTES = 512L * 1024L * 1024L
const val CREW_MEDIA_DIGEST_BYTES = 32
const val CREW_MEDIA_MAX_REQUEST_ID_BYTES = 96
const val CREW_MEDIA_MAX_ID_BYTES = 128
const val CREW_MEDIA_MAX_FRAME_BYTES = 48 * 1024

@JvmInline
value class CrewMediaRequestId(val value: String) {
    init {
        require(value.toByteArray(Charsets.UTF_8).size in 1..CREW_MEDIA_MAX_REQUEST_ID_BYTES)
    }
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

/**
 * An authorized source is intentionally bytes only: no path, URI, provider, or credential crosses
 * this seam.
 */
interface CrewAuthorizedMediaSource {
    /** Exact byte length when known, or [UNKNOWN_LENGTH] for a repeatable bounded source. */
    val lengthBytes: Long
    val mimeType: String?

    fun open(): InputStream

    companion object {
        const val UNKNOWN_LENGTH = -1L
    }
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
        fun sha256(bytes: ByteArray) =
            CrewMediaDigest(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

data class CrewMediaChunkDescriptor(val index: Int, val sizeBytes: Int) {
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
    val sessionId
        get() = transfer.sessionId

    val candidateId
        get() = transfer.candidateId

    init {
        require(mimeType == null || mimeType.toByteArray(Charsets.UTF_8).size <= 256) {
            "MIME type is too large"
        }
        require(objectSizeBytes in 1..CREW_MEDIA_MAX_OBJECT_BYTES) {
            "Crew media object is outside bounds"
        }
        require(chunks.size in 1..CREW_MEDIA_MAX_CHUNKS) {
            "Crew media chunk count is outside bounds"
        }
        require(chunks.map { it.index }.sorted() == chunks.indices.toList()) {
            "Chunk indexes must be contiguous"
        }
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
    val chunkIntegrity: CrewMediaDigest = CrewMediaDigest.sha256(payload),
) {
    val sessionId
        get() = transfer.sessionId

    private val bytes = payload.copyOf()

    init {
        require(index >= 0) { "Chunk index cannot be negative" }
        require(bytes.size in 1..CREW_MEDIA_MAX_CHUNK_BYTES) { "Chunk payload is outside bounds" }
        require(CrewMediaDigest.sha256(bytes) == chunkIntegrity) {
            "Crew media chunk integrity mismatch"
        }
    }

    val sizeBytes
        get() = bytes.size

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
    /** Hashes and describes an authorized object with one fixed-size buffer. */
    fun describe(
        transfer: CrewMediaTransferRef,
        source: CrewAuthorizedMediaSource,
    ): CrewMediaManifest {
        require(
            source.lengthBytes == CrewAuthorizedMediaSource.UNKNOWN_LENGTH ||
                source.lengthBytes in 1..CREW_MEDIA_MAX_OBJECT_BYTES
        ) {
            "Media source is outside Crew bounds"
        }
        val declaredLength = source.lengthBytes.takeIf { it > 0L }
        val objectDigest = MessageDigest.getInstance("SHA-256")
        val descriptors = mutableListOf<CrewMediaChunkDescriptor>()
        var observed = 0L
        source.open().use { input ->
            val buffer = ByteArray(CREW_MEDIA_MAX_CHUNK_BYTES)
            while (declaredLength == null || observed < declaredLength) {
                require(descriptors.size < CREW_MEDIA_MAX_CHUNKS) {
                    "Media source exceeded Crew bounds"
                }
                val wanted =
                    declaredLength?.let { minOf(buffer.size.toLong(), it - observed).toInt() }
                        ?: buffer.size
                val size = input.readFullyOrEnd(buffer, wanted)
                if (size == 0) break
                if (declaredLength != null) {
                    require(size == wanted) { "Media source ended early" }
                }
                val payload = if (size == buffer.size) buffer else buffer.copyOf(size)
                objectDigest.update(payload, 0, size)
                descriptors += CrewMediaChunkDescriptor(descriptors.size, size)
                observed += size
                if (declaredLength == null && size < wanted) break
            }
            require(observed > 0L) { "Media source is empty" }
            require(observed <= CREW_MEDIA_MAX_OBJECT_BYTES) { "Media source exceeded Crew bounds" }
            declaredLength?.let { require(observed == it) { "Media source ended early" } }
            require(input.read() == -1) { "Media source exceeded declared bound" }
        }
        require(descriptors.size in 1..CREW_MEDIA_MAX_CHUNKS)
        return CrewMediaManifest(
            transfer,
            source.mimeType,
            observed,
            CrewMediaDigest(objectDigest.digest()),
            descriptors,
        )
    }

    fun open(manifest: CrewMediaManifest, source: CrewAuthorizedMediaSource, startIndex: Int = 0) =
        CrewMediaChunkReader(manifest, source, startIndex)

    /** Compatibility helper for small pure tests; production uses [describe] plus [open]. */
    fun produce(
        transfer: CrewMediaTransferRef,
        source: CrewAuthorizedMediaSource,
    ): Pair<CrewMediaManifest, List<CrewMediaChunk>> {
        val manifest = describe(transfer, source)
        return manifest to
            open(manifest, source).use { reader -> generateSequence(reader::next).toList() }
    }
}

/** Re-opens the source after manifest hashing and retains only the current chunk. */
class CrewMediaChunkReader
internal constructor(
    private val manifest: CrewMediaManifest,
    source: CrewAuthorizedMediaSource,
    startIndex: Int,
) : Closeable {
    private val input = source.open()
    private var nextIndex = startIndex
    private var closed = false
    private val observedIntegrity = MessageDigest.getInstance("SHA-256")
    private val verifiesWholeObject = startIndex == 0

    init {
        require(startIndex in 0..manifest.chunks.size)
        var remaining = manifest.chunks.take(startIndex).sumOf { it.sizeBytes.toLong() }
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                require(input.read() >= 0) { "Media source ended before resume range" }
                remaining -= 1
            }
        }
    }

    fun next(): CrewMediaChunk? {
        check(!closed) { "Crew media reader is closed" }
        val descriptor = manifest.chunks.getOrNull(nextIndex) ?: return null
        val payload = ByteArray(descriptor.sizeBytes)
        require(input.readFullyOrEnd(payload, payload.size) == payload.size) {
            "Media source ended during transfer"
        }
        if (verifiesWholeObject) observedIntegrity.update(payload)
        nextIndex += 1
        if (nextIndex == manifest.chunks.size) {
            require(input.read() == -1) { "Media source grew during transfer" }
            if (verifiesWholeObject) {
                require(CrewMediaDigest(observedIntegrity.digest()) == manifest.objectIntegrity) {
                    "Media source changed after authorization"
                }
            }
        }
        return CrewMediaChunk(
            manifest.transfer,
            manifest.objectIntegrity,
            descriptor.index,
            payload,
        )
    }

    override fun close() {
        if (!closed) {
            closed = true
            input.close()
        }
    }
}

private fun InputStream.readFullyOrEnd(buffer: ByteArray, wanted: Int): Int {
    var offset = 0
    while (offset < wanted) {
        val read = read(buffer, offset, wanted - offset)
        if (read < 0) break
        if (read == 0) {
            val byte = read()
            if (byte < 0) break
            buffer[offset++] = byte.toByte()
        } else {
            offset += read
        }
    }
    return offset
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

    @Synchronized fun accepts(sessionId: CrewSessionId) = activeSessionId == sessionId && enabled
}
