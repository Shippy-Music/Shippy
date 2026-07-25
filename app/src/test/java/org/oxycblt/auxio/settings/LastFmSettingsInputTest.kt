package org.oxycblt.auxio.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LastFmSettingsInputTest {
    @Test
    fun `presentation exposes reauthorization only for saved credentials`() {
        assertEquals(
            LastFmSettingsState.Disconnected,
            LastFmSettingsPresentation.state(null, reauthorizationRequired = true),
        )
        assertEquals(
            LastFmSettingsState.ReauthorizationRequired("scrobbler"),
            LastFmSettingsPresentation.state("scrobbler", reauthorizationRequired = true),
        )
        assertEquals(
            LastFmSettingsState.Connected("scrobbler"),
            LastFmSettingsPresentation.state("scrobbler", reauthorizationRequired = false),
        )
    }

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
