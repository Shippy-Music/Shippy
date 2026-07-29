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

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.media.CREW_MEDIA_MAX_OBJECT_BYTES
import org.oxycblt.auxio.shippy.crew.media.CrewMediaDigest
import org.oxycblt.auxio.shippy.crew.media.CrewMediaManifest

/**
 * Private, bounded session storage compatible with Android API 24. Files are never catalogued as
 * library media and are removed at session end. [beginSession] removes crash leftovers first.
 */
class CrewTemporaryMediaCache(
    private val root: File,
    private val maxBytes: Long = CREW_MEDIA_MAX_OBJECT_BYTES,
) {
    private var sessionId: CrewSessionId? = null
    private var usedBytes = 0L

    init {
        require(maxBytes in 1..CREW_MEDIA_MAX_OBJECT_BYTES) { "Invalid Crew cache limit" }
    }

    @Synchronized
    fun beginSession(session: CrewSessionId) {
        require(root.exists() || root.mkdirs()) { "Cannot create Crew cache" }
        val previous = requireNotNull(root.listFiles()) { "Cannot inspect Crew cache" }
        previous.forEach(::deleteRecursively)
        sessionId = session
        usedBytes = 0
        require(sessionPath(session).mkdirs() || sessionPath(session).isDirectory) {
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
        require(usedBytes - replaced + bytes.size <= maxBytes) { "Crew cache capacity exceeded" }
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
        require(usedBytes - replaced + total <= maxBytes) { "Crew cache capacity exceeded" }
        val digest = MessageDigest.getInstance("SHA-256")
        partial.delete()
        try {
            FileOutputStream(partial).use { output ->
                chunks.forEachIndexed { index, bytes ->
                    require(CrewMediaDigest.sha256(bytes) == manifest.chunks[index].integrity) {
                        "Temporary media chunk integrity mismatch"
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

    @Synchronized
    fun endSession(session: CrewSessionId) {
        if (sessionId == session) {
            deleteRecursively(sessionPath(session))
            sessionId = null
            usedBytes = 0
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
}
