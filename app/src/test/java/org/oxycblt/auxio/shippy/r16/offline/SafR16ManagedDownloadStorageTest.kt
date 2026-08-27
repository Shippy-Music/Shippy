/*
 * Copyright (c) 2026 Auxio Project
 * SafR16ManagedDownloadStorageTest.kt is part of Auxio.
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

import android.net.Uri
import app.shippy.core.asset.AssetLocation
import app.shippy.core.identity.MediaAssetId
import app.shippy.data.offline.R16ManagedDownloadTarget
import app.shippy.data.offline.R16PhysicalRemovalResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.shippy.download.SafManagedDocumentPresence
import org.oxycblt.auxio.shippy.download.SafManagedDownloadDeletionResult
import org.oxycblt.auxio.shippy.download.deleteSafManagedDocument
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SafR16ManagedDownloadStorageTest {
    @Test
    fun `valid content target delegates and preserves physical outcome`() = runBlocking {
        val target = target()
        val calls = mutableListOf<String>()
        val outcomes =
            listOf(
                SafManagedDownloadDeletionResult.DELETED to R16PhysicalRemovalResult.DELETED,
                SafManagedDownloadDeletionResult.ALREADY_MISSING to
                    R16PhysicalRemovalResult.ALREADY_MISSING,
                SafManagedDownloadDeletionResult.FAILED to R16PhysicalRemovalResult.FAILED,
            )

        outcomes.forEach { (physical, expected) ->
            val storage = SafR16ManagedDownloadStorage { contentUri ->
                calls += contentUri
                physical
            }
            assertEquals(expected, storage.delete(target))
        }

        assertEquals(outcomes.size, calls.size)
        assertTrue(calls.all { it == target.location.opaqueHandle })

        var probes = 0
        var deletes = 0
        assertEquals(
            SafManagedDownloadDeletionResult.ALREADY_MISSING,
            deleteSafManagedDocument(
                uri = Uri.parse(target.location.opaqueHandle),
                probe = {
                    probes++
                    if (probes == 1) SafManagedDocumentPresence.PRESENT
                    else SafManagedDocumentPresence.ABSENT
                },
                delete = {
                    deletes++
                    0
                },
            ),
        )
        assertEquals(2, probes)
        assertEquals(1, deletes)
    }

    @Test
    fun `already missing pending URI is idempotent cleanup success`() = runBlocking {
        var deletes = 0

        assertEquals(
            SafManagedDownloadDeletionResult.ALREADY_MISSING,
            deleteSafManagedDocument(
                uri = Uri.parse("content://provider/tree/root/document/root%3Ajob-123"),
                probe = { SafManagedDocumentPresence.ABSENT },
                delete = {
                    deletes++
                    1
                },
            ),
        )
        assertEquals(0, deletes)
    }

    @Test
    fun `non content targets are rejected without calling SAF`() = runBlocking {
        var calls = 0
        val storage = SafR16ManagedDownloadStorage {
            calls++
            SafManagedDownloadDeletionResult.DELETED
        }

        assertEquals(
            R16PhysicalRemovalResult.FAILED,
            storage.delete(target(locationType = "PATH", location = "content://downloads/1")),
        )
        assertEquals(
            R16PhysicalRemovalResult.FAILED,
            storage.delete(target(locationType = "CONTENT_URI", location = "file:///tmp/1")),
        )
        assertEquals(
            R16PhysicalRemovalResult.FAILED,
            storage.delete(target(locationType = "CONTENT_URI", location = "content://")),
        )
        assertEquals(0, calls)
    }

    @Test
    fun `cancellation from SAF is not converted into storage failure`() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val storage = SafR16ManagedDownloadStorage { throw cancellation }

        try {
            storage.delete(target())
            fail("Expected cancellation")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
    }

    private fun target(
        locationType: String = "CONTENT_URI",
        location: String = "content://downloads/document/1",
    ) =
        R16ManagedDownloadTarget(
            assetId = MediaAssetId("00000000-0000-0000-0000-000000000001"),
            locationType = locationType,
            location = AssetLocation(location),
            documentId = "document-1",
        )
}
