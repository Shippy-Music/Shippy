/*
 * Copyright (c) 2026 Auxio Project
 * SafR16DownloadDestinationTest.kt is part of Auxio.
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.auxio.shippy.download.DownloadJobId
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SafR16DownloadDestinationTest {
    @Test
    fun pendingNameIsReadableStableAndJobSpecific() {
        val job = DownloadJobId("job-123")

        val name = r16PendingDownloadName(job, "A/B: Song", "audio/mp4")

        assertEquals("A_B_ Song [shippy-job-123].m4a", name)
        assertEquals(name, r16PendingDownloadName(job, "A/B: Song", "audio/mp4"))
        assertNotEquals(
            name,
            r16PendingDownloadName(DownloadJobId("job-456"), "A/B: Song", "audio/mp4"),
        )
        assertTrue(name.contains("[shippy-job-123]"))
    }

    @Test
    fun pendingNamePreservesSupportedWebmAndMatroskaContainers() {
        val job = DownloadJobId("job-123")

        assertEquals(
            "Song [shippy-job-123].webm",
            r16PendingDownloadName(job, "Song", "audio/webm"),
        )
        assertEquals(
            "Song [shippy-job-123].webm",
            r16PendingDownloadName(job, "Song", "video/webm"),
        )
        assertEquals(
            "Song [shippy-job-123].mka",
            r16PendingDownloadName(job, "Song", "audio/x-matroska"),
        )
        assertEquals(
            "Song [shippy-job-123].mka",
            r16PendingDownloadName(job, "Song", "video/x-matroska"),
        )
    }

    @Test
    fun pendingDocumentRetainsExactDestinationAndJobIdentity() {
        val document =
            R16PendingDownloadDocument(
                destinationIdentity = "content://provider/tree/root",
                jobId = DownloadJobId("job-123"),
                contentUri = "content://provider/tree/root/document/root%3Ajob-123",
                displayName = "Song [shippy-job-123].m4a",
                mimeType = "audio/mp4",
            )

        assertEquals("content://provider/tree/root", document.destinationIdentity)
        assertEquals(DownloadJobId("job-123"), document.jobId)
        assertEquals("content://provider/tree/root/document/root%3Ajob-123", document.contentUri)
    }

    @Test
    fun pendingUriMustBeAChildOfTheExactDestinationTree() {
        val destination = Uri.parse("content://provider/tree/root")

        assertTrue(
            r16PendingUriMatchesDestination(
                destination,
                Uri.parse("content://provider/tree/root/document/root%3ASong"),
            )
        )
        assertFalse(
            r16PendingUriMatchesDestination(
                destination,
                Uri.parse("content://provider/tree/other/document/other%3ASong"),
            )
        )
        assertFalse(
            r16PendingUriMatchesDestination(
                destination,
                Uri.parse("content://provider/document/root%3ASong"),
            )
        )
        assertFalse(
            r16PendingUriMatchesDestination(
                destination,
                Uri.parse("content://other/tree/root/document/root%3ASong"),
            )
        )
    }

    @Test
    fun persistedPendingCleanupRequiresAPathSafeJobIdentity() {
        assertTrue(r16PathSafeDownloadJobId(DownloadJobId("job-123")))
        assertFalse(r16PathSafeDownloadJobId(DownloadJobId("job/123")))
        assertFalse(r16PathSafeDownloadJobId(DownloadJobId("job\\123")))
        assertFalse(r16PathSafeDownloadJobId(DownloadJobId(" job-123")))
    }

    @Test
    fun recoveryRebuildsMetadataOnlyFromTheExactProviderDocument() {
        val recovered =
            r16RecoveredPendingDocument(
                destinationIdentity = "content://provider/tree/root",
                jobId = DownloadJobId("job-123"),
                contentUri = "content://provider/tree/root/document/root%3ASong",
                providerDisplayName = "Song [shippy-job-123].m4a",
                providerMimeType = "audio/mp4",
            )

        requireNotNull(recovered)
        assertEquals("Song [shippy-job-123].m4a", recovered.displayName)
        assertEquals("audio/mp4", recovered.mimeType)
        assertEquals("content://provider/tree/root/document/root%3ASong", recovered.contentUri)
    }

    @Test
    fun recoveryRejectsUnsafeJobOrUnownedProviderMetadata() {
        val destination = "content://provider/tree/root"
        val contentUri = "content://provider/tree/root/document/root%3ASong"
        val displayName = "Song [shippy-job-123].m4a"

        assertNull(
            r16RecoveredPendingDocument(
                destinationIdentity = destination,
                jobId = DownloadJobId("job/123"),
                contentUri = contentUri,
                providerDisplayName = displayName,
                providerMimeType = "audio/mp4",
            )
        )
        assertNull(
            r16RecoveredPendingDocument(
                destinationIdentity = destination,
                jobId = DownloadJobId("job-123"),
                contentUri = contentUri,
                providerDisplayName = "Song.m4a",
                providerMimeType = "audio/mp4",
            )
        )
        assertNull(
            r16RecoveredPendingDocument(
                destinationIdentity = destination,
                jobId = DownloadJobId("job-123"),
                contentUri = contentUri,
                providerDisplayName = displayName,
                providerMimeType = null,
            )
        )
    }
}
