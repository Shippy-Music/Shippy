/*
 * Copyright (c) 2026 Auxio Project
 * R16Media3DownloadVerifierTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.offline

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@androidx.annotation.OptIn(UnstableApi::class)
class R16Media3DownloadVerifierTest {
    @Test
    fun `real Media3 content data source opens and reads final content uri`() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val file = File.createTempFile("r16-media3-verifier", ".m4a", context.cacheDir)
        file.writeBytes(mp4Header())
        val provider = FileContentProvider(file)
        provider.attachInfo(context, ProviderInfo().apply { authority = TEST_AUTHORITY })
        ShadowContentResolver.registerProviderInternal(TEST_AUTHORITY, provider)

        try {
            assertEquals(
                R16Media3ReadabilityResult.Readable,
                R16Media3DownloadVerifier(context)
                    .verify(TEST_CONTENT_URI, expectedMimeType = "audio/mp4"),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `non content locations fail before any data source is created`() = runBlocking {
        var factoryCalls = 0
        val verifier =
            R16Media3DownloadVerifier(
                dataSourceFactory = {
                    factoryCalls++
                    FakeDataSource()
                },
                ioDispatcher = Dispatchers.Unconfined,
            )

        listOf(
                "",
                " content://downloads/document/1",
                "content://",
                "file:///tmp/download.m4a",
                "https://example.test/download.m4a",
            )
            .forEach { location ->
                assertEquals(
                    unreadable(R16Media3UnreadableReason.INVALID_CONTENT_URI),
                    verifier.verify(location, expectedMimeType = "audio/mp4"),
                )
            }

        assertEquals(
            unreadable(R16Media3UnreadableReason.UNSUPPORTED_MIME_TYPE),
            verifier.verify(TEST_CONTENT_URI, expectedMimeType = "text/html"),
        )
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `pure header matcher accepts supported families and rejects html or mismatch`() {
        val matchingHeaders =
            listOf(
                "audio/mp4" to mp4Header(),
                "audio/mpeg" to "ID3\u0004".toByteArray(),
                "audio/ogg" to "OggS".toByteArray(),
                "audio/flac" to "fLaC".toByteArray(),
                "audio/webm" to byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()),
                "audio/wav" to
                    byteArrayOf(
                        'R'.code.toByte(),
                        'I'.code.toByte(),
                        'F'.code.toByte(),
                        'F'.code.toByte(),
                        0,
                        0,
                        0,
                        0,
                        'W'.code.toByte(),
                        'A'.code.toByte(),
                        'V'.code.toByte(),
                        'E'.code.toByte(),
                    ),
                "audio/aac" to byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte()),
            )
        matchingHeaders.forEach { (mimeType, header) ->
            assertNull(r16AudioHeaderFailureOrNull(header, mimeType))
        }

        assertEquals(
            R16Media3UnreadableReason.HTML_RESPONSE,
            r16AudioHeaderFailureOrNull(" \n<!doctype html>".toByteArray(), "audio/mp4"),
        )
        assertEquals(
            R16Media3UnreadableReason.FORMAT_MISMATCH,
            r16AudioHeaderFailureOrNull("OggS".toByteArray(), "audio/mp4"),
        )
    }

    @Test
    fun `open empty read and close failures remain typed and every opened source closes`() =
        runBlocking {
            val openFailure = FakeDataSource(openFailure = IOException("open"))
            assertEquals(
                unreadable(R16Media3UnreadableReason.OPEN_FAILED),
                verifier(openFailure).verify(TEST_CONTENT_URI, "audio/mp4"),
            )
            assertTrue(openFailure.closed)

            val empty = FakeDataSource(bytes = byteArrayOf())
            assertEquals(
                unreadable(R16Media3UnreadableReason.EMPTY_ASSET),
                verifier(empty).verify(TEST_CONTENT_URI, "audio/mp4"),
            )
            assertTrue(empty.closed)

            val readFailure = FakeDataSource(readFailure = IOException("read"))
            assertEquals(
                unreadable(R16Media3UnreadableReason.READ_FAILED),
                verifier(readFailure).verify(TEST_CONTENT_URI, "audio/mp4"),
            )
            assertTrue(readFailure.closed)

            val closeFailure = FakeDataSource(closeFailure = IOException("close"))
            assertEquals(
                unreadable(R16Media3UnreadableReason.CLOSE_FAILED),
                verifier(closeFailure).verify(TEST_CONTENT_URI, "audio/mp4"),
            )
            assertTrue(closeFailure.closed)
        }

    @Test
    fun `cancellation is rethrown after the opened source is closed`() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val source = FakeDataSource(readFailure = cancellation)

        try {
            verifier(source).verify(TEST_CONTENT_URI, "audio/mp4")
            fail("Expected cancellation")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
        assertTrue(source.closed)
    }

    private fun verifier(source: FakeDataSource) =
        R16Media3DownloadVerifier(
            dataSourceFactory = { source },
            ioDispatcher = Dispatchers.Unconfined,
        )

    private fun unreadable(reason: R16Media3UnreadableReason) =
        R16Media3ReadabilityResult.Unreadable(reason)

    private class FakeDataSource(
        private val bytes: ByteArray = mp4Header(),
        private val openFailure: Exception? = null,
        private val readFailure: Exception? = null,
        private val closeFailure: Exception? = null,
    ) : DataSource {
        private var offset = 0
        private var uri: Uri? = null
        var closed = false
            private set

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            openFailure?.let { throw it }
            return bytes.size.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readFailure?.let { throw it }
            if (this.offset >= bytes.size) return C.RESULT_END_OF_INPUT
            buffer[offset] = bytes[this.offset++]
            return 1
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            closed = true
            closeFailure?.let { throw it }
        }
    }

    private class FileContentProvider(private val file: File) : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String = "audio/mp4"

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    private companion object {
        const val TEST_AUTHORITY = "org.oxycblt.auxio.test.r16.downloads"
        const val TEST_CONTENT_URI = "content://$TEST_AUTHORITY/document/final"

        fun mp4Header(): ByteArray =
            byteArrayOf(
                0,
                0,
                0,
                24,
                'f'.code.toByte(),
                't'.code.toByte(),
                'y'.code.toByte(),
                'p'.code.toByte(),
                'M'.code.toByte(),
                '4'.code.toByte(),
                'A'.code.toByte(),
                ' '.code.toByte(),
            )
    }
}
