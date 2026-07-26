/*
 * Copyright (c) 2026 Shippy contributors
 * CrewRelayLocatorSettingInputTest.kt is part of Shippy.
 */

package org.oxycblt.auxio.shippy.crew.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrewRelayLocatorSettingInputTest {
    @Test
    fun `blank input clears configured relay`() {
        assertEquals(CrewRelayLocatorSettingResult.Cleared, CrewRelayLocatorSettingInput.parse("  "))
    }

    @Test
    fun `valid https endpoint is trimmed and summarized safely`() {
        val result = CrewRelayLocatorSettingInput.parse(" https://relay.example.com:8443/v1/crew ")
        assertTrue(result is CrewRelayLocatorSettingResult.Configured)
        result as CrewRelayLocatorSettingResult.Configured

        assertEquals("https://relay.example.com:8443/v1/crew", result.locator.value)
        assertEquals("relay.example.com:8443/v1/crew", CrewRelayLocatorSettingInput.summary(result.locator))
    }

    @Test
    fun `non https or credential-bearing endpoint is rejected`() {
        assertEquals(
            CrewRelayLocatorSettingResult.Invalid,
            CrewRelayLocatorSettingInput.parse("http://relay.example.com/v1/crew"),
        )
        assertEquals(
            CrewRelayLocatorSettingResult.Invalid,
            CrewRelayLocatorSettingInput.parse("https://token@relay.example.com/v1/crew"),
        )
    }
}
