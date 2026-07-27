/*
 * Copyright (c) 2026 Auxio Project
 * DownloadDestinationSettings.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.download

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

data class DownloadDestination(val treeUri: String, val displayName: String) {
    init {
        require(treeUri.isNotBlank()) { "Download destination URI cannot be blank" }
        require(displayName.isNotBlank()) { "Download destination name cannot be blank" }
    }
}

interface DownloadDestinationSettings : Settings<Nothing> {
    val destination: DownloadDestination?

    /** The destination URI Shippy inserted into Auxio's Local SAF sources, if any. */
    val autoAddedLocalSourceUri: String?

    fun setDestination(destination: DownloadDestination, autoAddedLocalSourceUri: String?)

    fun clearDestination()
}

class DownloadDestinationSettingsImpl @Inject constructor(@ApplicationContext context: Context) :
    Settings.Impl<Nothing>(context), DownloadDestinationSettings {
    override val destination: DownloadDestination?
        get() {
            val uri =
                sharedPreferences
                    .getString(getString(R.string.set_key_download_destination_uri), null)
                    ?.takeIf(String::isNotBlank) ?: return null
            val name =
                sharedPreferences
                    .getString(getString(R.string.set_key_download_destination_name), null)
                    ?.takeIf(String::isNotBlank) ?: DEFAULT_NAME
            return DownloadDestination(uri, name)
        }

    override val autoAddedLocalSourceUri: String?
        get() =
            sharedPreferences
                .getString(
                    getString(R.string.set_key_download_destination_auto_local_source_uri),
                    null,
                )
                ?.takeIf(String::isNotBlank)

    override fun setDestination(
        destination: DownloadDestination,
        autoAddedLocalSourceUri: String?,
    ) {
        sharedPreferences.edit {
            putString(getString(R.string.set_key_download_destination_uri), destination.treeUri)
            putString(
                getString(R.string.set_key_download_destination_name),
                destination.displayName,
            )
            if (autoAddedLocalSourceUri == null) {
                remove(getString(R.string.set_key_download_destination_auto_local_source_uri))
            } else {
                putString(
                    getString(R.string.set_key_download_destination_auto_local_source_uri),
                    autoAddedLocalSourceUri,
                )
            }
        }
    }

    override fun clearDestination() {
        sharedPreferences.edit {
            remove(getString(R.string.set_key_download_destination_uri))
            remove(getString(R.string.set_key_download_destination_name))
            remove(getString(R.string.set_key_download_destination_auto_local_source_uri))
        }
    }

    private companion object {
        const val DEFAULT_NAME = "Downloads"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadDestinationSettingsModule {
    @Binds
    @Singleton
    abstract fun settings(settings: DownloadDestinationSettingsImpl): DownloadDestinationSettings
}
