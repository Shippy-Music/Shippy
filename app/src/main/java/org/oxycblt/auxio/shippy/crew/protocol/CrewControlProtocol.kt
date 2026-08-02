/*
 * Copyright (c) 2026 Auxio Project
 * CrewControlProtocol.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import org.oxycblt.auxio.shippy.crew.core.CoordinatorTerm
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.CrewAvatarDescriptor
import org.oxycblt.auxio.shippy.crew.core.CrewElectionCheckpoint
import org.oxycblt.auxio.shippy.crew.core.CrewElectionVote
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewPlaybackMode
import org.oxycblt.auxio.shippy.crew.core.CrewPlaybackState
import org.oxycblt.auxio.shippy.crew.core.CrewProfileId
import org.oxycblt.auxio.shippy.crew.core.CrewRepeatMode
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.CrewSnapshot
import org.oxycblt.auxio.shippy.crew.core.CrewSnapshotVersion
import org.oxycblt.auxio.shippy.crew.core.DurableCrewEvent
import org.oxycblt.auxio.shippy.crew.core.DurableEventId
import org.oxycblt.auxio.shippy.crew.core.EventSequence
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteId
import org.oxycblt.auxio.shippy.crew.invite.CrewRelayLocator
import org.oxycblt.auxio.shippy.crew.invite.CrewSessionLocator
import org.oxycblt.auxio.shippy.crew.media.publicizeCrewQueueItem
import org.oxycblt.auxio.shippy.crew.preparation.CrewAvailability
import org.oxycblt.auxio.shippy.crew.preparation.CrewAvailabilityAnnouncement
import org.oxycblt.auxio.shippy.crew.preparation.CrewAvailabilityEntry
import org.oxycblt.auxio.shippy.crew.preparation.MAX_CREW_AVAILABILITY_ENTRIES
import org.oxycblt.auxio.shippy.crew.rejoin.CrewRejoinLease
import org.oxycblt.auxio.shippy.crew.session.CrewActionRequest
import org.oxycblt.auxio.shippy.crew.session.CrewSequenceRejection
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.MediaDescriptor
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.domain.TrackVersion

private const val CONTROL_MAGIC = 0x53484343
private const val CONTROL_FORMAT_VERSION = 1
private const val FRAME_MAGIC = 0x53484346
private const val FRAME_FORMAT_VERSION = 1
private const val SHA_256_BYTES = 32
private const val FRAME_HEADER_BYTES =
    Int.SIZE_BYTES + Byte.SIZE_BYTES + Int.SIZE_BYTES + Short.SIZE_BYTES * 2 + SHA_256_BYTES
private const val MAX_STRING_BYTES = 16 * 1024
private const val MAX_ID_BYTES = 128
private const val MAX_DISPLAY_NAME_BYTES = 512
private const val MAX_AVATAR_DESCRIPTOR_BYTES = 16
private const val MAX_LOCATOR_BYTES = 64 * 1024
private const val MAX_ARTISTS = 64
private const val MAX_CANDIDATES = 32
private const val MAX_MEMBERS = 64
private const val MAX_QUEUE_ITEMS = 10_000
private const val MAX_ELECTION_VOTES = MAX_MEMBERS
private const val MAX_LOGICAL_MESSAGE_BYTES = 4 * 1024 * 1024
private const val MAX_REQUEST_MESSAGE_BYTES = MAX_LOGICAL_MESSAGE_BYTES - 64 * 1024
private const val MAX_CONTROL_CHUNKS = 128
private const val MAX_IN_FLIGHT_MESSAGES = 8
private const val MAX_REASSEMBLY_BYTES = 8 * 1024 * 1024
private const val REASSEMBLY_TTL_MS = 30_000L

sealed interface CrewControlMessage {
    data class Request(val request: CrewActionRequest) : CrewControlMessage

    data class Event(val event: DurableCrewEvent) : CrewControlMessage

    data class SnapshotRequested(val request: CrewSnapshotRequest) : CrewControlMessage

    data class SnapshotInstalled(
        val snapshot: CrewSnapshot,
        val electionVotes: List<CrewElectionVote> = emptyList(),
    ) : CrewControlMessage

    /**
     * One vote whose voter identity must be matched to the authenticated transport peer.
     *
     * Snapshot-carried votes are only a certificate copy. This standalone message is the path that
     * lets every receiver independently authenticate and retain each vote.
     */
    data class ElectionVoteCast(val vote: CrewElectionVote) : CrewControlMessage

    /** Path-free, transient availability bound to one exact canonical Crew checkpoint. */
    data class AvailabilityAnnounced(val announcement: CrewAvailabilityAnnouncement) :
        CrewControlMessage

    /** Private, transient credential sent only to its already authenticated intended member. */
    data class RejoinCredentialIssued(val lease: CrewRejoinLease) : CrewControlMessage

    data class RequestRejected(
        val sessionId: CrewSessionId,
        val protocolVersion: ProtocolVersion,
        val requestId: DurableEventId,
        val coordinatorMemberId: CrewMemberId,
        val reason: CrewSequenceRejection,
    ) : CrewControlMessage {
        init {
            require(sessionId.protocolVersion == protocolVersion) {
                "Rejected request session protocol must match"
            }
            require(coordinatorMemberId.protocolVersion == protocolVersion) {
                "Rejected request coordinator protocol must match"
            }
        }
    }
}

data class CrewSnapshotRequest(
    val sessionId: CrewSessionId,
    val protocolVersion: ProtocolVersion,
    val requestingMemberId: CrewMemberId,
    val knownTerm: CoordinatorTerm,
    val knownSequence: EventSequence,
) {
    init {
        require(sessionId.protocolVersion == protocolVersion) {
            "Snapshot request session protocol must match"
        }
        require(requestingMemberId.protocolVersion == protocolVersion) {
            "Snapshot requester protocol must match"
        }
    }
}

sealed interface CrewControlDecodeResult {
    data class Accepted(val message: CrewControlMessage) : CrewControlDecodeResult

    enum class Rejected : CrewControlDecodeResult {
        TOO_LARGE,
        UNSUPPORTED_FORMAT,
        MALFORMED,
    }
}

/**
 * Stable, bounded binary representation for durable Crew control messages.
 *
 * Network authentication remains the transport/session engine's responsibility. This codec only
 * guarantees a strict representation with no unbounded collections or trailing data.
 */
object CrewControlCodec {
    fun encode(message: CrewControlMessage): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(CONTROL_MAGIC)
            output.writeByte(CONTROL_FORMAT_VERSION)
            when (message) {
                is CrewControlMessage.Request -> {
                    output.writeByte(1)
                    output.writeActionRequest(message.request)
                }
                is CrewControlMessage.Event -> {
                    output.writeByte(2)
                    output.writeDurableEvent(message.event)
                }
                is CrewControlMessage.SnapshotRequested -> {
                    output.writeByte(3)
                    output.writeSnapshotRequest(message.request)
                }
                is CrewControlMessage.SnapshotInstalled -> {
                    output.writeByte(4)
                    output.writeSnapshot(message.snapshot)
                    output.writeBoundedCount(message.electionVotes.size, MAX_ELECTION_VOTES)
                    message.electionVotes.forEach(output::writeElectionVote)
                }
                is CrewControlMessage.RequestRejected -> {
                    output.writeByte(5)
                    output.writeSessionId(message.sessionId)
                    output.writeProtocolVersion(message.protocolVersion)
                    output.writeSizedString(message.requestId.value, MAX_ID_BYTES)
                    output.writeMemberId(message.coordinatorMemberId)
                    output.writeByte(message.reason.wireCode())
                }
                is CrewControlMessage.ElectionVoteCast -> {
                    output.writeByte(6)
                    output.writeElectionVote(message.vote)
                }
                is CrewControlMessage.AvailabilityAnnounced -> {
                    output.writeByte(7)
                    output.writeAvailabilityAnnouncement(message.announcement)
                }
                is CrewControlMessage.RejoinCredentialIssued -> {
                    output.writeByte(8)
                    output.writeRejoinLease(message.lease)
                }
            }
        }
        return bytes.toByteArray().also {
            if (message is CrewControlMessage.Request) {
                require(it.size <= MAX_REQUEST_MESSAGE_BYTES) {
                    "Crew action request leaves insufficient event-envelope capacity"
                }
            }
            require(it.size <= MAX_LOGICAL_MESSAGE_BYTES) {
                "Crew control message exceeds $MAX_LOGICAL_MESSAGE_BYTES bytes"
            }
        }
    }

    fun decode(payload: ByteArray): CrewControlDecodeResult {
        if (payload.size > MAX_LOGICAL_MESSAGE_BYTES) {
            return CrewControlDecodeResult.Rejected.TOO_LARGE
        }
        if (payload.size < Int.SIZE_BYTES + 2 * Byte.SIZE_BYTES) {
            return CrewControlDecodeResult.Rejected.MALFORMED
        }
        return try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != CONTROL_MAGIC) {
                    return CrewControlDecodeResult.Rejected.UNSUPPORTED_FORMAT
                }
                if (input.readUnsignedByte() != CONTROL_FORMAT_VERSION) {
                    return CrewControlDecodeResult.Rejected.UNSUPPORTED_FORMAT
                }
                val message =
                    when (input.readUnsignedByte()) {
                        1 -> CrewControlMessage.Request(input.readActionRequest())
                        2 -> CrewControlMessage.Event(input.readDurableEvent())
                        3 -> CrewControlMessage.SnapshotRequested(input.readSnapshotRequest())
                        4 ->
                            CrewControlMessage.SnapshotInstalled(
                                snapshot = input.readSnapshot(),
                                electionVotes =
                                    input.readBoundedList(MAX_ELECTION_VOTES) { readElectionVote() },
                            )
                        5 -> {
                            val sessionId = input.readSessionId()
                            val protocolVersion = input.readProtocolVersion()
                            val requestId = DurableEventId(input.readSizedString(MAX_ID_BYTES))
                            val coordinatorMemberId = input.readMemberId()
                            val rejectionCode = input.readUnsignedByte()
                            CrewControlMessage.RequestRejected(
                                sessionId = sessionId,
                                protocolVersion = protocolVersion,
                                requestId = requestId,
                                coordinatorMemberId = coordinatorMemberId,
                                reason =
                                    CrewSequenceRejection.entries.firstOrNull {
                                        it.wireCode() == rejectionCode
                                    } ?: throw IOException("Unknown Crew rejection code"),
                            )
                        }
                        6 -> CrewControlMessage.ElectionVoteCast(input.readElectionVote())
                        7 ->
                            CrewControlMessage.AvailabilityAnnounced(
                                input.readAvailabilityAnnouncement()
                            )
                        8 -> CrewControlMessage.RejoinCredentialIssued(input.readRejoinLease())
                        else -> return CrewControlDecodeResult.Rejected.UNSUPPORTED_FORMAT
                    }
                if (input.available() != 0) {
                    CrewControlDecodeResult.Rejected.MALFORMED
                } else {
                    CrewControlDecodeResult.Accepted(message)
                }
            }
        } catch (_: EOFException) {
            CrewControlDecodeResult.Rejected.MALFORMED
        } catch (_: IOException) {
            CrewControlDecodeResult.Rejected.MALFORMED
        } catch (_: IllegalArgumentException) {
            CrewControlDecodeResult.Rejected.MALFORMED
        } catch (_: ArithmeticException) {
            CrewControlDecodeResult.Rejected.MALFORMED
        }
    }
}

sealed interface CrewControlFrameResult {
    data class Pending(val receivedChunks: Int, val totalChunks: Int) : CrewControlFrameResult

    data class Complete(val message: CrewControlMessage) : CrewControlFrameResult

    enum class Rejected : CrewControlFrameResult {
        WRONG_CHANNEL,
        MALFORMED,
        UNSUPPORTED_FORMAT,
        LIMIT_EXCEEDED,
        INTEGRITY_FAILED,
        MESSAGE_REJECTED,
    }
}

/**
 * Splits logical control messages across reliable CONTROL frames and reassembles them safely.
 *
 * The reliable channel normally delivers in order, but accepting out-of-order chunks makes
 * reconnection/replay handling deterministic and keeps framing independent from WebRTC behavior.
 */
object CrewControlFramer {
    fun encode(message: CrewControlMessage): List<CrewTransportFrame> {
        val payload = CrewControlCodec.encode(message)
        val digest = payload.sha256()
        val chunkPayloadBytes = CrewTransportChannel.CONTROL.maxPayloadBytes - FRAME_HEADER_BYTES
        val chunkCount = (payload.size + chunkPayloadBytes - 1) / chunkPayloadBytes
        require(chunkCount in 1..MAX_CONTROL_CHUNKS) {
            "Crew control message needs too many frames"
        }
        return List(chunkCount) { index ->
            val start = index * chunkPayloadBytes
            val end = minOf(payload.size, start + chunkPayloadBytes)
            val frameBytes = ByteArrayOutputStream()
            DataOutputStream(frameBytes).use { output ->
                output.writeInt(FRAME_MAGIC)
                output.writeByte(FRAME_FORMAT_VERSION)
                output.writeInt(payload.size)
                output.writeShort(index)
                output.writeShort(chunkCount)
                output.write(digest)
                output.write(payload, start, end - start)
            }
            CrewTransportFrame(CrewTransportChannel.CONTROL, frameBytes.toByteArray())
        }
    }
}

class CrewControlReassembler(
    private val maxInFlightMessages: Int = MAX_IN_FLIGHT_MESSAGES,
    private val maxBufferedBytes: Int = MAX_REASSEMBLY_BYTES,
    private val messageTtlMs: Long = REASSEMBLY_TTL_MS,
) {
    private data class Assembly(
        val totalBytes: Int,
        val chunkCount: Int,
        val digest: ByteArray,
        val chunks: Array<ByteArray?>,
        val createdAtMonotonicMs: Long,
        var receivedBytes: Int = 0,
        var receivedChunks: Int = 0,
    )

    private data class Chunk(
        val totalBytes: Int,
        val index: Int,
        val count: Int,
        val digest: ByteArray,
        val payload: ByteArray,
    )

    private val assemblies = LinkedHashMap<String, Assembly>()
    private var bufferedBytes = 0

    init {
        require(maxInFlightMessages > 0) { "At least one in-flight message is required" }
        require(maxBufferedBytes in 1..MAX_REASSEMBLY_BYTES) {
            "Crew control reassembly byte limit is invalid"
        }
        require(messageTtlMs > 0) { "Crew control reassembly TTL must be positive" }
    }

    @Synchronized
    fun accept(frame: CrewTransportFrame, nowMonotonicMs: Long): CrewControlFrameResult {
        require(nowMonotonicMs >= 0) { "Monotonic time cannot be negative" }
        pruneExpired(nowMonotonicMs)
        if (frame.channel != CrewTransportChannel.CONTROL) {
            return CrewControlFrameResult.Rejected.WRONG_CHANNEL
        }
        val chunk =
            when (val decoded = decodeChunk(frame.copyPayload())) {
                is DecodedChunk.Accepted -> decoded.chunk
                is DecodedChunk.Rejected -> return decoded.reason
            }
        val key = chunk.digest.toHex()
        var assembly = assemblies[key]
        if (assembly == null) {
            if (assemblies.size >= maxInFlightMessages) {
                return CrewControlFrameResult.Rejected.LIMIT_EXCEEDED
            }
            assembly =
                Assembly(
                    totalBytes = chunk.totalBytes,
                    chunkCount = chunk.count,
                    digest = chunk.digest,
                    chunks = arrayOfNulls(chunk.count),
                    createdAtMonotonicMs = nowMonotonicMs,
                )
            assemblies[key] = assembly
        } else if (
            assembly.totalBytes != chunk.totalBytes ||
                assembly.chunkCount != chunk.count ||
                !assembly.digest.contentEquals(chunk.digest)
        ) {
            discard(key)
            return CrewControlFrameResult.Rejected.MALFORMED
        }

        val existing = assembly.chunks[chunk.index]
        if (existing != null) {
            return if (existing.contentEquals(chunk.payload)) {
                CrewControlFrameResult.Pending(assembly.receivedChunks, assembly.chunkCount)
            } else {
                discard(key)
                CrewControlFrameResult.Rejected.INTEGRITY_FAILED
            }
        }
        if (
            Math.addExact(bufferedBytes, chunk.payload.size) > maxBufferedBytes ||
                Math.addExact(assembly.receivedBytes, chunk.payload.size) > assembly.totalBytes
        ) {
            discard(key)
            return CrewControlFrameResult.Rejected.LIMIT_EXCEEDED
        }
        assembly.chunks[chunk.index] = chunk.payload
        assembly.receivedBytes += chunk.payload.size
        assembly.receivedChunks += 1
        bufferedBytes += chunk.payload.size
        if (assembly.receivedChunks != assembly.chunkCount) {
            return CrewControlFrameResult.Pending(assembly.receivedChunks, assembly.chunkCount)
        }

        val payload = ByteArray(assembly.totalBytes)
        var offset = 0
        assembly.chunks.forEach { part ->
            val bytes = part ?: error("Complete Crew assembly has a missing chunk")
            if (offset + bytes.size > payload.size) {
                discard(key)
                return CrewControlFrameResult.Rejected.MALFORMED
            }
            bytes.copyInto(payload, offset)
            offset += bytes.size
        }
        discard(key)
        if (offset != payload.size || !payload.sha256().contentEquals(chunk.digest)) {
            return CrewControlFrameResult.Rejected.INTEGRITY_FAILED
        }
        return when (val decoded = CrewControlCodec.decode(payload)) {
            is CrewControlDecodeResult.Accepted -> CrewControlFrameResult.Complete(decoded.message)
            is CrewControlDecodeResult.Rejected -> CrewControlFrameResult.Rejected.MESSAGE_REJECTED
        }
    }

    @Synchronized
    fun reset() {
        assemblies.clear()
        bufferedBytes = 0
    }

    private fun pruneExpired(nowMonotonicMs: Long) {
        val expired =
            assemblies
                .filterValues { nowMonotonicMs - it.createdAtMonotonicMs >= messageTtlMs }
                .keys
                .toList()
        expired.forEach(::discard)
    }

    private fun discard(key: String) {
        assemblies.remove(key)?.let { bufferedBytes -= it.receivedBytes }
    }

    private sealed interface DecodedChunk {
        data class Accepted(val chunk: Chunk) : DecodedChunk

        data class Rejected(val reason: CrewControlFrameResult.Rejected) : DecodedChunk
    }

    private fun decodeChunk(payload: ByteArray): DecodedChunk {
        if (payload.size <= FRAME_HEADER_BYTES) {
            return DecodedChunk.Rejected(CrewControlFrameResult.Rejected.MALFORMED)
        }
        return try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != FRAME_MAGIC) {
                    return DecodedChunk.Rejected(CrewControlFrameResult.Rejected.UNSUPPORTED_FORMAT)
                }
                if (input.readUnsignedByte() != FRAME_FORMAT_VERSION) {
                    return DecodedChunk.Rejected(CrewControlFrameResult.Rejected.UNSUPPORTED_FORMAT)
                }
                val totalBytes = input.readInt()
                val index = input.readUnsignedShort()
                val count = input.readUnsignedShort()
                val digest = ByteArray(SHA_256_BYTES).also(input::readFully)
                val chunkPayload = ByteArray(input.available()).also(input::readFully)
                if (
                    totalBytes !in 1..MAX_LOGICAL_MESSAGE_BYTES ||
                        count !in 1..MAX_CONTROL_CHUNKS ||
                        index !in 0 until count ||
                        chunkPayload.isEmpty() ||
                        chunkPayload.size >
                            CrewTransportChannel.CONTROL.maxPayloadBytes - FRAME_HEADER_BYTES
                ) {
                    DecodedChunk.Rejected(CrewControlFrameResult.Rejected.MALFORMED)
                } else {
                    DecodedChunk.Accepted(Chunk(totalBytes, index, count, digest, chunkPayload))
                }
            }
        } catch (_: IOException) {
            DecodedChunk.Rejected(CrewControlFrameResult.Rejected.MALFORMED)
        } catch (_: IllegalArgumentException) {
            DecodedChunk.Rejected(CrewControlFrameResult.Rejected.MALFORMED)
        }
    }
}

private fun DataOutputStream.writeActionRequest(request: CrewActionRequest) {
    writeSizedString(request.id.value, MAX_ID_BYTES)
    writeMemberId(request.issuingMemberId)
    writeLong(request.clientMonotonicTimestampMs)
    writeAction(request.action)
    writeNullableLong(request.baseSequence?.value)
}

private fun DataInputStream.readActionRequest() =
    CrewActionRequest(
        id = DurableEventId(readSizedString(MAX_ID_BYTES)),
        issuingMemberId = readMemberId(),
        clientMonotonicTimestampMs = readLong(),
        action = readAction(),
        baseSequence = readNullableLong()?.let(::EventSequence),
    )

private fun DataOutputStream.writeDurableEvent(event: DurableCrewEvent) {
    writeSessionId(event.sessionId)
    writeProtocolVersion(event.protocolVersion)
    writeLong(event.term.value)
    writeLong(event.sequence.value)
    writeSizedString(event.id.value, MAX_ID_BYTES)
    writeMemberId(event.publisherMemberId)
    writeMemberId(event.issuingMemberId)
    writeLong(event.clientMonotonicTimestampMs)
    writeAction(event.action)
}

private fun DataInputStream.readDurableEvent() =
    DurableCrewEvent(
        sessionId = readSessionId(),
        protocolVersion = readProtocolVersion(),
        term = CoordinatorTerm(readLong()),
        sequence = EventSequence(readLong()),
        id = DurableEventId(readSizedString(MAX_ID_BYTES)),
        publisherMemberId = readMemberId(),
        issuingMemberId = readMemberId(),
        clientMonotonicTimestampMs = readLong(),
        action = readAction(),
    )

private fun DataOutputStream.writeSnapshotRequest(request: CrewSnapshotRequest) {
    writeSessionId(request.sessionId)
    writeProtocolVersion(request.protocolVersion)
    writeMemberId(request.requestingMemberId)
    writeLong(request.knownTerm.value)
    writeLong(request.knownSequence.value)
}

private fun DataInputStream.readSnapshotRequest() =
    CrewSnapshotRequest(
        sessionId = readSessionId(),
        protocolVersion = readProtocolVersion(),
        requestingMemberId = readMemberId(),
        knownTerm = CoordinatorTerm(readLong()),
        knownSequence = EventSequence(readLong()),
    )

private fun DataOutputStream.writeSnapshot(snapshot: CrewSnapshot) {
    writeInt(snapshot.snapshotVersion.value)
    writeSessionId(snapshot.sessionId)
    writeProtocolVersion(snapshot.protocolVersion)
    writeLong(snapshot.term.value)
    writeLong(snapshot.lastSequence.value)
    writeMemberId(snapshot.publisherMemberId)
    writeMemberId(snapshot.coordinatorMemberId)
    writeBoundedCount(snapshot.members.size, MAX_MEMBERS)
    snapshot.members.forEach(::writeMember)
    writeQueue(snapshot.queue)
    writePlayback(snapshot.playback)
    writeBoolean(snapshot.shuffleEnabled)
    writeByte(snapshot.repeatMode.wireCode())
}

private fun DataInputStream.readSnapshot() =
    CrewSnapshot(
        snapshotVersion = CrewSnapshotVersion(readInt()),
        sessionId = readSessionId(),
        protocolVersion = readProtocolVersion(),
        term = CoordinatorTerm(readLong()),
        lastSequence = EventSequence(readLong()),
        publisherMemberId = readMemberId(),
        coordinatorMemberId = readMemberId(),
        members = readBoundedList(MAX_MEMBERS) { readMember() },
        queue = readQueue(),
        playback = readPlayback(),
        shuffleEnabled = readStrictBoolean(),
        repeatMode = readRepeatMode(),
    )

private fun DataOutputStream.writeElectionVote(vote: CrewElectionVote) {
    writeMemberId(vote.voterMemberId)
    writeMemberId(vote.candidateMemberId)
    writeElectionCheckpoint(vote.checkpoint)
}

private fun DataInputStream.readElectionVote() =
    CrewElectionVote(
        voterMemberId = readMemberId(),
        candidateMemberId = readMemberId(),
        checkpoint = readElectionCheckpoint(),
    )

private fun DataOutputStream.writeElectionCheckpoint(checkpoint: CrewElectionCheckpoint) {
    writeSessionId(checkpoint.sessionId)
    writeProtocolVersion(checkpoint.protocolVersion)
    writeLong(checkpoint.term.value)
    writeLong(checkpoint.sequence.value)
    writeMemberId(checkpoint.coordinatorMemberId)
    writeBoundedCount(checkpoint.memberIds.size, MAX_MEMBERS)
    checkpoint.memberIds.forEach(::writeMemberId)
}

private fun DataInputStream.readElectionCheckpoint() =
    CrewElectionCheckpoint(
        sessionId = readSessionId(),
        protocolVersion = readProtocolVersion(),
        term = CoordinatorTerm(readLong()),
        sequence = EventSequence(readLong()),
        coordinatorMemberId = readMemberId(),
        memberIds = readBoundedList(MAX_MEMBERS) { readMemberId() },
    )

private fun DataOutputStream.writeAvailabilityAnnouncement(
    announcement: CrewAvailabilityAnnouncement
) {
    writeSessionId(announcement.sessionId)
    writeProtocolVersion(announcement.protocolVersion)
    writeMemberId(announcement.publishingMemberId)
    writeLong(announcement.knownTerm.value)
    writeLong(announcement.knownSequence.value)
    writeBoundedCount(announcement.entries.size, MAX_CREW_AVAILABILITY_ENTRIES)
    announcement.entries.forEach { entry ->
        writeSizedString(entry.queueItemId.value, MAX_ID_BYTES)
        writeByte(entry.availability.wireCode())
    }
}

private fun DataInputStream.readAvailabilityAnnouncement() =
    CrewAvailabilityAnnouncement(
        sessionId = readSessionId(),
        protocolVersion = readProtocolVersion(),
        publishingMemberId = readMemberId(),
        knownTerm = CoordinatorTerm(readLong()),
        knownSequence = EventSequence(readLong()),
        entries =
            readBoundedList(MAX_CREW_AVAILABILITY_ENTRIES) {
                CrewAvailabilityEntry(
                    queueItemId = QueueItemId(readSizedString(MAX_ID_BYTES)),
                    availability = readCrewAvailability(),
                )
            },
    )

private fun DataOutputStream.writeAction(action: CrewAction) {
    when (action) {
        is CrewAction.MemberJoined -> {
            writeByte(1)
            writeMember(action.member)
        }
        is CrewAction.MemberUpdated -> {
            writeByte(2)
            writeMember(action.member)
        }
        is CrewAction.MemberLeft -> {
            writeByte(3)
            writeMemberId(action.memberId)
        }
        is CrewAction.QueueReplaced -> {
            writeByte(4)
            writeQueue(action.items)
        }
        is CrewAction.QueueItemInserted -> {
            writeByte(5)
            writeQueueItem(action.item)
            writeInt(action.index)
            writeNullableString(action.beforeItemId?.value, MAX_ID_BYTES)
            writeNullableString(action.afterItemId?.value, MAX_ID_BYTES)
        }
        is CrewAction.QueueItemMoved -> {
            writeByte(6)
            writeSizedString(action.itemId.value, MAX_ID_BYTES)
            writeInt(action.newIndex)
            writeNullableString(action.beforeItemId?.value, MAX_ID_BYTES)
            writeNullableString(action.afterItemId?.value, MAX_ID_BYTES)
        }
        is CrewAction.QueueItemRemoved -> {
            writeByte(7)
            writeSizedString(action.itemId.value, MAX_ID_BYTES)
        }
        is CrewAction.CurrentItemChanged -> {
            writeByte(8)
            writeSizedString(action.itemId.value, MAX_ID_BYTES)
        }
        is CrewAction.Play -> {
            writeByte(9)
            writeLong(action.positionAtEpochMs)
            writeLong(action.sessionEpochMs)
        }
        is CrewAction.Pause -> {
            writeByte(10)
            writeLong(action.positionAtEpochMs)
            writeLong(action.sessionEpochMs)
        }
        is CrewAction.Seek -> {
            writeByte(11)
            writeLong(action.positionAtEpochMs)
            writeLong(action.sessionEpochMs)
        }
        is CrewAction.ShuffleChanged -> {
            writeByte(12)
            writeBoolean(action.enabled)
        }
        is CrewAction.RepeatChanged -> {
            writeByte(13)
            writeByte(action.mode.wireCode())
        }
        is CrewAction.CoordinatorTransferred -> {
            writeByte(14)
            writeMemberId(action.newCoordinatorMemberId)
        }
        CrewAction.SessionEnded -> writeByte(15)
    }
}

private fun DataOutputStream.writeRejoinLease(lease: CrewRejoinLease) {
    writeSessionId(lease.sessionId)
    writeProtocolVersion(lease.protocolVersion)
    writeMemberId(lease.memberId)
    writeSizedString(lease.sessionLocator.value, MAX_ID_BYTES)
    writeBoolean(lease.relayLocator != null)
    lease.relayLocator?.let { writeSizedString(it.value, MAX_LOCATOR_BYTES) }
    writeSizedString(lease.rendezvousInviteId.value, MAX_ID_BYTES)
    writeSizedString(lease.credentialId, MAX_ID_BYTES)
    writeSizedString(lease.credentialSecret, MAX_ID_BYTES)
    writeLong(lease.issuedAtEpochMs)
    writeLong(lease.expiresAtEpochMs)
}

private fun DataInputStream.readRejoinLease(): CrewRejoinLease {
    val sessionId = readSessionId()
    val protocolVersion = readProtocolVersion()
    val memberId = readMemberId()
    val sessionLocator = CrewSessionLocator(readSizedString(MAX_ID_BYTES))
    val relayLocator =
        if (readBoolean()) CrewRelayLocator(readSizedString(MAX_LOCATOR_BYTES)) else null
    val inviteId = CrewInviteId(readSizedString(MAX_ID_BYTES))
    val credentialId = readSizedString(MAX_ID_BYTES)
    val credentialSecret = readSizedString(MAX_ID_BYTES)
    return CrewRejoinLease(
            sessionId,
            memberId,
            sessionLocator,
            relayLocator,
            inviteId,
            credentialId,
            credentialSecret,
            readLong(),
            readLong(),
        )
        .also {
            require(it.protocolVersion == protocolVersion) { "Crew credential protocol mismatch" }
        }
}

private fun DataInputStream.readAction(): CrewAction =
    when (readUnsignedByte()) {
        1 -> CrewAction.MemberJoined(readMember())
        2 -> CrewAction.MemberUpdated(readMember())
        3 -> CrewAction.MemberLeft(readMemberId())
        4 -> CrewAction.QueueReplaced(readQueue())
        5 ->
            CrewAction.QueueItemInserted(
                readQueueItem(),
                readInt(),
                readNullableString(MAX_ID_BYTES)?.let(::QueueItemId),
                readNullableString(MAX_ID_BYTES)?.let(::QueueItemId),
            )
        6 ->
            CrewAction.QueueItemMoved(
                QueueItemId(readSizedString(MAX_ID_BYTES)),
                readInt(),
                readNullableString(MAX_ID_BYTES)?.let(::QueueItemId),
                readNullableString(MAX_ID_BYTES)?.let(::QueueItemId),
            )
        7 -> CrewAction.QueueItemRemoved(QueueItemId(readSizedString(MAX_ID_BYTES)))
        8 -> CrewAction.CurrentItemChanged(QueueItemId(readSizedString(MAX_ID_BYTES)))
        9 -> CrewAction.Play(readLong(), readLong())
        10 -> CrewAction.Pause(readLong(), readLong())
        11 -> CrewAction.Seek(readLong(), readLong())
        12 -> CrewAction.ShuffleChanged(readStrictBoolean())
        13 -> CrewAction.RepeatChanged(readRepeatMode())
        14 -> CrewAction.CoordinatorTransferred(readMemberId())
        15 -> CrewAction.SessionEnded
        else -> throw IOException("Unknown Crew action")
    }

private fun DataOutputStream.writeQueue(queue: List<QueueItem>) {
    writeBoundedCount(queue.size, MAX_QUEUE_ITEMS)
    queue.forEach(::writeQueueItem)
}

private fun DataInputStream.readQueue() = readBoundedList(MAX_QUEUE_ITEMS) { readQueueItem() }

private fun DataOutputStream.writeQueueItem(item: QueueItem) {
    val publicItem = publicizeCrewQueueItem(item)
    writeSizedString(publicItem.id.value, MAX_ID_BYTES)
    writeTrack(publicItem.track)
    writeNullableString(publicItem.contextId, MAX_ID_BYTES)
    writeNullableString(publicItem.contributorId, MAX_ID_BYTES)
}

private fun DataInputStream.readQueueItem() =
    QueueItem(
        id = QueueItemId(readSizedString(MAX_ID_BYTES)),
        track = readTrack(),
        contextId = readNullableString(MAX_ID_BYTES),
        contributorId = readNullableString(MAX_ID_BYTES),
    )

private fun DataOutputStream.writeTrack(track: Track) {
    writeSizedString(track.id.value, MAX_ID_BYTES)
    writeByte(track.realm.wireCode())
    writeSizedString(track.title)
    writeBoundedCount(track.artists.size, MAX_ARTISTS)
    track.artists.forEach(::writeSizedString)
    writeNullableString(track.album)
    writeNullableLong(track.durationMs)
    writeTrackVersion(track.version)
    writeNullableString(track.artwork, MAX_LOCATOR_BYTES)
    writeBoundedCount(track.candidates.size, MAX_CANDIDATES)
    track.candidates.forEach(::writeCandidate)
}

private fun DataInputStream.readTrack() =
    Track(
        id = TrackId(readSizedString(MAX_ID_BYTES)),
        realm =
            when (readUnsignedByte()) {
                1 -> TrackRealm.PROVIDER
                2 -> TrackRealm.LOCAL
                else -> throw IOException("Unknown track realm")
            },
        title = readSizedString(),
        artists = readBoundedList(MAX_ARTISTS) { readSizedString() },
        album = readNullableString(),
        durationMs = readNullableLong(),
        version = readTrackVersion(),
        artwork = readNullableString(MAX_LOCATOR_BYTES),
        candidates = readBoundedList(MAX_CANDIDATES) { readCandidate() },
    )

private fun DataOutputStream.writeTrackVersion(version: TrackVersion) {
    writeNullableString(version.label)
    writeNullableBoolean(version.explicit)
    writeBoolean(version.isLive)
    writeBoolean(version.isRemix)
}

private fun DataInputStream.readTrackVersion() =
    TrackVersion(
        label = readNullableString(),
        explicit = readNullableBoolean(),
        isLive = readStrictBoolean(),
        isRemix = readStrictBoolean(),
    )

private fun DataOutputStream.writeCandidate(candidate: TrackCandidate) {
    writeSizedString(candidate.id.value, MAX_ID_BYTES)
    writeSizedString(candidate.trackId.value, MAX_ID_BYTES)
    writeByte(candidate.kind.wireCode())
    writeSizedString(candidate.sourceId, MAX_ID_BYTES)
    writeSizedString(candidate.sourceItemId)
    writeByte(candidate.availability.wireCode())
    writeNullableString(candidate.locator, MAX_LOCATOR_BYTES)
    writeNullableString(candidate.providerId?.value, MAX_ID_BYTES)
    writeNullableMedia(candidate.media)
}

private fun DataInputStream.readCandidate() =
    TrackCandidate(
        id = CandidateId(readSizedString(MAX_ID_BYTES)),
        trackId = TrackId(readSizedString(MAX_ID_BYTES)),
        kind = readCandidateKind(),
        sourceId = readSizedString(MAX_ID_BYTES),
        sourceItemId = readSizedString(),
        availability = readCandidateAvailability(),
        locator = readNullableString(MAX_LOCATOR_BYTES),
        providerId = readNullableString(MAX_ID_BYTES)?.let(::ProviderId),
        media = readNullableMedia(),
    )

private fun DataOutputStream.writeNullableMedia(media: MediaDescriptor?) {
    writeBoolean(media != null)
    if (media != null) {
        writeNullableString(media.mimeType)
        writeNullableString(media.container)
        writeNullableInt(media.bitrateBps)
        writeNullableLong(media.contentLength)
    }
}

private fun DataInputStream.readNullableMedia(): MediaDescriptor? {
    if (!readStrictBoolean()) return null
    return MediaDescriptor(
        mimeType = readNullableString(),
        container = readNullableString(),
        bitrateBps = readNullableInt(),
        contentLength = readNullableLong(),
    )
}

private fun DataOutputStream.writePlayback(playback: CrewPlaybackState) {
    writeNullableString(playback.currentQueueItemId?.value)
    writeByte(playback.mode.wireCode())
    writeLong(playback.positionAtEpochMs)
    writeLong(playback.sessionEpochMs)
}

private fun DataInputStream.readPlayback() =
    CrewPlaybackState(
        currentQueueItemId = readNullableString()?.let(::QueueItemId),
        mode = readPlaybackMode(),
        positionAtEpochMs = readLong(),
        sessionEpochMs = readLong(),
    )

private fun DataOutputStream.writeMember(member: CrewMember) {
    writeMemberId(member.id)
    writeSizedString(member.displayName, MAX_DISPLAY_NAME_BYTES)
    writeSizedString(member.profileId.value, MAX_ID_BYTES)
    writeSizedString(member.avatar.value, MAX_AVATAR_DESCRIPTOR_BYTES)
}

private fun DataInputStream.readMember() =
    CrewMember(
        id = readMemberId(),
        displayName = readSizedString(MAX_DISPLAY_NAME_BYTES),
        profileId = CrewProfileId(readSizedString(MAX_ID_BYTES)),
        avatar = CrewAvatarDescriptor(readSizedString(MAX_AVATAR_DESCRIPTOR_BYTES)),
    )

private fun DataOutputStream.writeSessionId(id: CrewSessionId) {
    writeSizedString(id.value, MAX_ID_BYTES)
    writeProtocolVersion(id.protocolVersion)
}

private fun DataInputStream.readSessionId() =
    CrewSessionId(readSizedString(MAX_ID_BYTES), readProtocolVersion())

private fun DataOutputStream.writeMemberId(id: CrewMemberId) {
    writeSizedString(id.value, MAX_ID_BYTES)
    writeProtocolVersion(id.protocolVersion)
}

private fun DataInputStream.readMemberId() =
    CrewMemberId(readSizedString(MAX_ID_BYTES), readProtocolVersion())

private fun DataOutputStream.writeProtocolVersion(version: ProtocolVersion) {
    writeInt(version.value)
}

private fun DataInputStream.readProtocolVersion() = ProtocolVersion(readInt())

private fun DataOutputStream.writeSizedString(value: String, maxBytes: Int = MAX_STRING_BYTES) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.size <= maxBytes) { "Crew control string exceeds $maxBytes bytes" }
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readSizedString(maxBytes: Int = MAX_STRING_BYTES): String {
    val size = readInt()
    require(size in 0..maxBytes) { "Crew control string size is invalid" }
    val bytes = ByteArray(size).also(::readFully)
    return Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}

private fun DataOutputStream.writeNullableString(value: String?, maxBytes: Int = MAX_STRING_BYTES) {
    writeBoolean(value != null)
    if (value != null) writeSizedString(value, maxBytes)
}

private fun DataInputStream.readNullableString(maxBytes: Int = MAX_STRING_BYTES): String? =
    if (readStrictBoolean()) readSizedString(maxBytes) else null

private fun DataOutputStream.writeNullableLong(value: Long?) {
    writeBoolean(value != null)
    if (value != null) writeLong(value)
}

private fun DataInputStream.readNullableLong(): Long? =
    if (readStrictBoolean()) readLong() else null

private fun DataOutputStream.writeNullableInt(value: Int?) {
    writeBoolean(value != null)
    if (value != null) writeInt(value)
}

private fun DataInputStream.readNullableInt(): Int? = if (readStrictBoolean()) readInt() else null

private fun DataOutputStream.writeNullableBoolean(value: Boolean?) {
    writeByte(
        when (value) {
            null -> 0
            false -> 1
            true -> 2
        }
    )
}

private fun DataInputStream.readNullableBoolean(): Boolean? =
    when (readUnsignedByte()) {
        0 -> null
        1 -> false
        2 -> true
        else -> throw IOException("Invalid nullable boolean")
    }

private fun DataInputStream.readStrictBoolean(): Boolean =
    when (readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw IOException("Invalid boolean")
    }

private fun DataOutputStream.writeBoundedCount(count: Int, maximum: Int) {
    require(count in 0..maximum) { "Crew control collection exceeds $maximum items" }
    writeInt(count)
}

private inline fun <T> DataInputStream.readBoundedList(
    maximum: Int,
    readItem: DataInputStream.() -> T,
): List<T> {
    val count = readInt()
    require(count in 0..maximum) { "Crew control collection size is invalid" }
    return List(count) { readItem() }
}

private fun CrewRepeatMode.wireCode() =
    when (this) {
        CrewRepeatMode.OFF -> 1
        CrewRepeatMode.ALL -> 2
        CrewRepeatMode.ONE -> 3
    }

private fun DataInputStream.readRepeatMode() =
    when (readUnsignedByte()) {
        1 -> CrewRepeatMode.OFF
        2 -> CrewRepeatMode.ALL
        3 -> CrewRepeatMode.ONE
        else -> throw IOException("Unknown Crew repeat mode")
    }

private fun CrewAvailability.wireCode() =
    when (this) {
        CrewAvailability.LOCAL_EXACT -> 1
        CrewAvailability.TEMPORARY_CACHE -> 2
        CrewAvailability.DOWNLOAD -> 3
        CrewAvailability.PREFERRED_PROVIDER -> 4
        CrewAvailability.FALLBACK_PROVIDER -> 5
        CrewAvailability.PEER_ONLY -> 6
        CrewAvailability.BLOCKED -> 7
        CrewAvailability.UNAVAILABLE -> 8
    }

private fun DataInputStream.readCrewAvailability() =
    when (readUnsignedByte()) {
        1 -> CrewAvailability.LOCAL_EXACT
        2 -> CrewAvailability.TEMPORARY_CACHE
        3 -> CrewAvailability.DOWNLOAD
        4 -> CrewAvailability.PREFERRED_PROVIDER
        5 -> CrewAvailability.FALLBACK_PROVIDER
        6 -> CrewAvailability.PEER_ONLY
        7 -> CrewAvailability.BLOCKED
        8 -> CrewAvailability.UNAVAILABLE
        else -> throw IOException("Unknown Crew availability")
    }

private fun CrewPlaybackMode.wireCode() =
    when (this) {
        CrewPlaybackMode.IDLE -> 1
        CrewPlaybackMode.PREPARING -> 2
        CrewPlaybackMode.PLAYING -> 3
        CrewPlaybackMode.PAUSED -> 4
        CrewPlaybackMode.BUFFERING -> 5
        CrewPlaybackMode.ENDED -> 6
    }

private fun DataInputStream.readPlaybackMode() =
    when (readUnsignedByte()) {
        1 -> CrewPlaybackMode.IDLE
        2 -> CrewPlaybackMode.PREPARING
        3 -> CrewPlaybackMode.PLAYING
        4 -> CrewPlaybackMode.PAUSED
        5 -> CrewPlaybackMode.BUFFERING
        6 -> CrewPlaybackMode.ENDED
        else -> throw IOException("Unknown Crew playback mode")
    }

private fun TrackRealm.wireCode() =
    when (this) {
        TrackRealm.PROVIDER -> 1
        TrackRealm.LOCAL -> 2
    }

private fun CandidateKind.wireCode() =
    when (this) {
        CandidateKind.LOCAL -> 1
        CandidateKind.DOWNLOAD -> 2
        CandidateKind.PROVIDER -> 3
        CandidateKind.CREW_TEMPORARY -> 4
        CandidateKind.CREW_PEER -> 5
    }

private fun DataInputStream.readCandidateKind() =
    when (readUnsignedByte()) {
        1 -> CandidateKind.LOCAL
        2 -> CandidateKind.DOWNLOAD
        3 -> CandidateKind.PROVIDER
        4 -> CandidateKind.CREW_TEMPORARY
        5 -> CandidateKind.CREW_PEER
        else -> throw IOException("Unknown candidate kind")
    }

private fun CandidateAvailability.wireCode() =
    when (this) {
        CandidateAvailability.AVAILABLE -> 1
        CandidateAvailability.RESOLVABLE -> 2
        CandidateAvailability.UNAVAILABLE -> 3
    }

private fun DataInputStream.readCandidateAvailability() =
    when (readUnsignedByte()) {
        1 -> CandidateAvailability.AVAILABLE
        2 -> CandidateAvailability.RESOLVABLE
        3 -> CandidateAvailability.UNAVAILABLE
        else -> throw IOException("Unknown candidate availability")
    }

private fun CrewSequenceRejection.wireCode() =
    when (this) {
        CrewSequenceRejection.NOT_COORDINATOR -> 1
        CrewSequenceRejection.REQUESTER_MISMATCH -> 2
        CrewSequenceRejection.REQUESTER_NOT_ACTIVE -> 3
        CrewSequenceRejection.PROTOCOL_MISMATCH -> 4
        CrewSequenceRejection.DUPLICATE_RECEIPT_UNAVAILABLE -> 5
        CrewSequenceRejection.ACTION_REJECTED -> 6
        CrewSequenceRejection.STALE_BASE_REVISION -> 7
    }

private fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)

private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
