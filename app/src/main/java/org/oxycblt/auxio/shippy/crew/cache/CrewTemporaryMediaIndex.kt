/*
 * Copyright (c) 2026 Auxio Project
 * CrewTemporaryMediaIndex.kt is part of Auxio.
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
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.media.CrewMediaManifest
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.MediaDescriptor
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.TrackCandidate

/**
 * Active-session-only local lookup for completed Crew media. It never alters canonical Crew state.
 */
@Singleton
class CrewTemporaryMediaIndex @Inject constructor() {
    private var activeSessionId: CrewSessionId? = null
    private val entries = mutableMapOf<Key, Entry>()
    private val entriesByToken = mutableMapOf<String, Entry>()
    private var deferredCleanup: (() -> Unit)? = null
    private val mutableCompletions =
        MutableSharedFlow<CrewTemporaryMediaCompletion>(extraBufferCapacity = 16)
    val completions: SharedFlow<CrewTemporaryMediaCompletion> = mutableCompletions.asSharedFlow()
    private val mutablePlayable =
        MutableSharedFlow<CrewTemporaryMediaCompletion>(extraBufferCapacity = 16)
    val playable: SharedFlow<CrewTemporaryMediaCompletion> = mutablePlayable.asSharedFlow()

    @Synchronized
    fun beginSession(sessionId: CrewSessionId) {
        check(activeSessionId == null || activeSessionId == sessionId) {
            "Previous Crew media session was not ended"
        }
        check(entries.isEmpty()) { "Previous Crew media readers have not released" }
        activeSessionId = sessionId
        entries.clear()
        entriesByToken.clear()
        deferredCleanup = null
    }

    /**
     * Publishes only the verified, contiguous prefix of an in-progress object. The backing file is
     * pre-sized by the disk assembler, so [contiguousBytes] is the authority; file length is not.
     */
    @Synchronized
    fun progress(manifest: CrewMediaManifest, file: File, contiguousBytes: Long): Boolean {
        if (activeSessionId != manifest.sessionId || !file.isFile) return false
        if (contiguousBytes !in 0..manifest.objectSizeBytes) return false
        val key = Key(manifest.transfer.queueItemId, manifest.candidateId)
        val token = temporaryToken(manifest.sessionId, key.queueItemId, key.candidateId)
        val entry =
            entries.getOrPut(key) {
                Entry(manifest, file, token).also { entriesByToken[token] = it }
            }
        val becamePlayable =
            synchronized(entry) {
                if (entry.manifest != manifest || entry.retired) return false
                entry.file = file
                val becamePlayable =
                    !entry.playable &&
                        contiguousBytes >= playableThreshold(manifest.objectSizeBytes)
                entry.contiguousBytes = maxOf(entry.contiguousBytes, contiguousBytes)
                if (becamePlayable) entry.playable = true
                entry.signalProgress()
                becamePlayable
            }
        if (becamePlayable) {
            mutablePlayable.tryEmit(
                CrewTemporaryMediaCompletion(
                    manifest.sessionId,
                    manifest.transfer.queueItemId,
                    manifest.candidateId,
                )
            )
        }
        return true
    }

    @Synchronized
    fun complete(manifest: CrewMediaManifest, file: File): Boolean {
        if (
            activeSessionId != manifest.sessionId ||
                !file.isFile ||
                file.length() != manifest.objectSizeBytes
        ) {
            return false
        }
        val key = Key(manifest.transfer.queueItemId, manifest.candidateId)
        val token = temporaryToken(manifest.sessionId, key.queueItemId, key.candidateId)
        val entry =
            entries.getOrPut(key) {
                Entry(manifest, file, token).also { entriesByToken[token] = it }
            }
        val becamePlayable =
            synchronized(entry) {
                if (entry.manifest != manifest || entry.retired) return false
                val becamePlayable = !entry.playable
                entry.file = file
                entry.contiguousBytes = manifest.objectSizeBytes
                entry.complete = true
                entry.playable = true
                entry.signalProgress()
                becamePlayable
            }
        if (becamePlayable) {
            mutablePlayable.tryEmit(
                CrewTemporaryMediaCompletion(
                    manifest.sessionId,
                    manifest.transfer.queueItemId,
                    manifest.candidateId,
                )
            )
        }
        mutableCompletions.tryEmit(
            CrewTemporaryMediaCompletion(
                manifest.sessionId,
                manifest.transfer.queueItemId,
                manifest.candidateId,
            )
        )
        return true
    }

    /**
     * Returns an exact active-session object only while its verified private file remains valid.
     * This is intentionally an in-process media boundary; callers must not serialize the file.
     */
    @Synchronized
    fun findActive(
        sessionId: CrewSessionId,
        queueItemId: QueueItemId,
        originalCandidateId: CandidateId,
    ): CrewTemporaryMediaEntry? {
        if (activeSessionId != sessionId) return null
        val entry = entries[Key(queueItemId, originalCandidateId)] ?: return null
        val manifest = entry.manifest
        if (
            !entry.complete ||
                entry.retired ||
                !entry.file.isFile ||
                entry.file.length() != manifest.objectSizeBytes
        ) {
            return null
        }
        return CrewTemporaryMediaEntry(
            entry.file,
            temporaryLocator(entry.token),
            manifest.mimeType,
            manifest.objectSizeBytes,
        )
    }

    @Synchronized
    fun augment(sessionId: CrewSessionId, item: QueueItem): QueueItem? {
        if (activeSessionId != sessionId) return null
        // A queue item may have several canonical candidates. Select one local overlay
        // deterministically; the transfer itself remains keyed by the exact candidate.
        val matched =
            entries.entries
                .asSequence()
                .filter { (key, value) ->
                    key.queueItemId == item.id &&
                        item.track.candidates.any { it.id == key.candidateId } &&
                        value.playable &&
                        !value.retired &&
                        value.file.isFile &&
                        value.contiguousBytes > 0
                }
                .sortedBy { (key, _) -> key.candidateId.value }
                .map { it.value }
                .firstOrNull() ?: return null
        val candidate =
            TrackCandidate(
                id = temporaryCandidateId(sessionId, item.id, matched.manifest.candidateId),
                trackId = item.track.id,
                kind = CandidateKind.CREW_TEMPORARY,
                sourceId = "crew-temporary",
                sourceItemId = matched.manifest.candidateId.value,
                availability = CandidateAvailability.AVAILABLE,
                locator = temporaryLocator(matched.token),
                media =
                    MediaDescriptor(
                        mimeType = matched.manifest.mimeType,
                        contentLength = matched.manifest.objectSizeBytes,
                    ),
            )
        return item.copy(
            track =
                item.track.copy(
                    candidates =
                        item.track.candidates.filterNot {
                            it.kind == CandidateKind.CREW_TEMPORARY
                        } + candidate
                )
        )
    }

    /**
     * Applies the current active-session overlay without requiring a playback resolver to know it.
     */
    @Synchronized
    fun augmentActive(item: QueueItem): QueueItem? = activeSessionId?.let { augment(it, item) }

    @Synchronized
    fun endSession(sessionId: CrewSessionId, cleanup: () -> Unit = {}) {
        if (activeSessionId == sessionId) {
            activeSessionId = null
            deferredCleanup = cleanup
            entries.values.forEach { entry ->
                synchronized(entry) {
                    entry.retired = true
                    entry.signalProgress()
                }
            }
            cleanupIfReleased()
        }
    }

    /** Acquires one reader pin for Media3. The locator contains no path or session secret. */
    @Synchronized
    internal fun acquire(locator: String): CrewTemporaryMediaLease? {
        val token = tokenFromLocator(locator) ?: return null
        val entry = entriesByToken[token]?.takeIf { it.playable && !it.retired } ?: return null
        val file = entry.file.takeIf(File::isFile) ?: return null
        entry.readers += 1
        return try {
            CrewTemporaryMediaLease(entry, RandomAccessFile(file, "r"), ::release)
        } catch (error: Exception) {
            entry.readers -= 1
            cleanupIfReleased()
            throw error
        }
    }

    @Synchronized
    private fun release(entry: Entry) {
        check(entry.readers > 0)
        entry.readers -= 1
        cleanupIfReleased()
    }

    @Synchronized
    private fun cleanupIfReleased() {
        if (activeSessionId != null || entries.values.any { it.readers > 0 }) return
        entries.clear()
        entriesByToken.clear()
        deferredCleanup.also { deferredCleanup = null }?.invoke()
    }

    private fun temporaryCandidateId(
        sessionId: CrewSessionId,
        queueItemId: QueueItemId,
        candidateId: CandidateId,
    ): CandidateId {
        return CandidateId("crew-temporary:${temporaryToken(sessionId, queueItemId, candidateId)}")
    }

    private fun temporaryToken(
        sessionId: CrewSessionId,
        queueItemId: QueueItemId,
        candidateId: CandidateId,
    ): String {
        val input =
            "${sessionId.protocolVersion.value}:${sessionId.value}:${queueItemId.value}:${candidateId.value}"
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
    }

    private fun temporaryLocator(token: String) = "$CREW_TEMPORARY_SCHEME://$token"

    private fun tokenFromLocator(locator: String): String? {
        val prefix = "$CREW_TEMPORARY_SCHEME://"
        return locator
            .takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
    }

    private fun playableThreshold(objectSizeBytes: Long) =
        minOf(objectSizeBytes, CREW_PROGRESSIVE_MIN_BYTES)

    private data class Key(val queueItemId: QueueItemId, val candidateId: CandidateId)

    internal class Entry(val manifest: CrewMediaManifest, var file: File, val token: String) {
        var contiguousBytes = 0L
        var complete = false
        var playable = false
        var retired = false
        var readers = 0
        var progressSignal = CountDownLatch(1)

        fun signalProgress() {
            progressSignal.countDown()
            progressSignal = CountDownLatch(1)
        }
    }

    private companion object {
        const val CREW_PROGRESSIVE_MIN_BYTES = 256L * 1024L
    }
}

data class CrewTemporaryMediaCompletion(
    val sessionId: CrewSessionId,
    val queueItemId: QueueItemId,
    val candidateId: CandidateId,
)

/** Immutable metadata for an exact, verified, active temporary Crew object. */
data class CrewTemporaryMediaEntry
internal constructor(
    val file: File,
    val locator: String,
    val mimeType: String?,
    val lengthBytes: Long,
)

internal const val CREW_TEMPORARY_SCHEME = "shippy-crew-temp"

/** A reader pin over a growing verified Crew object. */
internal class CrewTemporaryMediaLease(
    private val entry: CrewTemporaryMediaIndex.Entry,
    private val file: RandomAccessFile,
    private val onClose: (CrewTemporaryMediaIndex.Entry) -> Unit,
) : AutoCloseable {
    val lengthBytes: Long = entry.manifest.objectSizeBytes
    private var closed = false

    fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            val waitFor =
                synchronized(entry) {
                    check(!closed) { "Crew media lease is closed" }
                    val available = entry.contiguousBytes - position
                    if (available > 0L) {
                        file.seek(position)
                        return file.read(target, offset, minOf(length.toLong(), available).toInt())
                    }
                    if (entry.complete || entry.retired) return -1
                    entry.progressSignal
                }
            waitFor.await(1, TimeUnit.SECONDS)
        }
    }

    override fun close() {
        synchronized(entry) {
            if (closed) return
            closed = true
            entry.progressSignal.countDown()
        }
        runCatching(file::close)
        onClose(entry)
    }
}
