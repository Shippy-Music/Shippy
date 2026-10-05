/*
 * Copyright (c) 2026 Auxio Project
 * SystemCollectionQueries.kt is part of Auxio.
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
package app.shippy.data.db.dao

/** Restrict identity candidates before metadata matching; shared by display and playback. */
internal const val SYSTEM_COLLECTION_MATCHING_IDS =
    """
    SELECT recording_id FROM library_song_view
    WHERE recording_id IN (
        SELECT recording_id FROM library_recording
        WHERE UPPER(:collectionId) = 'LIKED' AND liked = 1
        UNION
        SELECT recording_id FROM media_asset
        WHERE asset_state = 'AVAILABLE' AND (
            (UPPER(:collectionId) = 'LOCAL' AND asset_kind = 'LOCAL_FILE')
            OR (UPPER(:collectionId) = 'DOWNLOADS' AND asset_kind = 'SHIPPY_DOWNLOAD')
        )
    ) AND (
        :query IS NULL
        OR INSTR(LOWER(title), LOWER(:query)) > 0
        OR INSTR(LOWER(artist_display), LOWER(:query)) > 0
        OR INSTR(LOWER(COALESCE(release_title, '')), LOWER(:query)) > 0
    )
"""

internal const val SYSTEM_COLLECTION_FILTER_PAGE =
    "SELECT * FROM library_song_view WHERE recording_id IN (" +
        SYSTEM_COLLECTION_MATCHING_IDS +
        ") ORDER BY title_sort_key, recording_id"

internal const val SYSTEM_COLLECTION_FILTER_PLAYBACK =
    SYSTEM_COLLECTION_MATCHING_IDS + " ORDER BY title_sort_key, recording_id"
