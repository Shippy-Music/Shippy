package org.oxycblt.auxio.image

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitmapProviderTest {
    @Test
    fun acceptsOnlyCredentialFreeHttpsArtwork() {
        assertTrue(BitmapProvider.isValidArtworkUrl("https://images.example.test/cover.jpg"))
        assertFalse(BitmapProvider.isValidArtworkUrl(null))
        assertFalse(BitmapProvider.isValidArtworkUrl(""))
        assertFalse(BitmapProvider.isValidArtworkUrl(" https://images.example.test/cover.jpg"))
        assertFalse(BitmapProvider.isValidArtworkUrl("http://images.example.test/cover.jpg"))
        assertFalse(BitmapProvider.isValidArtworkUrl("https://user:pass@images.example.test/cover.jpg"))
        assertFalse(BitmapProvider.isValidArtworkUrl("https:///cover.jpg"))
        assertFalse(BitmapProvider.isValidArtworkUrl("file:///data/user/0/cover.jpg"))
    }
}
