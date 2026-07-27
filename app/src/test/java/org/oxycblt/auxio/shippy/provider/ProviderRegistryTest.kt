/*
 * Copyright (c) 2026 Auxio Project
 * ProviderRegistryTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.TrackCandidate

class ProviderRegistryTest {
    @Test
    fun `enabled providers follow explicit priority`() {
        val first = FakeProvider("first")
        val second = FakeProvider("second")
        val registry = ProviderRegistry(setOf(first, second))

        val enabled =
            registry.enabled(ProviderSelection(listOf(second.descriptor.id, first.descriptor.id)))

        assertEquals(listOf(second, first), enabled)
    }

    @Test
    fun `disabled providers are omitted`() {
        val enabled = FakeProvider("enabled")
        val disabled = FakeProvider("disabled", ProviderHealth.DISABLED)
        val registry = ProviderRegistry(setOf(enabled, disabled))

        assertEquals(
            listOf(enabled),
            registry.enabled(
                ProviderSelection(listOf(disabled.descriptor.id, enabled.descriptor.id))
            ),
        )
    }

    @Test
    fun `duplicate provider IDs fail fast`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderRegistry(setOf(FakeProvider("same"), FakeProvider("same")))
        }
    }

    private class FakeProvider(
        id: String,
        private val providerHealth: ProviderHealth = ProviderHealth.AVAILABLE,
    ) : MusicProvider {
        override val descriptor =
            ProviderDescriptor(
                ProviderId(id),
                id,
                setOf(ProviderCapability.SEARCH, ProviderCapability.STREAM),
            )

        override fun health() = providerHealth

        override suspend fun search(query: String, continuation: String?) =
            ProviderResult.Success(SearchPage(emptyList()))

        override suspend fun resolve(candidate: TrackCandidate, constraints: StreamConstraints) =
            ProviderResult.Failure(ProviderFailureKind.UNSUPPORTED, false)
    }
}
