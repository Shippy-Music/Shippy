/*
 * Copyright (c) 2026 Auxio Project
 * DownloadTransferEngineTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.download

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTransferEngineTest {
    @Test
    fun `streams exact bytes and leaves caller output open`() = runBlocking {
        val source = FakeSource("music".toByteArray(), declaredLength = 5)
        val factory = FakeSourceFactory(source)
        val output = TrackingOutputStream()
        val progress = mutableListOf<DownloadTransferProgress>()
        val engine =
            DownloadTransferEngine(
                sourceFactory = factory,
                monotonicTimeMs = { 0 },
                progressByteInterval = 2,
                progressTimeIntervalMs = 1_000,
            )

        val result =
            engine.transfer(
                DownloadTransferRequest(
                    uri = "https://example.invalid/audio",
                    headers = mapOf("Authorization" to "redacted"),
                    expectedLength = 5,
                ),
                output,
                { progress += it },
            )

        assertTrue(result is DownloadTransferResult.Success)
        assertArrayEquals("music".toByteArray(), output.toByteArray())
        assertFalse(output.closed)
        assertTrue(source.closed)
        assertEquals(mapOf("Authorization" to "redacted"), factory.headers)
        assertEquals(5L, progress.last().bytesTransferred)
    }

    @Test
    fun `non-success http status is rejected without opening body`() = runBlocking {
        val source = FakeSource(ByteArray(0), httpStatusCode = 503)
        val engine =
            DownloadTransferEngine(
                sourceFactory = FakeSourceFactory(source),
                monotonicTimeMs = { 0 },
            )

        val result =
            engine.transfer(
                DownloadTransferRequest("https://example.invalid/audio"),
                ByteArrayOutputStream(),
            )

        val failure = (result as DownloadTransferResult.Failure).reason
        assertEquals(DownloadTransferFailure.HttpStatus(503), failure)
        assertTrue(failure.retryable)
        assertFalse(source.inputOpened)
        assertTrue(source.closed)
    }

    @Test
    fun `premature eof is retryable and does not close output`() = runBlocking {
        val source = FakeSource(byteArrayOf(1, 2, 3))
        val output = TrackingOutputStream()
        val engine =
            DownloadTransferEngine(
                sourceFactory = FakeSourceFactory(source),
                monotonicTimeMs = { 0 },
            )

        val result =
            engine.transfer(
                DownloadTransferRequest(uri = "file:///music", expectedLength = 4),
                output,
            )

        val failure = (result as DownloadTransferResult.Failure).reason
        assertEquals(DownloadTransferFailure.PrematureEof(4, 3), failure)
        assertTrue(failure.retryable)
        assertFalse(output.closed)
    }

    @Test
    fun `declared http length guards a request without resolved length`() = runBlocking {
        val source = FakeSource(byteArrayOf(1, 2, 3), declaredLength = 4)
        val engine =
            DownloadTransferEngine(
                sourceFactory = FakeSourceFactory(source),
                monotonicTimeMs = { 0 },
            )

        val result =
            engine.transfer(
                DownloadTransferRequest("https://example.invalid/audio"),
                ByteArrayOutputStream(),
            )

        val failure = (result as DownloadTransferResult.Failure).reason
        assertEquals(DownloadTransferFailure.PrematureEof(4, 3), failure)
        assertTrue(failure.retryable)
    }

    @Test
    fun `unsupported scheme is final and source is not opened`() = runBlocking {
        val factory = FakeSourceFactory(FakeSource(ByteArray(0)))
        val engine = DownloadTransferEngine(factory, monotonicTimeMs = { 0 })

        val result =
            engine.transfer(
                DownloadTransferRequest("ftp://example.invalid/audio"),
                ByteArrayOutputStream(),
            )

        val failure = (result as DownloadTransferResult.Failure).reason
        assertEquals(DownloadTransferFailure.UnsupportedScheme("ftp"), failure)
        assertFalse(failure.retryable)
        assertFalse(factory.opened)
    }

    private class FakeSourceFactory(private val source: FakeSource) :
        DownloadTransferSourceFactory {
        var opened = false
        var headers: Map<String, String>? = null

        override fun open(
            uri: URI,
            headers: Map<String, String>,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): DownloadTransferSource {
            opened = true
            this.headers = headers
            return source
        }
    }

    private class FakeSource(
        private val bytes: ByteArray,
        override val httpStatusCode: Int? = 200,
        override val declaredLength: Long? = null,
    ) : DownloadTransferSource {
        var inputOpened = false
        var closed = false

        override fun openInput(): InputStream {
            inputOpened = true
            return ByteArrayInputStream(bytes)
        }

        override fun close() {
            closed = true
        }
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
