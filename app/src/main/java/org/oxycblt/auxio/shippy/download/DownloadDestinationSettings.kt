/*
 * Copyright (c) 2026 Shippy contributors
 * DownloadDestinationSettings.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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

data class DownloadDestination(
    val treeUri: String,
    val displayName: String,
) {
    init {
        require(treeUri.isNotBlank()) { "Download destination URI cannot be blank" }
        require(displayName.isNotBlank()) { "Download destination name cannot be blank" }
    }
}

interface DownloadDestinationSettings : Settings<Nothing> {
    val destination: DownloadDestination?

    fun setDestination(destination: DownloadDestination)

    fun clearDestination()
}

class DownloadDestinationSettingsImpl
@Inject
constructor(
    @ApplicationContext context: Context,
) : Settings.Impl<Nothing>(context), DownloadDestinationSettings {
    override val destination: DownloadDestination?
        get() {
            val uri =
                sharedPreferences
                    .getString(getString(R.string.set_key_download_destination_uri), null)
                    ?.takeIf(String::isNotBlank)
                    ?: return null
            val name =
                sharedPreferences
                    .getString(getString(R.string.set_key_download_destination_name), null)
                    ?.takeIf(String::isNotBlank)
                    ?: DEFAULT_NAME
            return DownloadDestination(uri, name)
        }

    override fun setDestination(destination: DownloadDestination) {
        sharedPreferences.edit {
            putString(getString(R.string.set_key_download_destination_uri), destination.treeUri)
            putString(getString(R.string.set_key_download_destination_name), destination.displayName)
        }
    }

    override fun clearDestination() {
        sharedPreferences.edit {
            remove(getString(R.string.set_key_download_destination_uri))
            remove(getString(R.string.set_key_download_destination_name))
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
    abstract fun settings(
        settings: DownloadDestinationSettingsImpl
    ): DownloadDestinationSettings
}
