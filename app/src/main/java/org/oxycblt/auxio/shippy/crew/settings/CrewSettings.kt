/*
 * Copyright (c) 2026 Auxio Project
 * CrewSettings.kt is part of Auxio.
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

import android.content.Context
import androidx.core.content.edit
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.Settings
import org.oxycblt.auxio.shippy.crew.invite.CrewRelayLocator

/** User-controlled Crew media sharing preferences. */
interface CrewSettings : Settings<CrewSettings.Listener> {
    /** Whether active-Crew members may temporarily share missing media with each other. */
    val pushPullEnabled: Boolean

    fun setPushPullEnabled(enabled: Boolean)

    /**
     * Optional self-hosted relay endpoint. This is an HTTPS locator; the relay adapter changes only
     * its scheme when opening the WebSocket. Invalid persisted values are intentionally unavailable
     * to callers.
     */
    val relayLocator: CrewRelayLocator?

    /** Validates and persists an endpoint, or clears it when [input] is blank. */
    fun setRelayLocator(input: String): CrewRelayLocatorSettingResult

    interface Listener {
        /** Called when [pushPullEnabled] changes after this listener is registered. */
        fun onPushPullEnabledChanged(enabled: Boolean) {}

        /** Called when the configured hosted relay changes after this listener is registered. */
        fun onRelayLocatorChanged(locator: CrewRelayLocator?) {}
    }
}

/** Typed result used by Settings UI so raw preference storage cannot bypass relay validation. */
sealed interface CrewRelayLocatorSettingResult {
    data class Configured(val locator: CrewRelayLocator) : CrewRelayLocatorSettingResult

    data object Cleared : CrewRelayLocatorSettingResult

    data object Invalid : CrewRelayLocatorSettingResult
}

/** Pure parsing and display helpers for the self-hosted relay setting. */
object CrewRelayLocatorSettingInput {
    fun parse(input: String): CrewRelayLocatorSettingResult {
        val value = input.trim()
        if (value.isEmpty()) return CrewRelayLocatorSettingResult.Cleared
        return try {
            CrewRelayLocatorSettingResult.Configured(CrewRelayLocator(value))
        } catch (_: IllegalArgumentException) {
            CrewRelayLocatorSettingResult.Invalid
        }
    }

    /** Safe concise text for Settings; the locator contract excludes credentials/query/fragment. */
    fun summary(locator: CrewRelayLocator?): String? {
        locator ?: return null
        val endpoint = java.net.URI(locator.value)
        return buildString {
            append(endpoint.host)
            if (endpoint.port != -1) append(':').append(endpoint.port)
            if (endpoint.rawPath.isNotEmpty() && endpoint.rawPath != "/") append(endpoint.rawPath)
        }
    }
}

class CrewSettingsImpl @Inject constructor(@ApplicationContext context: Context) :
    Settings.Impl<CrewSettings.Listener>(context), CrewSettings {
    override val pushPullEnabled: Boolean
        get() =
            sharedPreferences.getBoolean(getString(R.string.set_key_crew_push_pull_enabled), false)

    override fun setPushPullEnabled(enabled: Boolean) {
        sharedPreferences.edit {
            putBoolean(getString(R.string.set_key_crew_push_pull_enabled), enabled)
        }
    }

    override val relayLocator: CrewRelayLocator?
        get() =
            runCatching {
                    sharedPreferences.getString(
                        getString(R.string.set_key_crew_relay_locator),
                        null,
                    )
                }
                .getOrNull()
                ?.let(CrewRelayLocatorSettingInput::parse)
                ?.let { result -> (result as? CrewRelayLocatorSettingResult.Configured)?.locator }

    override fun setRelayLocator(input: String): CrewRelayLocatorSettingResult {
        val result = CrewRelayLocatorSettingInput.parse(input)
        when (result) {
            is CrewRelayLocatorSettingResult.Configured ->
                sharedPreferences.edit {
                    putString(getString(R.string.set_key_crew_relay_locator), result.locator.value)
                }
            CrewRelayLocatorSettingResult.Cleared ->
                sharedPreferences.edit { remove(getString(R.string.set_key_crew_relay_locator)) }
            CrewRelayLocatorSettingResult.Invalid -> Unit
        }
        return result
    }

    override fun onSettingChanged(key: String, listener: CrewSettings.Listener) {
        if (key == getString(R.string.set_key_crew_push_pull_enabled)) {
            listener.onPushPullEnabledChanged(pushPullEnabled)
        }
        if (key == getString(R.string.set_key_crew_relay_locator)) {
            listener.onRelayLocatorChanged(relayLocator)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CrewSettingsModule {
    @Binds @Singleton abstract fun settings(implementation: CrewSettingsImpl): CrewSettings
}
