/*
 * Copyright (c) 2026 Auxio Project
 * CrewJoinBootstrapAccumulator.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.runtime

import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlFrameResult
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlMessage
import org.oxycblt.auxio.shippy.crew.protocol.CrewControlReassembler
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame

/**
 * Receives authenticated CONTROL frames while a joining member waits for its admission snapshot.
 *
 * This deliberately owns no transport and creates no
 * [org.oxycblt.auxio.shippy.crew.session.CrewSessionEngine]. The caller supplies the authenticated
 * remote identity for every frame.
 */
class CrewJoinBootstrapAccumulator(
    private val expectedSessionId: CrewSessionId,
    private val localMemberId: CrewMemberId,
    private val reassembler: CrewControlReassembler = CrewControlReassembler(),
) {
    init {
        require(expectedSessionId.protocolVersion == localMemberId.protocolVersion) {
            "Joining member protocol must match expected session"
        }
    }

    fun accept(
        authenticatedMemberId: CrewMemberId,
        frame: CrewTransportFrame,
        nowMonotonicMs: Long,
    ): CrewJoinBootstrapResult {
        if (frame.channel != CrewTransportChannel.CONTROL) {
            return CrewJoinBootstrapResult.Rejected.WrongChannel
        }
        if (nowMonotonicMs < 0) {
            return CrewJoinBootstrapResult.Rejected.InvalidMonotonicTime
        }
        val frameResult = reassembler.accept(frame, nowMonotonicMs)
        return when (frameResult) {
            is CrewControlFrameResult.Pending -> CrewJoinBootstrapResult.Waiting
            is CrewControlFrameResult.Rejected ->
                CrewJoinBootstrapResult.Rejected.Framing(frameResult)
            is CrewControlFrameResult.Complete ->
                acceptMessage(authenticatedMemberId, frameResult.message)
        }
    }

    private fun acceptMessage(
        authenticatedMemberId: CrewMemberId,
        message: CrewControlMessage,
    ): CrewJoinBootstrapResult {
        if (message !is CrewControlMessage.SnapshotInstalled) {
            return CrewJoinBootstrapResult.Waiting
        }
        if (message.electionVotes.isNotEmpty()) {
            return CrewJoinBootstrapResult.Rejected.ElectionCertificateBootstrap
        }
        val snapshot = message.snapshot
        if (
            snapshot.sessionId != expectedSessionId ||
                snapshot.protocolVersion != expectedSessionId.protocolVersion
        ) {
            return CrewJoinBootstrapResult.Rejected.WrongSession
        }
        if (authenticatedMemberId != snapshot.coordinatorMemberId) {
            return CrewJoinBootstrapResult.Rejected.ForgedCoordinator
        }
        if (snapshot.members.count { it.id == localMemberId } != 1) {
            return CrewJoinBootstrapResult.Rejected.LocalMemberMissingOrDuplicate
        }
        return CrewJoinBootstrapResult.Accepted(snapshot)
    }
}

sealed interface CrewJoinBootstrapResult {
    data object Waiting : CrewJoinBootstrapResult

    data class Accepted(val snapshot: org.oxycblt.auxio.shippy.crew.core.CrewSnapshot) :
        CrewJoinBootstrapResult

    sealed interface Rejected : CrewJoinBootstrapResult {
        data object WrongChannel : Rejected

        data object InvalidMonotonicTime : Rejected

        data class Framing(val reason: CrewControlFrameResult.Rejected) : Rejected

        data object WrongSession : Rejected

        data object ForgedCoordinator : Rejected

        data object LocalMemberMissingOrDuplicate : Rejected

        data object ElectionCertificateBootstrap : Rejected
    }
}
