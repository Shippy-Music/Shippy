/*
 * Copyright (c) 2026 Auxio Project
 * RecordingRedirects.kt is part of Auxio.
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
package app.shippy.core.identitymatch

import app.shippy.core.identity.IdentityDecisionId
import app.shippy.core.identity.RecordingId
import java.time.Instant

data class RecordingRedirectGraph(val redirects: Map<RecordingId, RecordingId> = emptyMap()) {
    init {
        redirects.keys.forEach(::resolve)
    }

    fun resolve(recordingId: RecordingId): RecordingId {
        var current = recordingId
        val visited = mutableSetOf<RecordingId>()
        while (true) {
            require(visited.add(current)) { "Recording redirect graph cannot contain a cycle" }
            current = redirects[current] ?: return current
        }
    }

    fun add(retiredId: RecordingId, survivorId: RecordingId): RedirectMutation {
        if (retiredId == survivorId) return RedirectMutation.Unchanged(this)
        val existing = redirects[retiredId]
        if (existing != null) {
            return if (resolve(existing) == resolve(survivorId)) {
                RedirectMutation.Unchanged(this)
            } else {
                RedirectMutation.Conflict(this, retiredId, existing, survivorId)
            }
        }
        val canonicalSurvivor = resolve(survivorId)
        if (canonicalSurvivor == retiredId) {
            return RedirectMutation.RejectedCycle(this, retiredId, survivorId)
        }
        return RedirectMutation.Added(
            graph = copy(redirects = redirects + (retiredId to canonicalSurvivor)),
            retiredId = retiredId,
            survivorId = canonicalSurvivor,
        )
    }

    fun removeExact(retiredId: RecordingId, survivorId: RecordingId): RecordingRedirectGraph {
        require(redirects[retiredId] == survivorId) { "Redirect no longer matches merge audit" }
        return copy(redirects = redirects - retiredId)
    }
}

sealed interface RedirectMutation {
    val graph: RecordingRedirectGraph

    data class Added(
        override val graph: RecordingRedirectGraph,
        val retiredId: RecordingId,
        val survivorId: RecordingId,
    ) : RedirectMutation

    data class Unchanged(override val graph: RecordingRedirectGraph) : RedirectMutation

    data class Conflict(
        override val graph: RecordingRedirectGraph,
        val retiredId: RecordingId,
        val existingTarget: RecordingId,
        val requestedTarget: RecordingId,
    ) : RedirectMutation

    data class RejectedCycle(
        override val graph: RecordingRedirectGraph,
        val retiredId: RecordingId,
        val requestedTarget: RecordingId,
    ) : RedirectMutation
}

enum class RecordingReferenceKind {
    SOURCE_REFERENCE,
    MEDIA_ASSET,
    LIBRARY_RELATIONSHIP,
    PLAYLIST_ENTRY,
    LISTENING_SESSION,
    EXTERNAL_IDENTIFIER,
    USER_OVERRIDE,
}

data class RecordingReferenceMove(val kind: RecordingReferenceKind, val stableId: String) {
    init {
        require(stableId.isNotBlank()) { "Moved reference ID cannot be blank" }
    }
}

data class RecordingMergePlan(
    val decisionId: IdentityDecisionId,
    val survivorId: RecordingId,
    val retiredId: RecordingId,
    val movedReferences: Set<RecordingReferenceMove>,
    val createdAt: Instant,
) {
    init {
        require(survivorId != retiredId) { "Merge requires two recording identities" }
    }

    fun reverse(graph: RecordingRedirectGraph): RecordingUnmergePlan =
        RecordingUnmergePlan(
            decisionId = decisionId,
            restoredRecordingId = retiredId,
            survivorId = survivorId,
            referencesToRestore = movedReferences,
            redirectGraph = graph.removeExact(retiredId, survivorId),
        )
}

data class RecordingUnmergePlan(
    val decisionId: IdentityDecisionId,
    val restoredRecordingId: RecordingId,
    val survivorId: RecordingId,
    val referencesToRestore: Set<RecordingReferenceMove>,
    val redirectGraph: RecordingRedirectGraph,
)

object RecordingMergePlanner {
    fun plan(
        firstId: RecordingId,
        secondId: RecordingId,
        decisionId: IdentityDecisionId,
        movedReferences: Set<RecordingReferenceMove>,
        createdAt: Instant,
    ): RecordingMergePlan {
        require(firstId != secondId) { "Merge requires two recording identities" }
        val survivor = minOf(firstId, secondId, compareBy(RecordingId::value))
        val retired = if (survivor == firstId) secondId else firstId
        return RecordingMergePlan(
            decisionId = decisionId,
            survivorId = survivor,
            retiredId = retired,
            movedReferences = movedReferences,
            createdAt = createdAt,
        )
    }
}
