/*
 * Copyright (c) 2026 Auxio Project
 * ProviderPlaybackLifecycle.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.playback

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.PlaybackPreparation
import org.oxycblt.auxio.shippy.domain.PlaybackResolutionCoordinator
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.ResolutionPolicy
import org.oxycblt.auxio.shippy.domain.ResolvedPlayback
import org.oxycblt.auxio.shippy.domain.ResolvedQueueItem
import org.oxycblt.auxio.shippy.provider.StreamConstraints

/**
 * Keeps an ordinary Shippy queue's logical occurrence list independent from ephemeral provider
 * locators. The player always receives one entry per [QueueItem]; entries that cannot currently
 * resolve are represented by a non-playable placeholder and are retried near playback.
 *
 * This class deliberately has no Crew behaviour. Crew owns its own projection and media leases.
 */
@Singleton
class ProviderPlaybackLifecycle
private constructor(
    private val resolutionCoordinator: PlaybackResolutionCoordinator,
    private val nowEpochMs: () -> Long,
) {
    @Inject
    constructor(
        resolutionCoordinator: PlaybackResolutionCoordinator
    ) : this(resolutionCoordinator, System::currentTimeMillis)

    internal constructor(
        resolutionCoordinator: PlaybackResolutionCoordinator,
        nowEpochMs: () -> Long,
        @Suppress("UNUSED_PARAMETER") testSeam: Unit = Unit,
    ) : this(resolutionCoordinator, nowEpochMs)

    private data class ActiveQueue(
        val generation: Long,
        val items: List<QueueItem>,
        val policy: ResolutionPolicy,
        val constraints: StreamConstraints,
        val resolved: MutableMap<QueueItemId, ResolvedQueueItem>,
    )

    private val mutex = Mutex()
    private var generation = 0L
    private var active: ActiveQueue? = null

    /**
     * Resolves the selected occurrence synchronously and only a bounded ordered lookahead. All
     * other logical occurrences are retained as placeholders instead of being dropped offline.
     */
    suspend fun prepareInitial(
        items: List<QueueItem>,
        selectedIndex: Int,
        policy: ResolutionPolicy,
        constraints: StreamConstraints,
    ): InitialPreparation {
        require(items.isNotEmpty()) { "Playback queue cannot be empty" }
        require(selectedIndex in items.indices) { "Selected queue index is out of bounds" }

        val selected = resolutionCoordinator.prepare(items[selectedIndex], policy, constraints)
        if (selected !is PlaybackPreparation.Ready) {
            return InitialPreparation.Failed(selected as PlaybackPreparation.Failed)
        }

        val seed = linkedMapOf(selected.value.item.id to selected.value)
        // Resolving this tiny window before Media3 starts covers normal next transitions without
        // coupling the queue's existence to a provider URL's lifetime.
        for (index in
            (selectedIndex + 1)..minOf(items.lastIndex, selectedIndex + LOOKAHEAD_ITEMS)) {
            when (val prepared = resolutionCoordinator.prepare(items[index], policy, constraints)) {
                is PlaybackPreparation.Ready -> seed[prepared.value.item.id] = prepared.value
                is PlaybackPreparation.Failed -> Unit
            }
        }

        val snapshot = activate(items, policy, constraints, seed)
        return InitialPreparation.Ready(snapshot, items[selectedIndex].id)
    }

    /**
     * Prepares a bounded leading window for an ordinary queue insertion without replacing the
     * existing active queue. Failed entries remain represented by placeholders.
     */
    suspend fun prepareAdditional(
        items: List<QueueItem>,
        policy: ResolutionPolicy,
        constraints: StreamConstraints,
    ): List<ResolvedQueueItem> {
        if (items.isEmpty()) return emptyList()
        val ready = linkedMapOf<QueueItemId, ResolvedQueueItem>()
        for (index in 0..minOf(items.lastIndex, LOOKAHEAD_ITEMS)) {
            when (val prepared = resolutionCoordinator.prepare(items[index], policy, constraints)) {
                is PlaybackPreparation.Ready -> ready[prepared.value.item.id] = prepared.value
                is PlaybackPreparation.Failed -> Unit
            }
        }
        return items.map { item -> ready[item.id] ?: item.placeholder() }
    }

    /**
     * Replaces the active logical queue after an ordinary local queue edit without dropping state.
     */
    suspend fun updateQueue(
        items: List<QueueItem>,
        policy: ResolutionPolicy,
        constraints: StreamConstraints,
        updates: List<ResolvedQueueItem> = emptyList(),
    ) {
        mutex.withLock {
            val current = active
            if (current == null) {
                generation += 1
                active =
                    ActiveQueue(
                        generation,
                        items,
                        policy,
                        constraints,
                        updates.associateByTo(linkedMapOf<QueueItemId, ResolvedQueueItem>()) {
                            it.item.id
                        },
                    )
                return
            }
            val itemIds = items.map(QueueItem::id).toSet()
            val surviving = current.resolved.filterKeys { it in itemIds }.toMutableMap()
            updates.filter { it.item.id in itemIds }.forEach { surviving[it.item.id] = it }
            active = current.copy(items = items, resolved = surviving)
        }
    }

    /**
     * Resolves the current entry when it is missing or within the refresh margin, then resolves a
     * bounded forward window. Returned updates are keyed by occurrence and can replace Media3 items
     * in place without changing queue order or identity.
     */
    suspend fun resolveNearPlayback(
        currentItemId: QueueItemId,
        orderedItemIds: List<QueueItemId>,
    ): List<ResolvedQueueItem> {
        val snapshot = mutex.withLock { active } ?: return emptyList()
        val currentIndex = orderedItemIds.indexOf(currentItemId)
        if (currentIndex < 0) return emptyList()
        val wanted =
            buildList {
                    add(currentItemId)
                    for (index in
                        (currentIndex + 1)..minOf(
                                orderedItemIds.lastIndex,
                                currentIndex + LOOKAHEAD_ITEMS,
                            )) {
                        add(orderedItemIds[index])
                    }
                }
                .distinct()
        return wanted.mapNotNull { itemId -> resolveIfNeeded(snapshot, itemId) }
    }

    /** Used by the narrow Media3 error hook to refresh the exact current provider locator once. */
    suspend fun refreshCurrent(itemId: QueueItemId): ResolvedQueueItem? {
        val snapshot = mutex.withLock { active } ?: return null
        return resolveIfNeeded(snapshot, itemId, force = true)
    }

    fun isManaged(itemId: QueueItemId): Boolean = active?.items?.any { it.id == itemId } == true

    fun requiresProviderRefresh(itemId: String): Boolean {
        val snapshot = active ?: return false
        val item = snapshot.items.firstOrNull { it.id.value == itemId } ?: return false
        val playback = snapshot.resolved[item.id]?.playback
        if (playback?.deferred == true) return true
        return item.track.candidates.any {
            it.id == playback?.candidateId && it.kind == CandidateKind.PROVIDER
        }
    }

    suspend fun refreshCurrent(itemId: String): ResolvedQueueItem? {
        val queueItemId =
            mutex.withLock { active?.items?.firstOrNull { it.id.value == itemId }?.id }
                ?: return null
        return refreshCurrent(queueItemId)
    }

    private suspend fun activate(
        items: List<QueueItem>,
        policy: ResolutionPolicy,
        constraints: StreamConstraints,
        resolved: Map<QueueItemId, ResolvedQueueItem>,
    ): List<ResolvedQueueItem> =
        mutex.withLock {
            generation += 1
            val current =
                ActiveQueue(generation, items, policy, constraints, resolved.toMutableMap())
            active = current
            items.map { item -> current.resolved[item.id] ?: item.placeholder() }
        }

    private suspend fun resolveIfNeeded(
        snapshot: ActiveQueue,
        itemId: QueueItemId,
        force: Boolean = false,
    ): ResolvedQueueItem? {
        val item = snapshot.items.firstOrNull { it.id == itemId } ?: return null
        val existing =
            mutex.withLock {
                active?.takeIf { it.generation == snapshot.generation }?.resolved?.get(itemId)
            }
        if (!force && existing != null && !existing.playback.needsRefresh(nowEpochMs())) return null

        val prepared = resolutionCoordinator.prepare(item, snapshot.policy, snapshot.constraints)
        val refreshed = (prepared as? PlaybackPreparation.Ready)?.value ?: return null
        return mutex.withLock {
            val current = active ?: return@withLock null
            if (
                current.generation != snapshot.generation || current.items.none { it.id == itemId }
            ) {
                return@withLock null
            }
            current.resolved[itemId] = refreshed
            refreshed
        }
    }

    private fun QueueItem.placeholder(): ResolvedQueueItem {
        val candidate =
            track.candidates.firstOrNull()
                ?: error(
                    "Queued track ${track.id} has no candidate to represent an unavailable occurrence"
                )
        return ResolvedQueueItem(
            item = this,
            playback =
                ResolvedPlayback(
                    queueItemId = id,
                    candidateId = candidate.id,
                    uri = "shippy-unresolved://${id.value}",
                    deferred = true,
                ),
        )
    }

    private fun ResolvedPlayback.needsRefresh(now: Long): Boolean =
        deferred || expiresAtEpochMs?.let { it - now <= URL_REFRESH_MARGIN_MS } == true

    sealed interface InitialPreparation {
        data class Ready(val items: List<ResolvedQueueItem>, val selectedItemId: QueueItemId) :
            InitialPreparation

        data class Failed(val failure: PlaybackPreparation.Failed) : InitialPreparation
    }

    private companion object {
        const val LOOKAHEAD_ITEMS = 2
        const val URL_REFRESH_MARGIN_MS = 60_000L
    }
}
