/*
 * Copyright (c) 2026 Shippy contributors
 * JioSaavnProviderResolveTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.provider.jiosaavn

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.MediaDescriptor
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.StreamConstraints
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpRequest
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpResponse
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpTransport

class JioSaavnProviderResolveTest {
    private val provider = JioSaavnProvider(UnusedTransport)

    @Test
    fun `resolve caps a non-320 candidate at 160 kbps`() = runBlocking {
        val result =
            provider.resolve(
                candidate(maximumBitrate = 160_000),
                StreamConstraints(preferredBitrateBps = 320_000),
            )

        assertTrue(result is ProviderResult.Success)
        val stream = (result as ProviderResult.Success).value
        assertEquals(160_000, stream.bitrateBps)
        assertTrue(stream.uri.contains("_160."))
    }

    @Test
    fun `resolve rejects a candidate owned by another provider`() = runBlocking {
        val result =
            provider.resolve(
                candidate(maximumBitrate = 320_000, providerId = ProviderId("other")),
                StreamConstraints(),
            )

        assertTrue(result is ProviderResult.Failure)
        assertEquals(
            ProviderFailureKind.UNSUPPORTED,
            (result as ProviderResult.Failure).kind,
        )
    }

    private fun candidate(
        maximumBitrate: Int,
        providerId: ProviderId = ProviderId("jiosaavn"),
    ) =
        TrackCandidate(
            id = CandidateId("jiosaavn:track"),
            trackId = TrackId("jiosaavn:track"),
            kind = CandidateKind.PROVIDER,
            sourceId = providerId.value,
            sourceItemId = "track",
            availability = CandidateAvailability.RESOLVABLE,
            locator = "https://aac.saavncdn.com/example_96.mp4",
            providerId = providerId,
            media = MediaDescriptor(mimeType = "audio/mp4", bitrateBps = maximumBitrate),
        )

    private object UnusedTransport : ProviderHttpTransport {
        override suspend fun execute(request: ProviderHttpRequest): ProviderHttpResponse =
            error("Network is not used by resolve")
    }
}
