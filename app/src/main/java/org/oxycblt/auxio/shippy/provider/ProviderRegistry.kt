/*
 * Copyright (c) 2026 Auxio Project
 * ProviderRegistry.kt is part of Auxio.
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

import javax.inject.Inject
import org.oxycblt.auxio.shippy.domain.ProviderId

data class ProviderSelection(val priority: List<ProviderId>) {
    init {
        require(priority.distinct().size == priority.size) {
            "Provider priority cannot contain duplicates"
        }
    }
}

class ProviderRegistry @Inject constructor(providers: Set<@JvmSuppressWildcards MusicProvider>) {
    private val providersById = providers.associateBy { it.descriptor.id }

    init {
        require(providersById.size == providers.size) { "Provider IDs must be unique" }
    }

    fun get(id: ProviderId): MusicProvider? = providersById[id]

    fun enabled(selection: ProviderSelection): List<MusicProvider> =
        selection.priority.mapNotNull(providersById::get).filter {
            it.health() != ProviderHealth.DISABLED
        }

    fun descriptors(): List<ProviderDescriptor> =
        providersById.values.map(MusicProvider::descriptor).sortedBy { it.displayName }

    fun supporting(capability: ProviderCapability): List<MusicProvider> =
        providersById.values
            .filter {
                capability in it.descriptor.capabilities &&
                    it.health() != ProviderHealth.DISABLED &&
                    it.health() != ProviderHealth.UNAVAILABLE
            }
            .sortedBy { it.descriptor.displayName }
}
