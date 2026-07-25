package org.oxycblt.auxio.shippy.lastfm

import org.junit.Assert.assertEquals
import org.junit.Test

class LastFmSigningTest {
    @Test fun `signature sorts ascii indexed parameter names`() {
        val params = mapOf("track[10]" to "ten", "track[2]" to "two", "api_key" to "key")
        assertEquals(LastFmSigning.signature(mapOf("api_key" to "key", "track[10]" to "ten", "track[2]" to "two"), "secret"), LastFmSigning.signature(params, "secret"))
    }
}
