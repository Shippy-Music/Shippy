/*
 * Copyright (c) 2026 Auxio Project
 * ProviderSettingsPresentationTest.kt is part of Auxio.
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
package org.oxycblt.auxio.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.ProviderId

class ProviderSettingsPresentationTest {
    private val jio = ProviderId("jiosaavn")
    private val youtube = ProviderId("youtube_music")
    private val third = ProviderId("third")

    @Test
    fun selectingFallbackAsPreferredSwapsTheFirstTwoProviders() {
        assertEquals(
            listOf(youtube, jio, third),
            ProviderSettingsPresentation.reordered(
                listOf(jio, youtube, third),
                youtube,
                preferred = true,
            ),
        )
    }

    @Test
    fun selectingPreferredAsFallbackSwapsTheFirstTwoProviders() {
        assertEquals(
            listOf(youtube, jio, third),
            ProviderSettingsPresentation.reordered(
                listOf(jio, youtube, third),
                jio,
                preferred = false,
            ),
        )
    }

    @Test
    fun selectingAnotherProviderKeepsRemainingPriorityStable() {
        assertEquals(
            listOf(third, youtube, jio),
            ProviderSettingsPresentation.reordered(
                listOf(jio, youtube, third),
                third,
                preferred = true,
            ),
        )
    }

    @Test
    fun aSingleProviderCannotGainAFallback() {
        assertEquals(
            listOf(jio),
            ProviderSettingsPresentation.reordered(listOf(jio), jio, preferred = false),
        )
    }
}
