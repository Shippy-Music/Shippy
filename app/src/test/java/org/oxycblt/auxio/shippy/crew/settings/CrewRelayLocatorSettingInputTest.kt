/*
 * Copyright (c) 2026 Auxio Project
 * CrewRelayLocatorSettingInputTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrewRelayLocatorSettingInputTest {
    @Test
    fun `blank input clears configured relay`() {
        assertEquals(
            CrewRelayLocatorSettingResult.Cleared,
            CrewRelayLocatorSettingInput.parse("  "),
        )
    }

    @Test
    fun `valid https endpoint is trimmed and summarized safely`() {
        val result = CrewRelayLocatorSettingInput.parse(" https://relay.example.com:8443/v1/crew ")
        assertTrue(result is CrewRelayLocatorSettingResult.Configured)
        result as CrewRelayLocatorSettingResult.Configured

        assertEquals("https://relay.example.com:8443/v1/crew", result.locator.value)
        assertEquals(
            "relay.example.com:8443/v1/crew",
            CrewRelayLocatorSettingInput.summary(result.locator),
        )
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
