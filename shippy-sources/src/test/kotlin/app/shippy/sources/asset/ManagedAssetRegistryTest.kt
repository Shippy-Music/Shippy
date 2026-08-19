/*
 * Copyright (c) 2026 Auxio Project
 * ManagedAssetRegistryTest.kt is part of Auxio.
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
package app.shippy.sources.asset

import app.shippy.core.asset.AssetLocation
import app.shippy.core.asset.ContentChecksum
import app.shippy.core.identity.MediaAssetId
import app.shippy.core.identity.RecordingId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedAssetRegistryTest {
    @Test
    fun `exact managed locations and moved verified content reuse the asset`() {
        val managed = record("asset-a", "recording-a", evidence("content://managed/a"))
        val registry = ManagedAssetRegistry(listOf(managed))

        val sameLocation = registry.classify(evidence("content://managed/a"))
        val moved =
            registry.classify(
                evidence(location = "content://managed/moved", pathToken = "moved/path")
            )

        assertEquals(managed, (sameLocation as ManagedAssetMatch.Exact).record)
        assertEquals(managed, (moved as ManagedAssetMatch.Exact).record)
        assertTrue(
            ManagedAssetMatchEvidence.CONTENT_CHECKSUM in moved.evidence ||
                ManagedAssetMatchEvidence.AUDIO_FINGERPRINT in moved.evidence
        )
    }

    @Test
    fun `unrelated download-folder file stays local and ambiguous bytes require review`() {
        val first = record("asset-a", "recording-a", evidence("content://managed/a"))
        val second = record("asset-b", "recording-b", evidence("content://managed/b"))
        val registry = ManagedAssetRegistry(listOf(first, second))

        val unrelated =
            registry.classify(
                evidence(
                    location = "content://download/user-file",
                    pathToken = "user/file",
                    checksum = ContentChecksum("SHA-256", "different"),
                    fingerprint = "different",
                )
            )
        val ambiguous =
            registry.classify(evidence(location = "content://managed/copied", pathToken = "copy"))

        assertEquals(ManagedAssetMatch.None, unrelated)
        assertEquals(2, (ambiguous as ManagedAssetMatch.Probable).candidates.size)
    }

    @Test
    fun `platform IDs are exact only inside the same provider and storage root`() {
        val managed =
            record(
                "asset-a",
                "recording-a",
                evidence("content://documents-a/root", pathToken = "external:primary/Music/a")
                    .copy(documentId = "primary:a", mediaStoreId = 7),
            )
        val registry = ManagedAssetRegistry(listOf(managed))
        val otherRoot =
            evidence(
                    location = "content://documents-b/moved",
                    pathToken = "external:sdcard/Music/a",
                    checksum = ContentChecksum("SHA-256", "other"),
                    fingerprint = "other",
                )
                .copy(documentId = "primary:a", mediaStoreId = 7)
        val sameScope =
            otherRoot.copy(
                location = AssetLocation("content://documents-a/moved"),
                normalizedPathToken = "external:primary/Music/moved-a",
            )

        assertTrue(registry.classify(otherRoot) is ManagedAssetMatch.Probable)
        assertEquals(managed, (registry.classify(sameScope) as ManagedAssetMatch.Exact).record)
    }

    private fun record(assetId: String, recordingId: String, evidence: ManagedAssetEvidence) =
        ManagedAssetRecord(
            MediaAssetId(TEST_IDS.getValue(assetId)),
            RecordingId(TEST_IDS.getValue(recordingId)),
            evidence,
            verified = true,
        )

    private fun evidence(
        location: String,
        pathToken: String = "managed/path",
        checksum: ContentChecksum = ContentChecksum("SHA-256", "checksum"),
        fingerprint: String = "fingerprint",
    ) =
        ManagedAssetEvidence(
            locationType = "CONTENT_URI",
            location = AssetLocation(location),
            normalizedPathToken = pathToken,
            contentLength = 100,
            lastModifiedEpochMs = 200,
            checksum = checksum,
            fingerprint = fingerprint,
        )

    private companion object {
        val TEST_IDS =
            mapOf(
                "asset-a" to "00000000-0000-0000-0000-000000000001",
                "asset-b" to "00000000-0000-0000-0000-000000000002",
                "recording-a" to "00000000-0000-0000-0000-000000000003",
                "recording-b" to "00000000-0000-0000-0000-000000000004",
            )
    }
}
