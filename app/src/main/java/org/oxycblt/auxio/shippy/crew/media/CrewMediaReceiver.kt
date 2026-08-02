/*
 * Copyright (c) 2026 Auxio Project
 * CrewMediaReceiver.kt is part of Auxio.
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

import org.oxycblt.auxio.shippy.crew.cache.CrewTemporaryMediaCache
import org.oxycblt.auxio.shippy.crew.cache.CrewTemporaryMediaCache.CrewTemporaryMediaAssembly
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId

data class CrewMediaReceiverPolicy(
    val maxAssemblies: Int = 2,
    val maxBufferedBytes: Long = CREW_MEDIA_SESSION_CACHE_BYTES,
) {
    init {
        require(maxAssemblies > 0 && maxBufferedBytes > 0)
    }
}

sealed interface CrewMediaReceiveResult {
    class Accepted(val nextChunkIndex: Int = 0, val progress: CrewMediaReceiveProgress? = null) :
        CrewMediaReceiveResult {
        override fun equals(other: Any?) =
            other is Accepted && nextChunkIndex == other.nextChunkIndex

        override fun hashCode() = nextChunkIndex

        override fun toString() = "Accepted(nextChunkIndex=$nextChunkIndex)"
    }

    data class Complete(val manifest: CrewMediaManifest, val file: java.io.File) :
        CrewMediaReceiveResult

    data class Retry(val reason: String) : CrewMediaReceiveResult

    data class Rejected(val reason: String) : CrewMediaReceiveResult
}

data class CrewMediaReceiveProgress(
    val manifest: CrewMediaManifest,
    val file: java.io.File,
    val contiguousBytes: Long,
)

/**
 * Bounded receiver: out-of-window media requests retry; protocol corruption is rejected locally.
 */
class CrewMediaReceiver(
    private val activeSessionId: CrewSessionId,
    private val cache: CrewTemporaryMediaCache,
    private val policy: CrewMediaReceiverPolicy = CrewMediaReceiverPolicy(),
) {
    private val assemblies = linkedMapOf<CrewMediaTransferRef, Assembly>()
    private var reservedBytes = 0L

    @Synchronized
    fun accept(manifest: CrewMediaManifest): CrewMediaReceiveResult {
        if (manifest.transfer.sessionId != activeSessionId)
            return CrewMediaReceiveResult.Rejected("wrong session")
        assemblies[manifest.transfer]?.let { existing ->
            return if (existing.manifest == manifest) {
                CrewMediaReceiveResult.Accepted(
                    existing.writer.nextMissingIndex,
                    existing.writer.progress(),
                )
            } else {
                CrewMediaReceiveResult.Rejected("conflicting manifest")
            }
        }
        if (
            assemblies.size >= policy.maxAssemblies ||
                reservedBytes + manifest.objectSizeBytes > policy.maxBufferedBytes
        ) {
            return CrewMediaReceiveResult.Retry("receiver window full")
        }
        val writer =
            runCatching { cache.openAssembly(manifest) }
                .getOrElse {
                    return CrewMediaReceiveResult.Retry("temporary cache unavailable")
                }
        assemblies[manifest.transfer] = Assembly(manifest, writer)
        reservedBytes += manifest.objectSizeBytes
        return CrewMediaReceiveResult.Accepted(writer.nextMissingIndex, writer.progress())
    }

    @Synchronized
    fun accept(chunk: CrewMediaChunk): CrewMediaReceiveResult {
        if (chunk.transfer.sessionId != activeSessionId)
            return CrewMediaReceiveResult.Rejected("wrong session")
        val assembly =
            assemblies[chunk.transfer] ?: return CrewMediaReceiveResult.Retry("manifest required")
        if (assembly.manifest.objectIntegrity != chunk.objectIntegrity) {
            return CrewMediaReceiveResult.Rejected("wrong object")
        }
        val descriptor =
            assembly.manifest.chunks.getOrNull(chunk.index)
                ?: return CrewMediaReceiveResult.Rejected("unknown chunk")
        val payload = chunk.copyPayload()
        if (
            payload.size != descriptor.sizeBytes ||
                CrewMediaDigest.sha256(payload) != chunk.chunkIntegrity
        ) {
            return CrewMediaReceiveResult.Rejected("chunk integrity mismatch")
        }
        if (!assembly.writer.isComplete) {
            val appended = runCatching { assembly.writer.append(chunk.index, payload) }
            if (appended.isFailure) {
                return CrewMediaReceiveResult.Retry("temporary cache unavailable")
            }
        }
        if (!assembly.writer.isComplete)
            return CrewMediaReceiveResult.Accepted(
                assembly.writer.nextMissingIndex,
                assembly.writer.progress(),
            )
        return runCatching { assembly.writer.commit() }
            .fold(
                onSuccess = { file ->
                    assemblies.remove(chunk.transfer)
                    reservedBytes -= assembly.manifest.objectSizeBytes
                    CrewMediaReceiveResult.Complete(assembly.manifest, file)
                },
                onFailure = {
                    // Keep the complete bounded assembly so an explicit retry can re-attempt the
                    // cache write without requiring the peer to restart with another manifest.
                    CrewMediaReceiveResult.Retry("temporary cache unavailable")
                },
            )
    }

    @Synchronized
    fun cancel(transfer: CrewMediaTransferRef): Boolean {
        val assembly = assemblies.remove(transfer) ?: return false
        reservedBytes -= assembly.manifest.objectSizeBytes
        assembly.writer.abort()
        return true
    }

    private class Assembly(val manifest: CrewMediaManifest, val writer: CrewTemporaryMediaAssembly)

    private fun CrewTemporaryMediaAssembly.progress() =
        CrewMediaReceiveProgress(manifest, partialFile, contiguousVerifiedBytes)
}
