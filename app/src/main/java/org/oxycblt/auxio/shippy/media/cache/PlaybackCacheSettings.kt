/*
 * Copyright (c) 2026 Auxio Project
 * PlaybackCacheSettings.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.media.cache

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.Settings

/** The two bounded streaming-cache policies exposed by the existing preference screen. */
interface PlaybackCacheSettings : Settings<Nothing> {
    val maximumBytes: Long
    val unusedMaxAgeMs: Long?
}

class PlaybackCacheSettingsImpl @Inject constructor(@ApplicationContext context: Context) :
    Settings.Impl<Nothing>(context), PlaybackCacheSettings {
    override val maximumBytes: Long
        get() =
            PlaybackCachePolicy.maximumBytes(
                sharedPreferences.getString(
                    getString(R.string.set_key_playback_cache_max_size),
                    PlaybackCachePolicy.DEFAULT_MAXIMUM_BYTES.toString(),
                )
            )

    override val unusedMaxAgeMs: Long?
        get() =
            PlaybackCachePolicy.unusedMaxAgeMs(
                sharedPreferences.getString(
                    getString(R.string.set_key_playback_cache_unused_age),
                    PlaybackCachePolicy.DEFAULT_UNUSED_MAX_AGE_MS.toString(),
                )
            )
}

/** Parses preference values defensively so malformed restored preferences fall back safely. */
internal object PlaybackCachePolicy {
    const val MEBIBYTE = 1024L * 1024L
    const val DAY_MS = 24L * 60L * 60L * 1000L
    const val DEFAULT_MAXIMUM_BYTES = 2_048L * MEBIBYTE
    const val DEFAULT_UNUSED_MAX_AGE_MS = 30L * DAY_MS

    private val maximums =
        setOf(
            256L * MEBIBYTE,
            512L * MEBIBYTE,
            1_024L * MEBIBYTE,
            DEFAULT_MAXIMUM_BYTES,
            5_120L * MEBIBYTE,
        )
    private val unusedAges = setOf(7L * DAY_MS, DEFAULT_UNUSED_MAX_AGE_MS, 90L * DAY_MS)

    fun maximumBytes(raw: String?): Long =
        raw?.toLongOrNull()?.takeIf { it in maximums } ?: DEFAULT_MAXIMUM_BYTES

    /** Null means size-only eviction; span timestamps are used for the other values. */
    fun unusedMaxAgeMs(raw: String?): Long? =
        raw?.toLongOrNull()?.takeIf { it in unusedAges }
            ?: if (raw == SIZE_ONLY_VALUE) null else DEFAULT_UNUSED_MAX_AGE_MS

    const val SIZE_ONLY_VALUE = "size_only"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackCacheSettingsModule {
    @Binds
    @Singleton
    abstract fun settings(implementation: PlaybackCacheSettingsImpl): PlaybackCacheSettings
}
