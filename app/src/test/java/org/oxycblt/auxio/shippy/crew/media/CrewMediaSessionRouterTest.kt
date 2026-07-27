/*
 * Copyright (c) 2026 Auxio Project
 * CrewMediaSessionRouterTest.kt is part of Auxio.
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
import org.oxycblt.auxio.shippy.domain.QueueItemId

class CrewMediaSessionRouterTest {
    @Test
    fun `malformed media frame is rejected without callback`() {
        val fixture = fixture()
        fixture.supplierRouter.onPeerAttached(fixture.supplierPeer)

        assertTrue(
            fixture.supplierRouter.onMediaFrame(
                fixture.supplierPeer,
                CrewTransportFrame(CrewTransportChannel.MEDIA, byteArrayOf(1, 2, 3)),
            ) is CrewMediaFrameResult.Rejected
        )
        assertEquals(0, fixture.supplierAuthorizations)
    }

    @Test
    fun `any non-host member may supply an authorized requested track`() {
        val fixture = fixture()
        fixture.supplierRouter.onPeerAttached(fixture.supplierPeer)
        fixture.targetRouter.onPeerAttached(fixture.targetPeer)

        assertEquals(
            CrewMediaFrameResult.Accepted,
            fixture.supplierRouter.onMediaFrame(
                fixture.supplierPeer,
                requestFrame(fixture.transfer),
            ),
        )
        drain(fixture)

        assertEquals(1, fixture.supplierAuthorizations)
        assertEquals(1, fixture.completed.size)
        assertTrue(fixture.completed.single().second.isFile)
        assertArrayEquals(
            fixture.bytes,
            fixture.targetCache.read(
                fixture.session,
                fixture.completed.single().first.objectIntegrity,
            ),
        )
    }

    @Test
    fun `transfer bound to another authenticated peer is rejected`() {
        val fixture = fixture()
        fixture.supplierRouter.onPeerAttached(fixture.supplierPeer)
        val wrong = fixture.transfer.copy(targetMemberId = member("intruder"))

        assertTrue(
            fixture.supplierRouter.onMediaFrame(fixture.supplierPeer, requestFrame(wrong))
                is CrewMediaFrameResult.Rejected
        )
        assertEquals(0, fixture.supplierAuthorizations)
    }

    @Test
    fun `detach removes exact peer route`() {
        val fixture = fixture()
        fixture.supplierRouter.onPeerAttached(fixture.supplierPeer)
        fixture.supplierRouter.onPeerDetached(fixture.supplierPeer)

        assertEquals(
            CrewMediaFrameResult.Rejected("media peer is not attached"),
            fixture.supplierRouter.onMediaFrame(
                fixture.supplierPeer,
                requestFrame(fixture.transfer),
            ),
        )
    }

    @Test
    fun `push pull disabled ignores media without sending or authorizing`() {
        val fixture = fixture(enabled = false)
        fixture.supplierRouter.onPeerAttached(fixture.supplierPeer)

        assertEquals(
            CrewMediaFrameResult.Accepted,
            fixture.supplierRouter.onMediaFrame(
                fixture.supplierPeer,
                requestFrame(fixture.transfer),
            ),
        )
        assertEquals(0, fixture.supplierAuthorizations)
        assertTrue(fixture.supplierTransport.sent.isEmpty())
    }

    @Test
    fun `outbound request and cancel require the exact attached supplier peer`() {
        val fixture = fixture()
        fixture.targetRouter.onPeerAttached(fixture.targetPeer)

        assertTrue(
            fixture.targetRouter.requestTemporaryMedia(
                fixture.transfer.supplierMemberId,
                fixture.transfer,
            ) is CrewSendResult.Sent
        )
        assertTrue(
            fixture.targetRouter.cancelTemporaryMediaRequest(
                fixture.transfer.supplierMemberId,
                fixture.transfer,
            ) is CrewSendResult.Sent
        )
        assertEquals(
            null,
            fixture.targetRouter.requestTemporaryMedia(member("intruder"), fixture.transfer),
        )
        assertEquals(
            null,
            fixture.targetRouter.cancelTemporaryMediaRequest(member("intruder"), fixture.transfer),
        )
    }

    @Test
    fun `receiver rejects completion when temporary media cannot be published`() {
        val fixture = fixture(completionAccepted = false)
        fixture.supplierRouter.onPeerAttached(fixture.supplierPeer)
        fixture.targetRouter.onPeerAttached(fixture.targetPeer)

        assertEquals(
            CrewMediaFrameResult.Accepted,
            fixture.supplierRouter.onMediaFrame(
                fixture.supplierPeer,
                requestFrame(fixture.transfer),
            ),
        )
        drain(fixture, expectTargetRejection = true)

        assertTrue(fixture.completed.isEmpty())
    }

    private fun drain(fixture: Fixture, expectTargetRejection: Boolean = false) {
        var guard = 16
        var targetRejected = false
        while (
            (fixture.supplierTransport.sent.isNotEmpty() ||
                fixture.targetTransport.sent.isNotEmpty()) && guard-- > 0
        ) {
            fixture.supplierTransport.sent.removeFirstOrNull()?.let { frame ->
                when (fixture.targetRouter.onMediaFrame(fixture.targetPeer, frame)) {
                    CrewMediaFrameResult.Accepted -> Unit
                    is CrewMediaFrameResult.Rejected -> targetRejected = true
                }
            }
            fixture.targetTransport.sent.removeFirstOrNull()?.let { frame ->
                assertEquals(
                    CrewMediaFrameResult.Accepted,
                    fixture.supplierRouter.onMediaFrame(fixture.supplierPeer, frame),
                )
            }
        }
        assertTrue("transfer should settle without a loop", guard > 0)
        assertEquals(expectTargetRejection, targetRejected)
    }

    private fun requestFrame(transfer: CrewMediaTransferRef) =
        CrewTransportFrame(
            CrewTransportChannel.MEDIA,
            CrewMediaWireCodec.encode(CrewMediaWireFrame.Request(transfer)),
        )

    private fun fixture(enabled: Boolean = true, completionAccepted: Boolean = true): Fixture {
        val session = CrewSessionId("crew", ProtocolVersion(1))
        val supplier = member("not-host-supplier")
        val target = member("target")
        val transfer =
            CrewMediaTransferRef(
                session,
                CrewMediaRequestId("request"),
                QueueItemId("queue-item"),
                CandidateId("candidate"),
                target,
                supplier,
            )
        val policy = ActiveCrewPushPullPolicy().also { it.activate(session, enabled) }
        val supplierTransport = FakePeer(target)
        val targetTransport = FakePeer(supplier)
        val targetCache = CrewTemporaryMediaCache(tempDirectory()).also { it.beginSession(session) }
        val bytes = byteArrayOf(3, 1, 4, 1)
        val completed = mutableListOf<Pair<CrewMediaManifest, File>>()
        var authorizations = 0
        val supplierCallbacks =
            object : CrewMediaSessionCallbacks {
                override fun authorizeSupplierSource(
                    transfer: CrewMediaTransferRef,
                    requestingMemberId: CrewMemberId,
                ): CrewAuthorizedMediaSource? {
                    authorizations++
                    return object : CrewAuthorizedMediaSource {
                        override val lengthBytes = bytes.size.toLong()
                        override val mimeType = "audio/test"

                        override fun open() = ByteArrayInputStream(bytes)
                    }
                }

                override fun onTemporaryMediaComplete(
                    manifest: CrewMediaManifest,
                    supplyingMemberId: CrewMemberId,
                    file: File,
                ) = true
            }
        val targetCallbacks =
            object : CrewMediaSessionCallbacks {
                override fun authorizeSupplierSource(
                    transfer: CrewMediaTransferRef,
                    requestingMemberId: CrewMemberId,
                ) = null

                override fun onTemporaryMediaComplete(
                    manifest: CrewMediaManifest,
                    supplyingMemberId: CrewMemberId,
                    file: File,
                ): Boolean {
                    if (completionAccepted) completed += manifest to file
                    return completionAccepted
                }
            }
        return Fixture(
            session,
            transfer,
            bytes,
            targetCache,
            completed,
            CrewMediaSessionRouter(
                session,
                supplier,
                policy,
                CrewMediaReceiver(session, targetCache),
                supplierCallbacks,
            ),
            CrewMediaSessionRouter(
                session,
                target,
                policy,
                CrewMediaReceiver(session, targetCache),
                targetCallbacks,
            ),
            CrewAuthenticatedMediaPeer(target, supplierTransport),
            CrewAuthenticatedMediaPeer(supplier, targetTransport),
            supplierTransport,
            targetTransport,
            { authorizations },
        )
    }

    private data class Fixture(
        val session: CrewSessionId,
        val transfer: CrewMediaTransferRef,
        val bytes: ByteArray,
        val targetCache: CrewTemporaryMediaCache,
        val completed: MutableList<Pair<CrewMediaManifest, File>>,
        val supplierRouter: CrewMediaSessionRouter,
        val targetRouter: CrewMediaSessionRouter,
        val supplierPeer: CrewAuthenticatedMediaPeer,
        val targetPeer: CrewAuthenticatedMediaPeer,
        val supplierTransport: FakePeer,
        val targetTransport: FakePeer,
        private val authorizationCount: () -> Int,
    ) {
        val supplierAuthorizations
            get() = authorizationCount()
    }

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

    private fun tempDirectory(): File =
        File.createTempFile("crew-router", "").also { require(it.delete() && it.mkdirs()) }
}
