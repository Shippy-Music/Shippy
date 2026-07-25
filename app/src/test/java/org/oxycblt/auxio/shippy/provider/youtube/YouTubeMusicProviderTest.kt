package org.oxycblt.auxio.shippy.provider.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.TrackCandidate
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.provider.ProviderFailureKind
import org.oxycblt.auxio.shippy.provider.ProviderResult
import org.oxycblt.auxio.shippy.provider.StreamConstraints
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpRequest
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpResponse
import org.oxycblt.auxio.shippy.provider.http.ProviderHttpTransport

class YouTubeMusicProviderTest {
    @Test fun `provider owns only YouTube Music candidates and requested capabilities`() {
        val provider = YouTubeMusicProvider(FakeTransport(SEARCH_JSON))
        assertEquals("youtube_music", provider.descriptor.id.value)
        assertTrue(provider.descriptor.capabilities.containsAll(setOf(
            org.oxycblt.auxio.shippy.provider.ProviderCapability.SEARCH,
            org.oxycblt.auxio.shippy.provider.ProviderCapability.TRACK,
            org.oxycblt.auxio.shippy.provider.ProviderCapability.STREAM,
            org.oxycblt.auxio.shippy.provider.ProviderCapability.DOWNLOAD,
        )))
    }

    @Test fun `extracts complete anonymous bootstrap only`() {
        val bootstrap = YouTubeMusicProvider.Bootstrap.extract("<script>ytcfg.set({\"INNERTUBE_API_KEY\":\"public-key\",\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"1.2026\",\"VISITOR_DATA\":\"visitor\"});</script>")
        assertEquals("public-key", bootstrap?.apiKey)
        assertNull(YouTubeMusicProvider.Bootstrap.extract("ytcfg.set({\"INNERTUBE_API_KEY\":\"x\"});"))
    }

    @Test fun `search maps only song shelf records`() = runBlocking {
        val provider = YouTubeMusicProvider(FakeTransport(SEARCH_JSON))
        val result = provider.search("song")
        assertTrue(result is ProviderResult.Success)
        val track = (result as ProviderResult.Success).value.tracks.single()
        assertEquals("Good Song", track.title)
        assertEquals("abc123_def", track.candidates.single().sourceItemId)
    }

    @Test fun `resolve selects direct audio near preferred bitrate and exposes expiry`() = runBlocking {
        val provider = YouTubeMusicProvider(FakeTransport(PLAYER_JSON))
        val result = provider.resolve(candidate(), StreamConstraints(preferredBitrateBps = 130_000))
        assertTrue(result is ProviderResult.Success)
        val stream = (result as ProviderResult.Success).value
        assertEquals(128_000, stream.bitrateBps)
        assertEquals(1_700_000_000_000L, stream.expiresAtEpochMs)
    }

    @Test fun `resolve rejects cipher only audio honestly`() = runBlocking {
        val provider = YouTubeMusicProvider(FakeTransport("""{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"mimeType":"audio/webm","signatureCipher":"s=x"}]}}"""))
        val result = provider.resolve(candidate(), StreamConstraints())
        assertTrue(result is ProviderResult.Failure)
        assertEquals(ProviderFailureKind.UNSUPPORTED, (result as ProviderResult.Failure).kind)
    }

    @Test fun `malformed search returns malformed failure`() = runBlocking {
        val provider = YouTubeMusicProvider(FakeTransport("{"))
        val result = provider.search("song")
        assertEquals(ProviderFailureKind.MALFORMED_RESPONSE, (result as ProviderResult.Failure).kind)
    }

    @Test fun `expiry parsing ignores absent or invalid URLs`() {
        assertEquals(1_700_000_000_000L, YouTubeMusicProvider.expiryFromUrl("https://r.example/a?expire=1700000000"))
        assertNull(YouTubeMusicProvider.expiryFromUrl("https://r.example/a?expire=bad"))
    }

    private fun candidate() = TrackCandidate(CandidateId("youtube_music:abc123_def"), TrackId("youtube_music:abc123_def"), CandidateKind.PROVIDER, "youtube_music", "abc123_def", CandidateAvailability.RESOLVABLE, providerId = ProviderId("youtube_music"))

    private class FakeTransport(private val endpointBody: String) : ProviderHttpTransport {
        override suspend fun execute(request: ProviderHttpRequest): ProviderHttpResponse = if (request.method.name == "GET") response(BOOTSTRAP) else response(endpointBody)
        private fun response(body: String) = ProviderHttpResponse(200, emptyMap(), body.toByteArray())
    }

    private companion object {
        const val BOOTSTRAP = """<script>ytcfg.set({"INNERTUBE_API_KEY":"public","INNERTUBE_CONTEXT_CLIENT_VERSION":"1.2026","VISITOR_DATA":"visitor"});</script>"""
        const val SEARCH_JSON = """{"contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"navigationEndpoint":{"watchEndpoint":{"videoId":"abc123_def"}},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Good Song"}]} }},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"},{"text":"Artist"},{"text":"Album"},{"text":"3:05"}]}}}],"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://i.example/a"}]}}}}},{"musicResponsiveListItemRenderer":{"flexColumns":[]}}]}}]}}"""
        const val PLAYER_JSON = """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{"mimeType":"audio/webm; codecs=opus","url":"https://r.example/a?expire=1700000000","bitrate":128000,"contentLength":"42"},{"mimeType":"audio/mp4","url":"https://r.example/b?expire=1700000000","bitrate":256000}]}}"""
    }
}
