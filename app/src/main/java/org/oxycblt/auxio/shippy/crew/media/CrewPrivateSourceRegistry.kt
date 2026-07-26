/*
 * Copyright (c) 2026 Shippy contributors
 * CrewPrivateSourceRegistry.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.crew.media

import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm

/** Ephemeral exact Local locators for the one active Crew, never durable queue state. */
@Singleton
class CrewPrivateSourceRegistry @Inject constructor() {
    private val lock = Any()
    private var activeSessionId: CrewSessionId? = null
    private val sources = LinkedHashMap<Key, Source>()

    fun beginSession(sessionId: CrewSessionId) = synchronized(lock) {
        if (activeSessionId != sessionId) {
            activeSessionId = sessionId
            sources.clear()
        }
    }

    fun endSession(sessionId: CrewSessionId) = synchronized(lock) {
        if (activeSessionId == sessionId) {
            activeSessionId = null
            sources.clear()
        }
    }

    fun captureAndPublicize(
        sessionId: CrewSessionId,
        memberId: CrewMemberId,
        action: CrewAction,
    ): CrewAction = synchronized(lock) {
        if (activeSessionId == sessionId) capture(memberId, action)
        publicizeCrewAction(action, memberId)
    }

    fun overlay(
        sessionId: CrewSessionId,
        memberId: CrewMemberId,
        item: QueueItem,
    ): QueueItem =
        synchronized(lock) {
            if (activeSessionId != sessionId || item.contributorId != memberId.value) {
                return item
            }
            item.copy(
                track =
                    item.track.copy(
                        candidates =
                            item.track.candidates.map { candidate ->
                                sources[Key(item.id, candidate.id)]
                                    ?.takeIf { source ->
                                        item.track.realm == TrackRealm.LOCAL &&
                                            candidate.kind == CandidateKind.LOCAL &&
                                            candidate.trackId == source.trackId &&
                                            candidate.sourceId == source.sourceId &&
                                            candidate.sourceItemId == source.sourceItemId
                                    }
                                    ?.let { source -> candidate.copy(locator = source.locator) }
                                    ?: candidate
                            }
                    )
            )
        }

    fun prune(sessionId: CrewSessionId, queue: List<QueueItem>) = synchronized(lock) {
        if (activeSessionId != sessionId) return@synchronized
        val retained = queue.flatMap { item -> item.track.candidates.map { Key(item.id, it.id) } }.toSet()
        sources.keys.retainAll(retained)
    }

    private fun capture(memberId: CrewMemberId, action: CrewAction) {
        val items =
            when (action) {
                is CrewAction.QueueReplaced -> {
                    val retained =
                        action.items
                            .flatMap { item ->
                                item.track.candidates.map { candidate ->
                                    Key(item.id, candidate.id)
                                }
                            }
                            .toSet()
                    sources.keys.retainAll(retained)
                    action.items
                }
                is CrewAction.QueueItemInserted -> listOf(action.item)
                else -> emptyList()
            }
        val publicItems = publicizeCrewQueue(items, memberId)
        items.zip(publicItems).forEach { (raw, publicItem) ->
            if (
                publicItem.contributorId != memberId.value ||
                    raw.track.realm != TrackRealm.LOCAL
            ) {
                return@forEach
            }
            raw.track.candidates
                .filter { it.kind == CandidateKind.LOCAL && isContentUri(it.locator) }
                .forEach { candidate ->
                    val key = Key(raw.id, candidate.id)
                    if (sources.size >= MAX_PRIVATE_SOURCES && key !in sources) {
                        return@forEach
                    }
                    sources[key] =
                        Source(
                            candidate.trackId,
                            candidate.sourceId,
                            candidate.sourceItemId,
                            requireNotNull(candidate.locator),
                        )
                }
        }
    }

    private data class Key(val itemId: QueueItemId, val candidateId: CandidateId)
    private data class Source(val trackId: TrackId, val sourceId: String, val sourceItemId: String, val locator: String)

    private companion object {
        const val MAX_PRIVATE_SOURCES = 10_000
    }
}

internal fun publicizeCrewAction(action: CrewAction, localMemberId: CrewMemberId? = null): CrewAction = when (action) {
    is CrewAction.QueueReplaced -> action.copy(items = publicizeCrewQueue(action.items, localMemberId))
    is CrewAction.QueueItemInserted -> action.copy(item = publicizeCrewQueueItem(action.item, localMemberId))
    else -> action
}

internal fun publicizeCrewQueue(items: List<QueueItem>, localMemberId: CrewMemberId? = null): List<QueueItem> =
    items.map { publicizeCrewQueueItem(it, localMemberId) }

internal fun publicizeCrewQueueItem(item: QueueItem, localMemberId: CrewMemberId? = null): QueueItem {
    val contributor =
        if (
            item.contributorId == null &&
                localMemberId != null &&
                item.track.realm == TrackRealm.LOCAL &&
                item.track.candidates.any { it.kind == CandidateKind.LOCAL }
        ) {
            localMemberId.value
        } else {
            item.contributorId
        }
    return item.copy(contributorId = contributor, track = publicizeCrewTrack(item.track))
}

internal fun publicizeCrewTrack(track: Track): Track =
    track.copy(
        artwork = track.artwork?.takeIf(::isPublicArtworkUri),
        candidates = track.candidates
            .filterNot { it.kind == CandidateKind.DOWNLOAD || it.kind == CandidateKind.CREW_TEMPORARY }
            .map { it.copy(locator = null) },
    )

private fun isContentUri(value: String?): Boolean =
    value?.let { raw ->
        runCatching {
                URI(raw).let {
                    it.scheme.equals("content", true) &&
                        !it.isOpaque &&
                        !it.authority.isNullOrBlank()
                }
            }
            .getOrDefault(false)
    } == true

private fun isPublicArtworkUri(value: String): Boolean = runCatching {
    URI(value).let {
        it.scheme.equals("https", true) &&
            !it.host.isNullOrBlank() &&
            it.userInfo == null &&
            it.rawQuery.isNullOrBlank() &&
            it.rawFragment.isNullOrBlank()
    }
}.getOrDefault(false)
