/*
 * Copyright (c) 2026 Auxio Project
 * R16BackupCoverage.kt is part of Auxio.
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
package app.shippy.data.backup

/** The bounded primitive values allowed in app-owned backup snapshots. */
public sealed interface R16BackupValue {
    public data class Text(val value: String) : R16BackupValue

    public data class BooleanValue(val value: Boolean) : R16BackupValue

    public data class LongValue(val value: Long) : R16BackupValue

    public data class DoubleValue(val value: Double) : R16BackupValue
}

public enum class R16BackupValueKind {
    TEXT,
    BOOLEAN,
    LONG,
    DOUBLE,
}

/** Explicitly portable settings selected by the app layer. */
public data class R16PortableSettingsSnapshot(val values: Map<String, R16BackupValue>)

/** Last.fm state that is safe to move between installs; credentials are never represented here. */
public data class R16SanitizedLastFmConfig(
    val username: String?,
    val preferences: Map<String, R16BackupValue> = emptyMap(),
)

/** Supplies one explicit, already-read portable-settings snapshot to the data exporter. */
public fun interface R16PortableSettingsProvider {
    public fun snapshot(): R16PortableSettingsSnapshot
}

/** Supplies one explicit, already-sanitized Last.fm snapshot to the data exporter. */
public fun interface R16SanitizedLastFmConfigProvider {
    public fun snapshot(): R16SanitizedLastFmConfig
}

/** Stable archive keys shared by the data contract and the app adapters. */
public object R16BackupAllowlist {
    public val portableSettingKinds: Map<String, R16BackupValueKind> =
        mapOf(
            "ui.theme" to R16BackupValueKind.LONG,
            "ui.blackTheme" to R16BackupValueKind.BOOLEAN,
            "ui.accent" to R16BackupValueKind.LONG,
            "ui.roundMode" to R16BackupValueKind.BOOLEAN,
            "home.tabs" to R16BackupValueKind.LONG,
            "home.hideCollaborators" to R16BackupValueKind.BOOLEAN,
            "playback.headsetAutoplay" to R16BackupValueKind.BOOLEAN,
            "playback.replayGain" to R16BackupValueKind.LONG,
            "playback.preAmpWith" to R16BackupValueKind.DOUBLE,
            "playback.preAmpWithout" to R16BackupValueKind.DOUBLE,
            "playback.playInListWith" to R16BackupValueKind.LONG,
            "playback.playInParentWith" to R16BackupValueKind.LONG,
            "playback.keepShuffle" to R16BackupValueKind.BOOLEAN,
            "playback.rewindWithPrev" to R16BackupValueKind.BOOLEAN,
            "playback.pauseOnRepeat" to R16BackupValueKind.BOOLEAN,
            "playback.rememberPause" to R16BackupValueKind.BOOLEAN,
            "playback.barAction" to R16BackupValueKind.LONG,
            "playback.exitOnTaskRemoval" to R16BackupValueKind.BOOLEAN,
            "playback.transitionMode" to R16BackupValueKind.LONG,
            "playback.crossfadeDurationMs" to R16BackupValueKind.LONG,
            "library.songsSort" to R16BackupValueKind.LONG,
            "library.albumsSort" to R16BackupValueKind.LONG,
            "library.artistsSort" to R16BackupValueKind.LONG,
            "library.genresSort" to R16BackupValueKind.LONG,
            "library.playlistsSort" to R16BackupValueKind.LONG,
            "library.albumSongsSort" to R16BackupValueKind.LONG,
            "library.artistSongsSort" to R16BackupValueKind.LONG,
            "library.genreSongsSort" to R16BackupValueKind.LONG,
        )

    public val portableSettingKeys: Set<String>
        get() = portableSettingKinds.keys

    /** Only preferences that are explicitly portable; the current app may supply none. */
    public val lastFmPreferenceKinds: Map<String, R16BackupValueKind> = emptyMap()

    public val lastFmPreferenceKeys: Set<String>
        get() = lastFmPreferenceKinds.keys
}
