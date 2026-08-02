/*
 * Copyright (c) 2026 Auxio Project
 * CrewMediaTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.media

import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.cache.CrewTemporaryMediaCache
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport
import org.oxycblt.auxio.shippy.crew.transport.CrewSendResult
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportState
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.QueueItemId

class CrewMediaTest {
    @Test
    fun `manifest and chunks round trip without locators or secrets`() {
        val manifest = manifest(byteArrayOf(1, 2), byteArrayOf(3, 4, 5))
        val decoded =
            CrewMediaWireCodec.decode(
                CrewMediaWireCodec.encode(CrewMediaWireFrame.Manifest(manifest))
            )

        assertEquals(CrewMediaWireFrame.Manifest(manifest), decoded)
        assertEquals(
            CrewMediaWireFrame.Chunk(chunk(manifest, 0, byteArrayOf(1, 2))),
            CrewMediaWireCodec.decode(
                CrewMediaWireCodec.encode(
                    CrewMediaWireFrame.Chunk(chunk(manifest, 0, byteArrayOf(1, 2)))
                )
            ),
        )
        assertFalse(
            CrewMediaWireCodec.encode(CrewMediaWireFrame.Manifest(manifest))
                .decodeToString()
                .contains("file:")
        )
    }

    @Test
    fun `receiver checks each chunk then whole object and persists only complete object`() {
        val first = byteArrayOf(1, 2)
        val second = byteArrayOf(3, 4, 5)
        val manifest = manifest(first, second)
        val cache = CrewTemporaryMediaCache(tempDirectory())
        cache.beginSession(manifest.transfer.sessionId)
        val receiver = CrewMediaReceiver(manifest.transfer.sessionId, cache)

        assertEquals(CrewMediaReceiveResult.Accepted(0), receiver.accept(manifest))
        assertEquals(
            CrewMediaReceiveResult.Accepted(0),
            receiver.accept(chunk(manifest, 1, second)),
        )
        val complete = receiver.accept(chunk(manifest, 0, first)) as CrewMediaReceiveResult.Complete
        assertEquals(manifest, complete.manifest)
        assertTrue(complete.file.isFile)
        assertArrayEquals(
            first + second,
            cache.read(manifest.transfer.sessionId, manifest.objectIntegrity),
        )
    }

    @Test
    fun `receiver rejects a manifest before transfer when cache capacity is unavailable`() {
        val bytes = byteArrayOf(1, 2)
        val manifest = manifest(bytes)
        val cache = CrewTemporaryMediaCache(tempDirectory(), maxBytes = 1)
        cache.beginSession(manifest.transfer.sessionId)
        val receiver = CrewMediaReceiver(manifest.transfer.sessionId, cache)
        assertEquals(
            CrewMediaReceiveResult.Retry("temporary cache unavailable"),
            receiver.accept(manifest),
        )
        assertEquals(
            CrewMediaReceiveResult.Retry("temporary cache unavailable"),
            receiver.accept(manifest),
        )
    }

    @Test
    fun `invalid chunk is rejected and receiver window asks sender to retry`() {
        val manifest = manifest(byteArrayOf(1, 2), byteArrayOf(3, 4, 5))
        val cache = CrewTemporaryMediaCache(tempDirectory())
        cache.beginSession(manifest.transfer.sessionId)
        val receiver =
            CrewMediaReceiver(
                manifest.transfer.sessionId,
                cache,
                CrewMediaReceiverPolicy(maxAssemblies = 1, maxBufferedBytes = 5),
            )

        assertEquals(CrewMediaReceiveResult.Accepted(0), receiver.accept(manifest))
        assertTrue(
            receiver.accept(chunk(manifest, 0, byteArrayOf(9))) is CrewMediaReceiveResult.Rejected
        )
        assertTrue(
            receiver.accept(manifest.copy(objectIntegrity = CrewMediaDigest.sha256(byteArrayOf(9))))
                is CrewMediaReceiveResult.Rejected
        )
    }

    @Test
    fun `receiver reserves declared object bytes and cancel releases the window`() {
        val first = manifest(byteArrayOf(1, 2, 3))
        val second =
            first.copy(
                transfer = first.transfer.copy(requestId = CrewMediaRequestId("request-2")),
                objectIntegrity = CrewMediaDigest.sha256(byteArrayOf(4, 5, 6)),
            )
        val cache = CrewTemporaryMediaCache(tempDirectory())
        cache.beginSession(first.transfer.sessionId)
        val receiver =
            CrewMediaReceiver(
                first.transfer.sessionId,
                cache,
                CrewMediaReceiverPolicy(maxAssemblies = 2, maxBufferedBytes = 3),
            )

        assertEquals(CrewMediaReceiveResult.Accepted(0), receiver.accept(first))
        assertTrue(receiver.accept(second) is CrewMediaReceiveResult.Retry)
        assertTrue(receiver.cancel(first.transfer))
        assertEquals(CrewMediaReceiveResult.Accepted(0), receiver.accept(second))
    }

    @Test
    fun `cache clears stale crash data and session end data`() {
        val root = tempDirectory()
        val cache = CrewTemporaryMediaCache(root)
        val old = manifest(byteArrayOf(1))
        cache.beginSession(old.transfer.sessionId)
        cache.put(old, byteArrayOf(1))
        val current =
            old.copy(
                transfer = old.transfer.copy(sessionId = CrewSessionId("next", ProtocolVersion(1)))
            )

        cache.beginSession(current.transfer.sessionId)
        assertEquals(null, cache.read(old.transfer.sessionId, old.objectIntegrity))
        cache.put(current, byteArrayOf(1))
        cache.endSession(current.transfer.sessionId)
        assertEquals(null, cache.read(current.transfer.sessionId, current.objectIntegrity))
    }

    @Test
    fun `push pull only accepts the active Crew`() {
        val policy = ActiveCrewPushPullPolicy()
        val first = CrewSessionId("first", ProtocolVersion(1))
        val second = CrewSessionId("second", ProtocolVersion(1))
        policy.activate(first, true)
        assertTrue(policy.accepts(first))
        assertFalse(policy.accepts(second))
        policy.deactivate(first)
        assertFalse(policy.accepts(first))
    }

    @Test
    fun `request protocol frames round trip and keep exact target supplier identity`() {
        val transfer = manifest(byteArrayOf(1)).transfer
        listOf(
                CrewMediaWireFrame.Request(transfer),
                CrewMediaWireFrame.Cancel(transfer),
                CrewMediaWireFrame.ManifestAccepted(transfer),
                CrewMediaWireFrame.RetryLater(transfer),
                CrewMediaWireFrame.Rejected(transfer),
                CrewMediaWireFrame.ObjectComplete(transfer),
            )
            .forEach { assertEquals(it, CrewMediaWireCodec.decode(CrewMediaWireCodec.encode(it))) }
    }

    @Test
    fun `transfer codec preserves exact queue item identity and bounds it`() {
        val transfer = manifest(byteArrayOf(1)).transfer
        val decoded =
            CrewMediaWireCodec.decode(
                CrewMediaWireCodec.encode(CrewMediaWireFrame.Request(transfer))
            ) as CrewMediaWireFrame.Request
        assertEquals(transfer.queueItemId, decoded.transfer.queueItemId)
        val previousVersion =
            CrewMediaWireCodec.encode(CrewMediaWireFrame.Request(transfer)).also { it[0] = 2 }
        assertTrue(runCatching { CrewMediaWireCodec.decode(previousVersion) }.isFailure)
        val tooLong =
            transfer.copy(queueItemId = QueueItemId("q".repeat(CREW_MEDIA_MAX_ID_BYTES + 1)))
        assertTrue(
            runCatching { CrewMediaWireCodec.encode(CrewMediaWireFrame.Request(tooLong)) }.isFailure
        )
    }

    @Test
    fun `producer bounds source before transfer and emits verified chunks`() {
        val bytes = byteArrayOf(1, 2, 3)
        val source =
            object : CrewAuthorizedMediaSource {
                override val lengthBytes = bytes.size.toLong()
                override val mimeType = "audio/test"

                override fun open() = ByteArrayInputStream(bytes)
            }
        val (manifest, chunks) = CrewMediaProducer.produce(manifest(bytes).transfer, source)
        assertEquals(CrewMediaDigest.sha256(bytes), manifest.objectIntegrity)
        assertEquals(
            CrewMediaDigest.sha256(bytes),
            chunks.single().let { CrewMediaDigest.sha256(it.copyPayload()) },
        )
        assertTrue(
            runCatching { CrewMediaWireCodec.decode(ByteArray(CREW_MEDIA_MAX_FRAME_BYTES + 1)) }
                .isFailure
        )
    }

    @Test
    fun `producer safely discovers an initially unknown repeatable content length`() {
        val bytes = ByteArray(CREW_MEDIA_MAX_CHUNK_BYTES + 7) { (it % 251).toByte() }
        val source =
            object : CrewAuthorizedMediaSource {
                override val lengthBytes = CrewAuthorizedMediaSource.UNKNOWN_LENGTH
                override val mimeType = "audio/test"

                override fun open() = ByteArrayInputStream(bytes)
            }

        val (described, chunks) =
            CrewMediaProducer.produce(manifest(byteArrayOf(1)).transfer, source)

        assertEquals(bytes.size.toLong(), described.objectSizeBytes)
        assertEquals(2, chunks.size)
        assertArrayEquals(bytes, chunks.flatMap { it.copyPayload().asIterable() }.toByteArray())
    }

    @Test
    fun `verified ranges resume after session cache recreation`() {
        val first = byteArrayOf(1, 2)
        val second = byteArrayOf(3, 4, 5)
        val manifest = manifest(first, second)
        val root = tempDirectory()
        val initialCache = CrewTemporaryMediaCache(root)
        initialCache.beginSession(manifest.transfer.sessionId)
        val initialReceiver = CrewMediaReceiver(manifest.transfer.sessionId, initialCache)
        initialReceiver.accept(manifest)
        assertEquals(
            CrewMediaReceiveResult.Accepted(1),
            initialReceiver.accept(chunk(manifest, 0, first)),
        )
        initialCache.suspendSessionForRecovery(manifest.transfer.sessionId)

        val restoredCache = CrewTemporaryMediaCache(root)
        restoredCache.beginSession(manifest.transfer.sessionId)
        val restoredReceiver = CrewMediaReceiver(manifest.transfer.sessionId, restoredCache)
        assertEquals(CrewMediaReceiveResult.Accepted(1), restoredReceiver.accept(manifest))
        val complete =
            restoredReceiver.accept(chunk(manifest, 1, second)) as CrewMediaReceiveResult.Complete
        assertArrayEquals(first + second, complete.file.readBytes())
    }

    @Test
    fun `one hundred mebibyte manifest stays within one bounded media frame`() {
        val objectSize = 100L * 1024L * 1024L
        val chunks = buildList {
            var remaining = objectSize
            var index = 0
            while (remaining > 0) {
                val size = minOf(remaining, CREW_MEDIA_MAX_CHUNK_BYTES.toLong()).toInt()
                add(CrewMediaChunkDescriptor(index++, size))
                remaining -= size
            }
        }
        val template = manifest(byteArrayOf(1))
        val large =
            template.copy(
                objectSizeBytes = objectSize,
                objectIntegrity = CrewMediaDigest(ByteArray(CREW_MEDIA_DIGEST_BYTES)),
                chunks = chunks,
            )

        val encoded = CrewMediaWireCodec.encode(CrewMediaWireFrame.Manifest(large))
        assertTrue(encoded.size <= CREW_MEDIA_MAX_FRAME_BYTES)
        assertEquals(CrewMediaWireFrame.Manifest(large), CrewMediaWireCodec.decode(encoded))
    }

    @Test
    fun `any member can supply while wrong member and disabled policy are rejected`() {
        val transfer = manifest(byteArrayOf(1)).transfer
        val policy = ActiveCrewPushPullPolicy().also { it.activate(transfer.sessionId, true) }
        val peer = FakePeer(transfer.targetMemberId)
        val media = CrewMediaTransport(transfer.sessionId, policy, peer)
        val supplier =
            CrewMediaTransferController(
                transfer.sessionId,
                transfer.supplierMemberId,
                peer,
                media,
                policy,
            )
        assertEquals(
            CrewMediaTransferState.REQUESTED,
            supplier.receive(CrewMediaWireFrame.Request(transfer)),
        )
        val wrong = transfer.copy(targetMemberId = CrewMemberId("other", ProtocolVersion(1)))
        assertEquals(
            CrewMediaTransferState.REJECTED,
            supplier.receive(CrewMediaWireFrame.Request(wrong)),
        )
        policy.deactivate(transfer.sessionId)
        assertEquals(
            CrewMediaTransferState.REJECTED,
            supplier.receive(CrewMediaWireFrame.Request(transfer)),
        )
    }

    @Test
    fun `supplier uses a bounded sliding window and resumes from cumulative acknowledgement`() {
        val template = manifest(byteArrayOf(1))
        val transfer = template.transfer
        val bytes = ByteArray(CREW_MEDIA_MAX_CHUNK_BYTES * 6) { (it % 251).toByte() }
        val source =
            object : CrewAuthorizedMediaSource {
                override val lengthBytes = bytes.size.toLong()
                override val mimeType = "audio/test"

                override fun open() = ByteArrayInputStream(bytes)
            }
        val policy = ActiveCrewPushPullPolicy().also { it.activate(transfer.sessionId, true) }
        val peer = FakePeer(transfer.targetMemberId)
        val controller =
            CrewMediaTransferController(
                transfer.sessionId,
                transfer.supplierMemberId,
                peer,
                CrewMediaTransport(transfer.sessionId, policy, peer),
                policy,
            )
        controller.receive(CrewMediaWireFrame.Request(transfer))
        assertTrue(controller.offer(transfer, source) is CrewSendResult.Sent)
        assertEquals(1, peer.frames.filterIsInstance<CrewMediaWireFrame.Manifest>().size)

        controller.receive(CrewMediaWireFrame.ManifestAccepted(transfer, 0))
        controller.resume(transfer)
        assertEquals(4, peer.frames.filterIsInstance<CrewMediaWireFrame.Chunk>().size)

        controller.receive(CrewMediaWireFrame.ManifestAccepted(transfer, 2))
        controller.resume(transfer)
        assertEquals(6, peer.frames.filterIsInstance<CrewMediaWireFrame.Chunk>().size)
        assertEquals(
            (0 until 6).toList(),
            peer.frames.filterIsInstance<CrewMediaWireFrame.Chunk>().map { it.value.index },
        )
    }

    private fun manifest(vararg chunks: ByteArray): CrewMediaManifest {
        val bytes = chunks.fold(byteArrayOf()) { all, next -> all + next }
        return CrewMediaManifest(
            CrewMediaTransferRef(
                CrewSessionId("session", ProtocolVersion(1)),
                CrewMediaRequestId("request-1"),
                QueueItemId("queue-item-1"),
                CandidateId("crew-temporary:exact"),
                CrewMemberId("target", ProtocolVersion(1)),
                CrewMemberId("supplier", ProtocolVersion(1)),
            ),
            "audio/test",
            bytes.size.toLong(),
            CrewMediaDigest.sha256(bytes),
            chunks.mapIndexed { index, payload -> CrewMediaChunkDescriptor(index, payload.size) },
        )
    }

    private fun chunk(manifest: CrewMediaManifest, index: Int, bytes: ByteArray) =
        CrewMediaChunk(manifest.transfer, manifest.objectIntegrity, index, bytes)

    private fun tempDirectory(): File =
        File.createTempFile("crew-media", "").also { require(it.delete() && it.mkdirs()) }

    private class FakePeer(override val remoteMemberId: CrewMemberId) : CrewPeerTransport {
        val frames = mutableListOf<CrewMediaWireFrame>()
        override val state = MutableStateFlow(CrewTransportState.CONNECTED)
        override val incoming = emptyFlow<CrewTransportFrame>()
        override val drops = emptyFlow<org.oxycblt.auxio.shippy.crew.transport.CrewTransportDrop>()

        override fun trySend(frame: CrewTransportFrame): CrewSendResult {
            if (frame.channel == CrewTransportChannel.MEDIA) {
                frames += CrewMediaWireCodec.decode(frame.copyPayload())
            }
            return CrewSendResult.Sent(0)
        }

        override fun bufferedBytes(channel: CrewTransportChannel) = 0L

        override fun close() = Unit
    }
}
