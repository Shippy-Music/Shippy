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
import java.security.MessageDigest
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
    private val completed = mutableMapOf<Key, Entry>()
    private val mutableCompletions =
        MutableSharedFlow<CrewTemporaryMediaCompletion>(extraBufferCapacity = 16)
    val completions: SharedFlow<CrewTemporaryMediaCompletion> = mutableCompletions.asSharedFlow()

    @Synchronized
    fun beginSession(sessionId: CrewSessionId) {
        activeSessionId = sessionId
        completed.clear()
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
        completed[Key(manifest.transfer.queueItemId, manifest.candidateId)] = Entry(manifest, file)
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
        val entry = completed[Key(queueItemId, originalCandidateId)] ?: return null
        val manifest = entry.manifest
        if (!entry.file.isFile || entry.file.length() != manifest.objectSizeBytes) return null
        return CrewTemporaryMediaEntry(entry.file, manifest.mimeType, manifest.objectSizeBytes)
    }

    @Synchronized
    fun augment(sessionId: CrewSessionId, item: QueueItem): QueueItem? {
        if (activeSessionId != sessionId) return null
        // A queue item may have several canonical candidates. Select one local overlay
        // deterministically; the transfer itself remains keyed by the exact candidate.
        val matched =
            completed.entries
                .asSequence()
                .filter { (key, value) ->
                    key.queueItemId == item.id &&
                        item.track.candidates.any { it.id == key.candidateId } &&
                        value.file.isFile &&
                        value.file.length() == value.manifest.objectSizeBytes
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
                locator = matched.file.toURI().toString(),
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
    fun endSession(sessionId: CrewSessionId) {
        if (activeSessionId == sessionId) {
            activeSessionId = null
            completed.clear()
        }
    }

    private fun temporaryCandidateId(
        sessionId: CrewSessionId,
        queueItemId: QueueItemId,
        candidateId: CandidateId,
    ): CandidateId {
        val input =
            "${sessionId.protocolVersion.value}:${sessionId.value}:${queueItemId.value}:${candidateId.value}"
        val digest =
            MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        return CandidateId("crew-temporary:$digest")
    }

    private data class Key(val queueItemId: QueueItemId, val candidateId: CandidateId)

    private data class Entry(val manifest: CrewMediaManifest, val file: File)
}

data class CrewTemporaryMediaCompletion(
    val sessionId: CrewSessionId,
    val queueItemId: QueueItemId,
    val candidateId: CandidateId,
)

/** Immutable metadata for an exact, verified, active temporary Crew object. */
data class CrewTemporaryMediaEntry
internal constructor(val file: File, val mimeType: String?, val lengthBytes: Long)
