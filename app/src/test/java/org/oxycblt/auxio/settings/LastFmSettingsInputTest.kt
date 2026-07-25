package org.oxycblt.auxio.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LastFmSettingsInputTest {
    @Test
    fun `accepts bounded nonblank credentials`() {
        assertTrue(LastFmSettingsInput.isValid("api-key_123"))
        assertTrue(LastFmSettingsInput.isValid("secret with spaces"))
    }

    @Test
    fun `rejects blank control and oversized credentials before auth`() {
        assertFalse(LastFmSettingsInput.isValid("   "))
        assertFalse(LastFmSettingsInput.isValid("key\nsecret"))
        assertFalse(LastFmSettingsInput.isValid("x".repeat(1025)))
    }
}
