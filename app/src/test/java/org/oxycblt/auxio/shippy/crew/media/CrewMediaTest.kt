/* Copyright (c) 2026 Shippy contributors */
package org.oxycblt.auxio.shippy.crew.media

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.cache.CrewTemporaryMediaCache
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.domain.CandidateId

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
        cache.beginSession(manifest.sessionId)
        val receiver = CrewMediaReceiver(manifest.sessionId, cache)

        assertEquals(CrewMediaReceiveResult.Accepted, receiver.accept(manifest))
        assertEquals(CrewMediaReceiveResult.Accepted, receiver.accept(chunk(manifest, 1, second)))
        assertEquals(CrewMediaReceiveResult.Complete(manifest), receiver.accept(chunk(manifest, 0, first)))
        assertArrayEquals(first + second, cache.read(manifest.sessionId, manifest.objectIntegrity))
    }

    @Test
    fun `invalid chunk is rejected and receiver window asks sender to retry`() {
        val manifest = manifest(byteArrayOf(1, 2), byteArrayOf(3, 4, 5))
        val cache = CrewTemporaryMediaCache(tempDirectory())
        cache.beginSession(manifest.sessionId)
        val receiver = CrewMediaReceiver(manifest.sessionId, cache, CrewMediaReceiverPolicy(maxAssemblies = 1, maxBufferedBytes = 5))

        assertEquals(CrewMediaReceiveResult.Accepted, receiver.accept(manifest))
        assertTrue(receiver.accept(chunk(manifest, 0, byteArrayOf(9, 9))) is CrewMediaReceiveResult.Rejected)
        assertTrue(receiver.accept(manifest.copy(objectIntegrity = CrewMediaDigest.sha256(byteArrayOf(9)))) is CrewMediaReceiveResult.Retry)
    }

    @Test
    fun `cache clears stale crash data and session end data`() {
        val root = tempDirectory()
        val cache = CrewTemporaryMediaCache(root)
        val old = manifest(byteArrayOf(1))
        cache.beginSession(old.sessionId)
        cache.put(old, byteArrayOf(1))
        val current = old.copy(sessionId = CrewSessionId("next", ProtocolVersion(1)))

        cache.beginSession(current.sessionId)
        assertEquals(null, cache.read(old.sessionId, old.objectIntegrity))
        cache.put(current, byteArrayOf(1))
        cache.endSession(current.sessionId)
        assertEquals(null, cache.read(current.sessionId, current.objectIntegrity))
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

    private fun manifest(vararg chunks: ByteArray): CrewMediaManifest {
        val bytes = chunks.fold(byteArrayOf()) { all, next -> all + next }
        return CrewMediaManifest(
            CrewSessionId("session", ProtocolVersion(1)),
            CandidateId("crew-temporary:exact"),
            "audio/test",
            bytes.size.toLong(),
            CrewMediaDigest.sha256(bytes),
            chunks.mapIndexed { index, payload ->
                CrewMediaChunkDescriptor(index, payload.size, CrewMediaDigest.sha256(payload))
            },
        )
    }

    private fun chunk(manifest: CrewMediaManifest, index: Int, bytes: ByteArray) =
        CrewMediaChunk(manifest.sessionId, manifest.objectIntegrity, index, bytes)

    private fun tempDirectory(): File =
        File.createTempFile("crew-media", "").also { require(it.delete() && it.mkdirs()) }
}
