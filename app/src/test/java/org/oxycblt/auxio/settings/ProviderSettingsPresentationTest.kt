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
            ProviderSettingsPresentation.reordered(listOf(jio, youtube, third), youtube, preferred = true),
        )
    }

    @Test
    fun selectingPreferredAsFallbackSwapsTheFirstTwoProviders() {
        assertEquals(
            listOf(youtube, jio, third),
            ProviderSettingsPresentation.reordered(listOf(jio, youtube, third), jio, preferred = false),
        )
    }

    @Test
    fun selectingAnotherProviderKeepsRemainingPriorityStable() {
        assertEquals(
            listOf(third, youtube, jio),
            ProviderSettingsPresentation.reordered(listOf(jio, youtube, third), third, preferred = true),
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
