/*
 * Copyright (c) 2026 Auxio Project
 * ProviderSettingsViewModel.kt is part of Auxio.
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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.provider.ProviderHealth
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.provider.ProviderSettings

data class ProviderSettingsOption(
    val id: ProviderId,
    val displayName: String,
    val health: ProviderHealth,
    val checking: Boolean = false,
)

data class ProviderSettingsState(
    val providers: List<ProviderSettingsOption> = emptyList(),
    val preferred: ProviderId? = null,
    val fallback: ProviderId? = null,
    val refreshing: Boolean = false,
)

/** Presentation boundary for the small, user-facing provider priority surface. */
@HiltViewModel
class ProviderSettingsViewModel
@Inject
constructor(private val registry: ProviderRegistry, private val settings: ProviderSettings) :
    ViewModel() {
    private val mutableState = MutableStateFlow(ProviderSettingsState())
    val state: StateFlow<ProviderSettingsState> = mutableState.asStateFlow()

    private val healthById = mutableMapOf<ProviderId, ProviderHealth>()
    private var activeRefresh: Job? = null
    private var lastRefreshEpochMs = 0L

    init {
        render()
        refresh(force = false)
    }

    fun setPreferred(id: ProviderId) = setChoice(id, preferred = true)

    fun setFallback(id: ProviderId) = setChoice(id, preferred = false)

    fun refresh(force: Boolean = true) {
        if (activeRefresh?.isActive == true) return
        if (!force && System.currentTimeMillis() - lastRefreshEpochMs < FRESHNESS_MS) return
        val enabled = enabledProviders()
        render(refreshing = true)
        activeRefresh =
            viewModelScope.launch {
                try {
                    enabled
                        .map { provider ->
                            async {
                                provider.descriptor.id to
                                    try {
                                        provider.probeHealth()
                                    } catch (error: Exception) {
                                        if (error is CancellationException) throw error
                                        ProviderHealth.UNAVAILABLE
                                    }
                            }
                        }
                        .awaitAll()
                        .forEach { (id, health) -> healthById[id] = health }
                    lastRefreshEpochMs = System.currentTimeMillis()
                } finally {
                    activeRefresh = null
                    render(refreshing = false)
                }
            }
    }

    private fun setChoice(id: ProviderId, preferred: Boolean) {
        val ids = enabledProviders().map { it.descriptor.id }
        if (id !in ids) return
        settings.setPriority(
            ProviderSettingsPresentation.reordered(settings.selection(ids).priority, id, preferred)
        )
        render()
    }

    private fun enabledProviders() =
        registry
            .descriptors()
            .mapNotNull { registry.get(it.id) }
            .filter { it.health() != ProviderHealth.DISABLED }

    private fun render(refreshing: Boolean = mutableState.value.refreshing) {
        val providers = enabledProviders()
        val selection = settings.selection(providers.map { it.descriptor.id }).priority
        mutableState.value =
            ProviderSettingsState(
                providers =
                    providers.map { provider ->
                        val descriptor = provider.descriptor
                        ProviderSettingsOption(
                            descriptor.id,
                            descriptor.displayName,
                            healthById[descriptor.id] ?: provider.health(),
                            checking = refreshing,
                        )
                    },
                preferred = selection.firstOrNull(),
                fallback = selection.getOrNull(1),
                refreshing = refreshing,
            )
    }

    private companion object {
        const val FRESHNESS_MS = 5 * 60 * 1_000L
    }
}

internal object ProviderSettingsPresentation {
    /** Keeps the persisted priority complete and stable while swapping an occupied choice. */
    fun reordered(
        current: List<ProviderId>,
        selected: ProviderId,
        preferred: Boolean,
    ): List<ProviderId> {
        if (current.isEmpty() || selected !in current) return current
        val first = current.first()
        val second = current.getOrNull(1)
        val (newFirst, newSecond) =
            if (preferred) {
                selected to if (selected == second) first else second
            } else {
                if (second == null) {
                    first to null
                } else if (selected == first) {
                    second to first
                } else {
                    first to selected
                }
            }
        return listOfNotNull(newFirst, newSecond) +
            current.filterNot { it == newFirst || it == newSecond }
    }
}
