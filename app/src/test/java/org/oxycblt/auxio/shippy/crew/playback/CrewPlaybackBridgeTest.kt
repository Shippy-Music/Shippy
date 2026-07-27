/*
 * Copyright (c) 2026 Auxio Project
 * CrewPlaybackBridgeTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewPlaybackMode
import org.oxycblt.auxio.shippy.crew.core.CrewPlaybackState
import org.oxycblt.auxio.shippy.crew.core.CrewRepeatMode
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewState
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.media.publicizeCrewQueueItem
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm

class CrewPlaybackBridgeTest {
    @Test
    fun `local crew queue item receives local contributor without replacing remote provenance`() {
        val local = CrewMemberId("local", ProtocolVersion(1))
        val localItem = item("local", CandidateKind.LOCAL)
        val remoteItem = item("remote", CandidateKind.LOCAL).copy(contributorId = "remote-member")
        val stamped = stampCrewContributor(listOf(localItem, remoteItem), local)
        assertEquals("local", stamped[0].contributorId)
        assertEquals("remote-member", stamped[1].contributorId)
    }

    @Test
    fun `canonical queue removes temporary and download candidates while retaining local`() {
        val item =
            item("one", CandidateKind.LOCAL, CandidateKind.DOWNLOAD, CandidateKind.CREW_TEMPORARY)

        val canonical = canonicalQueueForCrew(listOf(item)).single()

        assertEquals(listOf(CandidateKind.LOCAL), canonical.track.candidates.map { it.kind })
    }

    @Test
    fun `queue identity is duplicate safe and order sensitive`() {
        val first = item("first")
        val second = first.copy(id = QueueItemId("second"))

        assertEquals(
            listOf(first.id, second.id),
            canonicalQueueForCrew(listOf(first, second)).map { it.id },
        )
    }

    @Test
    fun `playing position is monotonic nonnegative and overflow safe`() {
        val playing = CrewPlaybackState(QueueItemId("one"), CrewPlaybackMode.PLAYING, 10, 100)
        assertEquals(60, crewPositionAt(playing, 150))
        assertEquals(10, crewPositionAt(playing, 1))
        assertEquals(
            Long.MAX_VALUE,
            crewPositionAt(playing.copy(positionAtEpochMs = Long.MAX_VALUE - 1), Long.MAX_VALUE),
        )
        assertEquals(10, crewPositionAt(playing.copy(mode = CrewPlaybackMode.PAUSED), 150))
    }

    @Test
    fun `equivalent final manager snapshot emits no Crew actions`() {
        val item = item("one")
        val crew =
            state(
                listOf(item),
                CrewPlaybackState(item.id, CrewPlaybackMode.PLAYING, 1_000, 100),
                shuffled = true,
                repeat = CrewRepeatMode.ALL,
            )
        val snapshot =
            PlayerCrewSnapshot(listOf(item), item.id, true, 1_050, 150, true, CrewRepeatMode.ALL)

        assertTrue(crewDiff(crew, snapshot).isEmpty())
    }

    @Test
    fun `private locator difference does not echo a queue replacement`() {
        val privateItem = item("one", CandidateKind.LOCAL).copy(contributorId = "member")
        val publicItem = publicizeCrewQueueItem(privateItem)
        val crew =
            state(
                listOf(publicItem),
                CrewPlaybackState(publicItem.id, CrewPlaybackMode.PAUSED, 0, 0),
            )
        val player =
            PlayerCrewSnapshot(
                queue = listOf(privateItem),
                currentItemId = privateItem.id,
                playing = false,
                positionMs = 0,
                sessionEpochMs = 0,
                shuffled = false,
                repeatMode = CrewRepeatMode.OFF,
            )

        assertTrue(crewDiff(crew, player).isEmpty())
    }

    @Test
    fun `player diff is ordered queue current shuffle repeat then playback`() {
        val old = item("old")
        val replacement = item("replacement")
        val crew = state(listOf(old), CrewPlaybackState(old.id, CrewPlaybackMode.PAUSED, 0, 0))
        val snapshot =
            PlayerCrewSnapshot(
                listOf(replacement),
                replacement.id,
                true,
                5_000,
                1_000,
                true,
                CrewRepeatMode.ONE,
            )

        assertEquals(
            listOf(
                CrewAction.QueueReplaced(listOf(replacement)),
                CrewAction.CurrentItemChanged(replacement.id),
                CrewAction.ShuffleChanged(true),
                CrewAction.RepeatChanged(CrewRepeatMode.ONE),
                CrewAction.Play(5_000, 1_000),
            ),
            crewDiff(crew, snapshot),
        )
    }

    @Test
    fun `remote equivalent state has no echo even with a different epoch`() {
        val item = item("one")
        val crew =
            state(listOf(item), CrewPlaybackState(item.id, CrewPlaybackMode.PLAYING, 3_000, 10_000))
        val settledPlayer =
            PlayerCrewSnapshot(
                listOf(item),
                item.id,
                true,
                3_150,
                10_150,
                false,
                CrewRepeatMode.OFF,
            )

        assertTrue(crewDiff(crew, settledPlayer).isEmpty())
    }

    @Test
    fun `paused player seeds an idle Crew with an explicit pause`() {
        val item = item("one")
        val crew = state(emptyList(), CrewPlaybackState())
        val player =
            PlayerCrewSnapshot(
                queue = listOf(item),
                currentItemId = item.id,
                playing = false,
                positionMs = 2_000,
                sessionEpochMs = 4_000,
                shuffled = false,
                repeatMode = CrewRepeatMode.OFF,
            )

        assertEquals(
            listOf(
                CrewAction.QueueReplaced(listOf(item)),
                CrewAction.CurrentItemChanged(item.id),
                CrewAction.Pause(2_000, 4_000),
            ),
            crewDiff(crew, player),
        )
    }

    @Test
    fun `preparing equivalent queue does not echo a seek`() {
        val item = item("one")
        val crew = state(listOf(item), CrewPlaybackState(item.id, CrewPlaybackMode.PREPARING, 0, 0))
        val player =
            PlayerCrewSnapshot(
                listOf(item),
                item.id,
                false,
                10_000,
                20_000,
                false,
                CrewRepeatMode.OFF,
            )

        assertTrue(crewDiff(crew, player).isEmpty())
    }

    private fun state(
        queue: List<QueueItem>,
        playback: CrewPlaybackState,
        shuffled: Boolean = false,
        repeat: CrewRepeatMode = CrewRepeatMode.OFF,
    ): CrewState {
        val protocol = ProtocolVersion(1)
        val member = CrewMemberId("member", protocol)
        return CrewState(
            sessionId = CrewSessionId("session", protocol),
            protocolVersion = protocol,
            term = CoordinatorTerm(1),
            lastSequence = EventSequence(0),
            coordinatorMemberId = member,
            members = listOf(CrewMember(member, "Member")),
            queue = queue,
            playback = playback,
            shuffleEnabled = shuffled,
            repeatMode = repeat,
        )
    }

    private fun item(id: String, vararg kinds: CandidateKind): QueueItem {
        val trackId = TrackId("track")
        return QueueItem(
            QueueItemId(id),
            Track(
                trackId,
                TrackRealm.LOCAL,
                "Track",
                listOf("Artist"),
                candidates =
                    (if (kinds.isEmpty()) arrayOf(CandidateKind.LOCAL) else kinds).mapIndexed {
                        index,
                        kind ->
                        TrackCandidate(
                            CandidateId("$id-$index"),
                            trackId,
                            kind,
                            "source",
                            "$id-$index",
                            CandidateAvailability.AVAILABLE,
                            locator = "content://$id/$index",
                        )
                    },
            ),
        )
    }
}
