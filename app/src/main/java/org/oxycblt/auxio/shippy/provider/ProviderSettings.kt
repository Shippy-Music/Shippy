/*
 * Copyright (c) 2026 Shippy contributors
 * ProviderSettings.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.provider

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.Settings
import org.oxycblt.auxio.shippy.domain.ProviderId

interface ProviderSettings : Settings<Nothing> {
    fun selection(available: Collection<ProviderId>): ProviderSelection

    fun setPriority(priority: List<ProviderId>)
}

class ProviderSettingsImpl
@Inject
constructor(
    @ApplicationContext context: Context,
) : Settings.Impl<Nothing>(context), ProviderSettings {
    override fun selection(available: Collection<ProviderId>): ProviderSelection {
        val availableIds = available.distinct()
        val availableSet = availableIds.toSet()
        val stored =
            sharedPreferences
                .getString(getString(R.string.set_key_provider_priority), null)
                ?.split(SEPARATOR)
                ?.mapNotNull { value ->
                    value.trim().takeIf(String::isNotEmpty)?.let(::ProviderId)
                }
                ?.distinct()
                ?.filter(availableSet::contains)
                .orEmpty()
        val missing =
            availableIds
                .filterNot(stored::contains)
                .sortedWith(compareBy<ProviderId> { it != DEFAULT_PROVIDER }.thenBy { it.value })
        return ProviderSelection(stored + missing)
    }

    override fun setPriority(priority: List<ProviderId>) {
        ProviderSelection(priority)
        sharedPreferences.edit {
            putString(
                getString(R.string.set_key_provider_priority),
                priority.joinToString(SEPARATOR) { it.value },
            )
        }
    }

    private companion object {
        const val SEPARATOR = ","
        val DEFAULT_PROVIDER = ProviderId("jiosaavn")
    }
}
