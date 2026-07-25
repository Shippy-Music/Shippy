package org.oxycblt.auxio.shippy.lastfm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LastFmReauthStateTest {
    @Test
    fun `delivery signal is process local and clears only explicitly`() {
        val state = LastFmReauthState()

        assertFalse(state.required.value)
        state.markRequired()
        assertTrue(state.required.value)
        state.clear()
        assertFalse(state.required.value)
    }
}
