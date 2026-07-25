/*
 * Copyright (c) 2026 Shippy contributors
 * CrewSettings.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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

/** User-controlled Crew media sharing preferences. */
interface CrewSettings : Settings<CrewSettings.Listener> {
    /** Whether active-Crew members may temporarily share missing media with each other. */
    val pushPullEnabled: Boolean
    fun setPushPullEnabled(enabled: Boolean)

    interface Listener {
        /** Called when [pushPullEnabled] changes after this listener is registered. */
        fun onPushPullEnabledChanged(enabled: Boolean) {}
    }
}

class CrewSettingsImpl
@Inject
constructor(
    @ApplicationContext context: Context,
) : Settings.Impl<CrewSettings.Listener>(context), CrewSettings {
    override val pushPullEnabled: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_crew_push_pull_enabled), false)

    override fun setPushPullEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(getString(R.string.set_key_crew_push_pull_enabled), enabled) }
    }

    override fun onSettingChanged(key: String, listener: CrewSettings.Listener) {
        if (key == getString(R.string.set_key_crew_push_pull_enabled)) {
            listener.onPushPullEnabledChanged(pushPullEnabled)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CrewSettingsModule {
    @Binds
    @Singleton
    abstract fun settings(implementation: CrewSettingsImpl): CrewSettings
}
