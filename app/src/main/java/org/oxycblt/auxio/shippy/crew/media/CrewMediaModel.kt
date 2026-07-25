/*
 * Copyright (c) 2026 Shippy contributors
 * CrewMediaModel.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.crew.media

import java.security.MessageDigest
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.domain.CandidateId

/** Wire bounds intentionally leave room below the 48 KiB Crew media-channel payload limit. */
const val CREW_MEDIA_MAX_CHUNK_BYTES = 44 * 1024
const val CREW_MEDIA_MAX_CHUNKS = 512
/** The manifest has no paging in this foundation, so this is the actual transferable-object cap. */
const val CREW_MEDIA_MAX_OBJECT_BYTES = 512L * CREW_MEDIA_MAX_CHUNK_BYTES
const val CREW_MEDIA_DIGEST_BYTES = 32

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
    val sessionId: CrewSessionId,
    val candidateId: CandidateId,
    val mimeType: String?,
    val objectSizeBytes: Long,
    val objectIntegrity: CrewMediaDigest,
    val chunks: List<CrewMediaChunkDescriptor>,
) {
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
    val sessionId: CrewSessionId,
    val objectIntegrity: CrewMediaDigest,
    val index: Int,
    payload: ByteArray,
) {
    private val bytes = payload.copyOf()

    init {
        require(index >= 0) { "Chunk index cannot be negative" }
        require(bytes.size in 1..CREW_MEDIA_MAX_CHUNK_BYTES) { "Chunk payload is outside bounds" }
    }

    val sizeBytes get() = bytes.size
    fun copyPayload() = bytes.copyOf()

    override fun equals(other: Any?) =
        other is CrewMediaChunk &&
            sessionId == other.sessionId &&
            objectIntegrity == other.objectIntegrity &&
            index == other.index &&
            bytes.contentEquals(other.bytes)

    override fun hashCode() =
        31 * (31 * (31 * sessionId.hashCode() + objectIntegrity.hashCode()) + index) +
            bytes.contentHashCode()
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
