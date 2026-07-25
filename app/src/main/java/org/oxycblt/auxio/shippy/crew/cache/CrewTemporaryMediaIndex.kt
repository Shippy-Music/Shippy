/* Copyright (c) 2026 Shippy contributors */
package org.oxycblt.auxio.shippy.crew.cache

import java.io.File
import java.security.MessageDigest
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.media.CrewMediaManifest
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.MediaDescriptor
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.TrackCandidate

/** Active-session-only local lookup for completed Crew media. It never alters canonical Crew state. */
class CrewTemporaryMediaIndex {
    private var activeSessionId: CrewSessionId? = null
    private val completed = mutableMapOf<Key, Entry>()

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
        return true
    }

    @Synchronized
    fun augment(sessionId: CrewSessionId, item: QueueItem): QueueItem? {
        if (activeSessionId != sessionId) return null
        // A queue item may have several canonical candidates. Select one local overlay
        // deterministically; the transfer itself remains keyed by the exact candidate.
        val matched = completed.entries
            .asSequence()
            .filter { (key, value) -> key.queueItemId == item.id && value.file.isFile }
            .sortedBy { (key, _) -> key.candidateId.value }
            .map { it.value }
            .firstOrNull() ?: return null
        val candidate = TrackCandidate(
            id = temporaryCandidateId(sessionId, item.id, matched.manifest.candidateId),
            trackId = item.track.id,
            kind = CandidateKind.CREW_TEMPORARY,
            sourceId = "crew-temporary",
            sourceItemId = matched.manifest.candidateId.value,
            availability = CandidateAvailability.AVAILABLE,
            locator = matched.file.toURI().toString(),
            media = MediaDescriptor(mimeType = matched.manifest.mimeType, contentLength = matched.manifest.objectSizeBytes),
        )
        return item.copy(track = item.track.copy(candidates = item.track.candidates.filterNot { it.id == candidate.id } + candidate))
    }

    @Synchronized
    fun endSession(sessionId: CrewSessionId) {
        if (activeSessionId == sessionId) {
            activeSessionId = null
            completed.clear()
        }
    }

    private fun temporaryCandidateId(sessionId: CrewSessionId, queueItemId: QueueItemId, candidateId: CandidateId): CandidateId {
        val input = "${sessionId.protocolVersion.value}:${sessionId.value}:${queueItemId.value}:${candidateId.value}"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return CandidateId("crew-temporary:$digest")
    }

    private data class Key(val queueItemId: QueueItemId, val candidateId: CandidateId)
    private data class Entry(val manifest: CrewMediaManifest, val file: File)
}
