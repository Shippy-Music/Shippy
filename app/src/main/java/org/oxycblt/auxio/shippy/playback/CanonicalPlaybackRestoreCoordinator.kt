/*
 * Copyright (c) 2026 Auxio Project
 * CanonicalPlaybackRestoreCoordinator.kt is part of Auxio.
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
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.shippy.domain.PlaybackPreparation
import org.oxycblt.auxio.shippy.domain.PlaybackResolutionCoordinator
import org.oxycblt.auxio.shippy.domain.ResolutionPolicy
import org.oxycblt.auxio.shippy.persistence.playback.PlaybackCheckpointRepository
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderSettings

/**
 * Restores durable queue intent only; stream URLs and temporary/download candidates are
 * re-resolved.
 */
class CanonicalPlaybackRestoreCoordinator
@Inject
constructor(
    private val checkpoints: PlaybackCheckpointRepository,
    private val resolution: PlaybackResolutionCoordinator,
    private val registry: ProviderRegistry,
    private val settings: ProviderSettings,
) {
    suspend fun restore(): PlaybackStateManager.CanonicalCheckpoint? {
        val stored = checkpoints.read() ?: return null
        val policy =
            ResolutionPolicy(
                settings
                    .selection(
                        registry.supporting(ProviderCapability.STREAM).map { it.descriptor.id }
                    )
                    .priority,
                pushPullEnabled = false,
            )
        val prepared =
            stored.heap.map { item ->
                when (val result = resolution.prepare(item, policy)) {
                    is PlaybackPreparation.Ready -> item.id to result.value
                    is PlaybackPreparation.Failed -> item.id to null
                }
            }
        val surviving = prepared.mapNotNull { it.second }
        if (surviving.isEmpty()) return null
        val shape =
            remapSurvivingQueue(
                prepared.size,
                stored.heapIndex,
                stored.mapping,
                prepared.mapIndexedNotNull { oldIndex, value -> value.second?.let { oldIndex } },
            ) ?: return null
        return PlaybackStateManager.CanonicalCheckpoint(
            surviving,
            shape.mapping,
            shape.heapIndex,
            stored.positionMs.takeIf { shape.selectedSurvived } ?: 0,
            stored.repeatMode,
        )
    }
}

internal data class RestoredQueueShape(
    val mapping: List<Int>,
    val heapIndex: Int,
    val selectedSurvived: Boolean,
)

internal fun remapSurvivingQueue(
    originalSize: Int,
    selectedHeapIndex: Int,
    mapping: List<Int>,
    survivingOldIndices: List<Int>,
): RestoredQueueShape? {
    if (originalSize <= 0 || selectedHeapIndex !in 0 until originalSize) return null
    if (
        survivingOldIndices.any { it !in 0 until originalSize } ||
            survivingOldIndices.distinct().size != survivingOldIndices.size
    ) {
        return null
    }
    val oldToNew = survivingOldIndices.withIndex().associate { (new, old) -> old to new }
    if (oldToNew.isEmpty()) return null

    val playbackOrder = mapping.ifEmpty { (0 until originalSize).toList() }
    if (
        playbackOrder.size != originalSize ||
            playbackOrder.sorted() != playbackOrder.indices.toList()
    ) {
        return null
    }
    val selectedOrderIndex = playbackOrder.indexOf(selectedHeapIndex)
    if (selectedOrderIndex < 0) return null
    val selected =
        (selectedOrderIndex downTo 0).firstNotNullOfOrNull { oldToNew[playbackOrder[it]] }
            ?: playbackOrder.firstNotNullOfOrNull(oldToNew::get)
            ?: return null
    val remapped =
        if (mapping.isEmpty()) {
            emptyList()
        } else {
            playbackOrder.mapNotNull(oldToNew::get)
        }
    return RestoredQueueShape(remapped, selected, oldToNew.containsKey(selectedHeapIndex))
}
