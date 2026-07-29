/*
 * Copyright (c) 2026 Auxio Project
 * DownloadTransferStagingTest.kt is part of Auxio.
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

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTransferStagingTest {
    @Test
    fun incompleteStageIsNotPublishable() = runBlocking {
        val root = Files.createTempDirectory("shippy-stage").toFile()
        try {
            val staging = DownloadTransferStaging(root)
            val id = DownloadJobId("job")
            staging.openOutput(id).use { it.write(byteArrayOf(1, 2, 3)) }

            assertNull(staging.verifiedFile(id, expectedBytes = 4))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun verifiedStageCopiesAndCleansUp() = runBlocking {
        val root = Files.createTempDirectory("shippy-stage").toFile()
        try {
            val staging = DownloadTransferStaging(root)
            val id = DownloadJobId("job")
            val bytes = byteArrayOf(1, 2, 3, 4)
            staging.openOutput(id).use { it.write(bytes) }
            val file = requireNotNull(staging.verifiedFile(id, bytes.size.toLong()))
            val destination = ByteArrayOutputStream()

            staging.copyTo(file, destination)
            assertArrayEquals(bytes, destination.toByteArray())
            staging.cleanup(id)
            assertTrue(staging.verifiedFile(id, bytes.size.toLong()) == null)
        } finally {
            root.deleteRecursively()
        }
    }
}
