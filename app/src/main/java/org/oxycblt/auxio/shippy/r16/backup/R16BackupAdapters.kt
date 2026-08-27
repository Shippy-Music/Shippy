/*
 * Copyright (c) 2026 Auxio Project
 * R16BackupAdapters.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import app.shippy.data.backup.R16BackupValue
import app.shippy.data.backup.R16PortableSettingsProvider
import app.shippy.data.backup.R16PortableSettingsSnapshot
import app.shippy.data.backup.R16SanitizedLastFmConfig
import app.shippy.data.backup.R16SanitizedLastFmConfigProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.shippy.lastfm.LastFmCredentialRepository
import org.oxycblt.auxio.shippy.lastfm.LastFmCredentials

/**
 * App-owned, explicit allowlist adapter. Device paths, SAF grants, provider endpoints, and Crew
 * identity values are intentionally not portable settings.
 */
class R16PortableSettingsAdapter(private val values: Map<String, *>) : R16PortableSettingsProvider {
    constructor(preferences: SharedPreferences) : this(preferences.all)

    constructor(
        context: Context
    ) : this(PreferenceManager.getDefaultSharedPreferences(context.applicationContext).all)

    override fun snapshot(): R16PortableSettingsSnapshot =
        R16PortableSettingsSnapshot(
            values =
                PREFERENCE_KEYS.mapNotNull { (archiveKey, preferenceKey) ->
                        readValue(preferenceKey)?.let { archiveKey to it }
                    }
                    .toMap()
        )

    private fun readValue(key: String): R16BackupValue? {
        val raw = values[key] ?: return null
        return when (raw) {
            is Boolean -> R16BackupValue.BooleanValue(raw)
            is Byte,
            is Short,
            is Int,
            is Long -> R16BackupValue.LongValue((raw as Number).toLong())
            is Float,
            is Double -> R16BackupValue.DoubleValue((raw as Number).toDouble())
            is String -> R16BackupValue.Text(raw)
            is Set<*> -> error("Portable setting is not a scalar: $key")
            else -> error("Portable setting has an unsupported type: $key")
        }
    }

    private companion object {
        val PREFERENCE_KEYS =
            linkedMapOf(
                "ui.theme" to "KEY_THEME2",
                "ui.blackTheme" to "KEY_BLACK_THEME",
                "ui.accent" to "auxio_accent2",
                "ui.roundMode" to "auxio_round_covers",
                "home.tabs" to "auxio_home_tabs",
                "home.hideCollaborators" to "auxio_hide_collaborators",
                "playback.headsetAutoplay" to "auxio_headset_autoplay",
                "playback.replayGain" to "auxio_replay_gain",
                "playback.preAmpWith" to "auxio_pre_amp_with",
                "playback.preAmpWithout" to "auxio_pre_amp_without",
                "playback.playInListWith" to "auxio_play_in_list_with",
                "playback.playInParentWith" to "auxio_play_in_parent_with",
                "playback.keepShuffle" to "KEY_KEEP_SHUFFLE",
                "playback.rewindWithPrev" to "KEY_PREV_REWIND",
                "playback.pauseOnRepeat" to "KEY_LOOP_PAUSE",
                "playback.rememberPause" to "auxio_remember_pause",
                "playback.barAction" to "auxio_bar_action",
                "playback.exitOnTaskRemoval" to "auxio_task_exit",
                "playback.transitionMode" to "shippy_transition_mode",
                "playback.crossfadeDurationMs" to "shippy_crossfade_duration_ms",
                "library.songsSort" to "auxio_songs_sort",
                "library.albumsSort" to "auxio_albums_sort",
                "library.artistsSort" to "auxio_artists_sort",
                "library.genresSort" to "auxio_genres_sort",
                "library.playlistsSort" to "auxio_playlists_sort",
                "library.albumSongsSort" to "auxio_album_sort",
                "library.artistSongsSort" to "auxio_artist_sort",
                "library.genreSongsSort" to "auxio_genre_sort",
            )
    }
}

/**
 * Converts the existing encrypted Last.fm credential result into the only portable account data:
 * username plus explicitly supplied non-secret preferences. The credential fields themselves are
 * never copied into the snapshot.
 */
class R16SanitizedLastFmConfigAdapter(private val credentials: LastFmCredentials?) :
    R16SanitizedLastFmConfigProvider {
    override fun snapshot(): R16SanitizedLastFmConfig =
        R16SanitizedLastFmConfig(username = credentials?.username, preferences = emptyMap())

    companion object {
        /** Reads the encrypted repository once; callers should invoke this off the main thread. */
        suspend fun fromRepository(
            repository: LastFmCredentialRepository
        ): R16SanitizedLastFmConfigAdapter =
            withContext(Dispatchers.IO) { R16SanitizedLastFmConfigAdapter(repository.load()) }
    }
}
