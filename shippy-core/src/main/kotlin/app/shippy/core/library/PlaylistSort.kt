/*
 * Copyright (c) 2026 Auxio Project
 * PlaylistSort.kt is part of Auxio.
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
package app.shippy.core.library

/** The seven persisted display orders supported by a canonical playlist. */
public enum class PlaylistSortMode(public val wireValue: String) {
    CUSTOM("CUSTOM"),
    RECENTLY_ADDED("RECENTLY_ADDED"),
    OLDEST_ADDED("OLDEST_ADDED"),
    TITLE("TITLE"),
    ARTIST("ARTIST"),
    ALBUM("ALBUM"),
    DURATION("DURATION");

    public companion object {
        /** Normalizes persisted values from older R16 fixtures/imports. */
        public fun fromWire(raw: String?): PlaylistSortMode {
            return fromWireOrNull(raw) ?: CUSTOM
        }

        public fun fromWireOrNull(raw: String?): PlaylistSortMode? {
            return when (raw?.trim()?.uppercase()) {
                "MANUAL",
                "CUSTOM" -> CUSTOM
                "ADDED",
                "RECENTLY_ADDED",
                "RECENTLYADDED" -> RECENTLY_ADDED
                "OLDEST_ADDED",
                "OLDESTADDED" -> OLDEST_ADDED
                "TITLE" -> TITLE
                "ARTIST" -> ARTIST
                "ALBUM" -> ALBUM
                "DURATION" -> DURATION
                else -> null
            }
        }
    }
}

/** Direction is persisted separately so a playlist's display choice is reversible. */
public enum class PlaylistSortDirection(public val wireValue: String) {
    ASCENDING("ASC"),
    DESCENDING("DESC");

    public companion object {
        public fun fromWire(raw: String?): PlaylistSortDirection {
            return fromWireOrNull(raw) ?: ASCENDING
        }

        public fun fromWireOrNull(raw: String?): PlaylistSortDirection? {
            return when (raw?.trim()?.uppercase()) {
                "DESC",
                "DESCENDING" -> DESCENDING
                "ASC",
                "ASCENDING" -> ASCENDING
                else -> null
            }
        }
    }
}

/** Typed playlist display sort; Custom deliberately retains canonical sparse order. */
public data class PlaylistSort(
    public val mode: PlaylistSortMode = PlaylistSortMode.CUSTOM,
    public val direction: PlaylistSortDirection = PlaylistSortDirection.ASCENDING,
) {
    public val isCustom: Boolean
        get() = mode == PlaylistSortMode.CUSTOM

    /** Custom always means the canonical sparse order; its direction has no independent meaning. */
    public fun normalized(): PlaylistSort =
        when {
            isCustom && direction != PlaylistSortDirection.ASCENDING ->
                copy(direction = PlaylistSortDirection.ASCENDING)
            mode == PlaylistSortMode.OLDEST_ADDED ->
                PlaylistSort(
                    mode = PlaylistSortMode.RECENTLY_ADDED,
                    direction =
                        if (direction == PlaylistSortDirection.ASCENDING) {
                            PlaylistSortDirection.DESCENDING
                        } else {
                            PlaylistSortDirection.ASCENDING
                        },
                )
            else -> this
        }

    public companion object {
        public fun fromWire(mode: String?, direction: String?): PlaylistSort =
            PlaylistSort(
                    mode = PlaylistSortMode.fromWire(mode),
                    direction = PlaylistSortDirection.fromWire(direction),
                )
                .normalized()
    }
}
