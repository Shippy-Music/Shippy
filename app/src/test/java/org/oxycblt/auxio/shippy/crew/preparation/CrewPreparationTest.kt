/*
 * Copyright (c) 2026 Auxio Project
 * CrewPreparationTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.preparation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm

class CrewPreparationTest {
    @Test
    fun `two members cannot use peer media when push pull is disabled`() {
        val item = item("one")
        val alpha = member("alpha")
        val bravo = member("bravo")
        val summary =
            summary(item, alpha to CrewAvailability.DOWNLOAD, bravo to CrewAvailability.PEER_ONLY)

        assertEquals(
            SupplierDecision.PushPullDisabled,
            CrewSupplierSelector.select(summary, bravo, pushPullEnabled = false),
        )
        assertEquals(
            CrewStartDecision.Unavailable(
                listOf(CrewReadinessProblem(bravo, CrewReadinessProblemReason.PUSH_PULL_DISABLED))
            ),
            CrewReadinessPlanner.decide(
                summary,
                listOf(alpha, bravo),
                pushPullEnabled = false,
                nowEpochMs = 10,
                policy = CrewReadinessPolicy(deadlineEpochMs = 100),
            ),
        )
    }

    @Test
    fun `provider resolvable member is playable but is never selected as supplier`() {
        val item = item("one")
        val alpha = member("alpha")
        val bravo = member("bravo")
        val charlie = member("charlie")
        val summary =
            summary(
                item,
                alpha to CrewAvailability.PEER_ONLY,
                bravo to CrewAvailability.PREFERRED_PROVIDER,
                charlie to CrewAvailability.DOWNLOAD,
            )

        assertEquals(
            SupplierDecision.Selected(charlie, PeerSupplySource.DOWNLOAD),
            CrewSupplierSelector.select(summary, alpha, pushPullEnabled = true),
        )
        assertEquals(
            CrewStartDecision.WaitForCommonStart(listOf(alpha), 100),
            CrewReadinessPlanner.decide(
                summary,
                listOf(alpha, bravo, charlie),
                true,
                1,
                CrewReadinessPolicy(deadlineEpochMs = 100),
            ),
        )
    }

    @Test
    fun `non coordinator supplier selection respects exact source then connection metrics and stable tie`() {
        val item = item("one")
        val alpha = member("alpha")
        val bravo = member("bravo")
        val charlie = member("charlie")
        val delta = member("delta")
        val summary =
            summary(
                item,
                alpha to CrewAvailability.PEER_ONLY,
                bravo to CrewAvailability.DOWNLOAD,
                charlie to CrewAvailability.DOWNLOAD,
                delta to CrewAvailability.LOCAL_EXACT,
            )

        assertEquals(
            SupplierDecision.Selected(delta, PeerSupplySource.LOCAL_EXACT),
            CrewSupplierSelector.select(
                summary,
                alpha,
                true,
                listOf(
                    metrics(alpha, bravo, completionMs = 10, uploads = 0),
                    metrics(alpha, charlie, completionMs = 10, uploads = 0),
                    metrics(alpha, delta, completionMs = 9_999, uploads = 99),
                ),
            ),
        )

        val noExact =
            summary(
                item,
                alpha to CrewAvailability.PEER_ONLY,
                bravo to CrewAvailability.DOWNLOAD,
                charlie to CrewAvailability.DOWNLOAD,
            )
        assertEquals(
            SupplierDecision.Selected(bravo, PeerSupplySource.DOWNLOAD),
            CrewSupplierSelector.select(
                noExact,
                alpha,
                true,
                listOf(
                    metrics(alpha, charlie, completionMs = 25, uploads = 1),
                    metrics(alpha, bravo, completionMs = 25, uploads = 1),
                ),
            ),
        )
    }

    @Test
    fun `ineligible battery or network supplier is skipped and blocked is not peer only`() {
        val item = item("one")
        val alpha = member("alpha")
        val bravo = member("bravo")
        val charlie = member("charlie")
        val summary =
            summary(
                item,
                alpha to CrewAvailability.PEER_ONLY,
                bravo to CrewAvailability.DOWNLOAD,
                charlie to CrewAvailability.TEMPORARY_CACHE,
            )

        assertEquals(
            SupplierDecision.Selected(charlie, PeerSupplySource.TEMPORARY_CACHE),
            CrewSupplierSelector.select(
                summary,
                alpha,
                true,
                listOf(metrics(alpha, bravo, batteryEligible = false)),
            ),
        )
        assertEquals(
            SupplierDecision.NotPlayable(CrewAvailability.BLOCKED),
            CrewSupplierSelector.select(
                summary(
                    item,
                    alpha to CrewAvailability.BLOCKED,
                    bravo to CrewAvailability.DOWNLOAD,
                ),
                alpha,
                true,
            ),
        )
    }

    @Test
    fun `common start waits then starts ready members with late join at deadline`() {
        val item = item("one")
        val alpha = member("alpha")
        val bravo = member("bravo")
        val charlie = member("charlie")
        val summary =
            summary(
                item,
                alpha to CrewAvailability.PREFERRED_PROVIDER,
                bravo to CrewAvailability.PEER_ONLY,
                charlie to CrewAvailability.DOWNLOAD,
            )
        val policy = CrewReadinessPolicy(deadlineEpochMs = 100)

        assertEquals(
            CrewStartDecision.WaitForCommonStart(listOf(bravo), 100),
            CrewReadinessPlanner.decide(summary, listOf(alpha, bravo, charlie), true, 99, policy),
        )
        assertEquals(
            CrewStartDecision.StartReadyMembersWithLateJoin(listOf(alpha, charlie), listOf(bravo)),
            CrewReadinessPlanner.decide(summary, listOf(alpha, bravo, charlie), true, 100, policy),
        )
    }

    @Test
    fun `unavailable after deadline only when no viable late join path remains`() {
        val item = item("one")
        val alpha = member("alpha")
        val bravo = member("bravo")
        val summary =
            summary(item, alpha to CrewAvailability.PEER_ONLY, bravo to CrewAvailability.DOWNLOAD)

        assertEquals(
            CrewStartDecision.Unavailable(
                listOf(CrewReadinessProblem(alpha, CrewReadinessProblemReason.DEADLINE_EXPIRED))
            ),
            CrewReadinessPlanner.decide(
                summary,
                listOf(alpha, bravo),
                true,
                100,
                CrewReadinessPolicy(deadlineEpochMs = 100, allowLateJoinAfterDeadline = false),
            ),
        )
    }

    @Test
    fun `prefetch includes current item and keeps or reprioritizes reusable work after queue changes`() {
        val current = item("current")
        val next = item("next")
        val replacement = item("replacement")
        val alpha = member("alpha")
        val bravo = member("bravo")
        val currentRequest =
            CrewPrefetchRequest(
                CrewPrefetchKey(current.id, alpha, bravo),
                PeerSupplySource.LOCAL_EXACT,
                2,
            )
        val oldNextRequest =
            CrewPrefetchRequest(
                CrewPrefetchKey(next.id, alpha, bravo),
                PeerSupplySource.LOCAL_EXACT,
                2,
            )

        val plan =
            CrewPrefetchPlanner.plan(
                queue = listOf(current, replacement),
                currentQueueItemId = current.id,
                summaries =
                    mapOf(
                        current.id to
                            summary(
                                current,
                                alpha to CrewAvailability.PEER_ONLY,
                                bravo to CrewAvailability.LOCAL_EXACT,
                            ),
                        replacement.id to
                            summary(
                                replacement,
                                alpha to CrewAvailability.PEER_ONLY,
                                bravo to CrewAvailability.LOCAL_EXACT,
                            ),
                    ),
                activeMembers = listOf(alpha, bravo),
                pushPullEnabled = true,
                policy = CrewPrefetchPolicy(lookAheadItems = 1),
                existingRequests = listOf(currentRequest, oldNextRequest),
            )

        assertEquals(listOf(currentRequest), plan.keep)
        assertEquals(
            listOf(
                CrewPrefetchRequest(
                    CrewPrefetchKey(replacement.id, alpha, bravo),
                    PeerSupplySource.LOCAL_EXACT,
                    1,
                )
            ),
            plan.start,
        )
        assertTrue(plan.reprioritize.isEmpty())
        assertEquals(listOf(oldNextRequest.key), plan.cancellations)

        val reprioritized =
            CrewPrefetchPlanner.plan(
                queue = listOf(current, replacement),
                currentQueueItemId = current.id,
                summaries =
                    mapOf(
                        current.id to
                            summary(
                                current,
                                alpha to CrewAvailability.PEER_ONLY,
                                bravo to CrewAvailability.LOCAL_EXACT,
                            ),
                        replacement.id to
                            summary(
                                replacement,
                                alpha to CrewAvailability.PEER_ONLY,
                                bravo to CrewAvailability.LOCAL_EXACT,
                            ),
                    ),
                activeMembers = listOf(alpha, bravo),
                pushPullEnabled = true,
                policy = CrewPrefetchPolicy(lookAheadItems = 1),
                existingRequests = listOf(currentRequest.copy(priority = 99)),
            )
        assertEquals(listOf(currentRequest), reprioritized.reprioritize)

        val advanced =
            CrewPrefetchPlanner.plan(
                queue = listOf(current, replacement),
                currentQueueItemId = replacement.id,
                summaries =
                    mapOf(
                        replacement.id to
                            summary(
                                replacement,
                                alpha to CrewAvailability.PEER_ONLY,
                                bravo to CrewAvailability.LOCAL_EXACT,
                            )
                    ),
                activeMembers = listOf(alpha, bravo),
                pushPullEnabled = true,
                policy = CrewPrefetchPolicy(lookAheadItems = 1),
                existingRequests = plan.start,
            )
        assertEquals(
            listOf(
                CrewPrefetchRequest(
                    CrewPrefetchKey(replacement.id, alpha, bravo),
                    PeerSupplySource.LOCAL_EXACT,
                    1,
                )
            ),
            advanced.keep,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `prefetch rejects duplicate active request keys`() {
        val current = item("current")
        val alpha = member("alpha")
        val bravo = member("bravo")
        val request =
            CrewPrefetchRequest(
                CrewPrefetchKey(current.id, alpha, bravo),
                PeerSupplySource.LOCAL_EXACT,
                1,
            )

        CrewPrefetchPlanner.plan(
            queue = listOf(current),
            currentQueueItemId = current.id,
            summaries =
                mapOf(
                    current.id to
                        summary(
                            current,
                            alpha to CrewAvailability.PEER_ONLY,
                            bravo to CrewAvailability.LOCAL_EXACT,
                        )
                ),
            activeMembers = listOf(alpha, bravo),
            pushPullEnabled = true,
            policy = CrewPrefetchPolicy(lookAheadItems = 0),
            existingRequests = listOf(request, request),
        )
    }

    private fun metrics(
        target: CrewMemberId,
        supplier: CrewMemberId,
        connectionMs: Long = 25,
        completionMs: Long = 50,
        uploads: Int = 0,
        batteryEligible: Boolean = true,
        networkEligible: Boolean = true,
    ) =
        SupplierCandidateMetrics(
            target,
            supplier,
            connectionMs,
            completionMs,
            uploads,
            batteryEligible,
            networkEligible,
        )

    private fun member(value: String) = CrewMemberId(value, ProtocolVersion(1))

    private fun item(value: String): QueueItem {
        val trackId = TrackId("track-$value")
        return QueueItem(
            id = QueueItemId(value),
            track =
                Track(
                    id = trackId,
                    realm = TrackRealm.PROVIDER,
                    title = value,
                    artists = listOf("Artist"),
                    candidates = emptyList(),
                ),
        )
    }

    private fun summary(item: QueueItem, vararg entries: Pair<CrewMemberId, CrewAvailability>) =
        QueueItemAvailabilitySummary(
            item,
            entries.map { (memberId, availability) ->
                MemberItemAvailability(memberId, item.id, availability)
            },
        )
}
