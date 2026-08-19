/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackLocatorRegistryTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.playback

import app.shippy.core.identity.QueueEntryId
import app.shippy.core.identity.RecordingId
import app.shippy.core.identity.SourceReferenceId
import app.shippy.core.playback.PlaybackError
import app.shippy.core.playback.PlaybackRequestTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.media.MediaObjectKey

class PlaybackLocatorRegistryTest {
    @Test
    fun `preparer publishes a fresh locator while snapshot retains identity only`() = runBlocking {
        val registry = PlaybackLocatorRegistry()
        val locator = locator(expiresAtEpochMs = 120_001)
        val preparer =
            RegistryPlaybackSourcePreparer(
                resolver = PlaybackLocatorResolver { PlaybackLocatorResolution.Ready(locator) },
                registry = registry,
                nowEpochMs = { 60_000 },
            )

        val result = preparer.prepare(request()) as PlaybackPreparationResult.Ready

        assertEquals(locator.handle, result.source)
        assertEquals(locator, registry.require(result.source))
        assertEquals(locator.headers, registry.headerProjection().getValue(locator.cacheKey!!))
    }

    @Test
    fun `near-expired locator is rejected for bounded retry instead of entering Media3`() =
        runBlocking {
            val preparer =
                RegistryPlaybackSourcePreparer(
                    resolver =
                        PlaybackLocatorResolver {
                            PlaybackLocatorResolution.Ready(locator(expiresAtEpochMs = 119_999))
                        },
                    registry = PlaybackLocatorRegistry(),
                    nowEpochMs = { 60_000 },
                )

            val result = preparer.prepare(request()) as PlaybackPreparationResult.Unavailable

            assertEquals(PlaybackError("SOURCE_LOCATOR_EXPIRED", true), result.error)
        }

    @Test
    fun `media projection separates rotating URL from stable cache and header key`() {
        val cached = locator(expiresAtEpochMs = null).media3Projection()
        val uncached =
            locator(expiresAtEpochMs = null)
                .copy(cacheKey = null, stableKey = "uncached-source")
                .media3Projection()

        assertEquals("https://audio.example.invalid/stream", cached.uri)
        assertEquals(locator().cacheKey, cached.dataSpecKey)
        assertEquals("shippy-r16-header:uncached-source", uncached.dataSpecKey)
    }

    @Test
    fun `registry refuses stable-key reassignment across durable source identities`() {
        val registry = PlaybackLocatorRegistry()
        registry.publish(locator())

        val error =
            runCatching {
                    registry.publish(
                        locator().copy(sourceReferenceId = SourceReferenceId(idValue(99)))
                    )
                }
                .exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    private fun request() =
        PlaybackPreparationRequest(
            tag = PlaybackRequestTag(4, 7, QueueEntryId(idValue(1))),
            recordingId = RecordingId(idValue(2)),
        )

    private fun locator(expiresAtEpochMs: Long? = null) =
        PlaybackLocator(
            stableKey = "provider-source",
            sourceReferenceId = SourceReferenceId(idValue(3)),
            mediaAssetId = null,
            uri = "https://audio.example.invalid/stream",
            mimeType = "audio/mp4",
            headers = mapOf("Authorization" to "redacted"),
            cacheKey = MediaObjectKey.CACHE_KEY_PREFIX + "a".repeat(64),
            expiresAtEpochMs = expiresAtEpochMs,
        )

    private fun idValue(value: Int) =
        "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}
