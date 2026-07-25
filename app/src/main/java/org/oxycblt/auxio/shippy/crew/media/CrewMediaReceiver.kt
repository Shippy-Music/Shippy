/* Copyright (c) 2026 Shippy contributors */
package org.oxycblt.auxio.shippy.crew.media

import org.oxycblt.auxio.shippy.crew.cache.CrewTemporaryMediaCache
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId

data class CrewMediaReceiverPolicy(
    val maxAssemblies: Int = 2,
    val maxBufferedBytes: Long = CREW_MEDIA_MAX_OBJECT_BYTES,
) {
    init { require(maxAssemblies > 0 && maxBufferedBytes > 0) }
}

sealed interface CrewMediaReceiveResult {
    data object Accepted : CrewMediaReceiveResult
    data class Complete(val manifest: CrewMediaManifest) : CrewMediaReceiveResult
    data class Retry(val reason: String) : CrewMediaReceiveResult
    data class Rejected(val reason: String) : CrewMediaReceiveResult
}

/** Bounded receiver: out-of-window media requests retry; protocol corruption is rejected locally. */
class CrewMediaReceiver(
    private val activeSessionId: CrewSessionId,
    private val cache: CrewTemporaryMediaCache,
    private val policy: CrewMediaReceiverPolicy = CrewMediaReceiverPolicy(),
) {
    private val assemblies = linkedMapOf<CrewMediaTransferRef, Assembly>()
    private var reservedBytes = 0L
    private var bufferedBytes = 0L

    fun accept(manifest: CrewMediaManifest): CrewMediaReceiveResult {
        if (manifest.transfer.sessionId != activeSessionId) return CrewMediaReceiveResult.Rejected("wrong session")
        assemblies[manifest.transfer]?.let { existing ->
            return if (existing.manifest == manifest) {
                CrewMediaReceiveResult.Accepted
            } else {
                CrewMediaReceiveResult.Rejected("conflicting manifest")
            }
        }
        if (assemblies.size >= policy.maxAssemblies || reservedBytes + manifest.objectSizeBytes > policy.maxBufferedBytes) {
            return CrewMediaReceiveResult.Retry("receiver window full")
        }
        assemblies[manifest.transfer] = Assembly(manifest)
        reservedBytes += manifest.objectSizeBytes
        return CrewMediaReceiveResult.Accepted
    }

    fun accept(chunk: CrewMediaChunk): CrewMediaReceiveResult {
        if (chunk.transfer.sessionId != activeSessionId) return CrewMediaReceiveResult.Rejected("wrong session")
        val assembly = assemblies[chunk.transfer] ?: return CrewMediaReceiveResult.Retry("manifest required")
        if (assembly.manifest.objectIntegrity != chunk.objectIntegrity) {
            return CrewMediaReceiveResult.Rejected("wrong object")
        }
        val descriptor = assembly.manifest.chunks.getOrNull(chunk.index)
            ?: return CrewMediaReceiveResult.Rejected("unknown chunk")
        val payload = chunk.copyPayload()
        if (payload.size != descriptor.sizeBytes || CrewMediaDigest.sha256(payload) != descriptor.integrity) {
            return CrewMediaReceiveResult.Rejected("chunk integrity mismatch")
        }
        if (assembly.chunks.containsKey(chunk.index)) return CrewMediaReceiveResult.Accepted
        if (bufferedBytes + payload.size > policy.maxBufferedBytes) return CrewMediaReceiveResult.Retry("receiver window full")
        assembly.chunks[chunk.index] = payload
        bufferedBytes += payload.size
        if (assembly.chunks.size != assembly.manifest.chunks.size) return CrewMediaReceiveResult.Accepted
        val objectBytes = ByteArray(assembly.manifest.objectSizeBytes.toInt())
        var offset = 0
        assembly.manifest.chunks.indices.forEach { index ->
            val payload = assembly.chunks.getValue(index)
            payload.copyInto(objectBytes, offset)
            offset += payload.size
        }
        assemblies.remove(chunk.transfer)
        reservedBytes -= assembly.manifest.objectSizeBytes
        bufferedBytes -= objectBytes.size
        return if (CrewMediaDigest.sha256(objectBytes) != assembly.manifest.objectIntegrity) {
            CrewMediaReceiveResult.Rejected("object integrity mismatch")
        } else {
            runCatching { cache.put(assembly.manifest, objectBytes) }
                .fold(
                    onSuccess = { CrewMediaReceiveResult.Complete(assembly.manifest) },
                    onFailure = { CrewMediaReceiveResult.Retry("temporary cache unavailable") },
                )
        }
    }

    fun cancel(transfer: CrewMediaTransferRef): Boolean {
        val assembly = assemblies.remove(transfer) ?: return false
        reservedBytes -= assembly.manifest.objectSizeBytes
        bufferedBytes -= assembly.chunks.values.sumOf(ByteArray::size)
        return true
    }

    private class Assembly(val manifest: CrewMediaManifest) {
        val chunks = mutableMapOf<Int, ByteArray>()
    }
}
