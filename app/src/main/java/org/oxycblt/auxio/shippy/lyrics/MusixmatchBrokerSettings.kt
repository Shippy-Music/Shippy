/*
 * Copyright (c) 2026 Auxio Project
 * MusixmatchBrokerSettings.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.lyrics

import android.content.Context
import androidx.core.content.edit
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.Settings

/**
 * Optional owner-controlled endpoint for a broker that owns Musixmatch credentials. The Android app
 * never stores or transmits a Musixmatch API key.
 */
interface MusixmatchBrokerSettings : Settings<Nothing> {
    /** Null means the broker source is disabled. */
    val endpoint: String?

    fun setEndpoint(endpoint: String?)
}

class MusixmatchBrokerSettingsImpl @Inject constructor(@ApplicationContext context: Context) :
    Settings.Impl<Nothing>(context), MusixmatchBrokerSettings {
    override val endpoint: String?
        get() =
            sharedPreferences
                .getString(getString(R.string.set_key_musixmatch_broker_endpoint), null)
                ?.let(::validatedMusixmatchBrokerEndpointOrNull)

    override fun setEndpoint(endpoint: String?) {
        sharedPreferences.edit {
            if (endpoint == null) {
                remove(getString(R.string.set_key_musixmatch_broker_endpoint))
            } else {
                putString(
                    getString(R.string.set_key_musixmatch_broker_endpoint),
                    validateMusixmatchBrokerEndpoint(endpoint),
                )
            }
        }
    }
}

internal fun validatedMusixmatchBrokerEndpointOrNull(value: String): String? =
    runCatching { validateMusixmatchBrokerEndpoint(value) }.getOrNull()

internal fun validateMusixmatchBrokerEndpoint(value: String): String {
    val endpoint = value.trim()
    val uri = URI(endpoint)
    require(uri.scheme == "https") { "Musixmatch broker must use HTTPS" }
    require(uri.host != null) { "Musixmatch broker must have a host" }
    require(uri.rawUserInfo == null) { "Musixmatch broker must not contain credentials" }
    require(uri.rawQuery == null) { "Musixmatch broker must not contain query credentials" }
    require(uri.rawFragment == null) { "Musixmatch broker must not contain a fragment" }
    return endpoint
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MusixmatchBrokerSettingsModule {
    @Binds
    @Singleton
    abstract fun settings(settings: MusixmatchBrokerSettingsImpl): MusixmatchBrokerSettings
}
