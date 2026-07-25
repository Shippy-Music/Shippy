/* Copyright (c) 2026 Shippy contributors */
package org.oxycblt.auxio.shippy.crew.media

import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.crew.cache.CrewTemporaryMediaCache
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewSessionId
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.transport.CrewPeerTransport
import org.oxycblt.auxio.shippy.crew.transport.CrewSendResult
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportChannel
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportDrop
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportFrame
import org.oxycblt.auxio.shippy.crew.transport.CrewTransportState
import org.oxycblt.auxio.shippy.domain.CandidateId

class CrewMediaSessionRouterTest {
    @Test
    fun `malformed media frame is rejected without callback`() {
        val fixture = fixture()
        fixture.supplierRouter.onPeerAttached(fixture.supplierPeer)

        assertTrue(
            fixture.supplierRouter.onMediaFrame(
                fixture.supplierPeer,
                CrewTransportFrame(CrewTransportChannel.MEDIA, byteArrayOf(1, 2, 3)),
            ) is CrewMediaFrameResult.Rejected,
        )
        assertEquals(0, fixture.supplierAuthorizations)
    }

    @Test
    fun `any non-host member may supply an authorized requested track`() {
        val fixture = fixture()
        fixture.supplierRouter.onPeerAttached(fixture.supplierPeer)
        fixture.targetRouter.onPeerAttached(fixture.targetPeer)

        assertEquals(CrewMediaFrameResult.Accepted, fixture.supplierRouter.onMediaFrame(fixture.supplierPeer, requestFrame(fixture.transfer)))
        drain(fixture)

        assertEquals(1, fixture.supplierAuthorizations)
        assertEquals(1, fixture.completed.size)
        assertArrayEquals(fixture.bytes, fixture.targetCache.read(fixture.session, fixture.completed.single().objectIntegrity))
    }

    @Test
    fun `transfer bound to another authenticated peer is rejected`() {
        val fixture = fixture()
        fixture.supplierRouter.onPeerAttached(fixture.supplierPeer)
        val wrong = fixture.transfer.copy(targetMemberId = member("intruder"))

        assertTrue(fixture.supplierRouter.onMediaFrame(fixture.supplierPeer, requestFrame(wrong)) is CrewMediaFrameResult.Rejected)
        assertEquals(0, fixture.supplierAuthorizations)
    }

    @Test
    fun `detach removes exact peer route`() {
        val fixture = fixture()
        fixture.supplierRouter.onPeerAttached(fixture.supplierPeer)
        fixture.supplierRouter.onPeerDetached(fixture.supplierPeer)

        assertEquals(
            CrewMediaFrameResult.Accepted,
            fixture.supplierRouter.onMediaFrame(
                fixture.supplierPeer,
                requestFrame(fixture.transfer),
            ),
        )
    }

    @Test
    fun `push pull disabled rejects media without sending or authorizing`() {
        val fixture = fixture(enabled = false)
        fixture.supplierRouter.onPeerAttached(fixture.supplierPeer)

        assertTrue(fixture.supplierRouter.onMediaFrame(fixture.supplierPeer, requestFrame(fixture.transfer)) is CrewMediaFrameResult.Rejected)
        assertEquals(0, fixture.supplierAuthorizations)
        assertTrue(fixture.supplierTransport.sent.isEmpty())
    }

    private fun drain(fixture: Fixture) {
        var guard = 16
        while ((fixture.supplierTransport.sent.isNotEmpty() || fixture.targetTransport.sent.isNotEmpty()) && guard-- > 0) {
            fixture.supplierTransport.sent.removeFirstOrNull()?.let { frame ->
                assertEquals(CrewMediaFrameResult.Accepted, fixture.targetRouter.onMediaFrame(fixture.targetPeer, frame))
            }
            fixture.targetTransport.sent.removeFirstOrNull()?.let { frame ->
                assertEquals(CrewMediaFrameResult.Accepted, fixture.supplierRouter.onMediaFrame(fixture.supplierPeer, frame))
            }
        }
        assertTrue("transfer should settle without a loop", guard > 0)
    }

    private fun requestFrame(transfer: CrewMediaTransferRef) =
        CrewTransportFrame(CrewTransportChannel.MEDIA, CrewMediaWireCodec.encode(CrewMediaWireFrame.Request(transfer)))

    private fun fixture(enabled: Boolean = true): Fixture {
        val session = CrewSessionId("crew", ProtocolVersion(1))
        val supplier = member("not-host-supplier")
        val target = member("target")
        val transfer = CrewMediaTransferRef(session, CrewMediaRequestId("request"), CandidateId("candidate"), target, supplier)
        val policy = ActiveCrewPushPullPolicy().also { it.activate(session, enabled) }
        val supplierTransport = FakePeer(target)
        val targetTransport = FakePeer(supplier)
        val targetCache = CrewTemporaryMediaCache(tempDirectory()).also { it.beginSession(session) }
        val bytes = byteArrayOf(3, 1, 4, 1)
        val completed = mutableListOf<CrewMediaManifest>()
        var authorizations = 0
        val supplierCallbacks = object : CrewMediaSessionCallbacks {
            override fun authorizeSupplierSource(transfer: CrewMediaTransferRef, requestingMemberId: CrewMemberId): CrewAuthorizedMediaSource? {
                authorizations++
                return object : CrewAuthorizedMediaSource {
                    override val lengthBytes = bytes.size.toLong()
                    override val mimeType = "audio/test"
                    override fun open() = ByteArrayInputStream(bytes)
                }
            }
            override fun onTemporaryMediaComplete(manifest: CrewMediaManifest, supplyingMemberId: CrewMemberId) = Unit
        }
        val targetCallbacks = object : CrewMediaSessionCallbacks {
            override fun authorizeSupplierSource(transfer: CrewMediaTransferRef, requestingMemberId: CrewMemberId) = null
            override fun onTemporaryMediaComplete(manifest: CrewMediaManifest, supplyingMemberId: CrewMemberId) { completed += manifest }
        }
        return Fixture(
            session, transfer, bytes, targetCache, completed,
            CrewMediaSessionRouter(session, supplier, policy, CrewMediaReceiver(session, targetCache), supplierCallbacks),
            CrewMediaSessionRouter(session, target, policy, CrewMediaReceiver(session, targetCache), targetCallbacks),
            CrewAuthenticatedMediaPeer(target, supplierTransport),
            CrewAuthenticatedMediaPeer(supplier, targetTransport),
            supplierTransport, targetTransport,
            { authorizations },
        )
    }

    private data class Fixture(
        val session: CrewSessionId,
        val transfer: CrewMediaTransferRef,
        val bytes: ByteArray,
        val targetCache: CrewTemporaryMediaCache,
        val completed: MutableList<CrewMediaManifest>,
        val supplierRouter: CrewMediaSessionRouter,
        val targetRouter: CrewMediaSessionRouter,
        val supplierPeer: CrewAuthenticatedMediaPeer,
        val targetPeer: CrewAuthenticatedMediaPeer,
        val supplierTransport: FakePeer,
        val targetTransport: FakePeer,
        private val authorizationCount: () -> Int,
    ) { val supplierAuthorizations get() = authorizationCount() }

    private class FakePeer(override val remoteMemberId: CrewMemberId) : CrewPeerTransport {
        override val state = MutableStateFlow(CrewTransportState.CONNECTED)
        override val incoming = emptyFlow<CrewTransportFrame>()
        override val drops = emptyFlow<CrewTransportDrop>()
        val sent = ArrayDeque<CrewTransportFrame>()
        override fun trySend(frame: CrewTransportFrame): CrewSendResult {
            sent.addLast(frame)
            return CrewSendResult.Sent(0)
        }
        override fun bufferedBytes(channel: CrewTransportChannel) = 0L
        override fun close() = Unit
    }

    private fun member(value: String) = CrewMemberId(value, ProtocolVersion(1))
    private fun tempDirectory(): File = File.createTempFile("crew-router", "").also { require(it.delete() && it.mkdirs()) }
}
