/* Copyright (c) 2026 Shippy contributors */
package org.oxycblt.auxio.shippy.crew.media

import java.io.File
import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.cache.CrewTemporaryMediaCache
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.domain.CandidateId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport
import org.oxycblt.auxio.shippy.crew.transport.CrewSendResult
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportState
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame

class CrewMediaTest {
    @Test
    fun `manifest and chunks round trip without locators or secrets`() {
        val manifest = manifest(byteArrayOf(1, 2), byteArrayOf(3, 4, 5))
        val decoded = CrewMediaWireCodec.decode(CrewMediaWireCodec.encode(CrewMediaWireFrame.Manifest(manifest)))

        assertEquals(CrewMediaWireFrame.Manifest(manifest), decoded)
        assertEquals(
            CrewMediaWireFrame.Chunk(chunk(manifest, 0, byteArrayOf(1, 2))),
            CrewMediaWireCodec.decode(
                CrewMediaWireCodec.encode(CrewMediaWireFrame.Chunk(chunk(manifest, 0, byteArrayOf(1, 2)))),
            ),
        )
        assertFalse(CrewMediaWireCodec.encode(CrewMediaWireFrame.Manifest(manifest)).decodeToString().contains("file:"))
    }

    @Test
    fun `receiver checks each chunk then whole object and persists only complete object`() {
        val first = byteArrayOf(1, 2)
        val second = byteArrayOf(3, 4, 5)
        val manifest = manifest(first, second)
        val cache = CrewTemporaryMediaCache(tempDirectory())
        cache.beginSession(manifest.transfer.sessionId)
        val receiver = CrewMediaReceiver(manifest.transfer.sessionId, cache)

        assertEquals(CrewMediaReceiveResult.Accepted, receiver.accept(manifest))
        assertEquals(CrewMediaReceiveResult.Accepted, receiver.accept(chunk(manifest, 1, second)))
        assertEquals(CrewMediaReceiveResult.Complete(manifest), receiver.accept(chunk(manifest, 0, first)))
        assertArrayEquals(first + second, cache.read(manifest.transfer.sessionId, manifest.objectIntegrity))
    }

    @Test
    fun `receiver retains a complete bounded assembly when cache publication must retry`() {
        val bytes = byteArrayOf(1, 2)
        val manifest = manifest(bytes)
        val cache = CrewTemporaryMediaCache(tempDirectory(), maxBytes = 1)
        cache.beginSession(manifest.transfer.sessionId)
        val receiver = CrewMediaReceiver(manifest.transfer.sessionId, cache)
        val finalChunk = chunk(manifest, 0, bytes)

        assertEquals(CrewMediaReceiveResult.Accepted, receiver.accept(manifest))
        assertEquals(
            CrewMediaReceiveResult.Retry("temporary cache unavailable"),
            receiver.accept(finalChunk),
        )
        assertEquals(
            CrewMediaReceiveResult.Retry("temporary cache unavailable"),
            receiver.accept(finalChunk),
        )
    }

    @Test
    fun `invalid chunk is rejected and receiver window asks sender to retry`() {
        val manifest = manifest(byteArrayOf(1, 2), byteArrayOf(3, 4, 5))
        val cache = CrewTemporaryMediaCache(tempDirectory())
        cache.beginSession(manifest.transfer.sessionId)
        val receiver = CrewMediaReceiver(manifest.transfer.sessionId, cache, CrewMediaReceiverPolicy(maxAssemblies = 1, maxBufferedBytes = 5))

        assertEquals(CrewMediaReceiveResult.Accepted, receiver.accept(manifest))
        assertTrue(receiver.accept(chunk(manifest, 0, byteArrayOf(9, 9))) is CrewMediaReceiveResult.Rejected)
        assertTrue(receiver.accept(manifest.copy(objectIntegrity = CrewMediaDigest.sha256(byteArrayOf(9)))) is CrewMediaReceiveResult.Retry)
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

        assertEquals(CrewMediaReceiveResult.Accepted, receiver.accept(first))
        assertTrue(receiver.accept(second) is CrewMediaReceiveResult.Retry)
        assertTrue(receiver.cancel(first.transfer))
        assertEquals(CrewMediaReceiveResult.Accepted, receiver.accept(second))
    }

    @Test
    fun `cache clears stale crash data and session end data`() {
        val root = tempDirectory()
        val cache = CrewTemporaryMediaCache(root)
        val old = manifest(byteArrayOf(1))
        cache.beginSession(old.transfer.sessionId)
        cache.put(old, byteArrayOf(1))
        val current = old.copy(transfer = old.transfer.copy(sessionId = CrewSessionId("next", ProtocolVersion(1))))

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
            CrewMediaWireFrame.Request(transfer), CrewMediaWireFrame.Cancel(transfer),
            CrewMediaWireFrame.ManifestAccepted(transfer), CrewMediaWireFrame.RetryLater(transfer),
            CrewMediaWireFrame.Rejected(transfer), CrewMediaWireFrame.ObjectComplete(transfer),
        ).forEach { assertEquals(it, CrewMediaWireCodec.decode(CrewMediaWireCodec.encode(it))) }
    }

    @Test
    fun `producer bounds source before transfer and emits verified chunks`() {
        val bytes = byteArrayOf(1, 2, 3)
        val source = object : CrewAuthorizedMediaSource {
            override val lengthBytes = bytes.size.toLong()
            override val mimeType = "audio/test"
            override fun open() = ByteArrayInputStream(bytes)
        }
        val (manifest, chunks) = CrewMediaProducer.produce(manifest(bytes).transfer, source)
        assertEquals(CrewMediaDigest.sha256(bytes), manifest.objectIntegrity)
        assertEquals(CrewMediaDigest.sha256(bytes), chunks.single().let { CrewMediaDigest.sha256(it.copyPayload()) })
        assertTrue(runCatching { CrewMediaWireCodec.decode(ByteArray(CREW_MEDIA_MAX_FRAME_BYTES + 1)) }.isFailure)
    }

    @Test
    fun `any member can supply while wrong member and disabled policy are rejected`() {
        val transfer = manifest(byteArrayOf(1)).transfer
        val policy = ActiveCrewPushPullPolicy().also { it.activate(transfer.sessionId, true) }
        val peer = FakePeer(transfer.targetMemberId)
        val media = CrewMediaTransport(transfer.sessionId, policy, peer)
        val supplier = CrewMediaTransferController(transfer.sessionId, transfer.supplierMemberId, peer, media, policy)
        assertEquals(CrewMediaTransferState.REQUESTED, supplier.receive(CrewMediaWireFrame.Request(transfer)))
        val wrong = transfer.copy(targetMemberId = CrewMemberId("other", ProtocolVersion(1)))
        assertEquals(CrewMediaTransferState.REJECTED, supplier.receive(CrewMediaWireFrame.Request(wrong)))
        policy.deactivate(transfer.sessionId)
        assertEquals(CrewMediaTransferState.REJECTED, supplier.receive(CrewMediaWireFrame.Request(transfer)))
    }

    private fun manifest(vararg chunks: ByteArray): CrewMediaManifest {
        val bytes = chunks.fold(byteArrayOf()) { all, next -> all + next }
        return CrewMediaManifest(
            CrewMediaTransferRef(
                CrewSessionId("session", ProtocolVersion(1)), CrewMediaRequestId("request-1"),
                CandidateId("crew-temporary:exact"), CrewMemberId("target", ProtocolVersion(1)),
                CrewMemberId("supplier", ProtocolVersion(1)),
            ),
            "audio/test",
            bytes.size.toLong(),
            CrewMediaDigest.sha256(bytes),
            chunks.mapIndexed { index, payload ->
                CrewMediaChunkDescriptor(index, payload.size, CrewMediaDigest.sha256(payload))
            },
        )
    }

    private fun chunk(manifest: CrewMediaManifest, index: Int, bytes: ByteArray) =
        CrewMediaChunk(manifest.transfer, manifest.objectIntegrity, index, bytes)

    private fun tempDirectory(): File =
        File.createTempFile("crew-media", "").also { require(it.delete() && it.mkdirs()) }

    private class FakePeer(override val remoteMemberId: CrewMemberId) : CrewPeerTransport {
        override val state = MutableStateFlow(CrewTransportState.CONNECTED)
        override val incoming = emptyFlow<CrewTransportFrame>()
        override val drops = emptyFlow<org.oxycblt.auxio.shippy.crew.transport.CrewTransportDrop>()
        override fun trySend(frame: CrewTransportFrame) = CrewSendResult.Sent(0)
        override fun bufferedBytes(channel: CrewTransportChannel) = 0L
        override fun close() = Unit
    }
}
