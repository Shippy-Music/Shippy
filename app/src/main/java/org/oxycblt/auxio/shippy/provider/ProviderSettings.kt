/*
 * Copyright (c) 2026 Auxio Project
 * ProviderSettings.kt is part of Auxio.
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

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.Settings
import org.oxycblt.auxio.shippy.domain.ProviderId

interface ProviderSettings : Settings<ProviderSettings.Listener> {
    interface Listener {
        fun onProviderPriorityChanged()
    }

    fun selection(available: Collection<ProviderId>): ProviderSelection

    fun setPriority(priority: List<ProviderId>)

    fun streamingBitrateBps(): Int = DEFAULT_STREAMING_BITRATE_BPS

    fun downloadBitrateBps(): Int = DEFAULT_DOWNLOAD_BITRATE_BPS

    companion object {
        const val DEFAULT_STREAMING_BITRATE_BPS = 160_000
        const val DEFAULT_DOWNLOAD_BITRATE_BPS = 320_000
    }
}

class ProviderSettingsImpl @Inject constructor(@ApplicationContext context: Context) :
    Settings.Impl<ProviderSettings.Listener>(context), ProviderSettings {
    override fun selection(available: Collection<ProviderId>): ProviderSelection {
        val availableIds = available.distinct()
        val availableSet = availableIds.toSet()
        val stored =
            sharedPreferences
                .getString(getString(R.string.set_key_provider_priority), null)
                ?.split(SEPARATOR)
                ?.mapNotNull { value -> value.trim().takeIf(String::isNotEmpty)?.let(::ProviderId) }
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

    override fun streamingBitrateBps(): Int =
        readBitrate(
            getString(R.string.set_key_playback_quality),
            ProviderSettings.DEFAULT_STREAMING_BITRATE_BPS,
        )

    override fun downloadBitrateBps(): Int =
        readBitrate(
            getString(R.string.set_key_download_quality),
            ProviderSettings.DEFAULT_DOWNLOAD_BITRATE_BPS,
        )

    private fun readBitrate(key: String, fallback: Int): Int =
        sharedPreferences.getString(key, fallback.toString())?.toIntOrNull()?.takeIf { it > 0 }
            ?: fallback

    override fun onSettingChanged(key: String, listener: ProviderSettings.Listener) {
        if (key == getString(R.string.set_key_provider_priority))
            listener.onProviderPriorityChanged()
    }

    private companion object {
        const val SEPARATOR = ","
        val DEFAULT_PROVIDER = ProviderId("jiosaavn")
    }
}
