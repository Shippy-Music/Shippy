/*
 * Copyright (c) 2026 Auxio Project
 * CrewTemporaryMediaCache.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.cache

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.media.CREW_MEDIA_MAX_OBJECT_BYTES
import org.oxycblt.auxio.shippy.crew.media.CREW_MEDIA_SESSION_CACHE_BYTES
import org.oxycblt.auxio.shippy.crew.media.CrewMediaDigest
import org.oxycblt.auxio.shippy.crew.media.CrewMediaManifest

/**
 * Private, bounded session storage compatible with Android API 24. Files are never catalogued as
 * library media and are removed at session end. [beginSession] removes crash leftovers first.
 */
class CrewTemporaryMediaCache(
    private val root: File,
    private val maxBytes: Long = CREW_MEDIA_SESSION_CACHE_BYTES,
) {
    private var sessionId: CrewSessionId? = null
    private var usedBytes = 0L
    private var reservedBytes = 0L
    private val assemblies = mutableSetOf<CrewTemporaryMediaAssembly>()

    init {
        require(maxBytes in 1..CREW_MEDIA_SESSION_CACHE_BYTES) { "Invalid Crew cache limit" }
    }

    @Synchronized
    fun beginSession(session: CrewSessionId) {
        assemblies.toList().forEach(CrewTemporaryMediaAssembly::abort)
        require(root.exists() || root.mkdirs()) { "Cannot create Crew cache" }
        val previous = requireNotNull(root.listFiles()) { "Cannot inspect Crew cache" }
        val activePath = sessionPath(session)
        previous.filter { it != activePath }.forEach(::deleteRecursively)
        sessionId = session
        usedBytes =
            activePath.listFiles()?.filter { it.extension == "media" }?.sumOf(File::length) ?: 0
        reservedBytes = 0
        require(activePath.mkdirs() || activePath.isDirectory) {
            "Cannot create Crew session cache"
        }
    }

    @Synchronized
    fun put(manifest: CrewMediaManifest, bytes: ByteArray): File {
        require(sessionId == manifest.sessionId) {
            "Temporary media is only valid for the active Crew"
        }
        require(bytes.size.toLong() == manifest.objectSizeBytes) { "Temporary media size mismatch" }
        require(CrewMediaDigest.sha256(bytes) == manifest.objectIntegrity) {
            "Temporary media integrity mismatch"
        }
        val target = objectFile(manifest)
        val replaced = if (target.exists()) target.length() else 0L
        require(usedBytes + reservedBytes - replaced + bytes.size <= maxBytes) {
            "Crew cache capacity exceeded"
        }
        target.outputStream().use { it.write(bytes) }
        usedBytes = usedBytes - replaced + bytes.size
        return target
    }

    /** Writes an assembled object once without allocating a second full-size byte array. */
    @Synchronized
    fun put(manifest: CrewMediaManifest, chunks: List<ByteArray>): File {
        require(sessionId == manifest.sessionId) {
            "Temporary media is only valid for the active Crew"
        }
        require(chunks.size == manifest.chunks.size) { "Temporary media chunk count mismatch" }
        val total = chunks.sumOf { it.size.toLong() }
        require(total == manifest.objectSizeBytes) { "Temporary media size mismatch" }
        val target = objectFile(manifest)
        if (target.isFile && target.length() == total) return target
        val partial = File(target.parentFile, "${target.name}.partial")
        val replaced = if (target.exists()) target.length() else 0L
        require(usedBytes + reservedBytes - replaced + total <= maxBytes) {
            "Crew cache capacity exceeded"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        partial.delete()
        try {
            FileOutputStream(partial).use { output ->
                chunks.forEachIndexed { index, bytes ->
                    require(bytes.size == manifest.chunks[index].sizeBytes) {
                        "Temporary media chunk size mismatch"
                    }
                    output.write(bytes)
                    digest.update(bytes)
                }
                output.fd.sync()
            }
            require(
                CrewMediaDigest(digest.digest()) == manifest.objectIntegrity &&
                    partial.length() == total
            ) {
                "Temporary media integrity mismatch"
            }
            require(partial.renameTo(target)) { "Cannot publish Crew media cache entry" }
            usedBytes = usedBytes - replaced + total
            return target
        } finally {
            partial.delete()
        }
    }

    @Synchronized
    fun read(session: CrewSessionId, integrity: CrewMediaDigest): ByteArray? {
        if (session != sessionId) return null
        val file = objectFile(session, integrity)
        if (!file.isFile) return null
        val bytes = file.readBytes()
        return bytes.takeIf { CrewMediaDigest.sha256(it) == integrity }
    }

    /** Opens one bounded partial file; received chunks never accumulate in the Java heap. */
    @Synchronized
    fun openAssembly(manifest: CrewMediaManifest): CrewTemporaryMediaAssembly {
        require(sessionId == manifest.sessionId) {
            "Temporary media is only valid for the active Crew"
        }
        require(manifest.objectSizeBytes in 1..CREW_MEDIA_MAX_OBJECT_BYTES)
        val target = objectFile(manifest)
        val replaced = if (target.exists()) target.length() else 0L
        require(usedBytes + reservedBytes - replaced + manifest.objectSizeBytes <= maxBytes) {
            "Crew cache capacity exceeded"
        }
        reservedBytes += manifest.objectSizeBytes
        val requestKey =
            CrewMediaDigest.sha256(manifest.transfer.requestId.value.toByteArray()).toString()
        val partial = File(target.parentFile, "${target.name}.$requestKey.partial")
        val progress = File(target.parentFile, "${partial.name}.resume")
        val received =
            loadProgress(manifest, partial, progress) ?: BooleanArray(manifest.chunks.size)
        if (received.none { it }) {
            partial.delete()
            progress.delete()
        }
        return CrewTemporaryMediaAssembly(
                this,
                manifest,
                target,
                partial,
                progress,
                replaced,
                received,
            )
            .also { assemblies += it }
    }

    @Synchronized
    private fun publish(assembly: CrewTemporaryMediaAssembly): File {
        return synchronized(assembly) {
            check(assembly.owner === this && !assembly.terminal)
            val manifest = assembly.manifest
            assembly.finishOutput()
            val digest = MessageDigest.getInstance("SHA-256")
            assembly.partial.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            require(
                assembly.isComplete &&
                    assembly.writtenBytes == manifest.objectSizeBytes &&
                    CrewMediaDigest(digest.digest()) == manifest.objectIntegrity &&
                    assembly.partial.length() == manifest.objectSizeBytes
            ) {
                "Temporary media integrity mismatch"
            }
            if (assembly.target.exists()) {
                require(assembly.target.delete()) { "Cannot replace Crew media" }
            }
            require(assembly.partial.renameTo(assembly.target)) {
                "Cannot publish Crew media cache entry"
            }
            assembly.progress.delete()
            reservedBytes -= manifest.objectSizeBytes
            usedBytes = usedBytes - assembly.replacedBytes + manifest.objectSizeBytes
            assemblies -= assembly
            assembly.terminal = true
            assembly.target
        }
    }

    @Synchronized
    private fun abandon(assembly: CrewTemporaryMediaAssembly) {
        synchronized(assembly) {
            if (assembly.owner !== this || assembly.terminal) return
            assembly.closeOutput()
            assembly.partial.delete()
            assembly.progress.delete()
            reservedBytes -= assembly.manifest.objectSizeBytes
            assemblies -= assembly
            assembly.terminal = true
        }
    }

    @Synchronized
    fun endSession(session: CrewSessionId) {
        if (sessionId == session) {
            assemblies.toList().forEach(CrewTemporaryMediaAssembly::abort)
            deleteRecursively(sessionPath(session))
            sessionId = null
            usedBytes = 0
            reservedBytes = 0
        }
    }

    private fun objectFile(manifest: CrewMediaManifest) =
        objectFile(manifest.sessionId, manifest.objectIntegrity)

    private fun objectFile(session: CrewSessionId, integrity: CrewMediaDigest) =
        File(sessionPath(session), "${integrity}.media")

    private fun sessionPath(session: CrewSessionId) =
        File(
            root,
            CrewMediaDigest.sha256(
                    "${session.protocolVersion.value}:${session.value}".toByteArray()
                )
                .toString(),
        )

    private fun deleteRecursively(file: File) {
        file.listFiles()?.forEach(::deleteRecursively)
        require(!file.exists() || file.delete()) { "Cannot clear Crew cache" }
    }

    private fun loadProgress(
        manifest: CrewMediaManifest,
        partial: File,
        progress: File,
    ): BooleanArray? =
        runCatching {
                if (
                    !partial.isFile ||
                        partial.length() != manifest.objectSizeBytes ||
                        !progress.isFile
                ) {
                    return@runCatching null
                }
                DataInputStream(progress.inputStream().buffered()).use { input ->
                    require(input.readInt() == PROGRESS_MAGIC)
                    require(input.readLong() == manifest.objectSizeBytes)
                    require(input.readInt() == manifest.chunks.size)
                    val received = BooleanArray(manifest.chunks.size) { input.readBoolean() }
                    require(input.read() == -1)
                    received
                }
            }
            .getOrNull()

    companion object {
        private const val PROGRESS_MAGIC = 0x53485052
    }

    class CrewTemporaryMediaAssembly
    internal constructor(
        internal val owner: CrewTemporaryMediaCache,
        internal val manifest: CrewMediaManifest,
        internal val target: File,
        internal val partial: File,
        internal val progress: File,
        internal val replacedBytes: Long,
        initialReceived: BooleanArray,
    ) {
        private val output =
            RandomAccessFile(partial, "rw").also { it.setLength(manifest.objectSizeBytes) }
        private val received = initialReceived.copyOf()
        internal var writtenBytes =
            manifest.chunks.indices
                .filter { received[it] }
                .sumOf { manifest.chunks[it].sizeBytes.toLong() }
        internal var terminal = false
        private var outputClosed = false
        internal val isComplete
            get() = received.all { it }

        internal val nextMissingIndex
            get() = received.indexOfFirst { !it }.takeIf { it >= 0 } ?: received.size

        internal val contiguousVerifiedBytes: Long
            get() = manifest.chunks.take(nextMissingIndex).sumOf { it.sizeBytes.toLong() }

        internal val partialFile: File
            get() = partial

        @Synchronized
        fun append(index: Int, bytes: ByteArray) {
            check(!terminal && !outputClosed) { "Temporary media assembly is closed" }
            val descriptor = manifest.chunks.getOrNull(index) ?: error("Unknown media chunk")
            require(bytes.size == descriptor.sizeBytes) {
                "Temporary media chunk integrity mismatch"
            }
            if (received[index]) return
            val offset = manifest.chunks.take(index).sumOf { it.sizeBytes.toLong() }
            output.seek(offset)
            output.write(bytes)
            received[index] = true
            writtenBytes += bytes.size
            persistProgress()
        }

        fun commit(): File = owner.publish(this)

        fun abort() = owner.abandon(this)

        internal fun finishOutput() {
            if (!outputClosed) {
                output.fd.sync()
                output.close()
                outputClosed = true
            }
        }

        internal fun closeOutput() {
            if (!outputClosed) {
                runCatching { output.close() }
                outputClosed = true
            }
        }

        private fun persistProgress() {
            val staged = File(progress.parentFile, "${progress.name}.new")
            try {
                FileOutputStream(staged).use { file ->
                    val output = DataOutputStream(file.buffered())
                    output.writeInt(PROGRESS_MAGIC)
                    output.writeLong(manifest.objectSizeBytes)
                    output.writeInt(received.size)
                    received.forEach(output::writeBoolean)
                    output.flush()
                    file.fd.sync()
                }
                if (progress.exists()) require(progress.delete())
                require(staged.renameTo(progress)) { "Cannot checkpoint Crew media ranges" }
            } finally {
                staged.delete()
            }
        }
    }

    /**
     * Closes live file handles but retains verified partial ranges for an authenticated session
     * restore. Explicit leave/end must use [endSession] instead.
     */
    @Synchronized
    fun suspendSessionForRecovery(session: CrewSessionId) {
        if (sessionId != session) return
        assemblies.toList().forEach { assembly ->
            assembly.closeOutput()
            assembly.terminal = true
        }
        assemblies.clear()
        sessionId = null
        reservedBytes = 0
    }
}
